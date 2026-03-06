package com.coride.lambda.features.trips

import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.coride.lambda.util.JwtUtils
import com.coride.tripdao.TripDAO

class CreateUserGroupHandlerSpec extends AnyFunSuite with MockitoSugar {
  test("create-user-group requires bearer token") {
    val event = new APIGatewayProxyRequestEvent()
    val resp = CreateUserGroupHandler.handle(event)
    assert(resp.getStatusCode == 401)
  }

  test("create-user-group rejects empty user group") {
    val mockDao = mock[TripDAO]
    val mockJwt = mock[JwtUtils]
    when(mockJwt.verifyIdToken(any())).thenReturn(Some(JwtUtils.VerifiedClaims("user-1", None, None, None)))
    val event = new APIGatewayProxyRequestEvent()
    event.setBody("""{"tripArn":"trip-1","groupName":"Riders","start":"A","destination":"B","pickupTime":1234567890000,"users":[]}""")
    event.setHeaders(new java.util.HashMap[String, String]())
    event.getHeaders.put("Authorization", "Bearer any")
    val resp = CreateUserGroupHandler.handle(event, mockDao, mockJwt)
    assert(resp.getStatusCode == 400)
    assert(resp.getBody != null && resp.getBody.contains("at least one user"))
  }
}