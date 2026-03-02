# lambda api architecture

per-API handler logic: validate, construct from input/DB, write/update, return.

---

**api:** POST /auth/register

**handler logic:**
- Validate body: password (min 6 chars), exactly one of email or phone_number (format valid), name non-empty; forbid userId/username. Validate rate limits (per-IP, per-email/phone OTP).
- No DB read for user; construct username from email/phone.
- Cognito signUp. If user already unconfirmed, resend code. Write rate table: PutItem `otp:issued:user:{username}` with TTL (30 min).
- Return 200 and message (verification code sent / resent) or 409 if user already confirmed.

---

**api:** POST /auth/verify-code

**handler logic:**
- Validate body: code required, exactly one of email or phone_number; forbid userId/username. Rate limit verify. Read rate table GetItem(issued key) to ensure OTP within issuance window.
- If Cognito user already confirmed: build User from Cognito attributes, userDAO.createUser(user) (transaction: Users + contact index). Return 200 "account already verified".
- Else: Cognito confirmSignUp. Write rate table: PutItem(used key), DeleteItem(issued key). Build User from Cognito, userDAO.createUser(user) (transaction).
- Return 200 "account verified".

---

**api:** POST /auth/login

**handler logic:**
- Validate body: password required, exactly one of email or phone_number; forbid userId/username. Read rate table GetItem(lock key); if locked return 423. Rate limit per-IP.
- Cognito initiatePasswordAuth.
- On success: return 200 with idToken, accessToken, refreshToken.
- On failure: rateDao.checkAndIncrement(login:fail:user); if over threshold, PutItem(lock key) on rate table; return 423 or 401.

---

**api:** GET /auth/me

**handler logic:**
- Validate Bearer token; JWT verify. Resolve user by verified.sub or username.
- userDao.getUser(key) to load user; fail if not found (profile not initialized).
- No write.
- Return 200 with user (userArn, name, email, phone_number, etc.).

---

**api:** POST /auth/logout, POST /auth/reset-password, POST /auth/reset-password/confirm, POST /auth/refresh-token

**handler logic:** Cognito-only flows; no DAO reads or writes. Return tokens or success as appropriate.

---

**api:** GET /api/trips?completed=true|false

**handler logic:**
- Validate Bearer; JWT verify. Validate query completed (true → "completed", false → "uncompleted").
- tripDao.queryUserTripsByStatus(userId, tripStatus) to get UserTrip list; dedupe and sort.
- No write.
- Return 200 with trips array (from UserTrip only).

---

**api:** GET /api/trips/{tripArn}

**handler logic:**
- Validate Bearer; JWT verify.
- tripDao.getTripMetadata(tripArn); tripDao.getUserTrip(userTripArn) for current user's status.
- No write.
- Return 200 with trip (metadata) and status.userTripStatus, or 404.

---

**api:** GET /api/trips/{tripArn}/users

**handler logic:**
- No auth in handler.
- tripDao.listUsersByTrip(tripArn).
- No write.
- Return 200 with users array.

---

**api:** POST /api/trips

**handler logic:**
- Validate Bearer; JWT verify. Validate body: startTime required; optional start, destination, car, notes, groups. If driver present, validate driver equals verified.sub (caller only). Caller from JWT only (verified.sub); driver name from JWT (verified.name) when driver set; no UserDAO for trip creation. TripValidation: no duplicate users across driver and groups.
- Generate tripArn. Group arn auto-generated per group (do not send). Build TripMetadata and groups from input. If driver set: build driverTrip, tripDao.createTripWithDriver(base, groups, driverTrip). Else: tripDao.createTrip(base, groups). tripDao.getTripMetadata(tripArn) once for response payload.
- Transaction: createTrip or createTripWithDriver (PutItem TripMetadata, UserGroupRecords, UserTrips). Then one read.
- Return 200 with trip (tripArn, locations, usergroups, etc.).

---

**api:** PUT /api/trips/{tripArn}

**handler logic:**
- Validate Bearer; userId from MeHandler (router). tripDao.getTripMetadata(tripArn); groupsDAO.listUserGroupRecordsByTripArn(tripArn). Validate caller is driver or in some group; else 403.
- Validate body: optional startTime, notes, locations. If locations: must be permutation of existing; cannot move arrived location to later position.
- Build updated TripMetadata from current + body. tripDao.updateTripMetadata(updated, expected).
- Single UpdateItem (conditional on version). Return 200 with updated trip or 409.

---

**api:** POST /api/trips/{tripArn}/start

**handler logic:**
- Validate Bearer; JWT verify. tripDao.getTripMetadata(tripArn). Validate caller is driver; validate trip status is Upcoming.
- Build updated trip (status InProgress, optionally first location arrived and currentStop). tripDao.startTripTransaction(updated, expected).
- Transactional write: one UpdateItem on trip metadata; N UpdateItems on UserTrips (listUsersByTrip then update tripStatus to InProgress per UserTrip). All-or-nothing; version checks on trip and each UserTrip. Return 200 with updated trip or 409.

