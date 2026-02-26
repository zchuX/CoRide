package com.coride.lambda.features.auth

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.coride.lambda.router.ValidationException

class ResetPasswordHandlerSpec extends AnyFunSuite {
  test("reset-password requires email or phone_number when identifier missing") {
    val event = new APIGatewayProxyRequestEvent()
    event.setBody("""{"newPassword":"abc","code":"123456"}""")
    intercept[ValidationException] {
      ResetPasswordHandler.handle(event)
    }
  }
}