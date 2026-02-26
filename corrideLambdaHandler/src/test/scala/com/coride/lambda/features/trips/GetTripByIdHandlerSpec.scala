package com.coride.lambda.features.trips

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

class GetTripByIdHandlerSpec extends AnyFunSuite {
  test("get-trip-by-id requires bearer token") {
    val event = new APIGatewayProxyRequestEvent()
    val resp = GetTripByIdHandler.handle(event, "arn:trip#123")
    assert(resp.getStatusCode == 401)
  }
}