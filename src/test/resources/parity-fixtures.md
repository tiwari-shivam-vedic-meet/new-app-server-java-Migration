# Parity fixtures (TEST values only — never production secrets)

These vectors were produced by tools independent of this codebase (OpenSSL CLI for
AES, a standalone Python HMAC script for JWT) and are asserted by the unit tests.
If the Java code ever stops matching these, encryption/login compatibility with the
mobile apps has broken.

## AES (crypto-js compatible, OpenSSL "Salted__" AES-256-CBC + MD5 KDF)

- TEST key:      `vedicmeet_test_key_do_not_use_in_prod`
- plaintext:     `{"phone":"9000000001","phonePrefix":"+91","foo":"bar","n":42}`
- reqData (b64): `U2FsdGVkX18LSGkUq4RA+7GtvXS/WSmZ0Xqzznz6k210gwMVlz+hMErEHjkFFqVYd9zahgjRicE2bj6YL7hIzy4bWOPbi0nvPmGsWe2VhVA=`

Reproduce:
```
printf '%s' '{"phone":"9000000001","phonePrefix":"+91","foo":"bar","n":42}' \
  | openssl enc -aes-256-cbc -a -A -salt -md md5 -pass pass:vedicmeet_test_key_do_not_use_in_prod
```
(The salt is random, so a fresh run yields a different base64 string — but decrypting
the fixed reqData above always yields the plaintext. Encryption is tested by round-trip.)

## JWT (HS256, jsonwebtoken compatible)

- TEST secret: `vedicmeet_test_jwt_secret_do_not_use_in_prod`
- claims:      phone=9000000001, phonePrefix=+91, role=user (exp set to year 2100)
- token:       `eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJwaG9uZSI6IjkwMDAwMDAwMDEiLCJwaG9uZVByZWZpeCI6Iis5MSIsInJvbGUiOiJ1c2VyIiwiaWF0IjoxNzU2NTAwMDAwLCJleHAiOjQxMDI0NDQ4MDB9.9E0-CRRnFpWszHGRIW2pephE6HlTCFJZLOaRmunHAl8`
