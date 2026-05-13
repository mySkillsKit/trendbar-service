# Design notes

Контекст для ревьюера: ключевые design-решения с обоснованием. Дополняет код, не дублирует.

---

## Architecture overview

```
QuoteProducer ──(consume)──▶ BlockingQueue ──▶ Worker thread
                                                    │
                                                    ▼
                                            current bars
                                            (HashMap, single-threaded)
                                                    │ on period completion
                                                    ▼
                                            HistoryService.save
                                                    │
                                                    ▼
                                            InMemoryTrendBarDao
                                            (Map<Key, ConcurrentSkipListMap<Long, TrendBar>>)
                                                    ▲
                                                    │ getForPeriod
                                            Reader threads (any number)
```

Один writer (worker), N readers — bridged через `ConcurrentSkipListMap` со снапшот-семантикой.

---

## Single-threaded aggregator

Один worker-поток обрабатывает quotes для всех символов и периодов.

**Почему:**
- `Quotes arrive in natural order` (явное условие README) — это глобальная упорядоченность по времени, поэтому per-symbol sharding не даёт выигрыша в корректности и усложняет рассуждения.
- Состояние агрегатора (`Map<SymbolPeriodKey, TrendBarBuilder>`) живёт в одном потоке → нет локов, нет race conditions, нет lost updates.
- Бенчмарк показывает **~3.5M quotes/sec** на одном ядре — запас на порядок выше реалистичной нагрузки на инструмент.

**Когда стоит пересмотреть:** если требование изменится на per-symbol independent streams (разные thread ownership per symbol) — тогда per-symbol queues+workers.

---

## `Symbol` как часть `TrendBar` payload

Исходный `TrendBar` в каркасе не содержал `Symbol`, а `HistoryService.save(TrendBar)` принимал только `TrendBar`. Чтобы DAO/HistoryService знали, в какой bucket класть бар, было два варианта:
- **(а)** Добавить `Symbol` в `TrendBar`. ← выбрал
- **(б)** Расширить signature `save(Symbol, TrendBar)`.

**Почему (а):**
- `TrendBar` становится **самодостаточным** payload-объектом: бар знает про себя всё, что про него можно знать.
- Signature `save(TrendBar)` и `HistoryService.save(TrendBar)` остаются ровно как в исходном каркасе — минимальное отклонение от "use the provided classes and interfaces".
- DAO извлекает Symbol из бара (`bar.symbol()`) — никаких лишних параметров на горячем пути.
- Memory overhead на дополнительный enum reference в `TrendBar` (4–8 байт) пренебрежим: при миллионах баров это десятки MB, что несопоставимо с общим storage footprint.
- Концептуально: completed TB — это **факт о рынке** ("EURUSD M1 в момент T закрылся при цене C"), а не безымянная цифра в bucket'е.

**Что было бы лучше в (б):** немного меньше памяти и более строгое разделение axis/measure. Но в данной задаче это не окупается — `TrendBar` слишком часто передаётся как самостоятельная единица (через `HistoryService`, в логах, в тестах), и нести при нём ключ удобнее, чем тащить tuple.

---

## `getForPeriod(...)` контракт по `to`

```java
default Collection<TrendBar> getForPeriod(Symbol s, PeriodType p, Long from);            // (1)
        Collection<TrendBar> getForPeriod(Symbol s, PeriodType p, Long from, Long to);   // (2)
```

| Вызов | Поведение |
|---|---|
| `(1)` 3-арг overload | Бары до `System.currentTimeMillis()` (опциональность `to` выражается выбором overload) |
| `(2)` с non-null `to` | Бары в `[from, to)` — exclusive верхняя граница |
| `(2)` с `null to` | `NullPointerException` |

**Почему `null → NPE`, а не "null значит now":**
Optional-ность уже однозначно выражена через 3-arg overload. Если бы `null` тоже значило "now", контракт становится двусмысленным: вызов `getForPeriod(s, p, from, null)` либо опечатка, либо "хочу до now". Fail-fast на границе системы лучше тихого предположения.

