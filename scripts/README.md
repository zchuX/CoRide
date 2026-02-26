# Scripts

This folder contains helper scripts to build, deploy, and call the API across stages (`dev`, `staging`, `prod`). Always use these scripts; do not run CDK deploy commands directly.

## Prerequisites

- AWS CLI v2 installed and configured (`aws configure`) with access to the target account
- Environment: `AWS_PROFILE` (optional), `AWS_REGION` or `AWS_DEFAULT_REGION` (defaults to `us-east-1`)
- Node.js and npm (for CDK apps)
- sbt (for Scala projects)
- `jq` (optional; improves JSON parsing)
- Ensure scripts are executable: `chmod +x scripts/*.sh easy-api.sh`

## Deployment

Use the deploy script; do not call `npx cdk deploy` yourself.

```
./scripts/deploy.sh --stage dev|staging|prod [--build-scala all|handler|daos|none] [--deploy all|infra|lambda|code|none]
```

- `--stage` (required): target stage
- `--build-scala` (default `all`): which Scala modules to test/build. DAOs are never deployed on their own; they are compiled into the Lambda handler jar.
  - `all` = build `tripDAO`, `userDAO`, `rateLimitDAO` (and publish to local Ivy), then build `corrideLambdaHandler` so the Lambda jar includes the latest DAOs
  - `handler` = only build `corrideLambdaHandler` (uses whatever DAO versions are in local Ivy or in its dependencies)
  - `daos` = only build and publish the three DAOs to local Ivy (no handler build). Useful for local dev when you want to refresh DAO jars and will build/run the handler separately (e.g. from an IDE). For any deploy you need the handler built too, so use `all` or `handler`.
  - `none` = skip Scala build
- `--deploy` (default `all`): what to deploy
  - `all` = install/build CDK app(s) and deploy all stacks
  - `infra` = same as all (deploy all stacks)
  - `lambda` = deploy only the trip stack (API Lambda). Use when **only Lambda handler code changed**; faster than full deploy.
  - `code` = skip CDK deployment
  - `none` = no deployment

### Deploy only Lambda (handler-only changes)

When you changed only code in `corrideLambdaHandler` (e.g. route or handler logic) and did not change any DAO or CDK infra:

1. Build the handler and deploy the trip stack:
   ```bash
   ./scripts/deploy.sh --stage dev --build-scala handler --deploy lambda
   ```
2. This runs tests and assembly for `corrideLambdaHandler`, then deploys only the `coride-trip-<stage>` stack. It does not rebuild DAOs or deploy auth/edge/feedback stacks.

If you changed `tripDAO`, `userDAO`, or `rateLimitDAO`, use `--build-scala all` (and `--deploy all` or `--deploy lambda`) so the handler gets the updated DAO jars.

### What gets persisted post-deploy

After a successful deploy, the script writes a stage info file at `scripts/.api/<stage>.json` containing:

- `baseUrl`: API Gateway base URL (e.g., `https://<restApiId>.execute-api.<region>.amazonaws.com/<stage>`) 
- `apiKeyValue`: API key value for non-prod stages (if usage plan and key exist)
- `usagePlanId`: Usage plan ID (if found)
- `cloudFrontDistributionId`, `cloudFrontDomainName`, `cloudFrontBaseUrl`: CloudFront distribution info (if present in the stack)

This enables easy API calls without hunting for IDs/keys.

## Easy API Calls

Prefer the convenience wrapper at repo root:

```
./easy-api.sh --stage <stage> <path> <json>
```

Examples:

- Register via API Gateway (name required):
  
  ```
  ./easy-api.sh --stage dev auth/register '{"username":"alice","password":"p@ss","email":"alice@example.com","name":"Alice Smith"}'
  ```

- Login with username + password:
  
  ```
  ./easy-api.sh --stage dev auth/login '{"username":"alice","password":"p@ss"}'
  ```

- Login with phone_number + password (E.164 phone format):
  
  ```
  ./easy-api.sh --stage dev auth/login '{"phone_number":"+15551234567","password":"p@ss"}'
  ```

- Call with payload from a file:
  
  ```
  ./easy-api.sh --stage dev auth/register @payload.json
  ```

- Call through CloudFront (if persisted):
  
  ```
  ./easy-api.sh --stage dev --source cloudfront auth/register '{"username":"alice","password":"p@ss","name":"Alice Smith"}'
  ```

Options:

- `--method` (default `POST`)
- `--header` for extra headers (repeatable), e.g. `--header 'Authorization: Bearer <token>'`
- `--source apigw|cloudfront` selects base URL source (default `apigw`)
- `--session` use bearer token from `scripts/.api/<stage>.session` (from a prior workflow run with `--save-session`)

### API key requirement

- `dev`/`staging`: `x-api-key` is required on methods; the script adds it automatically if found in `scripts/.api/<stage>.json`.
- `prod`: API key is typically disabled; protected endpoints still require valid auth (e.g., Cognito tokens).

## Troubleshooting

- Permission denied running scripts:
  - Run `chmod +x scripts/deploy.sh scripts/easy-api.sh easy-api.sh`
  - Or invoke with bash: `bash ./scripts/deploy.sh --stage dev`
- Missing `scripts/.api/<stage>.json`:
  - Run a deploy for that stage to create/update it: `./scripts/deploy.sh --stage dev`
- Wrong region:
  - Set `AWS_REGION` or `AWS_DEFAULT_REGION` to the correct region before deploying or calling the API.
