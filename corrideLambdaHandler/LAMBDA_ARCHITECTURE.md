# Lambda API Architecture

This document describes, per Lambda handler: **authentication/validation**, **data flow**, and **DAO interactions** (read/write/transaction) for the CoRide API.

---

## Table of contents

1. [Auth handlers](#auth-handlers)
2. [Trip handlers](#trip-handlers)
3. [User-group handlers](#user-group-handlers)
4. [DAO transaction reference](#dao-transaction-reference)

---

## Auth handlers

### RegisterHandler — `POST /auth/register`

| Aspect | Details |
|--------|--------|
| **Authentication** | None (public). |
| **Validation** | Body: `password` (min 6 chars), exactly one of `email` or `phone_number` (format validated), `name` non-empty. Forbidden: `userId`, `username`. Rate limit: per-IP (5/5min), per-email/per-phone OTP issuance. |
| **Processing** | Cognito `signUp`; if UNCONFIRMED already, resend code and return 200. OTP validity window (30 min) stored in rate table. |
| **DAO / storage** | **RateLimitDAO**: not used. **DynamoDB (rate table)**: 1× **PutItem** — `otp:issued:user:{username}` with TTL (now+30min). No UserDAO write; user record is created in **VerifyCodeHandler** after OTP confirmation. |

---

### VerifyCodeHandler — `POST /auth/verify-code`

| Aspect | Details |
|--------|--------|
| **Authentication** | None (public). |
| **Validation** | Body: `code` required, exactly one of `email` or `phone_number`. Forbidden: `userId`, `username`. Per-IP rate limit for verify. OTP must be within issuance window (read from rate table). |
| **Processing** | If Cognito user already CONFIRMED → ensure profile exists (createUser) and return 200. Else: confirm SignUp with Cognito, mark OTP used (PutItem + DeleteItem on rate table), then create user profile. |
| **DAO / storage** | **Rate table**: 1× **GetItem** (issued key), 1× **PutItem** (used key), 1× **DeleteItem** (issued key). **UserDAO.createUser(user)**: **Transaction** — see [UserDAO transactions](#userdao-transactions). |

---

### LoginHandler — `POST /auth/login`

| Aspect | Details |
|--------|--------|
| **Authentication** | None (public). |
| **Validation** | Body: `password` required, exactly one of `email` or `phone_number`. Forbidden: `userId`, `username`. Account lock check (GetItem on rate table). Per-IP login rate limit. |
| **Processing** | Cognito `initiatePasswordAuth`. On success return tokens. On failure: RateLimitDAO checkAndIncrement; if over threshold, write lock key to rate table and return 423. |
| **DAO / storage** | **Rate table**: 1× **GetItem** (lock key). **RateLimitDAO**: 1× **checkAndIncrement** (read + conditional update on rate table). On lock: 1× **PutItem** (lock key with TTL). No UserDAO. |

---

### MeHandler — `GET /auth/me`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required; JWT verified via JwtUtils. User must exist in UserDAO (profile initialized). |
| **Validation** | None beyond auth. |
| **Processing** | Resolve user by `verified.sub` or username → UserDAO.getUser; build JSON from User. |
| **DAO / storage** | **UserDAO**: 1× **getUser** (read by userArn/sub). |

---

### LogoutHandler / ResetPasswordHandler / RefreshTokenHandler

- **Logout**: Cognito global sign-out; no DAO.
- **ResetPassword / ResetPassword/confirm**: Cognito flows; no UserDAO/trip DAO.
- **RefreshToken**: Cognito refresh; no DAO.

---

## Trip handlers

### GetUserTripsHandler — `GET /api/trips?completed=true|false`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required; JWT verified. Caller = `verified.sub`. |
| **Validation** | Query `completed` required (true → "completed", false → "uncompleted"). |
| **Processing** | Query UserTrips by userStatusKey = `{userArn}-{completed|uncompleted}`; dedupe and sort; build list from UserTrip only (no per-trip metadata fetch). |
| **DAO / storage** | **TripDAO**: 1× **queryUserTripsByStatus** (Query on UserTrips GSI). |

---

### GetTripByIdHandler — `GET /api/trips/{tripArn}`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required; JWT verified. |
| **Validation** | None beyond auth. |
| **Processing** | Load trip metadata; resolve current user's UserTrip for `userTripStatus`; return trip + status. |
| **DAO / storage** | **TripDAO**: 1× **getTripMetadata** (GetItem), 1× **getUserTrip** (GetItem by userTripArn). |

---

### ListTripUsersHandler — `GET /api/trips/{tripArn}/users`

| Aspect | Details |
|--------|--------|
| **Authentication** | None in handler (API key only if enforced at gateway). |
| **Validation** | None. |
| **Processing** | List all UserTrip rows for tripArn. |
| **DAO / storage** | **TripDAO**: 1× **listUsersByTrip** (Query on UserTrips by tripArn GSI). |

---

### CreateTripHandler — `POST /api/trips`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required; JWT verified. Driver (if provided) must be the authenticated user (verified.sub or currentUserArn). |
| **Validation** | Body: `startTime` required; optional start, destination, car, notes, groups. Driver must equal caller. TripValidation for duplicate users across driver + groups. |
| **Processing** | Generate tripArn; resolve driver from UserDAO if driver set; build TripMetadata and groups; if driver present call createTripWithDriver else createTrip; then 2× getTripMetadata for response. |
| **DAO / storage** | **UserDAO**: 2× **getUser** (current user, driver lookup). **TripDAO**: 1× **createTrip** or 1× **createTripWithDriver** (transaction — see [TripDAO transactions](#tripdao-transactions)), then 2× **getTripMetadata** (read). |

---

### UpdateTripMetadataHandler — `PUT /api/trips/{tripArn}`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required; userId from MeHandler.decode (router). Only driver or a member of any group on the trip may update. |
| **Validation** | Body: optional startTime, notes, locations. If locations provided: must be permutation of existing; cannot move an already-arrived location to a later position. |
| **Processing** | Load trip; check driver or group membership (UserGroupsDAO); apply updates; updateTripMetadata. |
| **DAO / storage** | **TripDAO**: 1× **getTripMetadata**, 1× **updateTripMetadata** (single UpdateItem, conditional on version). **UserGroupsDAO**: 1× **listUserGroupRecordsByTripArn** (Query). |

---

### StartTripHandler — `POST /api/trips/{tripArn}/start`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required; only the trip driver may start. |
| **Validation** | Trip status must be "Upcoming". |
| **Processing** | Set trip status to InProgress; optionally mark first location arrived and set currentStop; updateTripMetadata then setUserTripStatusesForTrip(InProgress). |
| **DAO / storage** | **TripDAO**: 1× **getTripMetadata**, 1× **updateTripMetadata** (write), 1× **setUserTripStatusesForTrip** — which does 1× **listUsersByTrip** (Query) + N× **updateUserTripStatus** (UpdateItem per UserTrip). No transaction. |

---

### FlipLocationArrivalHandler — `POST /api/trips/{tripArn}/locations/{locationName}/arrival`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required; only driver or a member of a group whose start/destination equals locationName may mark arrival. |
| **Validation** | Body: tripArn, locationName, arrived (true). Trip must have started (now >= startTime). |
| **Processing** | Mark location arrived, reorder locations (arrived first by time), set currentStop; if all locations arrived set status Completed and completionTime; updateTripMetadata. |
| **DAO / storage** | **TripDAO**: 1× **getTripMetadata**, 1× **updateTripMetadata** (write). **UserGroupsDAO**: 1× **listUserGroupRecordsByTripArn**. |

---

### LeaveTripHandler — `POST /api/trips/{tripArn}/leave`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required; caller must be in a group on the trip. |
| **Validation** | User must be in at least one group for this trip. |
| **Processing** | Remove user from that group (update users list); updateUserGroup (transaction); if no registered users left, deleteTrip (transaction). |
| **DAO / storage** | **TripDAO**: 1× **getTripMetadata**, **UserGroupsDAO**: 1× **listUserGroupRecordsByTripArn**. Then **TripDAO**: 1× **updateUserGroup** (transaction — see [TripDAO transactions](#tripdao-transactions)), then optionally 1× **listUserGroupRecordsByTripArn** (via groupsDAO), and if empty 1× **deleteTrip** (transaction). |

---

### BecomeDriverHandler — `POST /api/trips/{tripArn}/driver`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required. |
| **Validation** | None beyond auth. |
| **Processing** | Resolve driver name/photo from UserDAO; set driver, driverName, driverPhotoUrl, driverConfirmed=true on trip; updateTripMetadata. |
| **DAO / storage** | **TripDAO**: 1× **getTripMetadata**, 1× **updateTripMetadata** (write). **UserDAO**: 1× **getUser** (driver). |

---

## User-group handlers

### GetUserGroupHandler — `GET /api/user-groups/{groupArn}`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required. |
| **Validation** | None. Path groupArn is URL-decoded in router. |
| **Processing** | Load group by groupArn; return as JSON. |
| **DAO / storage** | **TripDAO**: 1× **getUserGroup** (GetItem). |

---

### CreateUserGroupHandler — `POST /api/user-groups`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required. |
| **Validation** | Body: tripArn, groupArn, groupName, start, destination, pickupTime; optional users, numAnonymousUsers. Trip must exist. TripValidation: no duplicate users in trip (driver + all groups). |
| **Processing** | addUserGroup(tripArn, newGroup, expectedVersion). |
| **DAO / storage** | **TripDAO**: 1× **getTripMetadata**, 1× **listUserGroupRecordsByTripArn**, 1× **addUserGroup** (transaction — see [TripDAO transactions](#tripdao-transactions)). |

---

### UpdateUserGroupHandler — `PUT /api/user-groups/{groupArn}`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required; caller must be a member of the group. |
| **Validation** | Body: groupArn required; optional groupName, start, destination, pickupTime, users, numAnonymousUsers. TripValidation for no duplicate users across groups. |
| **Processing** | updateUserGroup (transaction). Then getUserGroup for response. |
| **DAO / storage** | **TripDAO**: 1× **getUserGroup**, 1× **getTripMetadata**, 1× **listUserGroupRecordsByTripArn**, 1× **updateUserGroup** (transaction), 1× **getUserGroup**. |

---

### AcceptInvitationHandler — `POST /api/user-groups/{groupArn}/accept`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required; user must be in the group and have accept=false. |
| **Validation** | UserTrip for this user must exist with tripStatus Invitation. |
| **Processing** | Set user accept=true in group; set UserTrip tripStatus to effective trip status (e.g. Upcoming). acceptUserInvitation (transaction). |
| **DAO / storage** | **TripDAO**: 1× **getUserGroup**, 1× **userTripArn** + **getUserTrip**, 1× **acceptUserInvitation** (transaction — see [TripDAO transactions](#tripdao-transactions)). |

---

### JoinUserGroupHandler — `POST /api/user-groups/{groupArn}/join`

| Aspect | Details |
|--------|--------|
| **Authentication** | Bearer required. |
| **Validation** | Group must have numAnonymousUsers ≥ 1; caller must not already be in the group. |
| **Processing** | Add caller as GroupUser (accept=true); decrement numAnonymousUsers. joinGroup performs a single UpdateItem on the UserGroups table (list_append users, decrement numAnonymousUsers, version increment). No UserTrip is created for the joining user in this path. |
| **DAO / storage** | **TripDAO**: 1× **getUserGroup** (read), 1× **joinGroup** (UpdateItem on UserGroups table only; not a transaction). |

---

## DAO transaction reference

### UserDAO transactions

- **createUser(user)**  
  **TransactWriteItems**: (1) PutItem Users table (condition: attribute_not_exists(userArn)); (2) PutItem contact index for email (if present) (condition: attribute_not_exists(contactKey)); (3) PutItem contact index for phone (if present) (condition: attribute_not_exists(contactKey)). Ensures user and contact index rows are created atomically.

- **updatePhoneNumber / updateEmailAddress**  
  **TransactWriteItems**: (1) Update Users.phone or .email; (2) PutItem new contact index entry (condition allows overwrite by same userArn); (3) DeleteItem old contact index entry if changed. Keeps contact index in sync with user record.

---

### TripDAO transactions

- **createTrip(base, groups)**  
  **TransactWriteItems**: (1) PutItem TripMetadata (condition: attribute_not_exists(tripArn)); (2) for each group: PutItem UserGroupRecord (condition: attribute_not_exists(groupArn)); (3) for each user in each group: PutItem UserTrip (condition: attribute_not_exists(arn)). Single atomic create for trip, all groups, and all user trips.

- **createTripWithDriver(base, groups, driverTrip)**  
  **TransactWriteItems**: (1) PutItem TripMetadata (condition: attribute_not_exists(tripArn)); (2) PutItem driver's UserTrip; (3) for each group: PutItem UserGroupRecord; (4) for each non-driver user in groups: PutItem UserTrip. Same idea as createTrip plus driver trip row.

- **addUserGroup(tripArn, newGroup, expectedTripVersion)**  
  **TransactWriteItems**: (1) Update TripMetadata (SET usergroups, locations, version++; condition: version = expected); (2) PutItem new UserGroupRecord; (3) for each user in newGroup: PutItem UserTrip. Adds one group and its user trips, and refreshes trip aggregates in one transaction.

- **deleteTrip(tripArn)**  
  **TransactWriteItems**: (1) DeleteItem TripMetadata; (2) for each group: DeleteItem UserGroupRecord; (3) for each user in each group: DeleteItem UserTrip. Removes trip and all related group and user-trip rows atomically.

- **updateUserGroup(groupArn, …)**  
  **TransactWriteItems**: (1) Update UserGroupRecord (condition: version = expected); (2) for added users: PutItem UserTrip; (3) for removed users: DeleteItem UserTrip; (4) Update TripMetadata (usergroups, locations, version++; condition: version = expected). Keeps group, user trips, and trip metadata in sync.

- **removeUserGroup(groupArn, …)**  
  **TransactWriteItems**: (1) Update TripMetadata (usergroups, locations, version++; condition: version = expected); (2) DeleteItem UserGroupRecord (condition: version = expected); (3) for each user in group: DeleteItem UserTrip. Removes group and its user trips, updates trip in one transaction.

- **acceptUserInvitation(tripArn, groupArn, userId, expectedGroupVersion)**  
  **TransactWriteItems**: (1) Update UserGroupRecord (set user accept=true, version++; condition: version = expected); (2) Update UserTrip (set tripStatus and userStatusKey; no condition on version in snippet). Single atomic accept for group and user trip status.

---

### Non-transaction writes

- **TripDAO**: updateTripMetadata, updateTripStatus, updateUserTripStatus, setUserTripStatusesForTrip (N× updateUserTripStatus), joinGroup (UpdateItem on group only), putUserTrip, putUserGroup.
- **UserDAO**: updateUserProfile (single UpdateItem).
- **Rate table**: PutItem/DeleteItem in Register, VerifyCode, Login (lock) — all single-item writes.

---

## Summary table (handlers × DAO)

| Handler | TripDAO R | TripDAO W | TripDAO Tx | UserDAO R | UserDAO W/Tx | UserGroupsDAO R | Rate/other |
|---------|-----------|-----------|------------|-----------|--------------|-----------------|------------|
| Register | — | — | — | — | — | — | PutItem (rate) |
| VerifyCode | — | — | — | — | createUser (Tx) | — | GetItem, PutItem, DeleteItem |
| Login | — | — | — | — | — | — | GetItem, checkAndIncrement, PutItem |
| Me | — | — | — | 1 getUser | — | — | — |
| GetUserTrips | 1 query | — | — | — | — | — | — |
| GetTripById | 2 get | — | — | — | — | — | — |
| ListTripUsers | 1 query | — | — | — | — | — | — |
| CreateTrip | 2 get (after) | — | 1 createTrip/createTripWithDriver | 2 getUser | — | — | — |
| UpdateTripMetadata | 1 get | 1 update | — | — | — | 1 list | — |
| StartTrip | 1 get + 1 list + N update | 1 update + N update | — | — | — | — | — |
| FlipLocationArrival | 1 get | 1 update | — | — | — | 1 list | — |
| LeaveTrip | 1 get | — | 1 updateUserGroup, maybe 1 deleteTrip | — | — | 2 list | — |
| BecomeDriver | 1 get | 1 update | — | 1 getUser | — | — | — |
| GetUserGroup | 1 get | — | — | — | — | — | — |
| CreateUserGroup | 1 get, 1 list | — | 1 addUserGroup | — | — | — | — |
| UpdateUserGroup | 2 get, 2 list | — | 1 updateUserGroup | — | — | — | — |
| AcceptInvitation | 1 get, 1 getUserTrip | — | 1 acceptUserInvitation | — | — | — | — |
| JoinUserGroup | 1 get | — | — | — | — | — | — |

R = read, W = single-item write, Tx = TransactWriteItems.
