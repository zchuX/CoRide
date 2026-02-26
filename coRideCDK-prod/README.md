# coRideCDK-prod (stage app)

AWS CDK app for the production environment. Consumes the shared `corideCDK` library.

## What this package does
- Synthesizes and deploys production stacks:
  - `CorideAuthInfraStack` (Cognito, auth tables)
  - `CorideTripStack` (API composition: tables, lambdas, API Gateway)
  - `CorideEdgeStack` (CloudFront/WAF for API)
  - `CorideFeedbackStack` (SES/SNS feedback logging)

## Prerequisites
- Node.js 18+
- npm 9+
- AWS credentials configured for the production account/role.

## Build and deploy
- `npm install`
- `npm run build`
- `npx cdk synth`
- `npx cdk deploy coride-auth-infra-prod coride-trip-prod coride-edge-prod coride-feedback-prod --require-approval never`

## Scripted deployment
- From repo root: `scripts/deploy.sh --stage prod --build-scala all --deploy all`

## Imports
- Production app imports stacks from the published library package:
  - `import { ... } from 'corideCDK';`