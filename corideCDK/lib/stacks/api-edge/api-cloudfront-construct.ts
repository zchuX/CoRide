import { Construct } from 'constructs';
import { aws_cloudfront as cloudfront, Stack } from 'aws-cdk-lib';
import { aws_cloudfront_origins as origins } from 'aws-cdk-lib';
import { aws_apigateway as apigw } from 'aws-cdk-lib';
import { aws_wafv2 as wafv2 } from 'aws-cdk-lib';
import { aws_certificatemanager as acm } from 'aws-cdk-lib';

export interface ApiCloudFrontProps {
  api: apigw.RestApi;
  stage: string;
  wafRateLimit: number;
  domainName?: string; // optional custom domain for CloudFront
  certificateArn?: string; // ACM certificate in us-east-1 for CloudFront
}

export class ApiCloudFront extends Construct {
  public readonly distribution: cloudfront.Distribution;
  public readonly webAclArn: string;

  constructor(scope: Construct, id: string, props: ApiCloudFrontProps) {
    super(scope, id);

    const { api, stage, wafRateLimit, domainName, certificateArn } = props;

    // CloudFront origin must be a domain name (no path). Derive the API Gateway host
    // and use originPath to route to the stage.
    const originDomain = `${api.restApiId}.execute-api.${Stack.of(this).region}.amazonaws.com`;
    const origin = new origins.HttpOrigin(originDomain, {
      originPath: `/${stage}`,
      protocolPolicy: cloudfront.OriginProtocolPolicy.HTTPS_ONLY,
    });

    const cacheDisabled = cloudfront.CachePolicy.CACHING_DISABLED;

    // CloudFront-scope WAF (global). Note: CLOUDFRONT WAF must be created in us-east-1.
    const webAcl = new wafv2.CfnWebACL(this, 'CorideCloudFrontWebACL', {
      name: `CorideCloudFrontWebACL-${stage}`,
      scope: 'CLOUDFRONT',
      defaultAction: { allow: {} },
      visibilityConfig: {
        cloudWatchMetricsEnabled: true,
        metricName: `CorideCloudFrontWebACL-${stage}`,
        sampledRequestsEnabled: true,
      },
      rules: [
        {
          name: 'CfRateLimitRule',
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
            metricName: `CfRateLimitRule-${stage}`,
            sampledRequestsEnabled: true,
          },
        },
      ],
    });

    this.distribution = new cloudfront.Distribution(this, 'CorideDist', {
      defaultBehavior: {
        origin,
        allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
        cachePolicy: cacheDisabled,
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
      },
      additionalBehaviors: {
        // Disable caching explicitly for auth APIs
        'auth/*': {
          origin,
          allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
          cachePolicy: cacheDisabled,
          viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        },
      },
      // Associate CloudFront WAF by providing the WebACL ARN directly
      webAclId: webAcl.attrArn,
      ...(domainName && certificateArn
        ? {
            domainNames: [domainName],
            certificate: acm.Certificate.fromCertificateArn(this, 'CfCert', certificateArn),
          }
        : {}),
    });
    this.webAclArn = webAcl.attrArn;
  }
}