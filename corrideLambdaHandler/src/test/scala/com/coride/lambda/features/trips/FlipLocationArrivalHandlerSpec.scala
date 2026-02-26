package com.coride.lambda.features.trips

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
// JsonUtils.require throws RuntimeException for missing required fields

class FlipLocationArrivalHandlerSpec extends AnyFunSuite {
  test("flip-location-arrival requires tripArn and locationName") {
    val event = new APIGatewayProxyRequestEvent()
    event.setBody("{}")
    intercept[RuntimeException] {
      FlipLocationArrivalHandler.handle(event)
    }
  }
}