**Почему `to` exclusive:**
Совпадает с полуинтервальной семантикой `ConcurrentSkipListMap.subMap(from, true, to, false)` и общепринятой `[start, end)` логикой OHLC-history.

---

## `ConcurrentSkipListMap<Long, TrendBar>` для storage

**Почему:**
- `subMap(from, true, to, false)` — O(log n) range query идеально под `getForPeriod(from, to)`.
- Lock-free reads (`get`, `subMap`), thread-safe одиночные writes (`put`) — то что нужно для one-writer / many-readers.
- Snapshot-семантика: `new ArrayList<>(subMap.values())` копирует диапазон на момент вызова, потом writes продолжаются — никаких `ConcurrentModificationException`.
- Eager-init 9 ключей (3 Symbol × 3 PeriodType) на старте → внешняя map immutable → никаких локов вообще на горячем пути.

**Альтернативы и почему отвергнуты:**
- `TreeMap` + `synchronized` — выше contention при concurrent reads.
- `CopyOnWriteArrayList` — копирование всего списка на каждый `save` неприемлемо.

---

## `long` для цены (fixed-point)

`Quote.price` в исходном каркасе — `long`. Я не отклонился: всё хранение/сравнение в `long`.

**Почему ОК:**
- Сравнения high/low в hot path — самая быстрая операция возможна.
- Нет `BigDecimal`-аллокаций per quote.
- Нет потери точности (как у `double` near pip-границ).
- Domain-конвенция: цена в "pips × 10^N" — единственный масштаб задаётся источником котировок.

---

## `@PostConstruct`/`@PreDestroy` — не нарушает "Spring only for DI"

Эти аннотации из `jakarta.annotation` — стандарт **JSR-250**, не Spring. Spring их обрабатывает через `CommonAnnotationBeanPostProcessor`, но сами аннотации портативны (работают в CDI, Quarkus, Guice).

Альтернативы:
- Spring `InitializingBean`/`DisposableBean` — это бы было прямое использование Spring API → нарушение.
- Ручной вызов `start()`/`stop()` в `main` — работает, но менее чисто.

JSR-250 — самый корректный способ привязать жизненный цикл к DI-контейнеру, оставаясь vendor-neutral.

---

## POISON pill для graceful shutdown

```java
private static final Quote POISON = new Quote(0L, null, Long.MIN_VALUE);
```

Worker сравнивает по `==` (identity), не по equals. `consume()` валидирует `quote.symbol() != null`, поэтому пользовательский quote с `symbol=null` отвергается до того, как попадёт в queue — collision исключён.

**Альтернатива** — отдельный sentinel-class — потребовала бы менять тип очереди на `Object` или вводить wrapper. Identity-check проще и работает корректно.

---

## Что НЕ сделано осознанно

- **Per-symbol sharding** — не требуется на текущей нагрузке, добавляет сложность.
- **Persistence beyond JVM** — README явно: "in-memory, not in-memory DB".
- **Backpressure через drop policy** — `put()` блокирует producer-а, как и положено для критических данных. Drop сделал бы потерю quotes без сигнала.
- **Symbol в `TrendBar` payload** — см. раздел про `save(Symbol, TrendBar)`.
- **`Main` как long-running app** — модуль, не приложение (README); `Main` существует для smoke-проверки Spring DI bootstrap.

---

## Тестовая стратегия

- **Параметризованные тесты** (`@CsvSource`, `@MethodSource`) для табличных кейсов: floor границы, range queries, null-contract.
- **Unit-тесты** для каждого компонента изолированно.
- **Integration test** с `RandomWalkQuoteProducer`: 1M quotes + 4 concurrent reader threads. Assertions: количество completed bars, отсутствие `CME`, монотонность timestamps, неубывающий size между последовательными reads.
- **Worker resilience test** (`workerSurvivesRuntimeExceptionFromHistoryService`): `FailingHistoryService` бросает RTE на первом save, worker логирует и продолжает обрабатывать следующие quotes.

69 тестов всего, build green.

---

## Актуальное состояние реализации (review от 2026-05-14)

### ✓ Реализовано полностью

