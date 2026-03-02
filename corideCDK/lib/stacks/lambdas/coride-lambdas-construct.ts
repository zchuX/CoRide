import { Construct } from 'constructs';
import { aws_lambda as lambda, Duration } from 'aws-cdk-lib';
import { aws_logs as logs } from 'aws-cdk-lib';
import { aws_iam as iam } from 'aws-cdk-lib';
import { Stack } from 'aws-cdk-lib';
import { DynamoEventSource } from 'aws-cdk-lib/aws-lambda-event-sources';
import { CorideTables } from '../../constructs/tables';
import { aws_cognito as cognito } from 'aws-cdk-lib';

export interface LambdaProps {
  stage: string;
  tables: CorideTables;
  userPool: cognito.UserPool;
  userPoolClient: cognito.UserPoolClient;
  lambdaJar: string;
  // Handler rate limit envs
  loginLimit: number;
  loginWindow: number;
  otpEmailLimit: number;
  otpEmailWindow: number;
  otpPhoneLimit: number;
  otpPhoneWindow: number;
  resetSendLimit: number;
  resetSendWindow: number;
  resetConfirmLimit: number;
  resetConfirmWindow: number;
}

export class CorideLambdas extends Construct {
  public readonly apiLambda: lambda.Function;
  public readonly ttlLambda: lambda.Function;
  public readonly apiAlias: lambda.Alias;
  public readonly ttlAlias: lambda.Alias;

  constructor(scope: Construct, id: string, props: LambdaProps) {
    super(scope, id);

    const { stage, tables, userPool, userPoolClient, lambdaJar, loginLimit, loginWindow, otpEmailLimit, otpEmailWindow, otpPhoneLimit, otpPhoneWindow, resetSendLimit, resetSendWindow, resetConfirmLimit, resetConfirmWindow } = props;

    // API Lambda (Scala/JVM)
    const apiLogGroup = new logs.LogGroup(this, 'ApiHandlerLogGroup', {
      retention: logs.RetentionDays.ONE_WEEK,
    });
    this.apiLambda = new lambda.Function(this, 'ApiHandler', {
      runtime: lambda.Runtime.JAVA_17,
      handler: 'com.coride.lambda.LambdaEntrypoint::handleRequest',
      code: lambda.Code.fromAsset(lambdaJar),
      functionName: `coride-api-handler-${stage}`,
      logGroup: apiLogGroup,
      timeout: Duration.seconds(30),
      memorySize: 1024,
      environment: {
        USERS_TABLE: tables.users.tableName,
        USERS_TABLE_NAME: tables.users.tableName,
        USERGROUPS_TABLE: tables.usergroups.tableName,
        USERTRIPS_TABLE: tables.userTrips.tableName,
        USER_TRIPS_TABLE: tables.userTrips.tableName,
        TRIPMETADATA_TABLE: tables.tripMetadata.tableName,
        TRIP_METADATA_TABLE: tables.tripMetadata.tableName,
        RATE_LIMIT_TABLE: tables.rateLimit.tableName,
        USER_CONTACT_INDEX_TABLE_NAME: tables.userContactIndex.tableName,
        USER_FRIENDS_TABLE: tables.userFriends.tableName,
        USER_POOL_ID: userPool.userPoolId,
        USER_POOL_CLIENT_ID: userPoolClient.userPoolClientId,
        LOGIN_LIMIT: String(loginLimit),
        LOGIN_WINDOW: String(loginWindow),
        OTP_EMAIL_LIMIT: String(otpEmailLimit),
        OTP_EMAIL_WINDOW: String(otpEmailWindow),
        OTP_PHONE_LIMIT: String(otpPhoneLimit),
        OTP_PHONE_WINDOW: String(otpPhoneWindow),
        RESET_SEND_LIMIT: String(resetSendLimit),
        RESET_SEND_WINDOW: String(resetSendWindow),
        RESET_CONFIRM_LIMIT: String(resetConfirmLimit),
        RESET_CONFIRM_WINDOW: String(resetConfirmWindow),
        ...(stage !== 'prod' ? { CORS_ALLOW_ORIGIN: '*' } : {}),
        // Enable dev/staging JWT bypass for local testing with debug:<sub> tokens
        ...(stage !== 'prod' ? { DEBUG_BYPASS_JWT: 'true' } : {}),
      },
    });

    // Permissions for Cognito admin/auth calls (scoped to this User Pool)
    this.apiLambda.addToRolePolicy(new iam.PolicyStatement({
      effect: iam.Effect.ALLOW,
      actions: [
        'cognito-idp:SignUp',
        'cognito-idp:AdminCreateUser',
        'cognito-idp:AdminInitiateAuth',
        'cognito-idp:InitiateAuth',
        'cognito-idp:AdminUpdateUserAttributes',
        'cognito-idp:RespondToAuthChallenge',
        'cognito-idp:AdminRespondToAuthChallenge',
        'cognito-idp:ConfirmSignUp',
        'cognito-idp:ForgotPassword',
        'cognito-idp:ConfirmForgotPassword',
        'cognito-idp:GetUser',
        'cognito-idp:AdminGetUser',
        'cognito-idp:RevokeToken',
      ],
      resources: [userPool.userPoolArn],
    }));

    // DynamoDB table access grants
    tables.users.grantReadWriteData(this.apiLambda);
    tables.usergroups.grantReadWriteData(this.apiLambda);
    tables.userTrips.grantReadWriteData(this.apiLambda);
    tables.tripMetadata.grantReadWriteData(this.apiLambda);
    tables.rateLimit.grantReadWriteData(this.apiLambda);
    tables.userContactIndex.grantReadWriteData(this.apiLambda);
    tables.userFriends.grantReadWriteData(this.apiLambda);

    // Version and alias for API Lambda
    const apiVersion = this.apiLambda.currentVersion;
    this.apiAlias = new lambda.Alias(this, 'CorideApiAlias', {
      aliasName: stage,
      version: apiVersion,
    });

    // TTL Lambda (Scala/JVM)
    const ttlLogGroup = new logs.LogGroup(this, 'TtlHandlerLogGroup', {
      retention: logs.RetentionDays.ONE_WEEK,
    });
    this.ttlLambda = new lambda.Function(this, 'TtlHandler', {
      runtime: lambda.Runtime.JAVA_17,
      handler: 'com.coride.lambda.ttl.TtlHandler::handleRequest',
      code: lambda.Code.fromAsset(lambdaJar),
      functionName: `coride-ttl-handler-${stage}`,
      logGroup: ttlLogGroup,
    });
    const ttlVersion = this.ttlLambda.currentVersion;
    this.ttlAlias = new lambda.Alias(this, 'CorideTtlAlias', {
      aliasName: stage,
      version: ttlVersion,
    });

    // DynamoDB streams to TTL alias
    this.ttlAlias.addEventSource(new DynamoEventSource(tables.users, {
      startingPosition: lambda.StartingPosition.LATEST,
      batchSize: 10,
      retryAttempts: 2,
    }));
    this.ttlAlias.addEventSource(new DynamoEventSource(tables.usergroups, {
      startingPosition: lambda.StartingPosition.LATEST,
      batchSize: 10,
      retryAttempts: 2,
    }));
    this.ttlAlias.addEventSource(new DynamoEventSource(tables.userTrips, {
      startingPosition: lambda.StartingPosition.LATEST,
      batchSize: 10,
      retryAttempts: 2,
    }));
    this.ttlAlias.addEventSource(new DynamoEventSource(tables.tripMetadata, {
      startingPosition: lambda.StartingPosition.LATEST,
      batchSize: 10,
      retryAttempts: 2,
    }));
  }
}