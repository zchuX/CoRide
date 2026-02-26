package com.coride.lambda.features.auth

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.coride.lambda.router.ValidationException

class RegisterHandlerSpec extends AnyFunSuite {
  test("register fails when name is missing") {
    val event = new APIGatewayProxyRequestEvent()
    event.setBody("""{"username":"user1","password":"secret"}""")
    intercept[ValidationException] {
      RegisterHandler.handle(event)
    }
  }
}