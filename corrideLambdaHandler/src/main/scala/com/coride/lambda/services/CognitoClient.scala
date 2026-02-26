package com.coride.lambda.services

import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder
import com.amazonaws.services.cognitoidp.model._

class CognitoClient {
  private val region: String = Option(System.getenv("AWS_REGION")).getOrElse("us-west-2")
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val clientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")

  private val client: AWSCognitoIdentityProvider = AWSCognitoIdentityProviderClientBuilder
    .standard()
    .withRegion(region)
    .build()

  def signUp(username: String, password: String, emailOpt: Option[String], phoneOpt: Option[String], nameOpt: Option[String]): SignUpResult = {
    val attrs = new java.util.ArrayList[AttributeType]()
    emailOpt.foreach(e => attrs.add(new AttributeType().withName("email").withValue(e)))
    phoneOpt.foreach(p => attrs.add(new AttributeType().withName("phone_number").withValue(p)))
    nameOpt.foreach(n => attrs.add(new AttributeType().withName("name").withValue(n)))
    val req = new SignUpRequest()
      .withClientId(clientId)
      .withUsername(username)
      .withPassword(password)
      .withUserAttributes(attrs)
    client.signUp(req)
  }

  def confirmSignUp(username: String, code: String): ConfirmSignUpResult = {
    val req = new ConfirmSignUpRequest()
      .withClientId(clientId)
      .withUsername(username)
      .withConfirmationCode(code)
    client.confirmSignUp(req)
  }

  def resendConfirmationCode(username: String): ResendConfirmationCodeResult = {
    val req = new ResendConfirmationCodeRequest()
      .withClientId(clientId)
      .withUsername(username)
    client.resendConfirmationCode(req)
  }

  def initiatePasswordAuth(username: String, password: String): AuthenticationResultType = {
    val params = new java.util.HashMap[String, String]()
    params.put("USERNAME", username)
    params.put("PASSWORD", password)
    val req = new InitiateAuthRequest()
      .withAuthFlow(AuthFlowType.USER_PASSWORD_AUTH)
      .withClientId(clientId)
      .withAuthParameters(params)
    val res = client.initiateAuth(req)
    res.getAuthenticationResult
  }

  // Initiate custom auth flow (OTP via phone or username alias)
  def initiateCustomAuth(usernameOrPhone: String): InitiateAuthResult = {
    val params = new java.util.HashMap[String, String]()
    params.put("USERNAME", usernameOrPhone)
    val req = new InitiateAuthRequest()
      .withAuthFlow(AuthFlowType.CUSTOM_AUTH)
      .withClientId(clientId)
      .withAuthParameters(params)
    client.initiateAuth(req)
  }

  // Respond to custom challenge with the OTP code
  def respondToCustomChallenge(usernameOrPhone: String, session: String, code: String): AuthenticationResultType = {
    val params = new java.util.HashMap[String, String]()
    params.put("USERNAME", usernameOrPhone)
    params.put("ANSWER", code)
    val req = new RespondToAuthChallengeRequest()
      .withChallengeName(ChallengeNameType.CUSTOM_CHALLENGE)
      .withClientId(clientId)
      .withSession(session)
      .withChallengeResponses(params)
    val res = client.respondToAuthChallenge(req)
    res.getAuthenticationResult
  }

  def forgotPassword(username: String): ForgotPasswordResult = {
    val req = new ForgotPasswordRequest()
      .withClientId(clientId)
      .withUsername(username)
    client.forgotPassword(req)
  }

  def confirmForgotPassword(username: String, code: String, newPassword: String): ConfirmForgotPasswordResult = {
    val req = new ConfirmForgotPasswordRequest()
      .withClientId(clientId)
      .withUsername(username)
      .withConfirmationCode(code)
      .withPassword(newPassword)
    client.confirmForgotPassword(req)
  }

  def adminInitiateRefresh(refreshToken: String): AuthenticationResultType = {
    val params = new java.util.HashMap[String, String]()
    params.put("REFRESH_TOKEN", refreshToken)
    val req = new AdminInitiateAuthRequest()
      .withAuthFlow(AuthFlowType.REFRESH_TOKEN_AUTH)
      .withUserPoolId(userPoolId)
      .withClientId(clientId)
      .withAuthParameters(params)
    val res = client.adminInitiateAuth(req)
    res.getAuthenticationResult
  }

  def getUser(accessToken: String): GetUserResult = {
    val req = new GetUserRequest().withAccessToken(accessToken)
    client.getUser(req)
  }

  // Admin fetch by username, used post-OTP verification to persist profile
  def adminGetUser(username: String): AdminGetUserResult = {
    val req = new AdminGetUserRequest()
      .withUserPoolId(userPoolId)
      .withUsername(username)
    client.adminGetUser(req)
  }

  // Global sign-out using access token; invalidates all refresh tokens
  def globalSignOut(accessToken: String): GlobalSignOutResult = {
    val req = new GlobalSignOutRequest().withAccessToken(accessToken)
    client.globalSignOut(req)
  }

  // Revoke a single refresh token (single-device logout)
  def revokeToken(refreshToken: String): RevokeTokenResult = {
    val req = new RevokeTokenRequest()
      .withClientId(clientId)
      .withToken(refreshToken)
    client.revokeToken(req)
  }
}