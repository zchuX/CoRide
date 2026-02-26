package com.coride.lambda.features.auth

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

class MeHandlerSpec extends AnyFunSuite {
  test("me endpoint requires bearer token") {
    val event = new APIGatewayProxyRequestEvent()
    val resp = MeHandler.handle(event)
    assert(resp.getStatusCode == 401)
  }
}