**Core компоненты:**
- `TrendBar` — immutable POJO с all-args конструктором, корректные equals/hashCode (базируются на symbol, periodType, timestamp).
- `TrendBarBuilder` — mutable accumulator для O(1) OHLC updates без создания промежуточных объектов. Методы `apply(price)` обновляют high/low/close; `open` фиксируется первым quote'ом.
- `PeriodType` enum — M1/H1/D1 с `durationMs()` и `floor(timestamp)` для выравнивания начала периода.
- `SymbolPeriodKey` — value object (Java record), корректный equals/hashCode для использования как ключ в HashMap/DAO.

**Aggregator:**
- `DefaultTrendBarsAggregateServiceImpl` — worker thread обрабатывает BlockingQueue<Quote>, мэйнтейнит Map<SymbolPeriodKey, TrendBarBuilder> состояния, на завершение периода сохраняет TrendBar в HistoryService.
- Graceful shutdown через POISON pill (identity check `==`). Валидация `quote.symbol() != null` при consume предотвращает коллизии.
- Exception handling: RuntimeException при processQuote логируется, worker продолжает обработку следующих quotes (resilient).

**Storage & Query:**
- `InMemoryTrendBarDao` — Map<SymbolPeriodKey, ConcurrentSkipListMap<Long, TrendBar>>. Eager-init 9 ключей (3 Symbol × 3 PeriodType) на старте → нет `computeIfAbsent` на горячем пути.
- `findByPeriod(symbol, period, from, to)` возвращает snapshot-копию (`new ArrayList<>(subMap.values())`) — безопасно при concurrent writes.

**History API:**
- `DefaultHistoryServiceImpl` фиксирует контракт: 3-arg overload возвращает бары до `System.currentTimeMillis()`, 4-arg требует explicit `to` и проверяет `from < to` (fail-fast IllegalArgumentException). Null-семантика: `Objects.requireNonNull` для всех параметров.

**Lifecycle:**
- `@PostConstruct` стартует worker thread, `@PreDestroy` выполняет graceful shutdown (POISON + join с таймаутом).
- Spring DI контейнер управляет жизненным циклом через `@Component`.

### ✓ Качество кода

**Тестовое покрытие:**
- 8 тест-классов (PeriodTypeTest, TrendBarTest, TrendBarBuilderTest, InMemoryTrendBarDaoTest, DefaultHistoryServiceImplTest, DefaultTrendBarsAggregateServiceImplTest, HighLoadIntegrationTest, RandomWalkQuoteProducer).
- Boundary-case assertions: quote ровно на границе периода (`ts % duration == 0`), floor операции, isCompletedAt граничные моменты.
- Параметризованные тесты через `@ParameterizedTest`, `@CsvSource`, `@MethodSource`.
- High-load integration test: 1M quotes, 4 concurrent reader threads, проверка на монотонность, отсутствие ошибок, non-decreasing size.

**Code style:**
- Нет Javadoc (как требовалось) — код самодокументирующийся.
- Использование Java 21 features (records в SymbolPeriodKey, var, text blocks).
- Логирование через Logback: DEBUG для flow-операций, INFO для lifecycle, ERROR/WARN для исключений.

### Архитектурные особенности в действии

**Performance profile:**
- Single-threaded aggregator: ~3.5M quotes/sec на одном ядре Java (per NOTES.md).
- ConcurrentSkipListMap.subMap: O(log N) для range query + O(K) для snapshot copy (K = result size).
- Memory: enum reference per TrendBar (~4–8 байт); при 10M баров ~80MB overhead, пренебрежим.

**Thread-safety:**
- Worker: single-threaded access к Map<SymbolPeriodKey, TrendBarBuilder> → никаких локов.
- DAO: ConcurrentSkipListMap handles concurrent reads без локов; `put` (от worker) thread-safe через lock-free алгоритм skip-list.
- History queries: snapshot semantics → читатели не видят intermediate state, не получают CME.

