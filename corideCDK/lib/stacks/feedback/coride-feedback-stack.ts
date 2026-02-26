import { Stack, StackProps } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { SesFeedback } from './ses-feedback-construct';
import { SmsFeedback } from './sms-feedback-construct';
import { CorideTables } from '../../constructs/tables';
import { getConfig } from '../../config';

export interface CorideFeedbackStackProps extends StackProps {
  stage: string;
  tables: CorideTables;
}

export class CorideFeedbackStack extends Stack {
  constructor(scope: Construct, id: string, props: CorideFeedbackStackProps) {
    super(scope, id, props);

    const cfg = getConfig(this.node);
    const tables = props.tables;

    const ses = new SesFeedback(this, 'SesFeedback', {
      stage: props.stage,
      sesIdentityArn: cfg.sesIdentityArn,
      usersTable: tables.users,
      emailFeedbackTable: tables.emailFeedback,
      contactIndexTable: tables.userContactIndex,
      rateLimitTable: tables.rateLimit,
      enableAlarms: props.stage !== 'dev',
    });

    new SmsFeedback(this, 'SmsFeedback', {
      stage: props.stage,
      usersTable: tables.users,
      contactIndexTable: tables.userContactIndex,
      rateLimitTable: tables.rateLimit,
      alarmTopic: ses.bounceTopic, // reuse alarm topic for simplicity
      enableAlarms: props.stage !== 'dev',
    });
  }
}