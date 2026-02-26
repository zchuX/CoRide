package com.coride.userdao

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.PrivateMethodTester
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import scala.jdk.CollectionConverters._

class UserDAOSpec extends AnyFunSuite with PrivateMethodTester {
  test("normalizeEmail and normalizePhone sanitize inputs") {
    val dao = new UserDAO(null.asInstanceOf[DynamoDbClient], "Users", "UserContactIndex")
    val normalizeEmail = PrivateMethod[Option[String]](Symbol("normalizeEmail"))
    val normalizePhone = PrivateMethod[Option[String]](Symbol("normalizePhone"))

    val e1 = (dao invokePrivate normalizeEmail(Some("  Test@Example.Com  ")))
    assert(e1.contains("test@example.com"))
    val e2 = (dao invokePrivate normalizeEmail(None))
    assert(e2.isEmpty)

    val p1 = (dao invokePrivate normalizePhone(Some(" +1 234 567 ")))
    assert(p1.contains("+1234567"))
    val p2 = (dao invokePrivate normalizePhone(None))
    assert(p2.isEmpty)
  }

  test("toItem and fromItem round-trip user") {
    val dao = new UserDAO(null.asInstanceOf[DynamoDbClient], "Users", "UserContactIndex")
    val toItem = PrivateMethod[java.util.Map[String, AttributeValue]](Symbol("toItem"))
    val fromItem = PrivateMethod[User](Symbol("fromItem"))

    val u = User(
      userArn = "arn:aws:iam::123:user/test",
      name = "Alice",
      email = Some("alice@example.com"),
      phone = Some("+19998887777"),
      friendList = List("bob", "carol"),
      incomingInvitations = List("dave"),
      outgoingInvitations = Nil,
      description = Some("Hi"),
      photoUrl = Some("https://example.com/p.jpg"),
      totalPassengerDelivered = 3,
      totalCarpoolJoined = 4,
      createdAt = 1000L,
      updatedAt = 2000L
    )

    val item = (dao invokePrivate toItem(u))
    val u2 = (dao invokePrivate fromItem(item))

    assert(u2.userArn == u.userArn)
    assert(u2.name == u.name)
    assert(u2.email.contains("alice@example.com"))
    assert(u2.phone.contains("+19998887777"))
    assert(u2.friendList == u.friendList)
    assert(u2.incomingInvitations == u.incomingInvitations)
    assert(u2.outgoingInvitations == u.outgoingInvitations)
    assert(u2.description.contains("Hi"))
    assert(u2.photoUrl.contains("https://example.com/p.jpg"))
    assert(u2.totalPassengerDelivered == 3)
    assert(u2.totalCarpoolJoined == 4)
    assert(u2.createdAt == 1000L)
    assert(u2.updatedAt == 2000L)
  }
}