import { Stack, StackProps } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { ApiCloudFront } from '../api-edge';
import { aws_apigateway as apigw } from 'aws-cdk-lib';
import { getConfig } from '../../config';

export interface CorideEdgeStackProps extends StackProps {
  stage: string;
  api: apigw.RestApi;
}

export class CorideEdgeStack extends Stack {
  constructor(scope: Construct, id: string, props: CorideEdgeStackProps) {
    super(scope, id, props);

    const cfg = getConfig(this.node);

    // Optional domain/cert can be provided via context; if not, CF uses default domain
    new ApiCloudFront(this, 'ApiFront', {
      api: props.api,
      stage: props.stage,
      wafRateLimit: cfg.wafRateLimit,
    });
  }
}