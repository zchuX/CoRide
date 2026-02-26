package com.coride.lambda.features.trips

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

class GetUserTripsHandlerSpec extends AnyFunSuite {
  test("get-user-trips requires bearer token") {
    val event = new APIGatewayProxyRequestEvent()
    val resp = GetUserTripsHandler.handle(event)
    assert(resp.getStatusCode == 401)
  }
}