# Friends API

User relationship (friends) management. Uses a dedicated UserFriends DynamoDB table with single-table design: partition key `userArn`, sort key `sk` (`PROFILE` for profile, `FRIEND#<friendUserArn>` for each accepted friend). Each accepted friendship is stored under both users’ partitions with denormalized `friendUserArn` and `friendName` so listing friends requires no extra lookups.

## Endpoints

### List my friends

**GET** `/api/friends`

Returns the authenticated user’s accepted friends. Requires a valid id token.

**Headers:** `Authorization: Bearer <idToken>`, `x-api-key: <apiKey>`

**Query params (optional):**
- `limit` (Int, default 100) — Max number of friends to return.

**Responses:**
- **200** — Body: `{ "friends": [ { "userArn": "...", "name": "..." } ] }`. Each item includes denormalized `userArn` and `name`.
- **401** — Missing or invalid Bearer token.

---

### Add friend (accept friendship)

**POST** `/api/friends`

Creates an accepted friendship between the authenticated user and the given user. Idempotent if already friends. Both users must exist (e.g. in Users table). Caller is identified by JWT (`sub` → userArn).

**Headers:** `Authorization: Bearer <idToken>`, `Content-Type: application/json`, `x-api-key: <apiKey>`

**Body:**
- `friendUserArn` (String, required) — The other user’s ARN (e.g. Cognito sub).

**Responses:**
- **200** — Friendship created (or already exists).
- **400** — Missing `friendUserArn` or same as caller.
- **401** — Missing or invalid Bearer token.
- **404** — Other user not found.

---

### Remove friend

**DELETE** `/api/friends/{friendUserArn}`

Removes the accepted friendship between the authenticated user and the given user. No-op if not friends.

**Headers:** `Authorization: Bearer <idToken>`, `x-api-key: <apiKey>`

**Responses:**
- **200** — Friendship removed (or was not present).
- **401** — Missing or invalid Bearer token.

---

### Get my friends profile (UserFriends table)

**GET** `/api/friends/profile`

Returns the caller’s profile record stored in the UserFriends table (userArn + name). Used to sync or display the minimal profile kept in the friends table.

**Headers:** `Authorization: Bearer <idToken>`, `x-api-key: <apiKey>`

**Responses:**
- **200** — Body: `{ "userArn": "...", "name": "..." }`.
- **401** — Missing or invalid Bearer token.
- **404** — No profile in UserFriends table (e.g. not yet created).

---

## Data model (DynamoDB UserFriends table)

- **PK:** `userArn` (String)
- **SK:** `sk` (String)
  - `PROFILE` — One item per user: `userArn`, `name`.
  - `FRIEND#<friendUserArn>` — One item per accepted friend; attributes: `friendUserArn`, `friendName`, `createdAt`.
- **Access patterns:**
  - Get profile: `GetItem(PK=userArn, SK=PROFILE)`.
  - List friends: `Query(PK=userArn, SK begins_with "FRIEND#")`.
  - Add friendship: two `PutItem`s (one under each user’s partition) in a transaction.
  - Remove friendship: two `DeleteItem`s in a transaction.
- No GSI required for normal friend listing.
