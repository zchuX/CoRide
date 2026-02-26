package com.coride.lambda.features.trips

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

class CreateUserGroupHandlerSpec extends AnyFunSuite {
  test("create-user-group requires bearer token") {
    val event = new APIGatewayProxyRequestEvent()
    val resp = CreateUserGroupHandler.handle(event)
    assert(resp.getStatusCode == 401)
  }
}