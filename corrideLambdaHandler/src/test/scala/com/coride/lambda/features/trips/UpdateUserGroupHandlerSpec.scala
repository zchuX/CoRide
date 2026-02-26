package com.coride.lambda.features.trips

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

class UpdateUserGroupHandlerSpec extends AnyFunSuite {
  test("update-user-group requires bearer token") {
    val event = new APIGatewayProxyRequestEvent()
    event.setBody("""{"groupArn":"arn:g","version":1}""")
    val resp = UpdateUserGroupHandler.handle(event)
    assert(resp.getStatusCode == 401)
  }
}