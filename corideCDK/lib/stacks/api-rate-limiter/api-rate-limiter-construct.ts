import { Construct } from 'constructs';
import { aws_apigateway as apigw } from 'aws-cdk-lib';
import { aws_wafv2 as wafv2 } from 'aws-cdk-lib';

export interface ApiRateLimiterProps {
  api: apigw.RestApi;
  stage: string;
  clientThrottlingRateLimit: number;
  clientThrottlingBurstLimit: number;
  wafRateLimit?: number;
  enableRegionalWaf?: boolean; // optional: keep WAF at API Gateway stage (default false)
  enableApiKey?: boolean; // optional: create Usage Plan & API Key (default true except prod)
}

export class ApiRateLimiter extends Construct {
  public readonly apiKey?: apigw.IApiKey;
  public readonly usagePlan?: apigw.UsagePlan;
  public readonly webAclArn?: string;

  constructor(scope: Construct, id: string, props: ApiRateLimiterProps) {
    super(scope, id);

    const { api, stage, clientThrottlingRateLimit, clientThrottlingBurstLimit, wafRateLimit, enableRegionalWaf } = props;
    const shouldEnableApiKey = props.enableApiKey ?? (stage !== 'prod');

    // Per-client throttling via Usage Plan & API Key (optional for prod)
    if (shouldEnableApiKey) {
      const usagePlan = api.addUsagePlan('AppUsagePlan', {
        throttle: {
          rateLimit: clientThrottlingRateLimit,
          burstLimit: clientThrottlingBurstLimit,
        },
      });
      const apiKey = api.addApiKey('MobileAppKey');
      usagePlan.addApiKey(apiKey);
      usagePlan.addApiStage({ stage: api.deploymentStage });
      this.usagePlan = usagePlan;
      this.apiKey = apiKey;
    }

    // Optional: Regional WAF at API Gateway stage. Prefer CloudFront-scope WAF for central protection.
    if (enableRegionalWaf && typeof wafRateLimit === 'number') {
      const webAcl = new wafv2.CfnWebACL(this, 'CorideWebACL', {
        name: `CorideWebACL-${stage}`,
        scope: 'REGIONAL',
        defaultAction: { allow: {} },
        visibilityConfig: {
          cloudWatchMetricsEnabled: true,
          metricName: `CorideWebACL-${stage}`,
          sampledRequestsEnabled: true,
        },
        rules: [
          {
            name: 'RateLimitRule',
            priority: 1,
            action: { block: {} },
            statement: {
              rateBasedStatement: {
                limit: wafRateLimit, // per 5-minute window per IP
                aggregateKeyType: 'IP',
              },
            },
            visibilityConfig: {
              cloudWatchMetricsEnabled: true,
              metricName: `RateLimitRule-${stage}`,
              sampledRequestsEnabled: true,
            },
          },
        ],
      });

      new wafv2.CfnWebACLAssociation(this, 'WebACLAssociation', {
        resourceArn: api.deploymentStage.stageArn,
        webAclArn: webAcl.attrArn,
      });

      this.webAclArn = webAcl.attrArn;
    }

    // webAclArn is set only if regional WAF is enabled
  }
}