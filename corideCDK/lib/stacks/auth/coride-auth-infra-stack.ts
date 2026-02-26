import { Stack, StackProps } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { CorideTables } from '../../constructs/tables';
import { CorideCognito } from './coride-cognito-construct';
import { aws_cognito as cognito } from 'aws-cdk-lib';

export interface CorideAuthInfraStackProps extends StackProps {
  stage: string;
}

// Foundation stack: core auth-related infra that changes infrequently
export class CorideAuthInfraStack extends Stack {
  public readonly tables: CorideTables;
  public readonly userPool: cognito.UserPool;
  public readonly userPoolClient: cognito.UserPoolClient;

  constructor(scope: Construct, id: string, props: CorideAuthInfraStackProps) {
    super(scope, id, props);

    this.tables = new CorideTables(this, 'Tables', { stage: props.stage });

    const cognitoInfra = new CorideCognito(this, 'Cognito', { stage: props.stage });
    this.userPool = cognitoInfra.userPool;
    this.userPoolClient = cognitoInfra.userPoolClient;
  }
}