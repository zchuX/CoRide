# User Search API

Search for users by **email**, **phone number**, or **name**. Email and phone are unique identifiers (at most one user per match). Name is non-unique; results are all users whose normalized name matches (normalized = no spaces, lowercase).

## Endpoint

### Search users

**GET** `/api/users/search`

Returns a list of users matching the criteria. Requires a valid id token.

**Headers:** `Authorization: Bearer <idToken>`, `x-api-key: <apiKey>`

**Query params:**
- `type` (String, required) — One of: `email`, `phone`, `name`.
- `q` (String, required) — Search value: email address, phone number, or name (for name, normalized automatically: no spaces, lowercase).

**Responses:**
- **200** — Body: `{ "users": [ { "userArn": "...", "name": "...", "photoUrl": "..." | null } ] }`.
  - For `type=email` or `type=phone`: list length is 0 or 1 (unique identifiers).
  - For `type=name`: list length 0 or more (all users with matching normalized name).
- **400** — Missing or invalid `type` or `q`.
- **401** — Missing or invalid Bearer token.

## Data model (Users table)

- **GSI:** `gsiNormalizedName` — partition key `normalizedName` (STRING). Used for name search. Each user item stores `normalizedName = name` with spaces removed and lowercased so that listing by name is a single Query.
