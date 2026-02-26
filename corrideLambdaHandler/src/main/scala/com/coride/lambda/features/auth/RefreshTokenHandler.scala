package com.coride.lambda.features.auth

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.lambda.util.JsonUtils
import com.coride.lambda.util.RateLimitUtils
import com.coride.lambda.services.CognitoClient

object RefreshTokenHandler {
  private val cognito = new CognitoClient()

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    val refreshToken = JsonUtils.require(node, "refreshToken")
    // Per-IP limit: 30 requests in 300 seconds
    if (!RateLimitUtils.checkIp(event, namespace = "refresh-token", limit = 30, windowSeconds = 300)) {
      return Responses.json(429, """{"error":"Too Many Requests","retryAfter":300}""")
    }
    val auth = cognito.adminInitiateRefresh(refreshToken)
    val body = s"""{
      "status":"ok",
      "idToken":"${auth.getIdToken}",
      "accessToken":"${auth.getAccessToken}",
      "refreshToken":"${Option(auth.getRefreshToken).getOrElse(refreshToken)}"
    }"""
    Responses.json(200, body)
  }
}