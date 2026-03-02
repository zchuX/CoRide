package com.coride.lambda.features.friends

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.coride.userdao.User
import com.coride.userfriendsdao.UserFriendsDAO
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.verify
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

class RemoveFriendHandlerSpec extends AnyFunSuite with Matchers with MockitoSugar {

  test("handle returns 200 and calls removeFriendship") {
    val mockDao = mock[UserFriendsDAO]
    val handler = new RemoveFriendHandler(mockDao)
    val event = new APIGatewayProxyRequestEvent()
    val user = User(userArn = "user-1", name = "Alice")
    val resp = handler.handle(user, event, "user-2")
    resp.getStatusCode shouldBe 200
    resp.getBody should include("Friendship removed")
    verify(mockDao).removeFriendship(ArgumentMatchers.eq("user-1"), ArgumentMatchers.eq("user-2"))
  }
}
