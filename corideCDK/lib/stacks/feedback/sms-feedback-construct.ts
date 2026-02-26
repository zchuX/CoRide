import { Construct } from 'constructs';
import { aws_logs as logs } from 'aws-cdk-lib';
import { aws_logs_destinations as logs_destinations } from 'aws-cdk-lib';
import { aws_lambda as lambda } from 'aws-cdk-lib';
import { aws_dynamodb as dynamodb } from 'aws-cdk-lib';
import { aws_cloudwatch as cw } from 'aws-cdk-lib';
import { aws_cloudwatch_actions as cw_actions } from 'aws-cdk-lib';
import { aws_iam as iam } from 'aws-cdk-lib';
import { custom_resources as cr } from 'aws-cdk-lib';
import { Duration, Stack } from 'aws-cdk-lib';
import { aws_sns as sns } from 'aws-cdk-lib';

export interface SmsFeedbackProps {
  stage: string;
  usersTable: dynamodb.Table;
  contactIndexTable: dynamodb.Table;
  rateLimitTable: dynamodb.Table;
  alarmTopic?: sns.ITopic;
  enableAlarms?: boolean;
}

/**
 * Configures SNS SMS delivery status logging to CloudWatch Logs and subscribes
 * a Lambda to process failure/opt-out events. Emits metrics and updates Users
 * and RateLimit tables similarly to email handling.
 */
export class SmsFeedback extends Construct {
  public readonly deliveryLogGroup: logs.LogGroup;
  public readonly smsDeliveryHandler: lambda.Function;

