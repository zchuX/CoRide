# coRideCDK-staging (stage app)

AWS CDK app for the staging environment. Consumes the shared `corideCDK` library.

## What this package does
- Synthesizes and deploys staging stacks mirroring production:
  - `CorideAuthInfraStack` (Cognito, auth tables)
  - `CorideTripStack` (API composition: tables, lambdas, API Gateway)
  - `CorideEdgeStack` (CloudFront/WAF for API)
  - `CorideFeedbackStack` (SES/SNS feedback logging)

## Prerequisites
- Node.js 18+
- npm 9+
- AWS credentials configured for the staging account/role.

## Build and deploy
- `npm install`
- `npm run build`
- `npx cdk synth`
- `npx cdk deploy coride-auth-infra-staging coride-trip-staging coride-edge-staging coride-feedback-staging --require-approval never`

## Scripted deployment
- From repo root: `scripts/deploy.sh --stage staging --build-scala all --deploy all`

## Imports
- Staging app imports stacks from the library barrel via relative path:
  - `import { ... } from '../../corideCDK/lib';`