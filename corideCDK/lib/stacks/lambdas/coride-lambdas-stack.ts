import { Stack, StackProps } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { CorideLambdas } from './coride-lambdas-construct';
import { CorideTables } from '../../constructs/tables';
import { aws_lambda as lambda } from 'aws-cdk-lib';
import { aws_cognito as cognito } from 'aws-cdk-lib';
import { getConfig } from '../../config';

export interface CorideLambdasStackProps extends StackProps {
  stage: string;
  tables: CorideTables;
  userPool: cognito.UserPool;
  userPoolClient: cognito.UserPoolClient;
}

export class CorideLambdasStack extends Stack {
  public readonly apiLambda: lambda.Function;
  public readonly ttlLambda: lambda.Function;
  public readonly apiAlias: lambda.Alias;
  public readonly ttlAlias: lambda.Alias;

  constructor(scope: Construct, id: string, props: CorideLambdasStackProps) {
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
  }
}