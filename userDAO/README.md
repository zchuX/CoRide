# userDAO

Scala library for user persistence and contact reverse lookups with DynamoDB.

## What this package does
- Defines `User` and `UserProfileUpdate` case classes.
- Provides `UserDAO` with operations:
  - `getUser(userArn)`
  - `createUser(user)` (transactional write with contact index entries)
  - `updateUserProfile(userArn, updates)` (excludes email/phone)
  - `updatePhoneNumber(userArn, newPhone)` (updates Users table and contact index)
  - `updateEmailAddress(userArn, newEmail)` (updates Users table and contact index)
  - `getUserByEmail(email)` / `getUserByPhone(phone)` via contact index

## Prerequisites
- Java 17+
- sbt `1.9.7`

## Run tests
- `sbt test`

## Build assembly (fat JAR)
- `sbt assembly`
- Output: `target/scala-2.13/userDAO-assembly.jar`

## Publish locally (optional)
- `sbt publishLocal`
- Consumed by other sbt projects via `"com.coride" %% "userDAO" % "0.1.0"`.

## Runtime environment & tables
- Environment variables are consumed by callers (handlers) rather than this lib.
- DynamoDB tables:
  - `Users`: PK `userArn` (String). Fields include `name`, optional `email`/`phone`, lists `friendList`/`incomingInvitations`/`outgoingInvitations`, and counters.
  - `UserContactIndex`: PK `contactKey` (String) with values `email:<normalized>` or `phone:<normalized>`, value `userArn`.

## Notes
- Normalization trims and lowercases emails; trims and removes whitespace for phone.