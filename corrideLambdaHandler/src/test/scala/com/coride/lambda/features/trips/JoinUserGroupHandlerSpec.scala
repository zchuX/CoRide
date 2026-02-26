package com.coride.lambda.features.trips

import org.scalatest.funsuite.AnyFunSuite
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

class JoinUserGroupHandlerSpec extends AnyFunSuite {
  test("join-user-group requires bearer token") {
    val event = new APIGatewayProxyRequestEvent()
    event.setBody("""{"tripArn":"arn:t","groupArn":"arn:g","version":1}""")
    val resp = JoinUserGroupHandler.handle(event)
    assert(resp.getStatusCode == 401)
  }
}