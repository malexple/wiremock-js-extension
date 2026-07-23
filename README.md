# WireMock JS Extension

Extension для WireMock, позволяющий описывать динамическую логику стабов на упрощённом JS-подобном скриптовом языке. Скрипты хранятся отдельно от маппингов и подключаются к стабу через `scriptId`, что позволяет переиспользовать одну и ту же логику в разных стабах и управлять ей через отдельный Admin API.

## Содержание

- [Как это работает](#как-это-работает)
- [Установка и запуск](#установка-и-запуск)
- [Синтаксис скрипта](#синтаксис-скрипта)
- [Доступные объекты и функции](#доступные-объекты-и-функции)
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

## Синтаксис скрипта

Язык поддерживает условия, арифметику, логические операторы, вызовы whitelisted-функций и возврат JSON-объекта.

```js
if (query("amount") > 1000) {
  return { "approved": false, "reason": "limit exceeded" };
} else {
  return { "approved": true };
}
```

### Поддерживаемые конструкции

| Конструкция | Пример | Описание |
|---|---|---|
| `if / else` | `if (cond) { ... } else { ... }` | Условное выполнение, else опционален |
| `return` | `return { "k": "v" };` | Обязателен хотя бы один достижимый return |
| Арифметика | `+ - * / %` | Все операции работают с числами (double) |
| Сравнение | `> >= < <=` | Числовое сравнение |
| Равенство | `== !=` | Числовое, если оба операнда числовые; иначе строковое |
| Логика | `&& \|\| !` | Логическое И, ИЛИ, НЕ |
| Литералы | `"строка"`, `123`, `12.5`, `true/false`, `{ ... }` | JSON-объект как литерал |
| Вызов функции | `query("name")` | Только whitelisted-функции, см. ниже |
| Скобки | `(expr)` | Группировка выражений |

### Что запрещено

Прямой доступ к полям через точку (`request.method`) не поддерживается и бросает `ScriptExecutionException` — весь доступ к запросу идёт только через функции ниже. Это осознанное архитектурное решение: скрипт не должен иметь произвольного доступа к внутренним объектам WireMock.

## Доступные объекты и функции

Единственная точка доступа скрипта к HTTP-запросу — набор whitelisted-функций, реализованных в `RequestFacade`.

| Функция | Сигнатура | Возвращает | Описание |
|---|---|---|---|
| `query(name)` | `query(String) -> String \| null` | Значение query-параметра | `null`, если параметр отсутствует |
| `header(name)` | `header(String) -> String \| null` | Значение заголовка | `null`, если заголовок отсутствует |
| `body()` | `body() -> String` | Тело запроса как строка | Без парсинга JSON, сырая строка |
| `method()` | `method() -> String` | HTTP-метод | Например, `"GET"`, `"POST"` |
| `pathSegment(index)` | `pathSegment(Number) -> String \| null` | Сегмент пути по индексу | `/api/customer/vip` → `pathSegment(2)` = `"vip"` |
| `contains(str, substr)` | `contains(String, String) -> Boolean` | true/false | Безопасно обрабатывает `null` как первый аргумент |

### Примеры использования функций

```js
query("amount")            // GET /path?amount=2000 -> "2000"
header("Authorization")    // -> "Bearer secret-token-123"
pathSegment(0)             // /api/customer/vip -> "api"
pathSegment(1)             // /api/customer/vip -> "customer"
pathSegment(2)             // /api/customer/vip -> "vip"
contains(header("Authorization"), "secret-token")  // true/false
```

Обратите внимание: `query()` и `header()` всегда возвращают строку. Для числового сравнения (`query("amount") > 1000`) интерпретатор автоматически приводит строку к числу — явного приведения типов в языке не предусмотрено.

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
  "sourceCode": "if (query(\"amount\") > 1000) { return { \"approved\": false }; } else { return { \"approved\": true }; }"
}
```

Поля `id`, `createdAt`, `updatedAt` генерируются автоматически и не передаются при создании.

## Примеры

Ниже — три протестированных сценария целиком, от создания скрипта до проверки curl-запросом.

### Пример 1 — одобрение по сумме (query-параметр)

**Создание скрипта:**

```bash
curl -X POST http://localhost:8888/__admin/extensions/wiremock-js/scripts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Approve by amount",
    "description": "Одобряет заявку, если сумма меньше 1000",
    "sourceCode": "if (query(\"amount\") > 1000) { return { \"approved\": false, \"reason\": \"limit exceeded\" }; } else { return { \"approved\": true }; }"
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
| Максимальная длина скрипта | 2000 символов | Защита от чрезмерно сложных скриптов |
| Максимальная глубина вложенности `{}` | 5 уровней | Защита от переусложнённой логики |
| Таймаут исполнения | 100 мс | Защита от бесконечных циклов / зависаний (язык циклов не поддерживает, но защита оставлена как страховка) |
| Whitelist функций | `query, header, body, method, pathSegment, contains` | Скрипт не имеет доступа ни к чему за пределами этого списка |

При превышении лимитов или обращении к неразрешённым конструкциям выбрасываются `ScriptTooLargeException` или `ScriptExecutionException`, которые WireMock превращает в HTTP 500 с сообщением об ошибке.
