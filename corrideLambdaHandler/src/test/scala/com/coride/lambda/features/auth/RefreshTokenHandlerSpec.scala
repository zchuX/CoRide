package com.coride.lambda.features.auth

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
// JsonUtils.require throws RuntimeException for missing fields

class RefreshTokenHandlerSpec extends AnyFunSuite {
  test("refresh-token requires refreshToken") {
    val event = new APIGatewayProxyRequestEvent()
    event.setBody("""{}""")
    intercept[RuntimeException] {
      RefreshTokenHandler.handle(event)
    }
  }
}