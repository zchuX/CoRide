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
./scripts/deploy.sh --stage dev|staging|prod [--build-scala all|handler|daos|none] [--deploy all|infra|code|none]
```

- `--stage` (required): target stage
- `--build-scala` (default `all`): which Scala modules to test/build
  - `all` = `userDAO`, `rateLimitDAO`, `corrideLambdaHandler`
  - `handler` = only `corrideLambdaHandler`
  - `daos` = only DAOs
  - `none` = skip Scala build
- `--deploy` (default `all`): what to deploy
  - `all` = install/build CDK app(s) and deploy stacks
  - `infra` = deploy stacks (installs/builds CDK first)
  - `code` = reserved for code-only updates (if applicable)
  - `none` = no deployment

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

The script also supports a higher-level workflow that chains calls together to quickly create mock trips:

```
./easy-api.sh --stage <stage> workflow login-create-trip \
  --email <email> | --phone <E.164> \
  --password <password> \
  [--status Upcoming|Invitation|InProgress|Completed|Cancelled] \
  [--start <location>] [--dest <location>] [--pickup <epochMillis>] \
  [--group-name <name>] [--driver-confirmed true|false] [--notes <text>]
```

What it does:

- Logs in via `/auth/login` using the provided credentials.
- Calls `/auth/me` to resolve your `userArn` and name.
- Creates a trip via `POST /api/trips` with a generated `tripArn` and a single group containing the caller as a pending member. If `--driver-confirmed true`, it sets the caller as `driver` with `driverConfirmed=true`.

Defaults:

- `status`: `Upcoming`
- `start`: `Home`
- `dest`: `Work`
- `pickup`: now + 1h (epoch millis)
- `group-name`: `Friends`
- `driver-confirmed`: `false`

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