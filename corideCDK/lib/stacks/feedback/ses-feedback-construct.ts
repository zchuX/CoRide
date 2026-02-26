import { Construct } from 'constructs';
import { aws_sns as sns } from 'aws-cdk-lib';
import { aws_sns_subscriptions as subs } from 'aws-cdk-lib';
import { aws_lambda as lambda } from 'aws-cdk-lib';
import { aws_dynamodb as dynamodb } from 'aws-cdk-lib';
import { aws_sqs as sqs } from 'aws-cdk-lib';
import { aws_cloudwatch as cw } from 'aws-cdk-lib';
import { aws_cloudwatch_actions as cw_actions } from 'aws-cdk-lib';
import { Duration } from 'aws-cdk-lib';
import { aws_iam as iam } from 'aws-cdk-lib';
import { custom_resources as cr } from 'aws-cdk-lib';
import { Stack } from 'aws-cdk-lib';

export interface SesFeedbackProps {
  stage: string;
  sesIdentityArn: string;
  usersTable: dynamodb.Table;
  emailFeedbackTable: dynamodb.Table;
  contactIndexTable: dynamodb.Table;
  rateLimitTable: dynamodb.Table;
  alarmTopic?: sns.ITopic;
  enableAlarms?: boolean;
}

/**
 * Configures SES bounce and complaint notifications to publish to SNS topics,
 * and grants SES permission to publish. Uses AwsCustomResource to call
 * SetIdentityNotificationTopic for both Bounce and Complaint events.
 */
export class SesFeedback extends Construct {
  public readonly bounceTopic: sns.Topic;
  public readonly complaintTopic: sns.Topic;
  public readonly bounceHandler: lambda.Function;
  public readonly complaintHandler: lambda.Function;

