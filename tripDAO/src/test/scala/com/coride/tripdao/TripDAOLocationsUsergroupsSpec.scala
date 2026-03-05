package com.coride.tripdao

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.mockito.Mockito._
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.ArgumentMatchers._
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import scala.jdk.CollectionConverters._

class TripDAOLocationsUsergroupsSpec extends AnyFunSuite with Matchers {

  private def s(v: String): AttributeValue = AttributeValue.builder().s(v).build()
  private def n(v: Long): AttributeValue = AttributeValue.builder().n(v.toString).build()
  private def nInt(v: Int): AttributeValue = AttributeValue.builder().n(v.toString).build()
  private def bool(v: Boolean): AttributeValue = AttributeValue.builder().bool(v).build()
  private def list(vs: List[AttributeValue]): AttributeValue = AttributeValue.builder().l(vs.asJava).build()
  private def map(m: Map[String, AttributeValue]): AttributeValue = AttributeValue.builder().m(m.asJava).build()

  test("updateUserGroup transactionally updates TripMetadata locations and usergroups with correct order") {
    val client = Mockito.mock(classOf[DynamoDbClient])
    when(client.transactWriteItems(any(classOf[TransactWriteItemsRequest]))).thenReturn(TransactWriteItemsResponse.builder().build())

    // Prepare base Trip with driver and empty locations
    when(client.getItem(any(classOf[GetItemRequest]))).thenAnswer { invocation =>
      val req = invocation.getArgument[GetItemRequest](0)
      req.tableName() match {
        case "TripMetaTest" =>
          val item = Map(
            "tripArn" -> s("trip#T"),
            "startTime" -> n(1710000000L),
            "status" -> s("Upcoming"),
            "driver" -> s("driver#1"),
            "locations" -> list(Nil),
            "version" -> nInt(1)
          ).asJava
          GetItemResponse.builder().item(item).build()
        case "GroupsTest" =>
          // Return existing group B when asked by key (update path calls getUserGroup)
          val key = req.key().asScala.toMap
          val gArn = key.get("groupArn").flatMap(a => Option(a.s())).getOrElse("")
          val usersB = list(List(map(Map("userId" -> s("user#2"), "name" -> s("U2"), "accept" -> bool(true)))))
          val item = Map(
            "groupArn" -> s(gArn),
            "tripArn" -> s("trip#T"),
            "groupName" -> s("Group B"),
            "start" -> s("BStart"),
            "destination" -> s("BDest"),
            "pickupTime" -> n(1710000100L),
            "users" -> usersB,
            "version" -> nInt(2)
          ).asJava
          GetItemResponse.builder().item(item).build()
        case _ => GetItemResponse.builder().build()
      }
    }

    // Query all groups for the trip: A (driver) then B (updated later)
    when(client.query(any(classOf[QueryRequest]))).thenAnswer { invocation =>
      val req = invocation.getArgument[QueryRequest](0)
      if (req.indexName() == "gsiTripArn") {
        val usersA = list(List(map(Map("userId" -> s("driver#1"), "name" -> s("Driver"), "accept" -> bool(true)))))
        val usersB = list(List(map(Map("userId" -> s("user#2"), "name" -> s("U2"), "accept" -> bool(true)))))
        val gA = Map(
          "groupArn" -> s("group#A"),
          "tripArn" -> s("trip#T"),
          "groupName" -> s("Group A"),
          "start" -> s("AStart"),
          "destination" -> s("ADest"),
          "pickupTime" -> n(1710000001L),
          "users" -> usersA,
          "version" -> nInt(1)
        ).asJava
        val gB = Map(
          "groupArn" -> s("group#B"),
          "tripArn" -> s("trip#T"),
          "groupName" -> s("Group B"),
          "start" -> s("BStart"),
          "destination" -> s("BDest"),
          "pickupTime" -> n(1710000100L),
          "users" -> usersB,
          "version" -> nInt(2)
        ).asJava
        QueryResponse.builder().items(java.util.Arrays.asList(gA, gB)).build()
      } else {
        QueryResponse.builder().build()
      }
    }

    val dao = new TripDAO(client, "TripMetaTest", "UserTripsTest", "GroupsTest")

    // Update group B (simulate name change, pickup time unchanged for order stability)
    val updatedB = UserGroupRecord(
      arn = "group#B",
      tripArn = "trip#T",
      groupName = "Group B Updated",
      start = "BStart",
      destination = "BDest",
      pickupTime = 1710000100L,
      users = List(GroupUser("user#2", "U2", None, accept = true)),
      version = 2
    )

    dao.updateUserGroup(updatedB.arn, 2, 1, Some(updatedB.groupName), None, None, None, None)

    val captor = ArgumentCaptor.forClass(classOf[TransactWriteItemsRequest])
    verify(client, times(1)).transactWriteItems(captor.capture())
    val req = captor.getValue
    val items = req.transactItems().asScala.toList
    val tripUpdate = items.flatMap(i => Option(i.update())).find(_.tableName() == "TripMetaTest").get
    val ean = tripUpdate.expressionAttributeNames().asScala
    ean("#locations") shouldBe "locations"
    ean("#usergroups") shouldBe "usergroups"

    val eavs = tripUpdate.expressionAttributeValues().asScala
    println(eavs)
    val locsAttr = eavs(":locations")
    val locs = locsAttr.l().asScala.toList.map(_.m().asScala.toMap)
    val namesInOrder = locs.flatMap(_.get("locationName").flatMap(a => Option(a.s())))
    // Order should be driver start (AStart), then BStart, then ADest, then BDest
    namesInOrder shouldBe List("AStart", "BStart", "BDest", "ADest")

    val ugsAttr = eavs(":usergroups")
    val ugs = ugsAttr.l().asScala.toList.map(_.m().asScala.toMap)
    val groupNames = ugs.flatMap(_.get("groupName").flatMap(a => Option(a.s())))
    groupNames.toSet shouldBe Set("Group A", "Group B Updated")
  }
}