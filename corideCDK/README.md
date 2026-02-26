# corideCDK (library)

Reusable AWS CDK constructs and stacks for the Coride platform. This package is consumed by stage-specific apps.

## What this package does
- Provides TypeScript constructs and stack definitions under `lib/`.
- Builds to `dist/` for consumption by `corideCDK-dev`, `coRideCDK-staging`, and `coRideCDK-prod`.

## Folder structure
- `lib/constructs`: shared low-level constructs (`tables`, `api-rate-limiter`, `api-edge`, etc.)
- `lib/stacks/auth`: Cognito and auth infra stacks and constructs
- `lib/stacks/api`: API Gateway stack and API-related compositions
- `lib/stacks/lambdas`: Lambda-related stack and helpers
- `lib/stacks/tables`: DynamoDB tables stack and re-export helper
- `lib/stacks/feedback`: SES/SNS feedback constructs and stack
- `lib/stacks/edge`: CloudFront/WAF edge stack

## Exports
- Import stack classes from the package barrel `lib/index.ts`:
  - `CorideAuthInfraStack`
  - `CorideTripStack`
  - `CorideEdgeStack`
  - `CorideFeedbackStack`
  - `CorideApiGatewayStack`
  - `CorideLambdasStack`
  - `CorideTablesStack`

Note: legacy `coride-api-stack` has been removed in favor of domain-grouped stacks.

## Prerequisites
- Node.js 18+
- npm 9+

## Build
- `npm install`
- `npm run build`

## Deployment
- Deployment is performed by stage apps (dev/staging/prod). See those READMEs.

## Usage example (stage app)
```ts
import { App } from 'aws-cdk-lib';
import { CorideAuthInfraStack, CorideTripStack, CorideEdgeStack, CorideFeedbackStack } from 'corideCDK';

const app = new App();
const env = { account: process.env.CDK_DEFAULT_ACCOUNT, region: 'us-east-1' };
const stage = 'dev';

const authInfra = new CorideAuthInfraStack(app, 'coride-auth-infra-dev', { stage, env });
const trip = new CorideTripStack(app, 'coride-trip-dev', { stage, env, tables: authInfra.tables, userPool: authInfra.userPool, userPoolClient: authInfra.userPoolClient });
new CorideEdgeStack(app, 'coride-edge-dev', { stage, env, api: trip.api });
new CorideFeedbackStack(app, 'coride-feedback-dev', { stage, env, tables: authInfra.tables });
```