import { App } from 'aws-cdk-lib';
import {
  CorideAuthInfraStack,
  CorideTripStack,
  CorideEdgeStack,
  CorideFeedbackStack,
} from '../../corideCDK/lib';

const app = new App();

const env = { account: process.env.CDK_DEFAULT_ACCOUNT, region: 'us-west-1' };
const stage = 'staging';

const authInfra = new CorideAuthInfraStack(app, 'coride-auth-infra-staging', {
  stackName: 'coride-auth-infra-staging',
  stage,
  env,
});

const trip = new CorideTripStack(app, 'coride-trip-staging', {
  stackName: 'coride-trip-staging',
  stage,
  tables: authInfra.tables,
  userPool: authInfra.userPool,
  userPoolClient: authInfra.userPoolClient,
  env,
});

new CorideEdgeStack(app, 'coride-edge-staging', {
  stackName: 'coride-edge-staging',
  stage,
  api: trip.api,
  env,
});

new CorideFeedbackStack(app, 'coride-feedback-staging', {
  stackName: 'coride-feedback-staging',
  stage,
  tables: authInfra.tables,
  env,
});