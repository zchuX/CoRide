package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class SimpleCreateTripHandlerSpec extends AnyFunSpec with Matchers {

  describe("CreateTripHandler") {
    it("should return 401 Unauthorized when no token is provided") {
      val handler = new CreateTripHandler()
      val request = new APIGatewayProxyRequestEvent()
      val response = handler.handle(request)
      response.getStatusCode should be(401)
    }
  }
}