---

**api:** POST /api/trips/{tripArn}/locations/{locationName}/arrival

**handler logic:**
- Validate Bearer; JWT verify. Body: tripArn, locationName, arrived (true). tripDao.getTripMetadata(tripArn). Validate now >= startTime; validate caller is driver or in a group with start/destination equal to locationName (groupsDAO.listUserGroupRecordsByTripArn).
- Build updated locations (mark location arrived, reorder arrived first by time), set currentStop. If all arrived: set status Completed and completionTime; tripDao.completeTripTransaction(updated, expected). Else: tripDao.updateTripMetadata(updated, expected).
- If all arrived: transactional write (one UpdateItem on trip; N UpdateItems on UserTrips: tripStatus = Completed, userStatusKey = {userId}-completed so GSI moves from -uncompleted to -completed). Otherwise single UpdateItem on trip. Return 200 with updated trip or 409.

---

**api:** POST /api/trips/{tripArn}/leave

**handler logic:**
- Validate Bearer; JWT verify. tripDao.getTripMetadata(tripArn); groupsDAO.listUserGroupRecordsByTripArn(tripArn). Find group containing caller; else 404.
- Build updated group (users without caller). tripDao.updateUserGroup(groupArn, expectedGroup, expectedTrip, ..., users = updated). Then groupsDAO.listUserGroupRecordsByTripArn; if no users left in any group, tripDao.deleteTrip(tripArn).
- Transaction updateUserGroup (Update UserGroupRecord, Put/Delete UserTrips, Update TripMetadata). Optionally transaction deleteTrip (Delete TripMetadata, all UserGroupRecords, all UserTrips). Return 200 "Successfully left trip" or 409.

---

**api:** POST /api/trips/{tripArn}/driver

**handler logic:**
- Validate Bearer; JWT verify.
- tripDao.getTripMetadata(tripArn). userDao.getUser(userId) for driver name/photo. Build updated trip (driver, driverName, driverPhotoUrl, driverConfirmed = true). tripDao.updateTripMetadata(updated, expected).
- Single UpdateItem. Return 200 with updated trip or 409.

---

**api:** GET /api/user-groups/{groupArn}

**handler logic:**
- Validate Bearer. Path groupArn is URL-decoded in router.
- tripDao.getUserGroup(groupArn).
- No write.
- Return 200 with group JSON or 404.

---

**api:** POST /api/user-groups

**handler logic:**
- Validate Bearer. Validate body: tripArn, groupName, start, destination, pickupTime required; optional users, numAnonymousUsers. groupArn is server-generated (not accepted from client). tripDao.getTripMetadata(tripArn); tripDao.listUserGroupRecordsByTripArn(tripArn). TripValidation: no duplicate users in trip (driver + all groups including new).
- Generate groupArn. Build UserGroupRecord. tripDao.addUserGroup(tripArn, newGroup, expectedTrip).
- Transaction: Update TripMetadata (usergroups, locations, version), PutItem UserGroupRecord, PutItem UserTrip per user in new group. Return 200 with created group (includes groupArn) or 409.

---

**api:** PUT /api/user-groups/{groupArn}

**handler logic:**
- Validate Bearer. tripDao.getUserGroup(groupArn). Validate caller is member of group.
- Validate body: optional groupName, start, destination, pickupTime, users, numAnonymousUsers. tripDao.getTripMetadata; listUserGroupRecordsByTripArn. TripValidation: no duplicate users across groups.
- tripDao.updateUserGroup(groupArn, ...). Then tripDao.getUserGroup(groupArn) for response.
- Transaction: Update UserGroupRecord, Put/Delete UserTrips as needed, Update TripMetadata. Return 200 with updated group or 409.

---

**api:** POST /api/user-groups/{groupArn}/accept

**handler logic:**
- Validate Bearer. tripDao.getUserGroup(groupArn). Validate user is in group and accept is false; tripDao.getUserTrip(userTripArn) and tripStatus must be Invitation.
- tripDao.acceptUserInvitation(tripArn, groupArn, userId, expectedGroupVersion).
- Transaction: Update UserGroupRecord (user accept=true), Update UserTrip (tripStatus, userStatusKey). Return 200 with updated group or 409.

---

**api:** POST /api/user-groups/{groupArn}/join

**handler logic:**
- Validate Bearer. tripDao.getUserGroup(groupArn). Validate numAnonymousUsers >= 1 and caller not already in group.
- Build GroupUser for caller (accept=true). tripDao.joinGroup(groupArn, newUser, group.version).
- Single UpdateItem on UserGroups table (list_append users, decrement numAnonymousUsers, version++). No UserTrip created for joiner. Return 200 with updated group or 409.
