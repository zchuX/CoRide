import { Construct } from 'constructs';
import { aws_dynamodb as dynamodb, RemovalPolicy } from 'aws-cdk-lib';

export interface CorideTablesProps {
  stage?: string;
  enableTtlOn?: Array<'users' | 'usergroups' | 'userTrips' | 'tripMetadata'>;
  ttlAttributeName?: string;
  streamViewType?: dynamodb.StreamViewType;
}

export class CorideTables extends Construct {
  public readonly users: dynamodb.Table;
  public readonly usergroups: dynamodb.Table;
  public readonly userTrips: dynamodb.Table;
  public readonly tripMetadata: dynamodb.Table;
  public readonly rateLimit: dynamodb.Table;
  public readonly emailFeedback: dynamodb.Table;
  public readonly userContactIndex: dynamodb.Table;

  constructor(scope: Construct, id: string, props: CorideTablesProps = {}) {
    super(scope, id);

    const stage = props.stage ?? 'prod';
    const ttlAttr = props.ttlAttributeName ?? 'ttl';
    const streamView = props.streamViewType ?? dynamodb.StreamViewType.NEW_AND_OLD_IMAGES;
    const namePrefix = `Coride-${stage}`;
    const shouldEnable = (name: 'users' | 'usergroups' | 'userTrips' | 'tripMetadata') =>
      props.enableTtlOn === undefined ? true : props.enableTtlOn.includes(name);

    this.users = new dynamodb.Table(this, 'Users', {
      tableName: `${namePrefix}-Users`,
      partitionKey: { name: 'userArn', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: RemovalPolicy.DESTROY,
      timeToLiveAttribute: shouldEnable('users') ? ttlAttr : undefined,
      stream: shouldEnable('users') ? streamView : undefined,
    });

    this.usergroups = new dynamodb.Table(this, 'UserGroups', {
      tableName: `${namePrefix}-UserGroups`,
      partitionKey: { name: 'groupArn', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: RemovalPolicy.DESTROY,
      timeToLiveAttribute: shouldEnable('usergroups') ? ttlAttr : undefined,
      stream: shouldEnable('usergroups') ? streamView : undefined,
    });
    // GSI to query groups by tripArn used by handlers
    this.usergroups.addGlobalSecondaryIndex({
      indexName: 'gsiTripArn',
      partitionKey: { name: 'tripArn', type: dynamodb.AttributeType.STRING },
      projectionType: dynamodb.ProjectionType.ALL,
    });

    this.userTrips = new dynamodb.Table(this, 'UserTrips', {
      tableName: `${namePrefix}-UserTrips`,
      partitionKey: { name: 'arn', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: RemovalPolicy.DESTROY,
      timeToLiveAttribute: shouldEnable('userTrips') ? ttlAttr : undefined,
      stream: shouldEnable('userTrips') ? streamView : undefined,
    });
    // Removed legacy index 'gsiUserTripStatus' to avoid redundancy; using the DateTime-sorted GSI only
    // New GSI including sort key for time-ordered queries
    this.userTrips.addGlobalSecondaryIndex({
      indexName: 'gsiUserTripStatusDateTime',
      partitionKey: { name: 'userStatusKey', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'tripDateTime', type: dynamodb.AttributeType.NUMBER },
      projectionType: dynamodb.ProjectionType.ALL,
    });

    this.userTrips.addGlobalSecondaryIndex({
      indexName: 'gsiTripArn',
      partitionKey: { name: 'tripArn', type: dynamodb.AttributeType.STRING },
      projectionType: dynamodb.ProjectionType.ALL,
    });

    this.tripMetadata = new dynamodb.Table(this, 'TripMetadata', {
      tableName: `${namePrefix}-TripMetadata`,
      partitionKey: { name: 'tripArn', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: RemovalPolicy.DESTROY,
      timeToLiveAttribute: shouldEnable('tripMetadata') ? ttlAttr : undefined,
      stream: shouldEnable('tripMetadata') ? streamView : undefined,
    });

    // Rate limiting table for Lambda-level protections (OTP/email/account flooding)
    this.rateLimit = new dynamodb.Table(this, 'RateLimitTable', {
      tableName: `${namePrefix}-RateLimit`,
      partitionKey: { name: 'key', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: RemovalPolicy.DESTROY,
      timeToLiveAttribute: 'ttl',
    });

    // Email feedback and audit records (bounces, complaints), keyed by messageId
    this.emailFeedback = new dynamodb.Table(this, 'EmailFeedback', {
      tableName: `${namePrefix}-EmailFeedback`,
      partitionKey: { name: 'id', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: RemovalPolicy.DESTROY,
    });

    // Secondary user lookup by contact: maps contactKey (email:..., phone:...) -> userArn
    this.userContactIndex = new dynamodb.Table(this, 'UserContactIndex', {
      tableName: `${namePrefix}-UserContactIndex`,
      partitionKey: { name: 'contactKey', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: RemovalPolicy.DESTROY,
    });
  }
}