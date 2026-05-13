# TrendBar Service — Test UI

Небольшой веб-интерфейс для ручного тестирования `DefaultHistoryService` и `DefaultTrendBarsAggregateService`.
Запускается поверх основного Spring-контекста через embedded Tomcat.

---

## Стек

| Слой | Технология |
|---|---|
| HTTP сервер | Apache Tomcat 10.1 (embedded) |
| MVC | Spring Web MVC 6.1 |
| Сериализация | Jackson Databind 2.17 |
| Frontend | Bootstrap 5.3 + TradingView Lightweight Charts 4.1 |

---

## Как запустить

**Требования:** JDK 21, Maven 3.9+

```bash
# если JAVA_HOME по умолчанию указывает не на JDK 21:
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

mvn compile
mvn exec:java -Dexec.mainClass=com.spotware.trendbar.Main
```

После старта в консоли появится:

```
INFO  com.spotware.trendbar.Main - TrendBar UI ready → http://localhost:8080
```

Откройте браузер: **http://localhost:8080**

---

## REST API

| Метод | URL | Описание |
|---|---|---|
| `GET` | `/api/symbols` | Список символов (`EURUSD`, `EURJPY`, `GBPUSD`) |
| `GET` | `/api/periods` | Список таймфреймов (`M1`, `H1`, `D1`) |
| `POST` | `/api/quotes` | Отправить одну котировку в агрегатор |
| `GET` | `/api/history` | Запросить историю из `DefaultHistoryService` |
| `POST` | `/api/simulate` | Запустить Random Walk генератор котировок |

### POST /api/quotes

```json
{
  "symbol": "EURUSD",
  "price": 1000055,
  "timestamp": 1715000000000
}
```

### GET /api/history

```
/api/history?symbol=EURUSD&period=M1&from=1715000000000&to=1715003600000
```

Ответ — массив завершённых баров:

```json
[
  {
    "symbol": "EURUSD",
    "periodType": "M1",
    "openPrice": 1000055,
    "highPrice": 1000068,
    "lowPrice": 1000041,
    "closePrice": 1000062,
    "timestamp": 1715000000000,
    "timestampFormatted": "2024-05-06 18:13:00"
  }
]
```

> Цены хранятся в raw-формате (long) без фиксированной шкалы — единицы измерения определяются на уровне бизнес-логики вне этого сервиса.

### POST /api/simulate

```json
{
  "count": 1000,
  "stepMs": 1000,
  "startTimestamp": 1715000000000,
  "seed": 42
}
```

Генерирует `count` котировок через Random Walk по всем символам и отправляет в `TrendBarsAggregateService`.
Метод блокируется до тех пор, пока агрегатор не обработает очередь.

---

## Структура файлов

```
src/main/
├── java/com/spotware/trendbar/
│   ├── Main.java                     # точка входа + embedded Tomcat
│   └── web/
│       ├── WebConfig.java            # @EnableWebMvc, статика, редиректы
│       └── TrendBarController.java   # REST контроллер + DTO records
└── resources/
    └── static/
        └── index.html                # Single-page UI
```

---

## Интерфейс

### Send Quote
Отправить одну котировку вручную: выбрать символ, указать цену и время. Удобно для точечной проверки граничных условий (смена периода, дубликат и т.д.).

### Simulate (Random Walk)
Генерирует `N` котировок с заданным шагом по времени.
После завершения автоматически подставляет временной диапазон в форму запроса истории.

Параметры:
- **Quote count** — количество котировок
- **Step (ms)** — шаг времени между котировками
- **Start time** — начало временного ряда
- **Seed** — инициализация генератора (одинаковый seed → одинаковые данные)

### Query History
Запрос к `DefaultHistoryService.getForPeriod(symbol, period, from, to)`.
Результат отображается на свечном графике и в таблице.

### Candlestick Chart
TradingView Lightweight Charts, тёмная тема.
Поддерживает масштабирование колесом мыши и перемещение.
Подписи времени на оси X соответствуют локальному часовому поясу сервера.

### Results Table
Столбцы: `#`, `Period Start`, `Symbol`, `Period`, `Open`, `High`, `Low`, `Close`, цветовой индикатор направления, `Range` (High − Low в пипсах).

---

## Типичный сценарий тестирования

```
1. Открыть http://localhost:8080
2. Simulate → count=3000, stepMs=1000 → нажать ▶ Simulate
3. Выбрать Symbol=EURUSD, Period=M1 → нажать Get History
4. Убедиться что свечи на графике корректны
5. Переключить Period=H1 → Get History → сравнить количество баров
```
