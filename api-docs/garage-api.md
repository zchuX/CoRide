# Garage API

User garage (cars) management. Each car has an optional Make, Model, Color, Car Plate, and State registered. The server generates a `carArn` for each car. All car fields except `carArn` and `userArn` are optional. Uses the Garage DynamoDB table (partition key `carArn`, GSI `gsiUserArn` on `userArn`).

## Endpoints

### List my cars

**GET** `/api/garage`

Returns all cars belonging to the authenticated user. Requires a valid id token.

**Headers:** `Authorization: Bearer <idToken>`, `x-api-key: <apiKey>`

**Query params (optional):**
- `limit` (Int, default 100) — Max number of cars to return.

**Responses:**
- **200** — Body: `{ "cars": [ { "carArn": "...", "userArn": "...", "make"?, "model"?, "color"?, "carPlate"?, "stateRegistered"? } ] }`. Only present fields are included.
- **401** — Missing or invalid Bearer token.

---

### Add car to garage

**POST** `/api/garage`

Creates a new car for the authenticated user. Server generates `carArn` (e.g. `car:<uuid>`). All body fields are optional.

**Headers:** `Authorization: Bearer <idToken>`, `Content-Type: application/json`, `x-api-key: <apiKey>`

**Body (all optional):**
- `make` (String)
- `model` (String)
- `color` (String)
- `carPlate` (String)
- `stateRegistered` (String)

**Responses:**
- **200** — Body: created car object with `carArn`, `userArn`, and any supplied fields.
- **401** — Missing or invalid Bearer token.

---

### Get a car

**GET** `/api/garage/{carArn}`

Returns a single car by `carArn`. Caller must be the owner (`userArn` matches the authenticated user).

**Headers:** `Authorization: Bearer <idToken>`, `x-api-key: <apiKey>`

**Responses:**
- **200** — Body: car object.
- **401** — Missing or invalid Bearer token.
- **403** — Not your car.
- **404** — Car not found.

---

### Update a car

**PUT** `/api/garage/{carArn}`

Updates an existing car. Only fields present in the body are updated; others are unchanged. Caller must be the owner.

**Headers:** `Authorization: Bearer <idToken>`, `Content-Type: application/json`, `x-api-key: <apiKey>`

**Body (all optional):**
- `make`, `model`, `color`, `carPlate`, `stateRegistered` (String)

**Responses:**
- **200** — Body: updated car object.
- **401** — Missing or invalid Bearer token.
- **403** — Not your car.
- **404** — Car not found.

---

### Delete a car

**DELETE** `/api/garage/{carArn}`

Deletes a car. Caller must be the owner.

**Headers:** `Authorization: Bearer <idToken>`, `x-api-key: <apiKey>`

**Responses:**
- **200** — Body: `{ "status": "ok", "message": "Car deleted" }`.
- **401** — Missing or invalid Bearer token.
- **403** — Not your car.
- **404** — Car not found.

---

## Data model (DynamoDB Garage table)

- **PK:** `carArn` (String), e.g. `car:<uuid>`
- **Attributes:** `userArn` (String, required), `make`, `model`, `color`, `carPlate`, `stateRegistered` (String, optional)
- **GSI:** `gsiUserArn` — partition key `userArn`; used to list all cars for a user.
- **Access patterns:**
  - Get car: `GetItem(PK=carArn)`.
  - List by user: `Query(GSI gsiUserArn, PK=userArn)`.
  - Add: `PutItem` with new `carArn` (server-generated).
  - Update: `PutItem` with same `carArn`.
  - Delete: `DeleteItem(PK=carArn)`.
