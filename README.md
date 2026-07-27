# WireMock JS Extension

Extension для WireMock, позволяющий описывать динамическую логику стабов на упрощённом JS-подобном скриптовом языке. Скрипты хранятся отдельно от маппингов и подключаются к стабу через `scriptId`, что позволяет переиспользовать одну и ту же логику в разных стабах и управлять ей через отдельный Admin API.

## Содержание

- [Как это работает](#как-это-работает)
- [Установка и запуск](#установка-и-запуск)
- [Синтаксис скрипта](#синтаксис-скрипта)
- [Переменные](#переменные)
- [Доступ к полям объекта через точку](#доступ-к-полям-объекта-через-точку)
- [null и безопасные сравнения](#null-и-безопасные-сравнения)
- [Доступные объекты и функции](#доступные-объекты-и-функции)
- [Вспомогательные функции (Фаза 4а)](#вспомогательные-функции-фаза-4а)
- [Детерминизм через seed](#детерминизм-через-seed)
- [Формат возвращаемого значения](#формат-возвращаемого-значения)
- [Admin API](#admin-api)
- [Примеры](#примеры)
- [Ограничения и защита](#ограничения-и-защита)

## Как это работает

1. Вы создаёте скрипт через Admin API `wiremock-js` — получаете `scriptId`.
2. Вы создаёте обычный стаб WireMock (`/__admin/mappings`), указывая в `response.transformers` значение `"wiremock-js"` и передавая `scriptId` в `transformerParameters`.
3. При каждом входящем запросе, попадающем под этот стаб, WireMock исполняет скрипт с доступом к данным реального HTTP-запроса и подставляет результат скрипта в ответ.

## Установка и запуск

Проект собирается через Gradle и запускается в официальном Docker-образе `wiremock/wiremock:3.13.1`.

```bash
./gradlew clean build
docker-compose up -d
```

Сборка автоматически копирует JAR в `wiremock/extensions/wiremock-js-extension.jar`, который монтируется в контейнер и подключается флагом `--extensions` в `docker-compose.yml`:

```yaml
command:
  - "--extensions=ru.mcs.wiremockjs.ScriptTransformer,ru.mcs.wiremockjs.admin.ScriptAdminApi"
```

Хранилище скриптов — JSON-файлы в директории, указанной через `WIREMOCKJS_STORAGE_DIR` (по умолчанию `wiremock/scripts`).

Максимальная длина скрипта конфигурируется через system property, без пересборки JAR:

```bash
-Dwiremockjs.max.script.length=4000
```

По умолчанию — 2000 символов.

## Синтаксис скрипта

Язык поддерживает переменные, условия, арифметику, логические операторы, вызовы whitelisted-функций и возврат JSON-объекта.

```js
var amount = query("amount");
if (amount != null && amount > 1000) {
  return { "approved": false, "reason": "limit exceeded" };
} else {
  return { "approved": true };
}
```

### Поддерживаемые конструкции

| Конструкция | Пример | Описание |
|---|---|---|
| `var` | `var name = query("x");` | Объявление переменной, function-scoped (живёт весь вызов скрипта) |
| `if / else` | `if (cond) { ... } else { ... }` | Условное выполнение, else опционален |
| `return` | `return { "k": "v" };` | Обязателен хотя бы один достижимый return |
| Арифметика | `+ - * / %` | Все операции работают с числами (double) |
| Сравнение | `> >= < <=` | Числовое сравнение |
| Равенство | `== !=` | Null-безопасное; числовое, если оба операнда числовые; иначе строковое |
| Логика | `&& \|\| !` | Логическое И, ИЛИ, НЕ — с настоящим коротким замыканием |
| Литералы | `"строка"`, `123`, `12.5`, `true/false`, `null`, `{ ... }` | JSON-объект как литерал, поддерживает вложенность любой глубины |
| Вызов функции | `query("name")` | Только whitelisted-функции, см. ниже |
| Доступ к полю переменной | `order.amount`, `order.details.city` | Только для переменных, объявленных через `var`, см. ниже |
| Скобки | `(expr)` | Группировка выражений |

## Переменные

Переменные объявляются через `var` и хранят результат любого выражения — включая результат вызова функции, число, строку или JSON-объект.

```js
var token = header("Authorization");
var order = jsonField("$");
var discount = 0;

if (contains(token, "vip") && order.amount > 5000) {
  discount = 20;
}

return { "discount": discount };
```

Переменные — function-scoped: одна плоская область видимости на весь вызов скрипта, без блочной вложенности (`let`/`const` не поддерживаются — это осознанное решение, упрощающее интерпретатор при лимите скрипта в единицы тысяч символов).

## Доступ к полям объекта через точку

Если переменная хранит JSON-объект (`Map`), к её полям можно обращаться через точку, включая произвольную глубину вложенности:

```js
var order = jsonField("$");

var city = order.details.city;      // многоуровневая навигация
var amount = order.amount;          // одноуровневая навигация
```

**Важно:** эта навигация работает только для переменных, объявленных через `var`. Прямой доступ к внутренним объектам WireMock (`request.method` и подобные) остаётся заблокированным — если базовый идентификатор не найден среди объявленных переменных, интерпретатор бросает `ScriptExecutionException`. Это архитектурное разделение: защита от доступа к внутренностям WireMock сохраняется, а навигация по пользовательским данным становится удобной.

Если промежуточное или конечное поле отсутствует в объекте — навигация возвращает `null`, а не бросает исключение:

```js
var order = jsonField("$");
var city = order.details.city;   // null, если order.details не существует
```

Если базовое значение — не объект (например, строка или число), а скрипт пытается обратиться к полю через точку — это бросает `ScriptExecutionException` с понятным сообщением, потому что это логическая ошибка в скрипте, а не штатная ситуация:

```js
var count = 10;
return { "result": count.amount };  // ScriptExecutionException: не является объектом
```

Навигация через точку **не поддерживает индексы массивов** (`order.items.0.id`) — для доступа к элементам массива используйте полноценный JSONPath внутри `jsonField()`, например `jsonField("items[0].id")`.

## null и безопасные сравнения

Отсутствующие значения (несуществующий query-параметр, заголовок, поле JSON) представлены как `null`. Сравнение `== null` и `!= null` работает предсказуемо:

```js
var token = header("Authorization");
if (token == null) {
  return { "status": 401, "body": { "error": "missing token" } };
}
```

**Важно:** арифметика и числовое сравнение (`+ - * / % > >= < <=`) с `null`-операндом бросают `ScriptExecutionException`, а не тихо превращают `null` в `0`. Поэтому перед арифметикой с потенциально отсутствующим значением нужна явная проверка:

```js
var amount = query("amount");
if (amount != null && amount > 1000) {   // проверка null идёт первой — короткое замыкание защищает от исключения
  return { "approved": false };
}
```

Логические `&&` и `||` реализуют настоящее короткое замыкание: если `amount != null` — `false`, правая часть `amount > 1000` не вычисляется вообще, и исключение не возникает.

`contains(str, substr)` безопасно обрабатывает `null` в любом из аргументов, возвращая `false` вместо исключения:

```js
contains(null, "x")       // false
contains("x", null)       // false
contains(header("Authorization"), "secret")  // false, если заголовок отсутствует
```

## Доступные объекты и функции

Единственная точка доступа скрипта к HTTP-запросу — набор whitelisted-функций, реализованных в `RequestFacade`.

| Функция | Сигнатура | Возвращает | Описание |
|---|---|---|---|
| `query(name)` | `query(String) -> String \| null` | Значение query-параметра | `null`, если параметр отсутствует |
| `header(name)` | `header(String) -> String \| null` | Значение заголовка | `null`, если заголовок отсутствует |
| `body()` | `body() -> String` | Тело запроса как строка | Без парсинга JSON, сырая строка |
| `method()` | `method() -> String` | HTTP-метод | Например, `"GET"`, `"POST"` |
| `pathSegment(index)` | `pathSegment(Number) -> String \| null` | Сегмент пути по индексу | `/api/customer/vip` → `pathSegment(2)` = `"vip"` |
| `jsonField(path)` | `jsonField(String) -> Any \| null` | Значение по JSONPath из тела запроса | `jsonField("user.role")`, `jsonField("items[0].id")` |
| `contains(str, substr)` | `contains(String, String) -> Boolean` | true/false | Безопасно обрабатывает `null` в любом аргументе |

### Примеры использования функций

```js
query("amount")            // GET /path?amount=2000 -> "2000"
header("Authorization")    // -> "Bearer secret-token-123"
pathSegment(0)             // /api/customer/vip -> "api"
pathSegment(1)             // /api/customer/vip -> "customer"
pathSegment(2)             // /api/customer/vip -> "vip"
jsonField("user.role")     // {"user":{"role":"admin"}} -> "admin"
jsonField("items[0].id")   // {"items":[{"id":42}]} -> 42
contains(header("Authorization"), "secret-token")  // true/false
```

Обратите внимание: `query()` и `header()` всегда возвращают строку. Для числового сравнения (`query("amount") > 1000`) интерпретатор автоматически приводит строку к числу — явного приведения типов в языке не предусмотрено. `jsonField()` возвращает нативный тип из JSON (число, строку, булево значение, объект) без строкового приведения.

## Вспомогательные функции (Фаза 4а)

| Функция | Сигнатура | Возвращает | Описание |
|---|---|---|---|
| `now()` | `now() -> String` | ISO-8601 timestamp с миллисекундами | Например, `"2026-07-28T01:02:03.456Z"` |
| `nowPlusDays(n)` | `nowPlusDays(Number) -> String` | ISO-8601 timestamp | `n` может быть отрицательным (дата в прошлом) |
| `uuid()` | `uuid() -> String` | Валидный UUID v4 | Детерминирован при заданном `seed`, см. ниже |
| `randomInt(min, max)` | `randomInt(Number, Number) -> Number` | Целое число в диапазоне `[min, max]` | Обе границы включительны; `min > max` бросает `ScriptExecutionException` |
| `matches(str, pattern)` | `matches(String, String) -> Boolean` | true/false | Проверка строки на соответствие regex-паттерну через движок RE2J |

### Примеры использования

```js
var orderId = uuid();
var expiresAt = nowPlusDays(7);
var issuedAt = nowPlusDays(-1);

return {
  "orderId": orderId,
  "issuedAt": issuedAt,
  "expiresAt": expiresAt,
  "riskLevel": randomInt(1, 10)
};
```

```js
var email = jsonField("user.email");
if (matches(email, "^[\w.]+@[\w.]+\.[a-z]+$")) {
  return { "status": 200, "body": { "valid": true } };
} else {
  return { "status": 400, "body": { "valid": false, "error": "invalid email format" } };
}
```

> **⚠️ Важно про backslash в строках:** WiremockJs не декодирует escape-последовательности внутри строковых литералов (кроме `\"` для завершения строки). Пишите regex как обычно, с одним backslash — `"\w"`, `"\."`, `"\d"` — а не с двойным, как это принято в Java-строках.

> **⚠️ Важно про безопасность regex:** `matches()` использует движок **RE2J** (Google RE2) вместо стандартного `java.util.regex`. Это архитектурно исключает ReDoS-атаки (катастрофический backtracking) — любое регулярное выражение гарантированно выполняется за линейное время `O(n)` от длины строки. Плата за это — RE2J **не поддерживает** обратные ссылки (`\1`, `\2`) и часть сложных lookahead/lookbehind конструкций. Для типовых задач мокирования (email, телефон, формат ID) этого более чем достаточно.

## Детерминизм через seed

Функции `random()`, `randomInt()` и `uuid()` используют общий генератор случайных чисел на уровне вызова скрипта. Если в `ScriptDefinition` указано поле `seed` (Integer), генератор инициализируется как `new Random(seed)`, и повторный вызов того же скрипта с тем же `seed` даёт **идентичную** последовательность значений — это делает chaos-сценарии воспроизводимыми в CI/CD.

```json
{
  "name": "Chaos 5 percent errors",
  "sourceCode": "if (random() < 0.05) { return { \"status\": 500 }; } else { return { \"status\": 200 }; }",
  "seed": 42
}
```

Без указания `seed` (поле `null` или отсутствует) генератор инициализируется обычным непредсказуемым способом — поведение как в обычном `Math.random()`.

`uuid()` при заданном `seed` генерирует UUID v4 на основе того же `Random`, что и `random()`/`randomInt()` — то есть весь набор случайных функций одновременно становится воспроизводимым или невоспроизводимым, в зависимости от `seed`.

## Формат возвращаемого значения

Скрипт обязан завершиться оператором `return`, возвращающим JSON-объект. Интерпретатор поддерживает три специальных поля в верхнем уровне возвращаемого объекта — они особым образом обрабатываются `ScriptTransformer`:

| Поле | Тип | Назначение |
|---|---|---|
| `status` | Number | HTTP-статус ответа. Если не указан — берётся статус из исходного стаба |
| `body` | Object | Тело ответа. Если не указано — телом становится весь возвращённый объект целиком |
| `headers` | Object (Map) | Дополнительные заголовки ответа, добавляются к стандартным |

### Пример с явным статусом и телом

```js
if (contains(header("Authorization"), "secret-token")) {
  return { "status": 200, "body": { "authorized": true } };
} else {
  return { "status": 401, "body": { "authorized": false } };
}
```

### Пример без явного статуса (весь объект становится телом)

```js
return { "approved": true };
```

Результат в ответе: `{"approved": true}` со статусом, взятым из исходного стаба (обычно 200).

## Admin API

Базовый путь: `/__admin/extensions/wiremock-js/scripts`.

| Метод | Путь | Описание |
|---|---|---|
| GET | `/scripts` | Список всех скриптов (краткая информация, без sourceCode) |
| GET | `/scripts?name=X` | Поиск по подстроке в имени (регистронезависимый) |
| GET | `/scripts/{id}` | Полная информация о скрипте, включая sourceCode |
| POST | `/scripts` | Создание нового скрипта |
| PUT | `/scripts/{id}` | Обновление существующего скрипта |
| DELETE | `/scripts/{id}` | Удаление скрипта |

### Модель ScriptDefinition (тело запроса для POST/PUT)

```json
{
  "name": "Approve by amount",
  "description": "Одобряет заявку, если сумма меньше 1000",
  "sourceCode": "var amount = query(\"amount\"); if (amount != null && amount > 1000) { return { \"approved\": false }; } else { return { \"approved\": true }; }",
  "seed": null
}
```

Поля `id`, `createdAt`, `updatedAt` генерируются автоматически и не передаются при создании. Поле `seed` опционально — указывается только если нужен воспроизводимый `random()`/`randomInt()`/`uuid()`.

## Примеры

Ниже — протестированные сценарии целиком, от создания скрипта до проверки curl-запросом.

### Пример 1 — одобрение по сумме (query-параметр, с переменной и null-проверкой)

**Создание скрипта:**

```bash
curl -X POST http://localhost:8888/__admin/extensions/wiremock-js/scripts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Approve by amount",
    "description": "Одобряет заявку, если сумма меньше 1000",
    "sourceCode": "var amount = query(\"amount\"); if (amount != null && amount > 1000) { return { \"approved\": false, \"reason\": \"limit exceeded\" }; } else { return { \"approved\": true }; }"
  }'
```

**Создание стаба** (замените `scriptId` на полученный из ответа выше):

```bash
curl -X POST http://localhost:8888/__admin/mappings \
  -H "Content-Type: application/json" \
  -d '{
    "request": { "method": "GET", "urlPath": "/api/orders/approve" },
    "response": {
      "status": 200,
      "transformers": ["wiremock-js"],
      "transformerParameters": { "scriptId": "<ID>" }
    }
  }'
```

**Проверка:**

```bash
curl "http://localhost:8888/api/orders/approve?amount=2000"
# {"approved":false,"reason":"limit exceeded"}

curl "http://localhost:8888/api/orders/approve?amount=500"
# {"approved":true}

curl "http://localhost:8888/api/orders/approve"
# {"approved":true}  — amount отсутствует, null-проверка защищает от исключения
```

### Пример 2 — проверка токена в заголовке (статус + тело)

**Создание скрипта:**

```bash
curl -X POST http://localhost:8888/__admin/extensions/wiremock-js/scripts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Auth by header",
    "description": "Проверяет наличие корректного токена авторизации",
    "sourceCode": "if (contains(header(\"Authorization\"), \"secret-token\")) { return { \"status\": 200, \"body\": { \"authorized\": true } }; } else { return { \"status\": 401, \"body\": { \"authorized\": false } }; }"
  }'
```

**Создание стаба:**

```bash
curl -X POST http://localhost:8888/__admin/mappings \
  -H "Content-Type: application/json" \
  -d '{
    "request": { "method": "GET", "urlPath": "/api/secure/resource" },
    "response": {
      "status": 200,
      "transformers": ["wiremock-js"],
      "transformerParameters": { "scriptId": "<ID>" }
    }
  }'
```

**Проверка:**

```bash
curl -H "Authorization: Bearer secret-token-123" http://localhost:8888/api/secure/resource
# {"authorized":true}

curl http://localhost:8888/api/secure/resource
# HTTP 401, {"authorized":false}
```

### Пример 3 — маршрутизация по path-сегменту

**Создание скрипта:**

```bash
curl -X POST http://localhost:8888/__admin/extensions/wiremock-js/scripts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Route by path segment",
    "description": "Возвращает разные ответы в зависимости от ID в пути",
    "sourceCode": "if (pathSegment(2) == \"vip\") { return { \"tier\": \"vip\", \"discount\": 20 }; } else { return { \"tier\": \"standard\", \"discount\": 0 }; }"
  }'
```

**Создание стаба с wildcard URL:**

```bash
curl -X POST http://localhost:8888/__admin/mappings \
  -H "Content-Type: application/json" \
  -d '{
    "request": { "method": "GET", "urlPathPattern": "/api/customer/.*" },
    "response": {
      "status": 200,
      "transformers": ["wiremock-js"],
      "transformerParameters": { "scriptId": "<ID>" }
    }
  }'
```

**Проверка:**

```bash
curl http://localhost:8888/api/customer/vip
# {"tier":"vip","discount":20}

curl http://localhost:8888/api/customer/regular
# {"tier":"standard","discount":0}
```

### Пример 4 — обработка тела запроса через jsonField() и переменные

**Создание скрипта:**

```bash
curl -X POST http://localhost:8888/__admin/extensions/wiremock-js/scripts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Order limit check",
    "description": "Блокирует заказы выше лимита для не-VIP клиентов",
    "sourceCode": "var token = header(\"Authorization\"); var order = jsonField(\"$\"); if (order.amount != null && contains(token, \"vip\") == false && order.amount > 1000) { return { \"status\": 400, \"body\": { \"error\": \"Limit exceeded\", \"details\": { \"maxLimit\": 1000, \"currentAmount\": order.amount } } }; } return { \"status\": 200, \"approved\": true };"
  }'
```

**Создание стаба:**

```bash
curl -X POST http://localhost:8888/__admin/mappings \
  -H "Content-Type: application/json" \
  -d '{
    "request": { "method": "POST", "urlPath": "/api/orders" },
    "response": {
      "status": 200,
      "transformers": ["wiremock-js"],
      "transformerParameters": { "scriptId": "<ID>" }
    }
  }'
```

**Проверка:**

```bash
curl -X POST http://localhost:8888/api/orders \
  -H "Authorization: Bearer plain-token" \
  -H "Content-Type: application/json" \
  -d '{"amount": 1500}'
# HTTP 400, {"error":"Limit exceeded","details":{"maxLimit":1000,"currentAmount":1500}}

curl -X POST http://localhost:8888/api/orders \
  -H "Authorization: Bearer vip-token" \
  -H "Content-Type: application/json" \
  -d '{"amount": 1500}'
# {"status":200,"approved":true}
```

### Пример 5 — chaos engineering с воспроизводимым random()

**Создание скрипта с seed:**

```bash
curl -X POST http://localhost:8888/__admin/extensions/wiremock-js/scripts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Flaky 5 percent errors",
    "description": "Симулирует 5% случайных внутренних ошибок для тестирования retry-логики",
    "sourceCode": "if (random() < 0.05) { return { \"status\": 500, \"body\": { \"error\": \"internal error\" } }; } else { return { \"status\": 200, \"body\": { \"ok\": true } }; }",
    "seed": 42
  }'
```

С заданным `seed: 42` каждый прогон CI-пайплайна против одного и того же стаба даёт одну и ту же последовательность ответов — воспроизводимость сохраняется даже при использовании `random()`.

### Управление скриптами

```bash
# Список всех скриптов
curl http://localhost:8888/__admin/extensions/wiremock-js/scripts

# Поиск по имени
curl "http://localhost:8888/__admin/extensions/wiremock-js/scripts?name=amount"

# Получение конкретного скрипта
curl http://localhost:8888/__admin/extensions/wiremock-js/scripts/<ID>

# Удаление скрипта
curl -X DELETE http://localhost:8888/__admin/extensions/wiremock-js/scripts/<ID>
```

> **Примечание для Windows/PowerShell:** используйте `curl.exe` вместо `curl` (алиас на `Invoke-WebRequest` иначе перехватывает команду и работает по-другому с флагом `-d`).

## Ограничения и защита

Проект включает встроенный `ScriptGuard`, ограничивающий скрипты до исполнения:

| Ограничение | Значение | Назначение |
|---|---|---|
| Максимальная длина скрипта | 2000 символов (конфигурируется через `-Dwiremockjs.max.script.length`) | Защита от чрезмерно сложных скриптов |
| Максимальная глубина вложенности `{}` | 5 уровней | Защита от переусложнённой логики |
| Таймаут исполнения | 100 мс | Защита от бесконечных циклов / зависаний (язык циклов не поддерживает, но защита оставлена как страховка) |
| Regex-движок | RE2J (линейный, без backtracking) | Архитектурная защита от ReDoS-атак в `matches()` |
| Лимит на количество переменных | Отсутствует (намеренно) | Лимит длины скрипта уже физически ограничивает количество `var` (~220 при лимите 2000 символов); отдельный лимит не защищает ни от чего дополнительно |
| Whitelist функций | `query, header, body, method, pathSegment, jsonField, contains, random, now, nowPlusDays, uuid, randomInt, matches` | Скрипт не имеет доступа ни к чему за пределами этого списка |

При превышении лимитов или обращении к неразрешённым конструкциям выбрасываются `ScriptTooLargeException`, `ScriptParseException` или `ScriptExecutionException`, которые WireMock превращает в HTTP 500 с сообщением об ошибке.