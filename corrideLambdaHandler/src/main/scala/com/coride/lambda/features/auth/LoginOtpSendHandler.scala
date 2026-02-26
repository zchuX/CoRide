package com.coride.lambda.features.auth

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils}
import com.coride.lambda.util.{RateLimitUtils, RateLimitConfig}
import com.coride.lambda.services.CognitoClient
import com.coride.lambda.router.ValidationException
import com.coride.lambda.util.Validation

object LoginOtpSendHandler {
  private val cognito = new CognitoClient()

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    val phone = JsonUtils.require(node, "phone_number")

    if (!Validation.isValidPhoneE164(phone)) throw new ValidationException("Invalid phone format (E.164 expected)")

    // Rate limit OTP sends per phone
    val allowed = RateLimitUtils.checkUser(phone, namespace = "otp-login-phone", limit = RateLimitConfig.otpPhoneLimit, windowSeconds = RateLimitConfig.otpPhoneWindow)
    if (!allowed) return Responses.json(429, s"""{"error":"Too Many Requests","retryAfter":${RateLimitConfig.otpPhoneWindow}}""")

    val res = cognito.initiateCustomAuth(phone)
    val session = Option(res.getSession).getOrElse("")
    val challenge = Option(res.getChallengeName).map(_.toString).getOrElse("CUSTOM_CHALLENGE")
    val body = s"""{
      "status":"ok",
      "message":"verification code sent",
      "challenge":"${challenge}",
      "session":"${session}"
    }"""
    Responses.json(200, body)
  }
}