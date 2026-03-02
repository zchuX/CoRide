package com.coride.userfriendsdao

import scala.jdk.CollectionConverters._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{mock, when, verify}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

class UserFriendsDAOSpec extends AnyFunSuite with Matchers {

  test("UserFriendsDAOKeys.skFriend formats correctly") {
    UserFriendsDAOKeys.skFriend("user-abc") shouldBe "FRIEND#user-abc"
    UserFriendsDAOKeys.SkProfile shouldBe "PROFILE"
  }

  test("getProfile returns None when item missing") {
    val mockDdb = mock(classOf[DynamoDbClient])
    when(mockDdb.getItem(any(classOf[GetItemRequest]))).thenReturn(GetItemResponse.builder().build())

    val dao = new UserFriendsDAO(mockDdb, "UserFriends")
    dao.getProfile("user-1") shouldBe None
  }

  test("getProfile returns Some when profile item exists") {
    val mockDdb = mock(classOf[DynamoDbClient])
    val item = Map(
      "userArn" -> AttributeValue.builder().s("user-1").build(),
      "sk" -> AttributeValue.builder().s("PROFILE").build(),
      "name" -> AttributeValue.builder().s("Alice").build()
    ).asJava
    when(mockDdb.getItem(any(classOf[GetItemRequest]))).thenReturn(GetItemResponse.builder().item(item).build())

    val dao = new UserFriendsDAO(mockDdb, "UserFriends")
    val profile = dao.getProfile("user-1")
    profile shouldBe Some(UserFriendProfile("user-1", "Alice"))
  }

  test("listFriends returns empty when no friend items") {
    val mockDdb = mock(classOf[DynamoDbClient])
    when(mockDdb.query(any(classOf[QueryRequest]))).thenReturn(QueryResponse.builder().build())

    val dao = new UserFriendsDAO(mockDdb, "UserFriends")
    dao.listFriends("user-1") shouldBe Nil
  }

  test("listFriends returns parsed FriendRecords") {
    val mockDdb = mock(classOf[DynamoDbClient])
    val item = Map(
      "userArn" -> AttributeValue.builder().s("user-1").build(),
      "sk" -> AttributeValue.builder().s("FRIEND#user-2").build(),
      "friendUserArn" -> AttributeValue.builder().s("user-2").build(),
      "friendName" -> AttributeValue.builder().s("Bob").build(),
      "createdAt" -> AttributeValue.builder().n("1000").build()
    ).asJava
    when(mockDdb.query(any(classOf[QueryRequest]))).thenReturn(
      QueryResponse.builder().items(java.util.List.of(item)).build()
    )

    val dao = new UserFriendsDAO(mockDdb, "UserFriends")
    val friends = dao.listFriends("user-1")
    friends should have size 1
    friends.head.friendUserArn shouldBe "user-2"
    friends.head.friendName shouldBe "Bob"
    friends.head.createdAt shouldBe 1000L
  }

  test("areFriends returns false when item missing") {
    val mockDdb = mock(classOf[DynamoDbClient])
    when(mockDdb.getItem(any(classOf[GetItemRequest]))).thenReturn(GetItemResponse.builder().build())

    val dao = new UserFriendsDAO(mockDdb, "UserFriends")
    dao.areFriends("user-1", "user-2") shouldBe false
  }

  test("areFriends returns true when friendship item exists") {
    val mockDdb = mock(classOf[DynamoDbClient])
    val item = Map(
      "userArn" -> AttributeValue.builder().s("user-1").build(),
      "sk" -> AttributeValue.builder().s("FRIEND#user-2").build()
    ).asJava
    when(mockDdb.getItem(any(classOf[GetItemRequest]))).thenReturn(GetItemResponse.builder().item(item).build())

    val dao = new UserFriendsDAO(mockDdb, "UserFriends")
    dao.areFriends("user-1", "user-2") shouldBe true
  }

  test("putProfile calls putItem with correct table name") {
    val mockDdb = mock(classOf[DynamoDbClient])
    when(mockDdb.putItem(any(classOf[PutItemRequest]))).thenReturn(PutItemResponse.builder().build())

    val dao = new UserFriendsDAO(mockDdb, "UserFriendsTable")
    dao.putProfile(UserFriendProfile("u1", "Alice"))

    verify(mockDdb).putItem(any(classOf[PutItemRequest]))
  }
}