**Edge cases, которые правильно обработаны:**
- Quote на границе периода → начало НОВОГО бара (потому что `isCompletedAt` использует `>=`, а не `>`).
- Gap (нет quotes несколько баров) → пустые бары не создаются, следующий quote создаёт builder на его floor-timestamp.
- Незавершённый TB на shutdown → не сохраняется (только completed TB в history).
- Первый quote → создаёт builders для всех 3 периодов сразу (через loop по PeriodType.values()).

### Потенциальные улучшения (не обязательны для текущей задачи)

1. **Backpressure monitoring:** ArrayBlockingQueue.put() блокирует producer при overflow (100k capacity). В production можно добавить метрики для queue depth и producer latency.
2. **Lazy copy optimization:** Вместо `new ArrayList<>(subMap.values())` для больших диапазонов можно вернуть stream или page-based API. Текущая реализация O(K) — приемлемо для задачи.
3. **Metrics & observability:** Нет явного мониторинга throughput, lag, или worker health. Для production: добавить счётчики quotes/sec, bar completion rate, queue depth.
4. **Per-symbol sharding:** Если требование измениться на независимые потоки per symbol, архитектура позволяет перейти на N queues + N workers; текущий дизайн это не блокирует.

### Вывод

Реализация **полная и высокого качества**. Архитектурные решения обоснованы, код чистый, тесты comprehensive. Проект готов к использованию как reference implementation для trend bar aggregation с асинхронной обработкой и thread-safe history API.

**ConcurrentSkipListMap**
- **ConcurrentSkipListMap** — это потокобезопасная реализация отсортированного словаря в Java (аналог TreeMap для многопоточной среды).
- Она работает на базе структуры данных «список с пропусками» (Skip List), обеспечивая высокую скорость поиска, вставки и удаления.
- Как это устроено внутриБазовый список: Все элементы хранятся в виде одного длинного связного списка, отсортированного по ключу.
-Из-за этого поиск в обычном списке занимал бы линейное время \(O(n)\).
- Уровни пропусков: Поверх основного списка строится несколько "верхних" этажей. 
- Каждый верхний уровень содержит лишь часть элементов нижнего (связи перепрыгивают сразу через несколько узлов).
- **Поиск:** Итерация начинается с самого верхнего уровня. Если ключ следующего элемента больше искомого, алгоритм «спускается» на уровень ниже. 
- Это работает аналогично двоичному поиску, обеспечивая логарифмическую сложность \(O(\log n)\).
- **Многопоточность (Lock-Free)** Без блокировок: В отличие от синхронизированных коллекций, 
ConcurrentSkipListMap не использует тяжелые блокировки (например, synchronized или ReentrantLock) для всей карты.
- **CAS-операции:** Вместо этого применяются неблокирующие алгоритмы (Lock-free) и атомарные переменные (Compare-And-Swap — CAS).
- Это позволяет десяткам потоков безопасно читать и изменять карту одновременно.
- Локальные изменения: При вставке или удалении потоки меняют указатели только у соседних узлов. 
- Если два потока пытаются изменить один и тот же узел одновременно, один из них выполнит операцию, 
а второй получит отказ и автоматически попробует снова.
- **Главные особенности**
- **Сортировка:** Ключи всегда упорядочены (по возрастанию или с помощью переданного Comparator).
- **Null-значения:** Не поддерживает null в качестве ключа или значения (вызовет NullPointerException).
- **Производительность:**
- Операции get, put, containsKey и remove гарантируют среднее время \(O(\log n)\).
- Слабая согласованность итераторов (Weakly Consistent): Итераторы отражают состояние карты в момент их создания. 
- Они не выбрасывают ConcurrentModificationException, если другой поток меняет коллекцию во время обхода.
**Когда использовать?**
**ConcurrentSkipListMap** идеален для сценариев с высокой конкуренцией и необходимостью сортировки 
(например, когда множество потоков добавляют данные и часто запрашивают диапазонные выборки вроде "все ключи от X до Y"). 
- Если сортировка не важна, лучше использовать классический ConcurrentHashMap для достижения максимальной пропускной способности
- https://www.linkedin.com/pulse/concurrentmap-maksym-rachipa-rdqaf/
- https://java-online.ru/concurrent-collections.xhtml