  constructor(scope: Construct, id: string, props: SmsFeedbackProps) {
    super(scope, id);
    const { stage, usersTable, contactIndexTable, rateLimitTable, alarmTopic: providedAlarmTopic } = props;
    const enableAlarms = props.enableAlarms ?? true;
    const region = Stack.of(this).region;

    // Shared alarm topic (if not provided, create and subscribe email)
    const alarmTopic = enableAlarms
      ? (providedAlarmTopic ?? new sns.Topic(this, 'AutoAlarmTopicSms', { topicName: `Coride-AutoAlarm-${stage}` }))
      : undefined;

    // CloudWatch Logs group for SNS SMS delivery status
    this.deliveryLogGroup = new logs.LogGroup(this, 'SmsDeliveryLogGroup', {
      logGroupName: `/aws/sns/sms/Coride-${stage}-${Stack.of(this).stackName}`,
      retention: logs.RetentionDays.ONE_WEEK,
    });

    // IAM role SNS uses to write to CloudWatch Logs
    const snsLogsRole = new iam.Role(this, 'SnsSmsLogsRole', {
      assumedBy: new iam.ServicePrincipal('sns.amazonaws.com'),
      description: 'Role for SNS to publish SMS delivery logs to CloudWatch Logs',
    });
    snsLogsRole.addToPolicy(new iam.PolicyStatement({
      actions: [
        'logs:CreateLogGroup',
        'logs:CreateLogStream',
        'logs:PutLogEvents',
        'logs:DescribeLogGroups',
        'logs:DescribeLogStreams',
      ],
      resources: ['*'],
    }));

    // Enable SNS SMS delivery status logging to the log group
    const enableSmsLogging = new cr.AwsCustomResource(this, 'EnableSmsDeliveryLogging', {
      onCreate: {
        service: 'SNS',
        action: 'SetSMSAttributes',
        parameters: {
          attributes: {
            DeliveryStatusIAMRole: snsLogsRole.roleArn,
            DeliveryStatusSuccessSamplingRate: '0',
          },
        },
        region,
        physicalResourceId: cr.PhysicalResourceId.of(`EnableSmsLogging-${stage}-${Stack.of(this).stackName}`),
      },
      onUpdate: {
        service: 'SNS',
        action: 'SetSMSAttributes',
        parameters: {
          attributes: {
            DeliveryStatusIAMRole: snsLogsRole.roleArn,
            DeliveryStatusSuccessSamplingRate: '0',
          },
        },
        region,
        physicalResourceId: cr.PhysicalResourceId.of(`EnableSmsLogging-${stage}-${Stack.of(this).stackName}`),
      },
      // Allow SetSMSAttributes and explicitly allow iam:PassRole on the SNS Logs role
      policy: cr.AwsCustomResourcePolicy.fromStatements([
        new iam.PolicyStatement({
          actions: ['sns:SetSMSAttributes'],
          resources: ['*'],
        }),
        new iam.PolicyStatement({
          actions: ['iam:PassRole'],
          resources: [snsLogsRole.roleArn],
        }),
      ]),
    });
    enableSmsLogging.node.addDependency(this.deliveryLogGroup);

    // Lambda to process delivery failures/opt-outs
    const handlerInline = `const AWS=require('aws-sdk');const zlib=require('zlib');const ddb=new AWS.DynamoDB.DocumentClient();const cloudwatch=new AWS.CloudWatch();const USERS_TABLE=process.env.USERS_TABLE;const RATE_LIMIT_TABLE=process.env.RATE_LIMIT_TABLE;const CONTACT_INDEX_TABLE=process.env.CONTACT_INDEX_TABLE;const STAGE=process.env.STAGE||'prod';exports.handler=async(e)=>{try{const payload=Buffer.from(e.awslogs.data,'base64');const decompressed=zlib.gunzipSync(payload).toString('utf8');const logs=JSON.parse(decompressed);for(const ev of logs.logEvents||[]){const msg=ev.message||'';const m=parseMessage(msg);if(!m)continue;if(m.status==='FAILURE'||m.status==='UNDELIVERABLE'){await markInvalid(m.phone);await suppressOtp(m.phone,'sms_failure');await putMetric('SmsDeliveryFailureCount',1);}else if(m.status==='OPT_OUT'){await markInvalid(m.phone);await suppressOtp(m.phone,'sms_opt_out');await putMetric('SmsOptOutCount',1);}}}catch(err){console.error('SmsDeliveryHandler error:',err);throw err;}};function e164(p){return String(p||'').trim().replace(/\s+/g,'');}function parseMessage(s){try{const obj=JSON.parse(s);const phone=obj.phoneNumber||obj.destination||'';const status=(obj.status||obj.deliveryStatus||obj.eventType||'').toString().toUpperCase();if(!phone||!status)return null;return {phone:e164(phone),status};}catch(_){const phone=(s.match(/\+\d{6,15}/)||[])[0];let status='';if(/OPT_OUT/i.test(s))status='OPT_OUT';else if(/UNDELIVERABLE|FAIL|FAILURE/i.test(s))status='FAILURE';else if(/SUCCESS/i.test(s))status='SUCCESS';if(!phone||!status)return null;return {phone:e164(phone),status};}}async function resolveUserArnByPhone(phone){const contactKey='phone:'+e164(phone);const res=await ddb.get({TableName:CONTACT_INDEX_TABLE,Key:{contactKey}}).promise();return res&&res.Item?res.Item.userArn:undefined;}async function verifyPhoneMatch(userArn,phone){try{const res=await ddb.get({TableName:USERS_TABLE,Key:{userArn},ProjectionExpression:'phone'}).promise();const p1=e164(phone);const p2=e164(res&&res.Item&&res.Item.phone);return p2?p2===p1:false;}catch(err){console.error('verifyPhoneMatch error',err);return false;}}async function markInvalid(phone){const userArn=await resolveUserArnByPhone(phone);if(!userArn){await putMetric('ContactIndexMissing',1);console.warn('No userArn found for phone, skipping Users update',phone);return;}const ok=await verifyPhoneMatch(userArn,phone);if(!ok){await putMetric('ContactIndexMismatch',1);console.error('Contact index userArn/phone mismatch, skipping Users update',userArn,phone);return;}await ddb.update({TableName:USERS_TABLE,Key:{userArn},UpdateExpression:'SET phoneStatus = :s, updatedAt = :u',ExpressionAttributeValues:{':s':'invalid',':u':Date.now()}}).promise();}async function suppressOtp(phone,reason){const key='otp:suppress:phone:'+e164(phone);await ddb.put({TableName:RATE_LIMIT_TABLE,Item:{key,blocked:true,reason,stage:STAGE,updatedAt:Date.now()}}).promise();}async function putMetric(name,value){await cloudwatch.putMetricData({Namespace:'Coride/SMSFeedback',MetricData:[{MetricName:name,Dimensions:[{Name:'Stage',Value:STAGE}],Value:value,Unit:'Count'}]}).promise();}`;

    this.smsDeliveryHandler = new lambda.Function(this, 'SmsDeliveryHandler', {
      runtime: lambda.Runtime.NODEJS_18_X,
      handler: 'index.handler',
      code: lambda.Code.fromInline(handlerInline),
      environment: {
        USERS_TABLE: usersTable.tableName,
        RATE_LIMIT_TABLE: rateLimitTable.tableName,
        CONTACT_INDEX_TABLE: contactIndexTable.tableName,
        STAGE: stage,
      },
      description: 'Processes SNS SMS delivery logs: failures/opt-outs -> invalidate & suppress',
      functionName: `coride-sms-delivery-${stage}`,
    });

    usersTable.grantWriteData(this.smsDeliveryHandler);
    usersTable.grantReadData(this.smsDeliveryHandler);
    rateLimitTable.grantWriteData(this.smsDeliveryHandler);
    contactIndexTable.grantReadData(this.smsDeliveryHandler);

    // Subscribe the Lambda to the log group for relevant events
    new logs.SubscriptionFilter(this, 'SmsDeliverySubFilter', {
      logGroup: this.deliveryLogGroup,
      destination: new logs_destinations.LambdaDestination(this.smsDeliveryHandler),
      filterPattern: logs.FilterPattern.anyTerm('UNDELIVERABLE', 'FAIL', 'FAILURE', 'OPT_OUT'),
    });

    // Metrics & alarms
    if (enableAlarms) {
      const failureMetric = new cw.Metric({
        namespace: 'Coride/SMSFeedback',
        metricName: 'SmsDeliveryFailureCount',
        dimensionsMap: { Stage: stage },
        period: Duration.minutes(1),
        statistic: 'sum',
      });
      const failureAlarm = new cw.Alarm(this, 'SmsDeliveryFailureSpikeAlarm', {
        metric: failureMetric,
        threshold: 5,
        evaluationPeriods: 5,
        datapointsToAlarm: 3,
        comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        alarmDescription: `SMS delivery failure spike detected for stage ${stage}`,
      });
      if (alarmTopic) failureAlarm.addAlarmAction(new cw_actions.SnsAction(alarmTopic));
    }

    if (enableAlarms) {
      const optoutMetric = new cw.Metric({
        namespace: 'Coride/SMSFeedback',
        metricName: 'SmsOptOutCount',
        dimensionsMap: { Stage: stage },
        period: Duration.minutes(1),
        statistic: 'sum',
      });
      const optoutAlarm = new cw.Alarm(this, 'SmsOptOutSpikeAlarm', {
        metric: optoutMetric,
        threshold: 5,
        evaluationPeriods: 5,
        datapointsToAlarm: 3,
        comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        alarmDescription: `SMS opt-out spike detected for stage ${stage}`,
      });
      if (alarmTopic) optoutAlarm.addAlarmAction(new cw_actions.SnsAction(alarmTopic));
    }

    if (enableAlarms) {
      const missingMetric = new cw.Metric({
        namespace: 'Coride/SMSFeedback',
        metricName: 'ContactIndexMissing',
        dimensionsMap: { Stage: stage },
        period: Duration.minutes(5),
        statistic: 'sum',
      });
      const missingAlarm = new cw.Alarm(this, 'SmsContactIndexMissingAlarm', {
        metric: missingMetric,
        threshold: 1,
        evaluationPeriods: 1,
        datapointsToAlarm: 1,
        comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        alarmDescription: `SMS contact index missing mapping detected for stage ${stage}`,
      });
      if (alarmTopic) missingAlarm.addAlarmAction(new cw_actions.SnsAction(alarmTopic));
    }

    if (enableAlarms) {
      const mismatchMetric = new cw.Metric({
        namespace: 'Coride/SMSFeedback',
        metricName: 'ContactIndexMismatch',
        dimensionsMap: { Stage: stage },
        period: Duration.minutes(5),
        statistic: 'sum',
      });
      const mismatchAlarm = new cw.Alarm(this, 'SmsContactIndexMismatchAlarm', {
        metric: mismatchMetric,
        threshold: 1,
        evaluationPeriods: 1,
        datapointsToAlarm: 1,
        comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        alarmDescription: `SMS contact index mismatch detected for stage ${stage}`,
      });
      if (alarmTopic) mismatchAlarm.addAlarmAction(new cw_actions.SnsAction(alarmTopic));
    }
  }
}