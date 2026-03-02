# Friends API — handler logic

User relationship (friends) is implemented with the **UserFriends** DynamoDB table and **userFriendsDAO**. Single-table design: partition key `userArn`, sort key `sk` = `PROFILE` (one profile per user) or `FRIEND#<friendUserArn>` (accepted friendships). Each accepted friendship is stored as two items—one under each user’s partition—with denormalized `friendUserArn` and `friendName` so listing friends is a single Query with no GSI or extra lookups.

**DAO:** `UserFriendsDAO` (env: `USER_FRIENDS_TABLE`). Methods: `getProfile`, `putProfile`, `listFriends`, `addFriendship`, `removeFriendship`, `areFriends`.

---

**api:** GET /api/friends

**handler logic:**
- Validate Bearer; JWT verify. Caller `userArn` = verified.sub.
- userFriendsDAO.listFriends(userArn, limit). Optional query param `limit` (default 100).
- No write.
- Return 200 with `{ "friends": [ { "userArn": "<friendUserArn>", "name": "<friendName>" } ] }` (denormalized from FRIEND# items).

---

**api:** POST /api/friends

**handler logic:**
- Validate Bearer; JWT verify. Caller `userArn` = verified.sub; caller name from JWT or userDAO.getUser(callerArn).map(_.name).getOrElse("").
- Validate body: `friendUserArn` required, non-empty; must not equal caller (else 400).
- userDAO.getUser(friendUserArn); if None return 404 (other user must exist).
- If userFriendsDAO.areFriends(callerArn, friendUserArn) return 200 (idempotent).
- userFriendsDAO.addFriendship(callerArn, callerName, friendUserArn, friendName). Transaction: two PutItems (one under each user’s partition with denormalized name).
- Return 200.

---

**api:** DELETE /api/friends/{friendUserArn}

**handler logic:**
- Validate Bearer; JWT verify. Caller `userArn` = verified.sub. Path `friendUserArn` is URL-decoded.
- userFriendsDAO.removeFriendship(callerArn, friendUserArn). Transaction: two DeleteItems (both sides). No-op if not friends.
- No read of other user required.
- Return 200.

---

**api:** GET /api/friends/profile

**handler logic:**
- Validate Bearer; JWT verify. Caller `userArn` = verified.sub.
- userFriendsDAO.getProfile(userArn).
- No write.
- Return 200 with `{ "userArn": "...", "name": "..." }` or 404 if no profile in UserFriends table (e.g. not yet synced via putProfile on verify/me).

---

**Profile sync (cross-cutting):** When a user is created or name is updated (e.g. in verify-code or me/profile update), call userFriendsDAO.putProfile(UserFriendProfile(userArn, name)) so the UserFriends table has an up-to-date profile and names used in addFriendship stay consistent.
