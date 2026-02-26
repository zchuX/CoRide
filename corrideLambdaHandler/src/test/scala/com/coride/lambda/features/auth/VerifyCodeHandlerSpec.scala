package com.coride.lambda.features.auth

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
// Missing required fields use JsonUtils.require which throws RuntimeException

class VerifyCodeHandlerSpec extends AnyFunSuite {
  test("verify-code requires code field") {
    val event = new APIGatewayProxyRequestEvent()
    event.setBody("""{"username":"user1"}""")
    intercept[RuntimeException] {
      VerifyCodeHandler.handle(event)
    }
  }
}