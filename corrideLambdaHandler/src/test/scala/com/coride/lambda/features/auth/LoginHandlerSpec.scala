package com.coride.lambda.features.auth

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.coride.lambda.router.ValidationException

class LoginHandlerSpec extends AnyFunSuite {
  test("login requires username or phone_number") {
    val event = new APIGatewayProxyRequestEvent()
    event.setBody("""{"password":"secret"}""")
    intercept[ValidationException] {
      LoginHandler.handle(event)
    }
  }
}