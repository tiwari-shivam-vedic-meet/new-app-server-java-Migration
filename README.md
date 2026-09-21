# new-app-server-java

Java / Spring Boot rewrite of the VedicMeet `new-app-server`, done as a **strangler
migration**: this service runs *alongside* the existing Node service, shares the
**same MongoDB and Redis**, and exposes migrated endpoints under a **`/v2`** prefix.
Business logic is ported unchanged — only the API version prefix is added. Endpoints
are moved over one module at a time; a gateway routes `/v2/*` here and everything
else stays on Node until it's migrated.

This started as the **Week-1 foundation** (the three compatibility gates below). Since then many
read endpoints (`/v2` master, category, cms, gallery, explore, banner, membership-discount,
notification, support i18n, user balance, and the consultant-discovery family) and the Prompt-C
idempotent writes have been ported behind the contract-test harness. The wallet/payments (D),
call-lifecycle (E) and real-time (F) cores are present but **shadow-only, unwired, and gated off**
pending human review — see `MIGRATION_STATUS.md` and the `PROMPT_*_STATUS.md` trackers. The full
Spring context boots (`AppServerContextLoadTest`), but this is **NOT** production-ready: it must keep
running as a shadow/strangler service and cannot replace Node yet.

## Why these three gates come first

If any of these is even slightly wrong, every real request fails on day one — so
they were built and tested before any business endpoint was ported.

| Gate | Node source | Java | The trap |
|---|---|---|---|
| **Payload encryption** | `utils/classes/cryptography.js` (crypto-js) | `crypto/CryptoUtil.java` | crypto-js with a string key uses OpenSSL **`Salted__`** format: AES-256-CBC, key+IV from **EVP_BytesToKey (MD5)**, output `base64("Salted__"+salt+cipher)`. A plain `AES/CBC` port with the key as raw bytes will NOT interoperate. |
| **Login token** | `utils/classes/tokenizer.js` (jsonwebtoken) | `security/JwtService.java` | HS256, secret as raw UTF-8 bytes, `iat`/`exp` auto-added. Some Java JWT libs reject short secrets that Node accepts, breaking existing tokens — so this is hand-rolled on HMAC-SHA256. |
| **Response contract** | every `res.json(...)` | `web/ApiResponse.java` | VedicMeet returns **HTTP 200** even on logical failure, with `success:false` in the body. Returning a 4xx would break the apps, which switch on `success`. |

The auth header handling in `security/JwtAuthFilter.java` mirrors `utils/middle-wares.js`
exactly (`vm-user-auth` for users; `authorization` else `vm-user-auth`, with optional
`Bearer ` stripping, for consultants/shared).

## What's inside

```
src/main/java/com/vedicmeet/appserver/
  AppServerApplication.java      Spring Boot entry point
  crypto/CryptoUtil.java         crypto-js-compatible AES (encrypt/decrypt)
  crypto/CryptoService.java      object <-> reqData helpers (like the Node validators)
  security/JwtService.java       jsonwebtoken-compatible HS256 sign/verify
  security/JwtAuthFilter.java    token extraction matching middle-wares.js
  security/RoleInterceptor.java  enforces @RequireRole with Node's exact responses
  security/RequireRole.java      per-endpoint role gate (replaces route middleware)
  security/CurrentUser*.java     @CurrentUser param injection of the caller
  security/AuthPrincipal.java    the authenticated caller (phone/prefix/role)
  security/Role.java             role string values (must match Node)
  config/MongoConfig.java        pool + secondary reads + timeouts (shared DB)
  config/RedisConfig.java        Lettuce RedisTemplate (string keys / JSON values)
  config/WebConfig.java          CORS + interceptor + argument-resolver wiring
  web/ApiResponse.java           the { success, code, message, result } envelope
  web/GlobalExceptionHandler.java  200-{success:false} / 401 error mapping
  web/SecurityHeadersFilter.java security response headers on every response
  web/RequestLoggingFilter.java  correlation id (MDC) + access log
  health/HealthController.java   GET /v2/health (first migrated endpoint)
  example/ExampleSecuredController.java  TEMPLATE showing the migration pattern
src/test/java/...                parity tests (Crypto + JWT)
src/test/resources/parity-fixtures.md   test vectors + how they were produced
Dockerfile, docker-compose.yml   build+run with only Docker (no local JDK/Maven)
.env.example, SETUP.md           config template + run guide
```

## Production hardening built in

- **Security:** per-endpoint role gates (`@RequireRole`) reproducing the Node
  middleware responses exactly (200 `{success:false}` for missing token / wrong
  role, 401 for an invalid/expired token); security response headers; configurable
  CORS (lock origins down per environment); no server-side session (stateless →
  horizontally scalable); request header/body size limits; secrets only via env.
- **Scalability:** bounded Mongo connection pool with `secondaryPreferred` reads to
  keep load off the primary, plus fail-fast timeouts so a slow DB degrades instead
  of cascading; Lettuce Redis pool; tuned Tomcat threads; response compression;
  liveness/readiness probes for autoscalers.
- **Robustness:** every request gets a correlation id echoed in `X-Correlation-Id`
  and stamped on every log line; graceful shutdown drains in-flight requests on
  deploy; centralized exception handling; retryable reads/writes.

## Requirements

- JDK 17
- Maven 3.9+ (or use the wrapper once added)

## Configuration (environment variables — never commit secrets)

| Var | Meaning |
|---|---|
| `MONGO_READ_URI` | Connection string to the **shared** Mongo (read connection for Week-1). |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Shared Redis. |
| `CRYPTO_SECRET_KEY` | Same value as the Node service (`CRYPTO_SECRET_KEY`). |
| `JWT_SECRET` | Same value as the Node service (`JWT_SECRET`). |
| `PORT` | Defaults to `8081` (runs beside Node; gateway routes `/v2` here). |

> Secrets must match Node's for tokens/payloads to interoperate. **Do not point
> `MONGO_READ_URI` at production from a laptop or CI** — use the test database. Any
> production secrets previously shared in chat should be rotated.

## Build & test

```bash
mvn test        # runs the parity tests — these must be green before migrating anything
mvn spring-boot:run
curl http://localhost:8081/v2/health
```

The parity tests assert against fixtures produced by **independent tools** (OpenSSL
CLI for AES, a standalone HMAC script for JWT), so a green run means genuine
cross-language compatibility, not just internal self-consistency.

## How a new endpoint gets migrated (the loop, from Week 2 on)

1. Read the Node route + its service function; port the logic 1:1 into a Spring
   `@RestController` + service under `/v2/...`.
2. Reuse `CryptoUtil` for `reqData`, `JwtService`/`JwtAuthFilter` for auth,
   `ApiResponse` for the envelope. Do not change business behavior.
3. Add a contract test: same request to Node and to Java, assert identical
   response. Only then flip the gateway for that route.
4. Start with **read-only** endpoints; writes (wallet, call lifecycle, sockets,
   queues) come in later phases.
