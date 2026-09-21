# Contract-test harness

Guarantees each migrated Java `/v2` endpoint returns the **same** response as the
current Node service before it goes live. No npm dependencies — uses Node 18+'s
built-in `fetch`.

## How it works

1. **Record** golden responses from the current Node service (source of truth).
2. **Replay** the identical requests against the Java service.
3. **Normalize** (strip volatile fields) and **deep-diff**; any mismatch fails.

An endpoint may only be enabled on `/v2` when its diff is 100% clean. Wire `run.js`
into CI as a required check.

## Usage

Tokens/secrets come from env vars (never hard-coded in fixtures — fixtures use
`${USER_JWT}` placeholders). Use a TEST account token and the TEST Node server.

```bash
# 1) Record golden responses from the TEST Node server
NODE_BASE_URL=https://<test-node-host> USER_JWT=<test-user-token> node record.js

# 2) Start the Java service (locally or in the test env), then diff
JAVA_BASE_URL=http://localhost:8081 USER_JWT=<test-user-token> node run.js
```

`run.js` exits non-zero if any case differs, printing the exact field paths.

## Files

- `fixtures/*.json` — one array of cases per module. Each case has `name`,
  `nodePath` (real Node path, e.g. `/api/master`), `javaPath` (`/v2/master`),
  `method`, `headers`, optional `body`, and an `ignore` list of dot-paths to skip
  (supports `field[]` for every array element and `*` for any key).
- `golden/` — recorded Node responses (git-ignored; may contain real data).
- `normalize.js` / `diff.js` / `lib.js` — the machinery.
- `record.js` / `run.js` — the two entry points.

## Adding an endpoint

Add a case to the relevant fixtures file (or a new file), `record.js`, port the Java
endpoint, then `run.js` until green. The two parity unit tests in the Java project
(`CryptoUtilTest`, `JwtServiceTest`) always run alongside as the crypto/JWT gate.
