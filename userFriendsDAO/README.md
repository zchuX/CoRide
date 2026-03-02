# userFriendsDAO

DynamoDB access for the UserFriends table (single-table design: PK=userArn, SK=PROFILE | FRIEND#&lt;friendUserArn&gt;). One profile item per user; each accepted friendship is stored under both users’ partitions with denormalized `friendUserArn` and `friendName`.

## Build

- `sbt compile`
- `sbt publishLocal` (required before building corrideLambdaHandler if it depends on this project)

## Runtime env

- `USER_FRIENDS_TABLE`: DynamoDB table name.
- `AWS_REGION`: Optional; defaults to `us-east-1`.
