import { Stack, StackProps } from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { CorideTables } from '../../constructs/tables';

export interface CorideTablesStackProps extends StackProps {
  stage: string;
}

export class CorideTablesStack extends Stack {
  public readonly tables: CorideTables;

  constructor(scope: Construct, id: string, props: CorideTablesStackProps) {
    super(scope, id, props);

    this.tables = new CorideTables(this, 'Tables', { stage: props.stage });
  }
}
