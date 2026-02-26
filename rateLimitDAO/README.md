# rateLimitDAO

Scala library providing a DynamoDB-backed rate limiting DAO.

## What this package does
- Exposes `RateLimitDAO` with `checkAndIncrement(key, windowSeconds, maxCount)`.
- Implements a TTL-based sliding window using DynamoDB `UpdateItem` and conditional checks.
- Returns `RateLimitDecision(allowed, retryAfterSeconds)`.

## Prerequisites
- Java 17+
- sbt `1.9.7`

## Run tests
- `sbt test`

## Build assembly (fat JAR)
- `sbt assembly`
- Output: `target/scala-2.13/rateLimitDAO-assembly.jar`

## Publish locally (optional)
- `sbt publishLocal`
- Consumed by other sbt projects via `"com.coride" %% "rateLimitDAO" % "0.1.0"`.

## Runtime environment variables
- `AWS_REGION`: region for DynamoDB client (default `us-west-2`).
- `RATE_LIMIT_TABLE`: DynamoDB table name with schema:
  - Partition key: `key` (String)
  - Attributes: `count` (Number), `ttl` (Number UNIX epoch seconds)
  - TTL enabled on attribute `ttl`

## Notes
- Constructor supports injecting a custom `DynamoDbClient` and `tableName` for testing.