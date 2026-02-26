package com.coride.lambda.features.trips

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

class BecomeDriverHandlerSpec extends AnyFunSuite {
  test("become-driver requires bearer token") {
    val event = new APIGatewayProxyRequestEvent()
    event.setBody("""{"tripArn":"arn:trip#1","version":1}""")
    val resp = BecomeDriverHandler.handle(event)
    assert(resp.getStatusCode == 401)
  }
}