import { Node } from 'constructs'

/**
 * Centralized configuration for the Coride CDK app.
 *
 * How to override values:
 * - Use CDK context flags on the command line (e.g., -c stage=dev)
 * - Or set them in the app's cdk.json under the "context" key.
 *
 * Required keys (no defaults; all must be explicitly provided per environment):
 * - lambdaJar: Absolute or relative path to the Scala Lambda fat JAR.
 * - sesIdentityArn: Verified SES identity ARN used by Cognito for email sending.
 * - enableDataTrace: Whether API Gateway should log request/response payloads.
 * - throttlingRateLimit: Global stage-level requests per second.
 * - throttlingBurstLimit: Global stage-level burst capacity.
 */
export interface AppConfig {
  lambdaJar: string
  sesIdentityArn: string
  enableDataTrace: boolean
  throttlingRateLimit: number
  throttlingBurstLimit: number
  clientThrottlingRateLimit: number
  clientThrottlingBurstLimit: number
  wafRateLimit: number
  // Handler rate limit envs (optional; defaults applied if not provided)
  loginLimit: number
  loginWindow: number
  otpEmailLimit: number
  otpEmailWindow: number
  otpPhoneLimit: number
  otpPhoneWindow: number
  resetSendLimit: number
  resetSendWindow: number
  resetConfirmLimit: number
  resetConfirmWindow: number
}

export function getConfig(node: Node): AppConfig {
  const lambdaJar = node.tryGetContext('lambdaJar') as string | undefined
  if (!lambdaJar || typeof lambdaJar !== 'string') {
    throw new Error('Missing CDK context: lambdaJar. Provide with -c lambdaJar=...</path/to/jar> or set in cdk.json')
  }

  const sesIdentityArn = node.tryGetContext('sesIdentityArn') as string | undefined
  if (!sesIdentityArn || typeof sesIdentityArn !== 'string') {
    throw new Error('Missing CDK context: sesIdentityArn. Provide a verified SES identity ARN via -c sesIdentityArn=arn:aws:ses:<region>:<account-id>:identity/<email-or-domain>')
  }

  const enableDataTraceCtx = node.tryGetContext('enableDataTrace')
  if (enableDataTraceCtx === undefined) {
    throw new Error('Missing CDK context: enableDataTrace. Provide true/false explicitly per environment.')
  }
  const enableDataTrace = enableDataTraceCtx === true || enableDataTraceCtx === 'true' || enableDataTraceCtx === 1 || enableDataTraceCtx === '1'

  const rate = node.tryGetContext('throttlingRateLimit')
  const burst = node.tryGetContext('throttlingBurstLimit')
  if (rate === undefined) {
    throw new Error('Missing CDK context: throttlingRateLimit. Provide a number explicitly per environment.')
  }
  if (burst === undefined) {
    throw new Error('Missing CDK context: throttlingBurstLimit. Provide a number explicitly per environment.')
  }
  const throttlingRateLimit = (typeof rate === 'number') ? rate : parseInt(rate as string, 10)
  const throttlingBurstLimit = (typeof burst === 'number') ? burst : parseInt(burst as string, 10)
  if (!Number.isFinite(throttlingRateLimit)) {
    throw new Error('Invalid CDK context: throttlingRateLimit must be a finite number.')
  }
  if (!Number.isFinite(throttlingBurstLimit)) {
    throw new Error('Invalid CDK context: throttlingBurstLimit must be a finite number.')
  }

  const clientRateCtx = node.tryGetContext('clientThrottlingRateLimit')
  const clientBurstCtx = node.tryGetContext('clientThrottlingBurstLimit')
  if (clientRateCtx === undefined) {
    throw new Error('Missing CDK context: clientThrottlingRateLimit. Provide a number explicitly per environment.')
  }
  if (clientBurstCtx === undefined) {
    throw new Error('Missing CDK context: clientThrottlingBurstLimit. Provide a number explicitly per environment.')
  }
  const clientThrottlingRateLimit = (typeof clientRateCtx === 'number') ? clientRateCtx : parseInt(clientRateCtx as string, 10)
  const clientThrottlingBurstLimit = (typeof clientBurstCtx === 'number') ? clientBurstCtx : parseInt(clientBurstCtx as string, 10)
  if (!Number.isFinite(clientThrottlingRateLimit)) {
    throw new Error('Invalid CDK context: clientThrottlingRateLimit must be a finite number.')
  }
  if (!Number.isFinite(clientThrottlingBurstLimit)) {
    throw new Error('Invalid CDK context: clientThrottlingBurstLimit must be a finite number.')
  }

  const wafRateCtx = node.tryGetContext('wafRateLimit')
  if (wafRateCtx === undefined) {
    throw new Error('Missing CDK context: wafRateLimit. Provide per-IP requests per 5 minutes explicitly per environment.')
  }
  const wafRateLimit = (typeof wafRateCtx === 'number') ? wafRateCtx : parseInt(wafRateCtx as string, 10)
  if (!Number.isFinite(wafRateLimit)) {
    throw new Error('Invalid CDK context: wafRateLimit must be a finite number.')
  }

  // Optional per-handler limits with sensible defaults
  const readNumber = (key: string, def: number): number => {
    const v = node.tryGetContext(key)
    if (v === undefined || v === null) return def
    const n = typeof v === 'number' ? v : parseInt(v as string, 10)
    return Number.isFinite(n) ? n : def
  }

  const loginLimit = readNumber('LOGIN_LIMIT', 5)
  const loginWindow = readNumber('LOGIN_WINDOW', 60)
  const otpEmailLimit = readNumber('OTP_EMAIL_LIMIT', 2)
  const otpEmailWindow = readNumber('OTP_EMAIL_WINDOW', 60)
  const otpPhoneLimit = readNumber('OTP_PHONE_LIMIT', 2)
  const otpPhoneWindow = readNumber('OTP_PHONE_WINDOW', 60)
  const resetSendLimit = readNumber('RESET_SEND_LIMIT', 2)
  const resetSendWindow = readNumber('RESET_SEND_WINDOW', 60)
  const resetConfirmLimit = readNumber('RESET_CONFIRM_LIMIT', 10)
  const resetConfirmWindow = readNumber('RESET_CONFIRM_WINDOW', 300)

  return {
    lambdaJar,
    sesIdentityArn,
    enableDataTrace,
    throttlingRateLimit,
    throttlingBurstLimit,
    clientThrottlingRateLimit,
    clientThrottlingBurstLimit,
    wafRateLimit,
    loginLimit,
    loginWindow,
    otpEmailLimit,
    otpEmailWindow,
    otpPhoneLimit,
    otpPhoneWindow,
    resetSendLimit,
    resetSendWindow,
    resetConfirmLimit,
    resetConfirmWindow,
  }
}