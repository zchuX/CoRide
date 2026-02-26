import { App } from 'aws-cdk-lib';
import {
  CorideAuthInfraStack,
  CorideTripStack,
  CorideEdgeStack,
  CorideFeedbackStack,
} from 'corideCDK';

const app = new App();

const env = { account: process.env.CDK_DEFAULT_ACCOUNT, region: 'us-west-2' };
const stage = 'prod';

const authInfra = new CorideAuthInfraStack(app, 'coride-auth-infra-prod', {
  stackName: 'coride-auth-infra-prod',
  stage,
  env,
});

const trip = new CorideTripStack(app, 'coride-trip-prod', {
  stackName: 'coride-trip-prod',
  stage,
  tables: authInfra.tables,
  userPool: authInfra.userPool,
  userPoolClient: authInfra.userPoolClient,
  env,
});

new CorideEdgeStack(app, 'coride-edge-prod', {
  stackName: 'coride-edge-prod',
  stage,
  api: trip.api,
  env,
});

new CorideFeedbackStack(app, 'coride-feedback-prod', {
  stackName: 'coride-feedback-prod',
  stage,
  tables: authInfra.tables,
  env,
});