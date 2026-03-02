package com.coride.lambda.features.friends

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.coride.userdao.User
import com.coride.userfriendsdao.{UserFriendProfile, UserFriendsDAO}
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.when
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

class GetFriendsProfileHandlerSpec extends AnyFunSuite with Matchers with MockitoSugar {

  test("handle returns 200 with profile when found") {
    val mockDao = mock[UserFriendsDAO]
    when(mockDao.getProfile(ArgumentMatchers.eq("user-1"))).thenReturn(Some(UserFriendProfile("user-1", "Alice")))
    val handler = new GetFriendsProfileHandler(mockDao)
    val event = new APIGatewayProxyRequestEvent()
    val user = User(userArn = "user-1", name = "Alice")
    val resp = handler.handle(user, event)
    resp.getStatusCode shouldBe 200
    resp.getBody should include("user-1")
    resp.getBody should include("Alice")
  }

  test("handle returns 404 when profile not found") {
    val mockDao = mock[UserFriendsDAO]
    when(mockDao.getProfile(ArgumentMatchers.eq("user-1"))).thenReturn(None)
    val handler = new GetFriendsProfileHandler(mockDao)
    val event = new APIGatewayProxyRequestEvent()
    val user = User(userArn = "user-1", name = "Alice")
    val resp = handler.handle(user, event)
    resp.getStatusCode shouldBe 404
    resp.getBody should include("Not Found")
  }
}
