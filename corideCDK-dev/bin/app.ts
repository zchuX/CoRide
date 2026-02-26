import { App } from 'aws-cdk-lib';
import {
  CorideAuthInfraStack,
  CorideTripStack,
  CorideEdgeStack,
  CorideFeedbackStack,
} from '../../corideCDK/lib';

const app = new App();

const env = { account: process.env.CDK_DEFAULT_ACCOUNT, region: 'us-east-1' };
const stage = 'dev';

const authInfra = new CorideAuthInfraStack(app, 'coride-auth-infra-dev', {
  stackName: 'coride-auth-infra-dev',
  stage,
  env,
});

const trip = new CorideTripStack(app, 'coride-trip-dev', {
  stackName: 'coride-trip-dev',
  stage,
  tables: authInfra.tables,
  userPool: authInfra.userPool,
  userPoolClient: authInfra.userPoolClient,
  env,
});

new CorideEdgeStack(app, 'coride-edge-dev', {
  stackName: 'coride-edge-dev',
  stage,
  api: trip.api,
  env,
});

new CorideFeedbackStack(app, 'coride-feedback-dev', {
  stackName: 'coride-feedback-dev',
  stage,
  tables: authInfra.tables,
  env,
});