# new-app-server-java

Java/Spring Boot service for VedicMeet, built as a strangler migration off the existing Node backend. It runs alongside Node, shares the same MongoDB and Redis, and serves migrated endpoints under `/v2`. Business logic is ported as-is — only the version prefix changes. Endpoints move over module by module while a gateway routes `/v2/*` here and everything else stays on Node.

## Stack

- Java 17, Spring Boot
- MongoDB, Redis (shared with the Node service)
- Maven

## Structure

```
src/main/java/com/vedicmeet/appserver/
  crypto/     AES encryption compatible with the Node crypto-js format
  security/   JWT auth, role checks, header parsing (mirrors Node middleware)
  config/     Mongo/Redis/web config
  web/        response envelope + global error handling
  health/     health check endpoint
src/test/java/...   crypto + JWT parity tests
```

## Setup

Copy `.env.example` to `.env` and fill in:

| Var | Notes |
|---|---|
| `MONGO_READ_URI` | shared Mongo connection |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | shared Redis |
| `CRYPTO_SECRET_KEY` | must match the Node service |
| `JWT_SECRET` | must match the Node service |
| `PORT` | defaults to `8081` |

Don't point this at production data from a laptop — use a test database.

## Run

```bash
mvn test
mvn spring-boot:run
curl http://localhost:8081/v2/health
```

Docker works too — see `Dockerfile` / `docker-compose.yml`.

## Adding an endpoint

1. Port the Node route/service 1:1 into a `@RestController` under `/v2`.
2. Reuse the existing crypto/JWT/response-envelope helpers instead of writing new ones.
3. Add a contract test comparing the Node and Java responses.
4. Flip the gateway for that route once tests pass.
