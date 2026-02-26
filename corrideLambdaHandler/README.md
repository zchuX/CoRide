# corrideLambdaHandler

Serverless Scala Lambda handlers for the Coride API (auth endpoints, utilities, and routing).

## What this package does
- Implements API Gateway Lambda handlers (e.g., `LoginHandler`, `RegisterHandler`, `MeHandler`).
- Provides shared utilities: JSON parsing, request header helpers, rate-limiting helpers.
- Depends on external DAOs: `userDAO` and `rateLimitDAO`.

## Prerequisites
- Java 17+
- sbt `1.9.7`

## Run tests
- `sbt test`

## Build assembly (fat JAR)
- `sbt assembly`
- Output: `target/scala-2.13/corrideLambdaHandler-assembly.jar`

## Key environment for runtime
- `AWS_REGION`: AWS region for SDK v1/v2 clients used by dependencies.
- `USERS_TABLE_NAME`, `USER_CONTACT_INDEX_TABLE_NAME`: used by `userDAO` consumers.
- `RATE_LIMIT_TABLE`: used by `rateLimitDAO` for rate limiting.

## Notes
- Unit tests avoid real AWS calls; rate-limiting tests use stubbed DAO.
 - Auth API contracts: `userId` is internal-only and auto-generated. Clients must not include `userId` in auth requests. Use `email` or `phone_number` for authentication; `username` is internal-only and not accepted from clients.

### Login
- Endpoint: `POST /auth/login`
 - Identify the account with exactly one of: `email` or `phone_number`.
 - Body: `{ "email"|"phone_number": "...", "password": "..." }`
- Returns tokens on success; clear error messages on validation issues.

### Register
- Endpoint: `POST /auth/register`
- Required fields: `name`, `password`, and exactly one of `email` or `phone_number`.
- Payload must not include `userId` or `username`.
- Behavior: sends an OTP to the provided identifier; account is persisted upon successful verification.

### Verify Code
- Endpoint: `POST /auth/verify-code`
- Required fields: exactly one of `email` or `phone_number`, and `code`.
- Confirms the account and persists the user; payload must not include `userId` or `username`.

### Reset Password
- Endpoint: `POST /auth/reset-password`
 - Identify the account with exactly one of: `email` or `phone_number`.
- Two flows:
  - Send reset code: include only the identifier; response `{"status":"ok","message":"reset code sent"}`.
  - Confirm reset: include the identifier plus `code` and `newPassword`; response `{"status":"ok","message":"password updated"}`.
- Validation:
  - Providing more than one identifier returns `400`.
  - Missing identifier returns `400`.
  - Invalid email or non–E.164 phone format returns `400`.
  - `userId` is rejected in payloads.

Examples (dev):

Send code using email:

```
curl -X POST "https://<api-id>.execute-api.us-east-1.amazonaws.com/dev/auth/reset-password" \
  -H "Content-Type: application/json" \
  -H "x-api-key: <dev-api-key>" \
  -d '{"email":"user@example.com"}'
```

Confirm reset with code:

```
curl -X POST "https://<api-id>.execute-api.us-east-1.amazonaws.com/dev/auth/reset-password" \
  -H "Content-Type: application/json" \
  -H "x-api-key: <dev-api-key>" \
  -d '{"email":"user@example.com","code":"123456","newPassword":"NewP@ssw0rd"}'
```