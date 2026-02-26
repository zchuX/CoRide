import { Stack, StackProps } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { CorideCognito } from './coride-cognito-construct';
import { aws_cognito as cognito } from 'aws-cdk-lib';

export interface CorideCognitoStackProps extends StackProps {
  stage: string;
}

export class CorideCognitoStack extends Stack {
  public readonly userPool: cognito.UserPool;
  public readonly userPoolClient: cognito.UserPoolClient;

  constructor(scope: Construct, id: string, props: CorideCognitoStackProps) {
    super(scope, id, props);

    const cognito = new CorideCognito(this, 'Cognito', { stage: props.stage });
    this.userPool = cognito.userPool;
    this.userPoolClient = cognito.userPoolClient;
  }
}
