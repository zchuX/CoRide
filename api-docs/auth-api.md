# Auth API

Authentication and user identity: registration, verification, login, logout, password reset, token refresh, and current user profile.

## Endpoints

### Register

**POST** `/auth/register`

Creates an unverified user and sends a verification code (email or SMS). Rate limited.

**Headers:** `Content-Type: application/json`, `x-api-key: <apiKey>`

**Body:**
- `password` (String, required)
- `name` (String, required)
- One of: `email` (String) or `phone_number` (String)

**Responses:**
- **200** — Verification code sent. Body may indicate channel (e.g. email).
- **400** — Missing/invalid body (e.g. missing password, or neither email nor phone_number).
- **429** — Rate limit exceeded.

---

### Verify code

**POST** `/auth/verify-code`

Confirms the verification code with Cognito and creates the User in the database.

**Headers:** `Content-Type: application/json`, `x-api-key: <apiKey>`

**Body:**
- `code` (String, required)
- One of: `email` (String) or `phone_number` (String)

**Responses:**
- **200** — User verified and created.
- **400** — Invalid or expired code, or missing email/phone_number.
- **429** — Rate limit exceeded.

---

### Login

**POST** `/auth/login`

Authenticates with email or phone_number and password. Returns Cognito tokens.

**Headers:** `Content-Type: application/json`, `x-api-key: <apiKey>`

**Body:**
- `password` (String, required)
- One of: `email` (String) or `phone_number` (String)

**Responses:**
- **200** — Success. Body: `idToken`, `accessToken`, `refreshToken` (and optionally `expiresIn`).
- **400** — Invalid credentials.
- **423** — Account locked.
- **429** — Rate limit exceeded.

---

### Logout

**POST** `/auth/logout`

Revokes the given refresh token.

**Headers:** `Content-Type: application/json`, `x-api-key: <apiKey>`

**Body:**
- `refreshToken` (String, required)

**Responses:**
- **200** — Token revoked.
- **400** — Missing or invalid refreshToken.

---

### Reset password (send code)

**POST** `/auth/reset-password`

Sends a password-reset code to the user's email or phone. No request body beyond identifier.

**Headers:** `Content-Type: application/json`, `x-api-key: <apiKey>`

**Body:**
- One of: `email` (String) or `phone_number` (String)

**Responses:**
- **200** — Reset code sent.
- **400** — Missing email/phone_number.
- **429** — Rate limit exceeded.

---

### Reset password (confirm)

**POST** `/auth/reset-password/confirm`

Confirms the reset with code and new password. Same path as send; behaviour depends on body (if `code` and `newPassword` are present, confirm; otherwise send code).

**Headers:** `Content-Type: application/json`, `x-api-key: <apiKey>`

**Body:**
- One of: `email` (String) or `phone_number` (String)
- `code` (String, required for confirm)
- `newPassword` (String, required for confirm)

**Responses:**
- **200** — Password updated.
- **400** — Invalid code or missing fields.
- **429** — Rate limit exceeded.

---

### Refresh token

**POST** `/auth/refresh-token`

Returns new id and access tokens using a valid refresh token.

**Headers:** `Content-Type: application/json`, `x-api-key: <apiKey>`

**Body:**
- `refreshToken` (String, required)

**Responses:**
- **200** — Body: `idToken`, `accessToken`, `refreshToken` (and optionally `expiresIn`).
- **400** — Invalid or expired refreshToken.
- **429** — Rate limit exceeded.

---

### Me (current user)

**GET** `/auth/me`

Returns the authenticated user's profile. Requires a valid id token.

**Headers:** `Authorization: Bearer <idToken>`, `x-api-key: <apiKey>`

**Responses:**
- **200** — Body: `userArn`, `name`, `email`, `phone_number`, `description`, `photoUrl`, `totalPassengerDelivered`, `totalCarpoolJoined` (fields may be optional/null).
- **401** — Missing or invalid Bearer token.

---

### Login OTP (deprecated)

**POST** `/auth/login-otp/send`  
**POST** `/auth/login-otp/verify`

These endpoints return **410 Gone**. OTP login is disabled; use `/auth/login` with password instead.
