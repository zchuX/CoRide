package com.coride.lambda.features.auth

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils}
import com.coride.lambda.util.RateLimitUtils
import com.coride.lambda.services.CognitoClient
import com.coride.lambda.router.ValidationException
import com.coride.lambda.util.Validation

object LoginOtpVerifyHandler {
  private val cognito = new CognitoClient()

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    val phone = JsonUtils.require(node, "phone_number")
    val session = JsonUtils.require(node, "session")
    val code = JsonUtils.require(node, "code")

    if (!Validation.isValidPhoneE164(phone)) throw new ValidationException("Invalid phone format (E.164 expected)")

    // Basic per-IP limit on verify attempts
    val allowed = RateLimitUtils.checkIp(event, namespace = "login-otp-verify", limit = 30, windowSeconds = 300)
    if (!allowed) return Responses.json(429, """{"error":"Too Many Requests","retryAfter":300}""")

    val auth = cognito.respondToCustomChallenge(phone, session, code)
    val body = s"""{
      "status":"ok",
      "idToken":"${auth.getIdToken}",
      "accessToken":"${auth.getAccessToken}",
      "refreshToken":"${Option(auth.getRefreshToken).getOrElse("")}" 
    }"""
    Responses.json(200, body)
  }
}