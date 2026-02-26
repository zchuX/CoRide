package com.coride.lambda.util

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

class RequestUtilsSpec extends AnyFunSuite {
  test("clientIp prefers CF-Connecting-IP when present") {
    val event = new APIGatewayProxyRequestEvent()
    val headers = new java.util.HashMap[String, String]()
    headers.put("CF-Connecting-IP", "1.2.3.4")
    event.setHeaders(headers)
    assert(RequestUtils.clientIp(event).contains("1.2.3.4"))
  }

  test("clientIp falls back to X-Forwarded-For first IP") {
    val event = new APIGatewayProxyRequestEvent()
    val headers = new java.util.HashMap[String, String]()
    headers.put("X-Forwarded-For", "5.6.7.8, 9.10.11.12")
    event.setHeaders(headers)
    assert(RequestUtils.clientIp(event).contains("5.6.7.8"))
  }

  test("clientIp returns None when headers empty") {
    val event = new APIGatewayProxyRequestEvent()
    event.setHeaders(new java.util.HashMap[String, String]())
    assert(RequestUtils.clientIp(event).isEmpty)
  }
}