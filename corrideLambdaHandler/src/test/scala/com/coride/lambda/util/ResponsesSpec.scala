package com.coride.lambda.util

import org.scalatest.funsuite.AnyFunSuite

class ResponsesSpec extends AnyFunSuite {
  test("json sets status code and content-type header") {
    System.setProperty("CORS_ALLOW_ORIGIN", "*")
    val resp = Responses.json(200, "{}")
    assert(resp.getStatusCode == 200)
    val headers = resp.getHeaders
    assert(headers != null)
    assert("application/json" == headers.get("Content-Type"))
    assert("*" == headers.get("Access-Control-Allow-Origin"))
    System.clearProperty("CORS_ALLOW_ORIGIN")
  }
}