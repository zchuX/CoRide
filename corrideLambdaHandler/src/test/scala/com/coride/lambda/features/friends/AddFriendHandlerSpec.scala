package com.coride.lambda.features.friends

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.coride.userdao.{User, UserDAO}
import com.coride.userfriendsdao.UserFriendsDAO
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.{when, verify}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

class AddFriendHandlerSpec extends AnyFunSuite with Matchers with MockitoSugar {

  private def event(body: String): APIGatewayProxyRequestEvent = {
    val e = new APIGatewayProxyRequestEvent()
    e.setBody(body)
    e
  }

  test("handle returns 400 when friendUserArn missing") {
    val mockUserDao = mock[UserDAO]
    val mockFriendsDao = mock[UserFriendsDAO]
    val handler = new AddFriendHandler(mockUserDao, mockFriendsDao)
    val user = User(userArn = "user-1", name = "Alice")
    val resp = handler.handle(user, event("{}"))
    resp.getStatusCode shouldBe 400
    resp.getBody should include("friendUserArn")
  }

  test("handle returns 400 when friendUserArn equals caller") {
    val mockUserDao = mock[UserDAO]
    val mockFriendsDao = mock[UserFriendsDAO]
    val handler = new AddFriendHandler(mockUserDao, mockFriendsDao)
    val user = User(userArn = "user-1", name = "Alice")
    val resp = handler.handle(user, event("""{"friendUserArn":"user-1"}"""))
    resp.getStatusCode shouldBe 400
    resp.getBody should include("must not be the caller")
  }

  test("handle returns 404 when other user not found") {
    val mockUserDao = mock[UserDAO]
    val mockFriendsDao = mock[UserFriendsDAO]
    when(mockUserDao.getUser(ArgumentMatchers.eq("user-2"))).thenReturn(None)
    val handler = new AddFriendHandler(mockUserDao, mockFriendsDao)
    val user = User(userArn = "user-1", name = "Alice")
    val resp = handler.handle(user, event("""{"friendUserArn":"user-2"}"""))
    resp.getStatusCode shouldBe 404
    resp.getBody should include("User not found")
  }

  test("handle returns 200 and skips add when already friends") {
    val mockUserDao = mock[UserDAO]
    val mockFriendsDao = mock[UserFriendsDAO]
    when(mockUserDao.getUser(ArgumentMatchers.eq("user-2"))).thenReturn(Some(User(userArn = "user-2", name = "Bob")))
    when(mockFriendsDao.areFriends(ArgumentMatchers.eq("user-1"), ArgumentMatchers.eq("user-2"))).thenReturn(true)
    val handler = new AddFriendHandler(mockUserDao, mockFriendsDao)
    val user = User(userArn = "user-1", name = "Alice")
    val resp = handler.handle(user, event("""{"friendUserArn":"user-2"}"""))
    resp.getStatusCode shouldBe 200
    resp.getBody should include("Already friends")
  }

  test("handle returns 200 and calls addFriendship when not yet friends") {
    val mockUserDao = mock[UserDAO]
    val mockFriendsDao = mock[UserFriendsDAO]
    when(mockUserDao.getUser(ArgumentMatchers.eq("user-2"))).thenReturn(Some(User(userArn = "user-2", name = "Bob")))
    when(mockFriendsDao.areFriends(ArgumentMatchers.eq("user-1"), ArgumentMatchers.eq("user-2"))).thenReturn(false)
    val handler = new AddFriendHandler(mockUserDao, mockFriendsDao)
    val user = User(userArn = "user-1", name = "Alice")
    val resp = handler.handle(user, event("""{"friendUserArn":"user-2"}"""))
    resp.getStatusCode shouldBe 200
    resp.getBody should include("Friendship added")
    verify(mockFriendsDao).addFriendship(ArgumentMatchers.eq("user-1"), ArgumentMatchers.eq("Alice"), ArgumentMatchers.eq("user-2"), ArgumentMatchers.eq("Bob"))
  }
}
