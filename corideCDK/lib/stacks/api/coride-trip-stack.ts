import { Stack, StackProps, Duration } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { CorideLambdas } from '../lambdas/coride-lambdas-construct';
import { CorideApiGateway } from './coride-apigw-construct';
import { CorideTables } from '../../constructs/tables';
import { aws_apigateway as apigw } from 'aws-cdk-lib';
import { aws_lambda as lambda } from 'aws-cdk-lib';
import { aws_cognito as cognito } from 'aws-cdk-lib';
import { aws_cloudwatch as cw } from 'aws-cdk-lib';
import { aws_cloudwatch_actions as cw_actions } from 'aws-cdk-lib';
import { aws_sns as sns } from 'aws-cdk-lib';
import { aws_sns_subscriptions as subs } from 'aws-cdk-lib';
import { getConfig } from '../../config';

export interface CorideTripStackProps extends StackProps {
  stage: string;
  tables: CorideTables;
  userPool: cognito.UserPool;
  userPoolClient: cognito.UserPoolClient;
}

// Trip domain stack: frequently changing services (API + Lambda)
export class CorideTripStack extends Stack {
  public readonly api: apigw.RestApi;
  public readonly apiLambda: lambda.Function;
  public readonly ttlLambda: lambda.Function;
  public readonly apiAlias: lambda.Alias;
  public readonly ttlAlias: lambda.Alias;

  constructor(scope: Construct, id: string, props: CorideTripStackProps) {
    super(scope, id, props);

    const cfg = getConfig(this.node);

    const lambdas = new CorideLambdas(this, 'Lambdas', {
      stage: props.stage,
      tables: props.tables,
      userPool: props.userPool,
      userPoolClient: props.userPoolClient,
      lambdaJar: cfg.lambdaJar,
      loginLimit: cfg.loginLimit,
      loginWindow: cfg.loginWindow,
      otpEmailLimit: cfg.otpEmailLimit,
      otpEmailWindow: cfg.otpEmailWindow,
      otpPhoneLimit: cfg.otpPhoneLimit,
      otpPhoneWindow: cfg.otpPhoneWindow,
      resetSendLimit: cfg.resetSendLimit,
      resetSendWindow: cfg.resetSendWindow,
      resetConfirmLimit: cfg.resetConfirmLimit,
      resetConfirmWindow: cfg.resetConfirmWindow,
    });

    this.apiLambda = lambdas.apiLambda;
    this.ttlLambda = lambdas.ttlLambda;
    this.apiAlias = lambdas.apiAlias;
    this.ttlAlias = lambdas.ttlAlias;

    const apiGateway = new CorideApiGateway(this, 'ApiGateway', {
      stage: props.stage,
      apiLambdaAlias: this.apiAlias,
      userPool: props.userPool,
      enableDataTrace: cfg.enableDataTrace,
      throttlingRateLimit: cfg.throttlingRateLimit,
      throttlingBurstLimit: cfg.throttlingBurstLimit,
      clientThrottlingRateLimit: cfg.clientThrottlingRateLimit,
      clientThrottlingBurstLimit: cfg.clientThrottlingBurstLimit,
      wafRateLimit: cfg.wafRateLimit,
    });

    this.api = apiGateway.api;

    // -------------------- CloudWatch Alarms --------------------
    // Central alarm topic for API-related alarms
    const alarmTopic = new sns.Topic(this, 'ApiAlarmTopic', {
      topicName: `Coride-ApiAlarm-${props.stage}`,
    });
    alarmTopic.addSubscription(new subs.EmailSubscription('admin@zumoride.com'));

    // API Gateway 5XX spike alarm
    const api5xxMetric = new cw.Metric({
      namespace: 'AWS/ApiGateway',
      metricName: '5XXError',
      dimensionsMap: {
        ApiName: `CorideApi-${props.stage}`,
        Stage: props.stage,
      },
      period: Duration.minutes(1),
      statistic: 'sum',
    });
    const api5xxAlarm = new cw.Alarm(this, 'Api5XXSpikeAlarm', {
      metric: api5xxMetric,
      threshold: 5, // >5 errors per minute
      evaluationPeriods: 5, // sustained for 5 minutes
      datapointsToAlarm: 3,
      comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
      alarmDescription: `API Gateway 5XX spike detected for stage ${props.stage}`,
    });
    api5xxAlarm.addAlarmAction(new cw_actions.SnsAction(alarmTopic));
  }
}