  constructor(scope: Construct, id: string, props: SesFeedbackProps) {
    super(scope, id);

    const { stage, sesIdentityArn, usersTable, emailFeedbackTable, contactIndexTable, rateLimitTable, alarmTopic: providedAlarmTopic } = props;
    const region = Stack.of(this).region;
    const enableAlarms = props.enableAlarms ?? true;

    // Central alarm topic (email notifications)
    const alarmTopic = enableAlarms
      ? (providedAlarmTopic ?? new sns.Topic(this, 'AutoAlarmTopic', { topicName: `Coride-AutoAlarm-${stage}` }))
      : undefined;
    if (enableAlarms && !providedAlarmTopic && alarmTopic) {
      alarmTopic.addSubscription(new subs.EmailSubscription('admin@zumoride.com'));
    }

    // SNS Topics for SES feedback
    this.bounceTopic = new sns.Topic(this, 'SesBounceTopic', {
      topicName: `Coride-SesBounce-${stage}`,
    });

    this.complaintTopic = new sns.Topic(this, 'SesComplaintTopic', {
      topicName: `Coride-SesComplaint-${stage}`,
    });

    // Allow SES to publish to the topics (scoped by identity ARN)
    const sesPrincipal = new iam.ServicePrincipal('ses.amazonaws.com');
    this.bounceTopic.addToResourcePolicy(new iam.PolicyStatement({
      principals: [sesPrincipal],
      actions: ['SNS:Publish'],
      resources: [this.bounceTopic.topicArn],
      conditions: {
        StringEquals: {
          'AWS:SourceArn': sesIdentityArn,
        },
      },
    }));
    this.complaintTopic.addToResourcePolicy(new iam.PolicyStatement({
      principals: [sesPrincipal],
      actions: ['SNS:Publish'],
      resources: [this.complaintTopic.topicArn],
      conditions: {
        StringEquals: {
          'AWS:SourceArn': sesIdentityArn,
        },
      },
    }));

    // Configure SES identity to send feedback to SNS topics via custom resources
    const bounceConfig = new cr.AwsCustomResource(this, 'SesSetBounceTopic', {
      onCreate: {
        service: 'SES',
        action: 'SetIdentityNotificationTopic',
        parameters: {
          Identity: sesIdentityArn,
          NotificationType: 'Bounce',
          SnsTopic: this.bounceTopic.topicArn,
        },
        region,
        physicalResourceId: cr.PhysicalResourceId.of(`SesBounceTopic-${stage}`),
      },
      onUpdate: {
        service: 'SES',
        action: 'SetIdentityNotificationTopic',
        parameters: {
          Identity: sesIdentityArn,
          NotificationType: 'Bounce',
          SnsTopic: this.bounceTopic.topicArn,
        },
        region,
        physicalResourceId: cr.PhysicalResourceId.of(`SesBounceTopic-${stage}`),
      },
      policy: cr.AwsCustomResourcePolicy.fromSdkCalls({ resources: cr.AwsCustomResourcePolicy.ANY_RESOURCE }),
    });

    const complaintConfig = new cr.AwsCustomResource(this, 'SesSetComplaintTopic', {
      onCreate: {
        service: 'SES',
        action: 'SetIdentityNotificationTopic',
        parameters: {
          Identity: sesIdentityArn,
          NotificationType: 'Complaint',
          SnsTopic: this.complaintTopic.topicArn,
        },
        region,
        physicalResourceId: cr.PhysicalResourceId.of(`SesComplaintTopic-${stage}`),
      },
      onUpdate: {
        service: 'SES',
        action: 'SetIdentityNotificationTopic',
        parameters: {
          Identity: sesIdentityArn,
          NotificationType: 'Complaint',
          SnsTopic: this.complaintTopic.topicArn,
        },
        region,
        physicalResourceId: cr.PhysicalResourceId.of(`SesComplaintTopic-${stage}`),
      },
      policy: cr.AwsCustomResourcePolicy.fromSdkCalls({ resources: cr.AwsCustomResourcePolicy.ANY_RESOURCE }),
    });

    // Ensure topics are created before custom resource calls
    bounceConfig.node.addDependency(this.bounceTopic);
    complaintConfig.node.addDependency(this.complaintTopic);

    // DLQs per handler
    const bounceDlq = new sqs.Queue(this, 'SesBounceDLQ', { queueName: `Coride-SesBounceDLQ-${stage}` });
    const complaintDlq = new sqs.Queue(this, 'SesComplaintDLQ', { queueName: `Coride-SesComplaintDLQ-${stage}` });

    // Bounce handler: resolve userArn via contact index, mark invalid email, suppress hard bounces, record feedback; emit soft bounce metric
    const bounceInline = `const AWS=require('aws-sdk');const ddb=new AWS.DynamoDB.DocumentClient();const cloudwatch=new AWS.CloudWatch();const USERS_TABLE=process.env.USERS_TABLE;const EMAIL_FEEDBACK_TABLE=process.env.EMAIL_FEEDBACK_TABLE;const RATE_LIMIT_TABLE=process.env.RATE_LIMIT_TABLE;const CONTACT_INDEX_TABLE=process.env.CONTACT_INDEX_TABLE;const STAGE=process.env.STAGE||'prod';exports.handler=async(e)=>{try{for(const r of e.Records||[]){if(r.EventSource!=='aws:sns')continue;const m=JSON.parse(r.Sns.Message);if(m.notificationType!=='Bounce')continue;const mail=m.mail||{};const mid=mail.messageId||('unknown-'+Date.now());const bt=(m.bounce&&m.bounce.bounceType)||'Unknown';const recips=(m.bounce&&Array.isArray(m.bounce.bouncedRecipients))?m.bounce.bouncedRecipients.map(x=>x.emailAddress).filter(Boolean):Array.isArray(mail.destination)?mail.destination.filter(Boolean):[];const uniq=[...new Set(recips)];for(const email of uniq){if(!email)continue;if(bt==='Permanent'){await markInvalid(email);await suppressOtp(email,'hard_bounce');await recordFeedback(mid,'hard_bounce',email);}else{await recordFeedback(mid,'soft_bounce',email);await putMetric('SoftBounceCount',1);}}}}catch(err){console.error('SesBounceHandler error:',err);throw err;}};function normEmail(e){return String(e||'').trim().toLowerCase();}async function resolveUserArnByEmail(email){const contactKey='email:'+normEmail(email);const res=await ddb.get({TableName:CONTACT_INDEX_TABLE,Key:{contactKey}}).promise();return res&&res.Item?res.Item.userArn:undefined;}async function verifyEmailMatch(userArn,email){try{const res=await ddb.get({TableName:USERS_TABLE,Key:{userArn},ProjectionExpression:'email'}).promise();const ue=normEmail(email);const ueStored=normEmail(res&&res.Item&&res.Item.email);return ueStored?ueStored===ue:false;}catch(err){console.error('verifyEmailMatch error',err);return false;}}async function markInvalid(email){const userArn=await resolveUserArnByEmail(email);if(!userArn){await putMetric('ContactIndexMissing',1);console.warn('No userArn found for email, skipping Users update',email);return;}const ok=await verifyEmailMatch(userArn,email);if(!ok){await putMetric('ContactIndexMismatch',1);console.error('Contact index userArn/email mismatch, skipping Users update',userArn,email);return;}await ddb.update({TableName:USERS_TABLE,Key:{userArn},UpdateExpression:'SET emailStatus = :s, updatedAt = :u',ExpressionAttributeValues:{':s':'invalid',':u':Date.now()}}).promise();}async function suppressOtp(email,reason){const key='otp:suppress:email:'+email;await ddb.put({TableName:RATE_LIMIT_TABLE,Item:{key,blocked:true,reason,stage:STAGE,updatedAt:Date.now()}}).promise();}async function recordFeedback(mid,type,email){const id=mid+':'+email;const item={id,type,email,stage:STAGE,timestamp:Date.now()};await ddb.put({TableName:EMAIL_FEEDBACK_TABLE,Item:item,ConditionExpression:'attribute_not_exists(id)'}).promise();}async function putMetric(name,value){await cloudwatch.putMetricData({Namespace:'Coride/SESFeedback',MetricData:[{MetricName:name,Dimensions:[{Name:'Stage',Value:STAGE}],Value:value,Unit:'Count'}]}).promise();}`;
    this.bounceHandler = new lambda.Function(this, 'SesBounceHandler', {
      runtime: lambda.Runtime.NODEJS_18_X,
      handler: 'index.handler',
      code: lambda.Code.fromInline(bounceInline),
      environment: {
        USERS_TABLE: usersTable.tableName,
        EMAIL_FEEDBACK_TABLE: emailFeedbackTable.tableName,
        RATE_LIMIT_TABLE: rateLimitTable.tableName,
        CONTACT_INDEX_TABLE: contactIndexTable.tableName,
        STAGE: stage,
      },
      deadLetterQueue: bounceDlq,
      deadLetterQueueEnabled: true,
      functionName: `coride-ses-bounce-${stage}`,
      description: 'Processes SES bounces: hard -> invalidate email; soft -> metric only',
    });
    usersTable.grantWriteData(this.bounceHandler);
    usersTable.grantReadData(this.bounceHandler);
    emailFeedbackTable.grantWriteData(this.bounceHandler);
    contactIndexTable.grantReadData(this.bounceHandler);
    this.bounceTopic.addSubscription(new subs.LambdaSubscription(this.bounceHandler));

    // Complaint handler: suppress sends (RateLimit), mark invalid, record feedback, emit complaint metric
    const complaintInline = `const AWS=require('aws-sdk');const ddb=new AWS.DynamoDB.DocumentClient();const cloudwatch=new AWS.CloudWatch();const USERS_TABLE=process.env.USERS_TABLE;const EMAIL_FEEDBACK_TABLE=process.env.EMAIL_FEEDBACK_TABLE;const RATE_LIMIT_TABLE=process.env.RATE_LIMIT_TABLE;const CONTACT_INDEX_TABLE=process.env.CONTACT_INDEX_TABLE;const STAGE=process.env.STAGE||'prod';exports.handler=async(e)=>{try{for(const r of e.Records||[]){if(r.EventSource!=='aws:sns')continue;const m=JSON.parse(r.Sns.Message);if(m.notificationType!=='Complaint')continue;const mail=m.mail||{};const mid=mail.messageId||('unknown-'+Date.now());const recips=(m.complaint&&Array.isArray(m.complaint.complainedRecipients))?m.complaint.complainedRecipients.map(x=>x.emailAddress).filter(Boolean):Array.isArray(mail.destination)?mail.destination.filter(Boolean):[];const uniq=[...new Set(recips)];for(const email of uniq){if(!email)continue;await markInvalid(email);await suppressOtp(email,'complaint');await recordFeedback(mid,'complaint',email,m.complaint);await putMetric('ComplaintCount',1);}}}catch(err){console.error('SesComplaintHandler error:',err);throw err;}};function normEmail(e){return String(e||'').trim().toLowerCase();}async function resolveUserArnByEmail(email){const contactKey='email:'+normEmail(email);const res=await ddb.get({TableName:CONTACT_INDEX_TABLE,Key:{contactKey}}).promise();return res&&res.Item?res.Item.userArn:undefined;}async function verifyEmailMatch(userArn,email){try{const res=await ddb.get({TableName:USERS_TABLE,Key:{userArn},ProjectionExpression:'email'}).promise();const ue=normEmail(email);const ueStored=normEmail(res&&res.Item&&res.Item.email);return ueStored?ueStored===ue:false;}catch(err){console.error('verifyEmailMatch error',err);return false;}}async function markInvalid(email){const userArn=await resolveUserArnByEmail(email);if(!userArn){await putMetric('ContactIndexMissing',1);console.warn('No userArn found for email, skipping Users update',email);return;}const ok=await verifyEmailMatch(userArn,email);if(!ok){await putMetric('ContactIndexMismatch',1);console.error('Contact index userArn/email mismatch, skipping Users update',userArn,email);return;}await ddb.update({TableName:USERS_TABLE,Key:{userArn},UpdateExpression:'SET emailStatus = :s, updatedAt = :u',ExpressionAttributeValues:{':s':'invalid',':u':Date.now()}}).promise();}async function suppressOtp(email,reason){const key='otp:suppress:email:'+email;await ddb.put({TableName:RATE_LIMIT_TABLE,Item:{key,blocked:true,reason,stage:STAGE,updatedAt:Date.now()}}).promise();}async function recordFeedback(mid,type,email,complaint){const id=mid+':'+email;const item={id,type,email,stage:STAGE,timestamp:Date.now(),complaintReason:(complaint&&complaint.complaintFeedbackType)||undefined};await ddb.put({TableName:EMAIL_FEEDBACK_TABLE,Item:item,ConditionExpression:'attribute_not_exists(id)'}).promise();}async function putMetric(name,value){await cloudwatch.putMetricData({Namespace:'Coride/SESFeedback',MetricData:[{MetricName:name,Dimensions:[{Name:'Stage',Value:STAGE}],Value:value,Unit:'Count'}]}).promise();}`;
    this.complaintHandler = new lambda.Function(this, 'SesComplaintHandler', {
      runtime: lambda.Runtime.NODEJS_18_X,
      handler: 'index.handler',
      code: lambda.Code.fromInline(complaintInline),
      environment: {
        USERS_TABLE: usersTable.tableName,
        EMAIL_FEEDBACK_TABLE: emailFeedbackTable.tableName,
        RATE_LIMIT_TABLE: rateLimitTable.tableName,
        CONTACT_INDEX_TABLE: contactIndexTable.tableName,
        STAGE: stage,
      },
      deadLetterQueue: complaintDlq,
      deadLetterQueueEnabled: true,
      functionName: `coride-ses-complaint-${stage}`,
      description: 'Processes SES complaints: suppress OTPs, mark invalid, record audit',
    });
    usersTable.grantWriteData(this.complaintHandler);
    usersTable.grantReadData(this.complaintHandler);
    emailFeedbackTable.grantWriteData(this.complaintHandler);
    rateLimitTable.grantWriteData(this.complaintHandler);
    contactIndexTable.grantReadData(this.complaintHandler);
    this.complaintTopic.addSubscription(new subs.LambdaSubscription(this.complaintHandler));

    if (enableAlarms) {
      // CloudWatch Alarms
      // - Complaint spikes: alarm on custom metric ComplaintCount (>5 in 5 minutes)
      const complaintMetric = new cw.Metric({
        namespace: 'Coride/SESFeedback',
        metricName: 'ComplaintCount',
        dimensionsMap: { Stage: stage },
        period: Duration.minutes(1),
        statistic: 'sum',
      });
      const complaintRateAlarm = new cw.Alarm(this, 'ComplaintRateSpikeAlarm', {
        metric: complaintMetric,
        threshold: 5,
        evaluationPeriods: 5,
        datapointsToAlarm: 3,
        comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        alarmDescription: `Complaint rate spike detected for stage ${stage}`,
      });
      if (alarmTopic) complaintRateAlarm.addAlarmAction(new cw_actions.SnsAction(alarmTopic));
    }

    // - Contact index integrity alarms
    if (enableAlarms) {
      const missingMetric = new cw.Metric({
        namespace: 'Coride/SESFeedback',
        metricName: 'ContactIndexMissing',
        dimensionsMap: { Stage: stage },
        period: Duration.minutes(5),
        statistic: 'sum',
      });
      new cw.Alarm(this, 'ContactIndexMissingAlarm', {
        metric: missingMetric,
        threshold: 1,
        evaluationPeriods: 1,
        datapointsToAlarm: 1,
        comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        alarmDescription: `Contact index missing mapping detected for stage ${stage}`,
      });
    }

    if (enableAlarms) {
      const mismatchMetric = new cw.Metric({
        namespace: 'Coride/SESFeedback',
        metricName: 'ContactIndexMismatch',
        dimensionsMap: { Stage: stage },
        period: Duration.minutes(5),
        statistic: 'sum',
      });
      new cw.Alarm(this, 'ContactIndexMismatchAlarm', {
        metric: mismatchMetric,
        threshold: 1,
        evaluationPeriods: 1,
        datapointsToAlarm: 1,
        comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        alarmDescription: `Contact index mismatch detected for stage ${stage}`,
      });
    }

    // - Lambda error alarms
    if (enableAlarms) {
      const bounceErrorsAlarm = new cw.Alarm(this, 'SesBounceErrorsAlarm', {
        metric: this.bounceHandler.metricErrors({ period: Duration.minutes(1) }),
        threshold: 1,
        evaluationPeriods: 1,
        comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        alarmDescription: `Errors in SesBounceHandler for stage ${stage}`,
      });
      if (alarmTopic) bounceErrorsAlarm.addAlarmAction(new cw_actions.SnsAction(alarmTopic));
      const complaintErrorsAlarm = new cw.Alarm(this, 'SesComplaintErrorsAlarm', {
        metric: this.complaintHandler.metricErrors({ period: Duration.minutes(1) }),
        threshold: 1,
        evaluationPeriods: 1,
        comparisonOperator: cw.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        alarmDescription: `Errors in SesComplaintHandler for stage ${stage}`,
      });
      if (alarmTopic) complaintErrorsAlarm.addAlarmAction(new cw_actions.SnsAction(alarmTopic));
    }
  }
}