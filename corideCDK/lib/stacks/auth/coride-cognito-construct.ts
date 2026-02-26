import { Construct } from 'constructs';
import { aws_cognito as cognito, Duration, Stack, RemovalPolicy } from 'aws-cdk-lib';
import { aws_iam as iam } from 'aws-cdk-lib';
import { aws_lambda as lambda } from 'aws-cdk-lib';
import { aws_dynamodb as dynamodb } from 'aws-cdk-lib';
import { aws_logs as logs } from 'aws-cdk-lib';

export interface CognitoProps {
  stage: string;
}

export class CorideCognito extends Construct {
  public readonly userPool: cognito.UserPool;
  public readonly userPoolClient: cognito.UserPoolClient;

  constructor(scope: Construct, id: string, props: CognitoProps) {
    super(scope, id);

    const { stage } = props;

    // SNS publish role for Cognito SMS
    const snsPublishRole = new iam.Role(this, 'CorideSNSPublishRole', {
      roleName: `CorideSNSPublishRole-${stage}`,
      assumedBy: new iam.ServicePrincipal('cognito-idp.amazonaws.com'),
      inlinePolicies: {
        CorideSNSPublishPolicy: new iam.PolicyDocument({
          statements: [
            new iam.PolicyStatement({
              effect: iam.Effect.ALLOW,
              actions: ['sns:Publish'],
              resources: ['*'],
            }),
          ],
        }),
      },
    });

    // Cognito User Pool
    this.userPool = new cognito.UserPool(this, 'CorideUserPool', {
      userPoolName: `CorideUserPool-${stage}`,
      // Use email/phone as the sign-in identifiers (no separate username)
      // This aligns with API contracts that accept only email or phone_number
      signInAliases: { email: true, phone: true },
      signInCaseSensitive: false,
      autoVerify: { email: true, phone: true },
      passwordPolicy: {
        minLength: 6,
        requireUppercase: false,
        requireDigits: false,
        requireSymbols: false,
      },
      mfa: cognito.Mfa.OPTIONAL,
      smsRole: snsPublishRole,
      selfSignUpEnabled: true,
      accountRecovery: cognito.AccountRecovery.PHONE_AND_EMAIL
    });

    // Configure SES email sending for Cognito via the underlying CfnUserPool
    const sesIdentityArn = this.node.tryGetContext('sesIdentityArn');
    if (!sesIdentityArn || typeof sesIdentityArn !== 'string') {
      throw new Error('Missing CDK context: sesIdentityArn. Provide a verified SES identity ARN via -c sesIdentityArn=arn:aws:ses:<region>:<account-id>:identity/<email-or-domain>');
    }
    const cfnUserPool = this.userPool.node.defaultChild as cognito.CfnUserPool;
    cfnUserPool.addPropertyOverride('EmailConfiguration', {
      EmailSendingAccount: 'DEVELOPER',
      SourceArn: sesIdentityArn,
      From: 'noreply@zumoride.com',
    });

    // Explicit SMS configuration requires both SnsCallerArn and ExternalId
    cfnUserPool.addPropertyOverride('SmsConfiguration', {
      SnsCallerArn: snsPublishRole.roleArn,
      ExternalId: Stack.of(this).stackId,
    });

    // Enable Cognito Advanced Security Mode (Adaptive Auth & Compromised Credential Detection)
    cfnUserPool.addPropertyOverride('UserPoolAddOns', {
      AdvancedSecurityMode: 'ENFORCED',
    });

    // Risk configuration: block compromised credentials and enable adaptive actions
    new cognito.CfnUserPoolRiskConfigurationAttachment(this, 'CorideRiskConfig', {
      userPoolId: this.userPool.userPoolId,
      clientId: 'ALL',
      compromisedCredentialsRiskConfiguration: {
        actions: { eventAction: 'BLOCK' },
      },
      accountTakeoverRiskConfiguration: {
        actions: {
          lowAction: { eventAction: 'NO_ACTION', notify: true },
          mediumAction: { eventAction: 'MFA_IF_CONFIGURED', notify: true },
          highAction: { eventAction: 'MFA_REQUIRED', notify: true },
        },
      },
    });

    // Cognito User Pool Client (enable custom auth for phone OTP login)
    this.userPoolClient = new cognito.UserPoolClient(this, 'CorideUserPoolClient', {
      userPool: this.userPool,
      userPoolClientName: `CorideUserPoolClient-${stage}`,
      generateSecret: false,
      authFlows: {
        adminUserPassword: true,
        userPassword: true,
        custom: false,
      },
      preventUserExistenceErrors: true,
      accessTokenValidity: Duration.minutes(60),
      idTokenValidity: Duration.minutes(60),
      refreshTokenValidity: Duration.days(365 * 5),
    });

    // -------------------- Custom Auth Triggers for Phone OTP --------------------
    // Use explicit LogGroups with retention instead of deprecated logRetention
    const retentionSetting = stage === 'dev'
      ? logs.RetentionDays.ONE_WEEK
      : stage === 'staging'
      ? logs.RetentionDays.TWO_WEEKS
      : logs.RetentionDays.THREE_MONTHS;
    // OTP custom auth triggers disabled
    const defineFnName = `coride-define-auth-challenge-${stage}`;
    const defineAuthChallengeLogGroup = new logs.LogGroup(this, 'DefineAuthChallengeLogGroup', {
      logGroupName: `/aws/lambda/${defineFnName}`,
      retention: retentionSetting,
    });

    // DefineAuthChallenge: decide when to issue tokens vs ask for custom challenge
    // DefineAuthChallenge trigger removed; keep placeholder names for clarity

    // CreateAuthChallenge: generate OTP and send via SMS using SNS
    const createFnName = `coride-create-auth-challenge-${stage}`;
    const createAuthChallengeLogGroup = new logs.LogGroup(this, 'CreateAuthChallengeLogGroup', {
      logGroupName: `/aws/lambda/${createFnName}`,
      retention: retentionSetting,
    });
    // DynamoDB table for rate limiting (per-user)
    const rateLimitTable = new dynamodb.Table(this, 'SmsRateLimitTable', {
      tableName: `CorideSmsRateLimit-${stage}`,
      partitionKey: { name: 'pk', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      timeToLiveAttribute: 'expireAt',
      removalPolicy: stage === 'prod' ? RemovalPolicy.RETAIN : RemovalPolicy.DESTROY,
    });

    // DynamoDB table for failed-attempt tracking and temporary lockouts
    const lockTable = new dynamodb.Table(this, 'AuthLockTable', {
      tableName: `CorideAuthLock-${stage}`,
      partitionKey: { name: 'pk', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      timeToLiveAttribute: 'expireAt',
      removalPolicy: stage === 'prod' ? RemovalPolicy.RETAIN : RemovalPolicy.DESTROY,
    });

    // CreateAuthChallenge trigger removed; login via password only

    // No custom auth functions; rate/lock tables remain available for future use

    // VerifyAuthChallengeResponse: compare provided answer to OTP
    // VerifyAuthChallenge trigger removed
    // No verify function; no grants needed

    // Permissions: allow triggers to publish SMS via SNS
    // No custom auth triggers; SNS publish permission for triggers not needed

    // Attach triggers
    // Triggers detached: login uses password with username/email/phone aliases
  }
}