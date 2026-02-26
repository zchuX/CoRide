package com.coride.lambda.util

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent

object Responses {
  def json(status: Int, body: String): APIGatewayProxyResponseEvent = {
    val resp = new APIGatewayProxyResponseEvent()
    resp.setStatusCode(status)
    // Stage-aware CORS: only include headers if allowed origin is configured
    val allowedOriginOpt = sys.env.get("CORS_ALLOW_ORIGIN").orElse(Option(System.getProperty("CORS_ALLOW_ORIGIN"))).filter(_.nonEmpty)
    val headers = new java.util.HashMap[String, String]()
    headers.put("Content-Type", "application/json")
    allowedOriginOpt.foreach { origin =>
      headers.put("Access-Control-Allow-Origin", origin)
      headers.put("Access-Control-Allow-Headers", "Content-Type,Authorization,X-Api-Key")
      headers.put("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS")
    }
    resp.setHeaders(headers)
    resp.setBody(body)
    resp
  }
}