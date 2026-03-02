# User Search API — handler logic

Search uses the **Users** table and **UserContactIndex** for email/phone (unique), and **Users** table **GSI gsiNormalizedName** for name (non-unique). Normalized name = no spaces, lowercase. UserDAO stores `normalizedName` on each user (set on create and when name is updated).

**DAO:** `UserDAO`. Methods: `getUserByEmail`, `getUserByPhone`, `listUsersByNormalizedName(normalizedName, limit)`, `normalizeName(name)`.

---

**api:** GET /api/users/search

**handler logic:**
- Validate Bearer; JWT verify.
- Validate query: `type` required, one of `email` | `phone` | `name`; `q` required, non-empty. Else 400.
- If `type=email`: userDAO.getUserByEmail(q). Fold to List (0 or 1). Return 200 with `{ "users": [ { "userArn", "name", "photoUrl" } ] }`.
- If `type=phone`: userDAO.getUserByPhone(q). Fold to List (0 or 1). Return 200 same shape.
- If `type=name`: normalized = userDAO.normalizeName(q). If empty after normalize return 200 with `{ "users": [] }`. Else userDAO.listUsersByNormalizedName(normalized, limit). Return 200 with same response shape (list length 0 or more).
- Response items: minimal public fields only (userArn, name, photoUrl); do not expose email/phone in search results.
