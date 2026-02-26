# corideCDK-dev (stage app)

AWS CDK app for the dev environment. Consumes the shared `corideCDK` library.

## What this package does
- Synthesizes and deploys dev stacks:
  - `CorideAuthInfraStack` (Cognito, auth tables)
  - `CorideTripStack` (API composition: tables, lambdas, API Gateway)
  - `CorideEdgeStack` (CloudFront/WAF for API)
  - `CorideFeedbackStack` (SES/SNS feedback logging)

## Prerequisites
- Node.js 18+
- npm 9+
- AWS credentials configured for the dev account/role.

## Build and deploy
- `npm install`
- `npm run build`
- `npx cdk synth`
- `npx cdk deploy coride-auth-infra-dev coride-trip-dev coride-edge-dev coride-feedback-dev --require-approval never`

## Scripted deployment
- From repo root: `scripts/deploy.sh --stage dev --build-scala all --deploy all`

## Imports
- Stage apps import stacks from the library barrel:
  - Dev/Staging (relative path): `import { ... } from '../../corideCDK/lib';`
  - Prod (package name): `import { ... } from 'corideCDK';`