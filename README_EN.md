# WireMock JS Extension (EN)

Extension for WireMock that lets you describe dynamic stub logic using a simplified, JS-like scripting language. Scripts are stored separately from mappings and attached to a stub via `scriptId`, so the same logic can be reused across multiple stubs and managed through a dedicated Admin API.

## Table of Contents

- [How It Works](#how-it-works)
- [Installation and Running](#installation-and-running)
- [Script Syntax](#script-syntax)
- [Variables](#variables)
- [Dot Notation Field Access](#dot-notation-field-access)
- [null and Safe Comparisons](#null-and-safe-comparisons)
- [Available Objects and Functions](#available-objects-and-functions)
- [Helper Functions (Phase 4a)](#helper-functions-phase-4a)
- [Determinism via seed](#determinism-via-seed)
- [Return Value Format](#return-value-format)
- [Admin API](#admin-api-en)
- [Examples](#examples-en)
- [Limitations and Safeguards](#limitations-and-safeguards)

## How It Works

1. Create a script via the `wiremock-js` Admin API — you get back a `scriptId`.
2. Create a regular WireMock stub (`/__admin/mappings`), setting `"wiremock-js"` in `response.transformers` and passing `scriptId` in `transformerParameters`.
3. For every incoming request matching that stub, WireMock executes the script with access to the actual HTTP request data, and the script's result is used to build the response.

## Installation and Running

The project is built with Gradle and runs inside the official `wiremock/wiremock:3.13.1` Docker image.

```bash
./gradlew clean build
docker-compose up -d
```

The build automatically copies the JAR into `wiremock/extensions/wiremock-js-extension.jar`, which is mounted into the container and enabled via the `--extensions` flag in `docker-compose.yml`:

```yaml
command:
  - "--extensions=ru.mcs.wiremockjs.ScriptTransformer,ru.mcs.wiremockjs.admin.ScriptAdminApi"
```

Scripts are stored as JSON files in the directory set via `WIREMOCKJS_STORAGE_DIR` (default: `wiremock/scripts`).

Maximum script length is configurable via a system property, no rebuild required:

```bash
-Dwiremockjs.max.script.length=4000
```

Default is 2000 characters.

## Script Syntax

The language supports variables, conditionals, arithmetic, logical operators, whitelisted function calls, and returning a JSON object.

```js
var amount = query("amount");
if (amount != null && amount > 1000) {
  return { "approved": false, "reason": "limit exceeded" };
} else {
  return { "approved": true };
}
```

### Supported Constructs

| Construct | Example | Description |
|---|---|---|
| `var` | `var name = query("x");` | Variable declaration, function-scoped (lives for the whole script call) |
| `if / else` | `if (cond) { ... } else { ... }` | Conditional execution, else is optional |
| `return` | `return { "k": "v" };` | At least one reachable return is required |
| Arithmetic | `+ - * / %` | All operations work with numbers (double) |
| Comparison | `> >= < <=` | Numeric comparison |
| Equality | `== !=` | Null-safe; numeric if both operands are numeric, otherwise string-based |
| Logic | `&& \|\| !` | Logical AND, OR, NOT — with real short-circuit evaluation |
| Literals | `"string"`, `123`, `12.5`, `true/false`, `null`, `{ ... }` | JSON object literal, supports arbitrary nesting depth |
| Function call | `query("name")` | Only whitelisted functions, see below |
| Variable field access | `order.amount`, `order.details.city` | Only for variables declared via `var`, see below |
| Parentheses | `(expr)` | Expression grouping |

## Variables

Variables are declared with `var` and hold the result of any expression — including a function call result, a number, a string, or a JSON object.

```js
var token = header("Authorization");
var order = jsonField("$");
var discount = 0;

if (contains(token, "vip") && order.amount > 5000) {
  discount = 20;
}

return { "discount": discount };
```

Variables are function-scoped: a single flat scope for the entire script call, with no block nesting (`let`/`const` are not supported — a deliberate choice that keeps the interpreter simple given the script length limit is a few thousand characters).

## Dot Notation Field Access

If a variable holds a JSON object (`Map`), its fields can be accessed via dot notation, including arbitrary nesting depth:

```js
var order = jsonField("$");

var city = order.details.city;      // multi-level navigation
var amount = order.amount;          // single-level navigation
```

**Important:** this navigation only works for variables declared via `var`. Direct access to WireMock's internal objects (`request.method` and similar) remains blocked — if the base identifier isn't found among declared variables, the interpreter throws `ScriptExecutionException`. This is a deliberate architectural split: protection against accessing WireMock internals is preserved, while navigating user data becomes convenient.

If an intermediate or final field is missing from the object, navigation returns `null` instead of throwing:

```js
var order = jsonField("$");
var city = order.details.city;   // null if order.details doesn't exist
```

If the base value is not an object (e.g. a string or number) and the script tries to access a field via dot notation, this throws `ScriptExecutionException` with a clear message, because this is a logical bug in the script, not a normal situation:

```js
var count = 10;
return { "result": count.amount };  // ScriptExecutionException: not an object
```

Dot notation does **not support array indices** (`order.items.0.id`) — to access array elements, use full JSONPath inside `jsonField()`, e.g. `jsonField("items[0].id")`.

## null and Safe Comparisons

Missing values (a nonexistent query parameter, header, or JSON field) are represented as `null`. `== null` and `!= null` work predictably:

```js
var token = header("Authorization");
if (token == null) {
  return { "status": 401, "body": { "error": "missing token" } };
}
```

**Important:** arithmetic and numeric comparison (`+ - * / % > >= < <=`) with a `null` operand throw `ScriptExecutionException` instead of silently treating `null` as `0`. An explicit check is required before doing arithmetic on a potentially missing value:

```js
var amount = query("amount");
if (amount != null && amount > 1000) {   // null check comes first — short-circuit prevents the exception
  return { "approved": false };
}
```

`&&` and `||` implement real short-circuit evaluation: if `amount != null` is `false`, the right-hand side `amount > 1000` is never evaluated, so no exception is thrown.

`contains(str, substr)` safely handles `null` in either argument, returning `false` instead of throwing:

```js
contains(null, "x")       // false
contains("x", null)       // false
contains(header("Authorization"), "secret")  // false if the header is absent
```

## Available Objects and Functions

The only entry point for a script to access the HTTP request is a set of whitelisted functions implemented in `RequestFacade`.

| Function | Signature | Returns | Description |
|---|---|---|---|
| `query(name)` | `query(String) -> String \| null` | Query parameter value | `null` if the parameter is absent |
| `header(name)` | `header(String) -> String \| null` | Header value | `null` if the header is absent |
| `body()` | `body() -> String` | Request body as a string | No JSON parsing, raw string |
| `method()` | `method() -> String` | HTTP method | E.g. `"GET"`, `"POST"` |
| `pathSegment(index)` | `pathSegment(Number) -> String \| null` | Path segment by index | `/api/customer/vip` → `pathSegment(2)` = `"vip"` |
| `jsonField(path)` | `jsonField(String) -> Any \| null` | Value at a JSONPath in the request body | `jsonField("user.role")`, `jsonField("items[0].id")` |
| `contains(str, substr)` | `contains(String, String) -> Boolean` | true/false | Safely handles `null` in either argument |

### Function Usage Examples

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

Note: `query()` and `header()` always return a string. For numeric comparison (`query("amount") > 1000`), the interpreter automatically coerces the string to a number — there's no explicit type casting in the language. `jsonField()` returns the native JSON type (number, string, boolean, object) without string coercion.

## Helper Functions (Phase 4a)

| Function | Signature | Returns | Description |
|---|---|---|---|
| `now()` | `now() -> String` | ISO-8601 timestamp with milliseconds | E.g. `"2026-07-28T01:02:03.456Z"` |
| `nowPlusDays(n)` | `nowPlusDays(Number) -> String` | ISO-8601 timestamp | `n` can be negative (a date in the past) |
| `uuid()` | `uuid() -> String` | A valid UUID v4 | Deterministic when `seed` is set, see below |
| `randomInt(min, max)` | `randomInt(Number, Number) -> Number` | An integer in the `[min, max]` range | Both bounds are inclusive; `min > max` throws `ScriptExecutionException` |
| `matches(str, pattern)` | `matches(String, String) -> Boolean` | true/false | Checks whether a string matches a regex pattern, using the RE2J engine |

### Usage Examples

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

> **⚠️ Important — backslashes in strings:** WiremockJs does not decode escape sequences inside string literals (except `\"` to close the string). Write regexes with a single backslash — `"\w"`, `"\."`, `"\d"` — not doubled up as in Java string literals.

> **⚠️ Important — regex safety:** `matches()` uses the **RE2J** engine (Google RE2) instead of the standard `java.util.regex`. This architecturally rules out ReDoS attacks (catastrophic backtracking) — any regular expression is guaranteed to run in linear time `O(n)` relative to the string length. The trade-off is that RE2J does **not support** backreferences (`\1`, `\2`) and some complex lookahead/lookbehind constructs. For typical mocking tasks (email, phone, ID format checks), this is more than sufficient.

## Determinism via seed

`random()`, `randomInt()`, and `uuid()` share a single random number generator for the duration of a script call. If `ScriptDefinition` specifies a `seed` field (Integer), the generator is initialized as `new Random(seed)`, and re-running the same script with the same `seed` produces an **identical** sequence of values — making chaos-engineering scenarios reproducible in CI/CD.

```json
{
  "name": "Chaos 5 percent errors",
  "sourceCode": "if (random() < 0.05) { return { \"status\": 500 }; } else { return { \"status\": 200 }; }",
  "seed": 42
}
```

Without a `seed` (field is `null` or omitted), the generator initializes unpredictably, like ordinary `Math.random()`.

When a `seed` is set, `uuid()` generates a UUID v4 from the same `Random` instance used by `random()`/`randomInt()` — meaning the entire set of random functions becomes reproducible or non-reproducible together, depending on `seed`.

## Return Value Format

A script must end with a `return` statement returning a JSON object. The interpreter recognizes three special top-level fields in the returned object — handled specially by `ScriptTransformer`:

| Field | Type | Purpose |
|---|---|---|
| `status` | Number | HTTP response status. If omitted, the status from the original stub is used |
| `body` | Object | Response body. If omitted, the entire returned object becomes the body |
| `headers` | Object (Map) | Additional response headers, added to the standard ones |

### Example with Explicit Status and Body

```js
if (contains(header("Authorization"), "secret-token")) {
  return { "status": 200, "body": { "authorized": true } };
} else {
  return { "status": 401, "body": { "authorized": false } };
}
```

### Example Without Explicit Status (entire object becomes the body)

```js
return { "approved": true };
```

Response result: `{"approved": true}` with the status taken from the original stub (usually 200).

## Admin API {#admin-api-en}

Base path: `/__admin/extensions/wiremock-js/scripts`.

| Method | Path | Description |
|---|---|---|
| GET | `/scripts` | List of all scripts (brief info, no sourceCode) |
| GET | `/scripts?name=X` | Search by substring in name (case-insensitive) |
| GET | `/scripts/{id}` | Full script info, including sourceCode |
| POST | `/scripts` | Create a new script |
| PUT | `/scripts/{id}` | Update an existing script |
| DELETE | `/scripts/{id}` | Delete a script |

### ScriptDefinition Model (request body for POST/PUT)

```json
{
  "name": "Approve by amount",
  "description": "Approves the order if the amount is under 1000",
  "sourceCode": "var amount = query(\"amount\"); if (amount != null && amount > 1000) { return { \"approved\": false }; } else { return { \"approved\": true }; }",
  "seed": null
}
```

`id`, `createdAt`, `updatedAt` are generated automatically and not sent on creation. `seed` is optional — set it only if you need reproducible `random()`/`randomInt()`/`uuid()`.

## Examples {#examples-en}

Below are fully tested scenarios, from creating a script to verifying it with a curl request.

### Example 1 — Approve by Amount (query parameter, with a variable and null check)

**Create the script:**

```bash
curl -X POST http://localhost:8888/__admin/extensions/wiremock-js/scripts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Approve by amount",
    "description": "Approves the order if the amount is under 1000",
    "sourceCode": "var amount = query(\"amount\"); if (amount != null && amount > 1000) { return { \"approved\": false, \"reason\": \"limit exceeded\" }; } else { return { \"approved\": true }; }"
  }'
```

**Create the stub** (replace `scriptId` with the one returned above):

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

**Verify:**

```bash
curl "http://localhost:8888/api/orders/approve?amount=2000"
# {"approved":false,"reason":"limit exceeded"}

curl "http://localhost:8888/api/orders/approve?amount=500"
# {"approved":true}

curl "http://localhost:8888/api/orders/approve"
# {"approved":true}  — amount is absent, the null check protects against the exception
```

### Example 2 — Header Token Check (status + body)

**Create the script:**

```bash
curl -X POST http://localhost:8888/__admin/extensions/wiremock-js/scripts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Auth by header",
    "description": "Checks for a valid authorization token",
    "sourceCode": "if (contains(header(\"Authorization\"), \"secret-token\")) { return { \"status\": 200, \"body\": { \"authorized\": true } }; } else { return { \"status\": 401, \"body\": { \"authorized\": false } }; }"
  }'
```

**Create the stub:**

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

**Verify:**

```bash
curl -H "Authorization: Bearer secret-token-123" http://localhost:8888/api/secure/resource
# {"authorized":true}

curl http://localhost:8888/api/secure/resource
# HTTP 401, {"authorized":false}
```

### Example 3 — Routing by Path Segment

**Create the script:**

```bash
curl -X POST http://localhost:8888/__admin/extensions/wiremock-js/scripts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Route by path segment",
    "description": "Returns different responses depending on the ID in the path",
    "sourceCode": "if (pathSegment(2) == \"vip\") { return { \"tier\": \"vip\", \"discount\": 20 }; } else { return { \"tier\": \"standard\", \"discount\": 0 }; }"
  }'
```

**Create a stub with a wildcard URL:**

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

**Verify:**

```bash
curl http://localhost:8888/api/customer/vip
# {"tier":"vip","discount":20}

curl http://localhost:8888/api/customer/regular
# {"tier":"standard","discount":0}
```

### Example 4 — Processing the Request Body via jsonField() and Variables

**Create the script:**

```bash
curl -X POST http://localhost:8888/__admin/extensions/wiremock-js/scripts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Order limit check",
    "description": "Blocks orders above the limit for non-VIP customers",
    "sourceCode": "var token = header(\"Authorization\"); var order = jsonField(\"$\"); if (order.amount != null && contains(token, \"vip\") == false && order.amount > 1000) { return { \"status\": 400, \"body\": { \"error\": \"Limit exceeded\", \"details\": { \"maxLimit\": 1000, \"currentAmount\": order.amount } } }; } return { \"status\": 200, \"approved\": true };"
  }'
```

**Create the stub:**

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

**Verify:**

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

### Example 5 — Chaos Engineering with Reproducible random()

**Create the script with a seed:**

```bash
curl -X POST http://localhost:8888/__admin/extensions/wiremock-js/scripts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Flaky 5 percent errors",
    "description": "Simulates 5% random internal errors for testing retry logic",
    "sourceCode": "if (random() < 0.05) { return { \"status\": 500, \"body\": { \"error\": \"internal error\" } }; } else { return { \"status\": 200, \"body\": { \"ok\": true } }; }",
    "seed": 42
  }'
```

With `seed: 42` set, every CI pipeline run against the same stub produces the same sequence of responses — reproducibility is preserved even when using `random()`.

### Managing Scripts

```bash
# List all scripts
curl http://localhost:8888/__admin/extensions/wiremock-js/scripts

# Search by name
curl "http://localhost:8888/__admin/extensions/wiremock-js/scripts?name=amount"

# Get a specific script
curl http://localhost:8888/__admin/extensions/wiremock-js/scripts/<ID>

# Delete a script
curl -X DELETE http://localhost:8888/__admin/extensions/wiremock-js/scripts/<ID>
```

> **Note for Windows/PowerShell:** use `curl.exe` instead of `curl` (the `Invoke-WebRequest` alias intercepts the command otherwise and behaves differently with the `-d` flag).

## Limitations and Safeguards

The project includes a built-in `ScriptGuard` that validates scripts before execution:

| Limit | Value | Purpose |
|---|---|---|
| Maximum script length | 2000 characters (configurable via `-Dwiremockjs.max.script.length`) | Protection against overly complex scripts |
| Maximum `{}` nesting depth | 5 levels | Protection against overly convoluted logic |
| Execution timeout | 100 ms | Protection against infinite loops / hangs (the language doesn't support loops, but this safeguard is kept as insurance) |
| Regex engine | RE2J (linear, no backtracking) | Architectural protection against ReDoS attacks in `matches()` |
| Limit on the number of variables | None (intentional) | The script length limit already physically bounds the number of `var` declarations (~220 at the 2000-character limit); a separate limit wouldn't add any additional protection |
| Function whitelist | `query, header, body, method, pathSegment, jsonField, contains, random, now, nowPlusDays, uuid, randomInt, matches` | The script has no access to anything outside this list |

Exceeding these limits or referencing disallowed constructs throws `ScriptTooLargeException`, `ScriptParseException`, or `ScriptExecutionException`, which WireMock turns into an HTTP 500 response with an error message.