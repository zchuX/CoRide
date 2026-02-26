import { Construct } from 'constructs';
import { aws_apigateway as apigw } from 'aws-cdk-lib';
import { aws_logs as logs } from 'aws-cdk-lib';
import { aws_lambda as lambda } from 'aws-cdk-lib';
import { aws_cognito as cognito } from 'aws-cdk-lib';
import { Stack } from 'aws-cdk-lib';
import { ApiRateLimiter } from '../api-rate-limiter';

export interface ApiProps {
  stage: string;
  apiLambdaAlias: lambda.Alias;
  userPool: cognito.UserPool;
  enableDataTrace: boolean;
  throttlingRateLimit: number;
  throttlingBurstLimit: number;
  clientThrottlingRateLimit: number;
  clientThrottlingBurstLimit: number;
  wafRateLimit: number;
}

export class CorideApiGateway extends Construct {
  public readonly api: apigw.RestApi;

  constructor(scope: Construct, id: string, props: ApiProps) {
    super(scope, id);

    const { stage, apiLambdaAlias, userPool, enableDataTrace, throttlingRateLimit, throttlingBurstLimit, clientThrottlingRateLimit, clientThrottlingBurstLimit, wafRateLimit } = props;
    const requireApiKey = stage !== 'prod';

    const accessLogGroup = new logs.LogGroup(this, 'ApiAccessLogs', {
      logGroupName: `CorideApiAccessLogs-${stage}-${Stack.of(this).stackName}`,
      retention: logs.RetentionDays.ONE_WEEK,
    });

    const enableCorsPreflight = stage !== 'prod';

    this.api = new apigw.RestApi(this, 'Api', {
      restApiName: `CorideApi-${stage}`,
      // Enable CORS preflight only for non-prod stages
      ...(enableCorsPreflight
        ? {
            defaultCorsPreflightOptions: {
              allowOrigins: apigw.Cors.ALL_ORIGINS,
              allowHeaders: apigw.Cors.DEFAULT_HEADERS,
              allowMethods: apigw.Cors.ALL_METHODS,
            },
          }
        : {}),
      deployOptions: {
        stageName: stage,
        accessLogDestination: new apigw.LogGroupLogDestination(accessLogGroup),
        accessLogFormat: apigw.AccessLogFormat.jsonWithStandardFields({
          caller: true,
          user: true,
          requestTime: true,
          httpMethod: true,
          resourcePath: true,
          status: true,
          responseLength: true,
          ip: true,
          protocol: true,
        }),
        loggingLevel: apigw.MethodLoggingLevel.INFO,
        metricsEnabled: true,
        // Global throttling (explicit; values required via props)
        throttlingRateLimit: throttlingRateLimit,   // requests per second
        throttlingBurstLimit: throttlingBurstLimit, // burst capacity
        dataTraceEnabled: enableDataTrace,
      },
    });

    const integration = new apigw.LambdaIntegration(apiLambdaAlias);

    const authorizer = new apigw.CognitoUserPoolsAuthorizer(this, 'CorideCognitoAuthorizer', {
      cognitoUserPools: [userPool],
    });

    // ---------- Encapsulated rate limiting (Usage Plan + API Key + WAF) ----------
    new ApiRateLimiter(this, 'ApiRateLimiter', {
      api: this.api,
      stage,
      clientThrottlingRateLimit: clientThrottlingRateLimit,
      clientThrottlingBurstLimit: clientThrottlingBurstLimit,
      wafRateLimit: wafRateLimit,
      enableRegionalWaf: false,
      enableApiKey: requireApiKey,
    });

    // ---------- Application API (greedy proxy) ----------
    // Route all /api and /api/* requests to the Lambda, letting the
    // application router handle path/method dispatch. Protect with
    // Cognito authorizer and require API key on non-prod stages.
    //
    // TODO: If we need per-endpoint throttling/metrics or differing auth,
    // define explicit resources (e.g., /api/trips GET/POST) with their own
    // MethodOptions and usage-plan throttles. Keep this greedy proxy as the
    // fallback so new endpoints don’t 404 with “Missing Authentication Token”.
    const appApi = this.api.root.addResource('api');
    const appApiProxy = appApi.addResource('{proxy+}');
    const securedMethod: apigw.MethodOptions = {
      authorizer,
      authorizationType: apigw.AuthorizationType.COGNITO,
      apiKeyRequired: requireApiKey,
    };
    // Support both /api and /api/*
    appApi.addMethod('ANY', integration, securedMethod);
    appApiProxy.addMethod('ANY', integration, securedMethod);

    // Existing demo resource
    const items = this.api.root.addResource('items');
    items.addMethod('GET', integration, {
      authorizer,
      authorizationType: apigw.AuthorizationType.COGNITO,
      apiKeyRequired: requireApiKey,
    });
    items.addMethod('POST', integration, {
      authorizer,
      authorizationType: apigw.AuthorizationType.COGNITO,
      apiKeyRequired: requireApiKey,
    });
    items.addMethod('DELETE', integration, {
      authorizer,
      authorizationType: apigw.AuthorizationType.COGNITO,
      apiKeyRequired: requireApiKey,
    });

    // ---------- Auth API Resources ----------
    const auth = this.api.root.addResource('auth');
    const register = auth.addResource('register');
    const verifyCode = auth.addResource('verify-code');
    const login = auth.addResource('login');
    const resetPassword = auth.addResource('reset-password');
    const resetConfirm = resetPassword.addResource('confirm');
    const refreshToken = auth.addResource('refresh-token');
    const logout = auth.addResource('logout');
    const me = auth.addResource('me');
    const loginOtp = auth.addResource('login-otp');
    const loginOtpSend = loginOtp.addResource('send');
    const loginOtpVerify = loginOtp.addResource('verify');

    // Public endpoints (no authorizer)
    register.addMethod('POST', integration, { apiKeyRequired: requireApiKey });
    verifyCode.addMethod('POST', integration, { apiKeyRequired: requireApiKey });
    login.addMethod('POST', integration, { apiKeyRequired: requireApiKey });
    resetPassword.addMethod('POST', integration, { apiKeyRequired: requireApiKey });
    // Explicit confirm subresource to support separate endpoint and CORS preflight
    resetConfirm.addMethod('POST', integration, { apiKeyRequired: requireApiKey });
    refreshToken.addMethod('POST', integration, { apiKeyRequired: requireApiKey });
    logout.addMethod('POST', integration, { apiKeyRequired: requireApiKey });
    loginOtpSend.addMethod('POST', integration, { apiKeyRequired: requireApiKey });
    loginOtpVerify.addMethod('POST', integration, { apiKeyRequired: requireApiKey });

    // Authenticated endpoint — always use Cognito authorizer across stages
    me.addMethod('GET', integration, {
      authorizer,
      authorizationType: apigw.AuthorizationType.COGNITO,
      apiKeyRequired: requireApiKey,
    });
  }
}