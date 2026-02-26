package com.coride.lambda.util

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

object RequestUtils {
  def clientIp(event: APIGatewayProxyRequestEvent): Option[String] = {
    val headers = Option(event.getHeaders).map(_.asInstanceOf[java.util.Map[String, String]]).getOrElse(new java.util.HashMap[String, String]())
    val lower = new java.util.HashMap[String, String]()
    val it = headers.entrySet().iterator()
    while (it.hasNext) {
      val e = it.next()
      lower.put(e.getKey.toLowerCase, e.getValue)
    }
    val cfIp = Option(lower.get("cf-connecting-ip")).filter(_.nonEmpty)
    val xff = Option(lower.get("x-forwarded-for")).filter(_.nonEmpty).map(_.split(",").head.trim)
    cfIp.orElse(xff)
  }
}