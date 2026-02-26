package com.coride.lambda.features.trips

import org.scalatest.funsuite.AnyFunSuite
import com.coride.tripdao.{UserGroupRecord, GroupUser}

class GetUserTripsHandlerGroupSpec extends AnyFunSuite {
  test("groupToJson serializes UserGroupRecord with users and version") {
    val users = List(
      GroupUser(userId = "u1", name = "Alice", imageUrl = Some("http://img/a"), accept = true),
      GroupUser(userId = "u2", name = "Bob", imageUrl = None, accept = false)
    )
    val group = UserGroupRecord(
      arn = "arn:trip#group-1",
      tripArn = "arn:trip",
      groupName = "Friends",
      start = "Start A",
      destination = "Dest B",
      pickupTime = 1710000000L,
      users = users,
      version = 3
    )

    val node = GetUserTripsHandler.groupToJson(group)
    assert(node.get("groupArn").asText() == "arn:trip#group-1")
    assert(node.get("tripArn").asText() == "arn:trip")
    assert(node.get("groupName").asText() == "Friends")
    assert(node.get("start").asText() == "Start A")
    assert(node.get("destination").asText() == "Dest B")
    assert(node.get("pickupTime").asLong() == 1710000000L)
    assert(node.get("version").asInt() == 3)

    val arr = node.get("users")
    assert(arr != null && arr.isArray && arr.size() == 2)
    val u1 = arr.get(0)
    val u2 = arr.get(1)
    assert(u1.get("userId").asText() == "u1")
    assert(u1.get("name").asText() == "Alice")
    assert(u1.get("imageUrl").asText() == "http://img/a")
    assert(u1.get("accept").asBoolean())
    assert(u2.get("userId").asText() == "u2")
    assert(u2.get("name").asText() == "Bob")
    assert(u2.get("imageUrl") == null || u2.get("imageUrl").isNull)
    assert(!u2.get("accept").asBoolean())
  }
}