- Using profiles:
  - Export `AWS_PROFILE=<profile>` or pass `--profile` to `aws` if manually testing.

## Notes

- Do not run `npx cdk deploy` directly; all deployments should go through `./scripts/deploy.sh`.
- The `easy-api.sh` script prefers `jq` for parsing; if not available, it falls back to basic parsing.
- CloudFront distribution is optional; if present, you can call via `--source cloudfront`. Otherwise use API Gateway.
 - Registration requires a non-empty `name` field. The name is stored in the `Users` table after sign-up verification.

## Workflow Mode

The script supports a workflow that logs in, optionally saves a session, and creates a trip in one go. You can then use the saved session for later API calls with `--session`.

### login-create-trip

```
./easy-api.sh --stage <stage> workflow login-create-trip \
  --email <email> | --phone <E.164> \
  --password <password> \
  [--trip-start <location>] [--trip-dest <location>] \
  [--start <location>] [--dest <location>] [--pickup <epochMillis>] \
  [--group-name <name>] [--driver-confirmed true|false] [--notes <text>] \
  [--save-session]
```

What it does:

- Logs in via `/auth/login` using the provided credentials.
- If `--save-session` is set, writes the bearer token to `scripts/.api/<stage>.session` so you can use `--session` in later calls.
- Calls `/auth/me` to resolve your `userArn` and name.
- Creates a trip via `POST /api/trips` with a generated `tripArn` and a single group. Trip-level `--trip-start` / `--trip-dest` set the trip’s start/destination (and the driver’s UserTrip); group `--start` / `--dest` set the group’s pickup/dropoff. If `--driver-confirmed true`, the caller is set as driver with `driverConfirmed=true`.

Defaults:

- `status`: `Upcoming`
- `start` (group): `Home`
- `dest` (group): `Work`
- `pickup`: now + 1h (epoch millis)
- `group-name`: `Friends`
- `driver-confirmed`: `false`

### Using a saved session

After a workflow run with `--save-session`, any one-off call can use the stored token:

```
./easy-api.sh --stage dev --session GET "api/trips?completed=false" '{}'
```

**Create a user group** (e.g. with 2 anonymous users) on an existing trip. Use your stored session (bearer token) for any authenticated call:

```bash
TRIP_ARN="B3MS6L"
GROUP_ARN="group:$(uuidgen | tr '[:upper:]' '[:lower:]')"
PICKUP=$(python3 -c 'import time; print(int(time.time()*1000)+3600000)')
./easy-api.sh --stage dev --session api/user-groups "{\"tripArn\":\"$TRIP_ARN\",\"groupArn\":\"$GROUP_ARN\",\"groupName\":\"Riders\",\"start\":\"Downtown\",\"destination\":\"Airport\",\"pickupTime\":$PICKUP,\"numAnonymousUsers\":2,\"users\":[]}"
```

Path must be `api/user-groups` so the request goes through the API Gateway proxy. If the session is expired, run `workflow login --profile primary --save-session` first to refresh the token.

Session file path: `scripts/.api/<stage>.session` (single line = bearer token). Do not commit this file; add `scripts/.api/*.session` to `.gitignore` if needed.

### User profiles (optional)

To avoid passing email/password on the command line, you can use a profiles file and `--profile`:

1. Copy the example and add your credentials (the file is gitignored):
   ```bash
   cp scripts/.api/users.example.json scripts/.api/users.json
   # Edit scripts/.api/users.json: set "password" and "users" with "email", "primary", "label"
   ```
2. Run the workflow with a profile:
   ```bash
   ./easy-api.sh --stage dev workflow login-create-trip --profile primary \
     --trip-start Seattle --trip-dest Vancouver --driver-confirmed true --save-session
   ```
   Use `--profile primary` for the user with `"primary": true`, or `--profile <label>` for a user’s `label` (e.g. `zchu1`, `cxh`).

### Example: login, store session, create trip as driver (Seattle → Vancouver)

With explicit credentials:
```bash
./easy-api.sh --stage dev workflow login-create-trip \
  --email your@email.com --password 'yourpassword' \
  --trip-start Seattle --trip-dest Vancouver \
  --driver-confirmed true --save-session
```

With user profile (recommended):
```bash
./easy-api.sh --stage dev workflow login-create-trip --profile primary \
  --trip-start Seattle --trip-dest Vancouver \
  --driver-confirmed true --save-session
```

You can then validate in the database that `TripMetadata.locations` and the driver’s `UserTrip.start` / `UserTrip.destination` are set correctly.

Notes:

- `jq` is required for workflow mode.
- The workflow reads API base URL and API key from `scripts/.api/<stage>.json`.

### Auth changes

- Phone OTP login has been disabled. Endpoints `/auth/login-otp/send` and `/auth/login-otp/verify` now return `410 Gone`.
- Password login accepts either `username` or `phone_number` with `password`.

### Deployed CDK stacks per stage

The deploy script builds and deploys the following stacks for the selected stage:

- `coride-auth-infra-<stage>`: Cognito User Pool, User Pool Client, and core auth tables
- `coride-trip-<stage>`: API composition (DynamoDB tables wiring, Lambdas, API Gateway)
- `coride-edge-<stage>`: CloudFront/WAF in front of the API
- `coride-feedback-<stage>`: SES/SNS feedback logging infrastructure

These match the `corideCDK` library exports and the stage app configurations.