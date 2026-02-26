package com.coride.lambda.util

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

object RateLimitUtils {
  def checkIp(event: APIGatewayProxyRequestEvent, namespace: String, limit: Int, windowSeconds: Int): Boolean = {
    val ip = RequestUtils.clientIp(event)
      .orElse(Option(event.getRequestContext).flatMap(rc => Option(rc.getIdentity)).flatMap(id => Option(id.getSourceIp)))
      .getOrElse("unknown")
    RateLimiter.checkRateLimit(s"$namespace:ip:$ip", limit, windowSeconds)
  }

  def checkUser(username: String, namespace: String, limit: Int, windowSeconds: Int): Boolean = {
    RateLimiter.checkRateLimit(s"$namespace:user:$username", limit, windowSeconds)
  }

  def checkIpOrUser(event: APIGatewayProxyRequestEvent, username: String, namespace: String, limit: Int, windowSeconds: Int): Boolean = {
    val ipOpt = RequestUtils.clientIp(event)
      .orElse(Option(event.getRequestContext).flatMap(rc => Option(rc.getIdentity)).flatMap(id => Option(id.getSourceIp)))
    ipOpt match {
      case Some(ip) => RateLimiter.checkRateLimit(s"$namespace:ip:$ip", limit, windowSeconds)
      case None => RateLimiter.checkRateLimit(s"$namespace:user:$username", limit, windowSeconds)
    }
  }
}