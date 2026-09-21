# Setup & run

## 0. Secrets — read this first

Nothing secret is committed. All secrets come from environment variables (a local
`.env`, which is gitignored). For local testing use the **TEST** values in
`src/test/resources/parity-fixtures.md`, not production keys. Do **not** point
`MONGO_READ_URI` at production from a laptop or CI, and rotate any production secret
that has been shared in chat.

```bash
cp .env.example .env   # then fill in values
```

## 1. Build & test — pick one path

### A. Docker only (no JDK/Maven needed) — recommended
```bash
docker build -t new-app-server-java .   # compiles + runs the parity tests
```
The image only builds if the parity tests pass.

### B. Local Maven (JDK 17 + Maven 3.9+)
```bash
mvn clean test        # parity gates — must be green before migrating anything
mvn spring-boot:run
```

### C. IDE
Open the folder in IntelliJ IDEA or VS Code (Java + Spring extensions). Both
auto-import the Maven project and download dependencies. Run `AppServerApplication`.

> This service targets **JDK 17**. (It was scaffolded on a machine without a JDK, so
> it has not been compiled here — path A or B does the first real compile. The
> crypto/JWT algorithms were verified independently with OpenSSL and a reference
> HMAC implementation; see below.)

## 2. Smoke test

```bash
curl http://localhost:8081/v2/health
# -> {"success":true,"code":200,"message":"OK","result":{"service":...,"mongo":"up"}}

curl http://localhost:8081/actuator/health/readiness
```

## 3. Verifying real compatibility with the running Node service

The unit tests prove compatibility against fixed vectors. To prove it against your
actual system:

1. On the TEST server, encrypt any dummy JSON with the **test** `CRYPTO_SECRET_KEY`
   (the `/crypto/encrypt` route or `openssl enc -aes-256-cbc -a -A -salt -md md5
   -pass pass:$CRYPTO_SECRET_KEY`), then confirm `POST /v2/example/echo` returns it.
2. Take a JWT the Node login already issues (test account) and call
   `GET /v2/example/whoami` with it in `vm-user-auth` — it must succeed.

## 4. What runs where

- Port `8081`, alongside the Node service. A gateway routes `/v2/*` here; everything
  else stays on Node until migrated.
- `/actuator/health/liveness` and `/actuator/health/readiness` are for the
  orchestrator (K8s/ECS) and the gateway.
- Every response carries `X-Correlation-Id`; every log line carries the same id.

## 5. Reference

- `README.md` — architecture, the three compatibility gates, the per-endpoint
  migration loop.
- `src/test/resources/parity-fixtures.md` — test vectors and how they were produced.
