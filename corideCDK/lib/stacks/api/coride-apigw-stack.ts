import { Stack, StackProps } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { CorideApiGateway } from './coride-apigw-construct';
import { aws_apigateway as apigw } from 'aws-cdk-lib';
import { aws_lambda as lambda } from 'aws-cdk-lib';
import { aws_cognito as cognito } from 'aws-cdk-lib';
import { getConfig } from '../../config';

export interface CorideApiGatewayStackProps extends StackProps {
  stage: string;
  apiLambdaAlias: lambda.Alias;
  userPool: cognito.UserPool;
}

export class CorideApiGatewayStack extends Stack {
  public readonly api: apigw.RestApi;

  constructor(scope: Construct, id: string, props: CorideApiGatewayStackProps) {
    super(scope, id, props);

    const cfg = getConfig(this.node);

    const apiGateway = new CorideApiGateway(this, 'ApiGateway', {
      stage: props.stage,
      apiLambdaAlias: props.apiLambdaAlias,
      userPool: props.userPool,
      enableDataTrace: cfg.enableDataTrace,
      throttlingRateLimit: cfg.throttlingRateLimit,
      throttlingBurstLimit: cfg.throttlingBurstLimit,
      clientThrottlingRateLimit: cfg.clientThrottlingRateLimit,
      clientThrottlingBurstLimit: cfg.clientThrottlingBurstLimit,
      wafRateLimit: cfg.wafRateLimit,
    });

    this.api = apiGateway.api;
  }
}