package com.coride.lambda.features.friends

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.coride.userdao.User
import com.coride.userfriendsdao.{FriendRecord, UserFriendsDAO}
import org.mockito.ArgumentMatchers.{any, eq => meq}
import org.mockito.Mockito.when
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

class ListFriendsHandlerSpec extends AnyFunSuite with Matchers with MockitoSugar {

  private def user(arn: String = "user-1", name: String = "Alice") =
    User(userArn = arn, name = name)

  test("handle returns 200 with friends array") {
    val mockDao = mock[UserFriendsDAO]
    when(mockDao.listFriends(meq("user-1"), any())).thenReturn(
      List(FriendRecord("user-2", "Bob", 1000L), FriendRecord("user-3", "Carol", 2000L))
    )
    val handler = new ListFriendsHandler(mockDao)
    val event = new APIGatewayProxyRequestEvent()
    val resp = handler.handle(user("user-1", "Alice"), event)
    resp.getStatusCode shouldBe 200
    resp.getBody should include("user-2")
    resp.getBody should include("Bob")
    resp.getBody should include("user-3")
    resp.getBody should include("Carol")
    resp.getBody should include("friends")
  }

  test("handle returns empty friends array when no friends") {
    val mockDao = mock[UserFriendsDAO]
    when(mockDao.listFriends(any(), any())).thenReturn(Nil)
    val handler = new ListFriendsHandler(mockDao)
    val event = new APIGatewayProxyRequestEvent()
    val resp = handler.handle(user(), event)
    resp.getStatusCode shouldBe 200
    resp.getBody should include("[]")
  }
}
