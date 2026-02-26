package com.coride.lambda.features.auth

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils, TokenUtils}
import com.coride.lambda.util.{RateLimitUtils}
import com.coride.lambda.services.CognitoClient

object LogoutHandler {
  private val cognito = new CognitoClient()

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    // Minimal per-IP rate limit to avoid spam
    val allowed = RateLimitUtils.checkIp(event, namespace = "logout", limit = 30, windowSeconds = 300)
    if (!allowed) return Responses.json(429, """{"error":"Too Many Requests","retryAfter":300}""")

    val node = JsonUtils.parse(Option(event.getBody).getOrElse(""))
    val refreshTokenOpt = Option(JsonUtils.get(node, "refreshToken").orNull).filter(_.nonEmpty)

    refreshTokenOpt match {
      case None =>
        // Single-device logout requires refreshToken; don't perform global sign-out
        Responses.json(400, """{"error":"Bad Request","message":"Missing refreshToken"}""")
      case Some(rt) =>
        try {
          val _ = cognito.revokeToken(rt)
          Responses.json(200, """{"status":"ok","message":"device logged out"}""")
        } catch {
          case _: Throwable => Responses.json(400, """{"error":"Bad Request","message":"Invalid or expired refresh token"}""")
        }
    }
  }
}