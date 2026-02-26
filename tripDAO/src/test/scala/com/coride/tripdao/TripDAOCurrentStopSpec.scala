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

class TripDAOCurrentStopSpec extends AnyFunSuite with Matchers {

  private def s(v: String): AttributeValue = AttributeValue.builder().s(v).build()
  private def n(v: Long): AttributeValue = AttributeValue.builder().n(v.toString).build()
  private def nInt(v: Int): AttributeValue = AttributeValue.builder().n(v.toString).build()
  private def bool(v: Boolean): AttributeValue = AttributeValue.builder().bool(v).build()
  private def list(vs: List[AttributeValue]): AttributeValue = AttributeValue.builder().l(vs.asJava).build()
  private def map(m: Map[String, AttributeValue]): AttributeValue = AttributeValue.builder().m(m.asJava).build()

  test("setCurrentStopAndSyncStatuses moves trip to InProgress and updates user trips from Upcoming") {
    val client = Mockito.mock(classOf[DynamoDbClient])
    when(client.updateItem(any(classOf[UpdateItemRequest]))).thenReturn(UpdateItemResponse.builder().build())

    // Sequence of getItem calls: trip, group, userTrip1, userTrip2
    when(client.getItem(any(classOf[GetItemRequest]))).thenAnswer(invocation => {
      val req = invocation.getArgument[GetItemRequest](0)
      val table = req.tableName()
      if (table == "TripMetaTest") {
        val usergroups = list(List(map(Map(
          "groupId" -> s("group#1"),
          "groupName" -> s("G"),
          "groupSize" -> nInt(2)
        ))))
        val locations = list(List(map(Map("locationName" -> s("Start")))))
        val item = Map(
          "tripArn" -> s("trip#1"),
          "locations" -> locations,
          "startTime" -> n(1710000000L),
          "status" -> s("Upcoming"),
          "usergroups" -> usergroups,
          "version" -> nInt(3)
        ).asJava
        GetItemResponse.builder().item(item).build()
      } else if (table == "GroupsTest") {
        val users = list(List(
          map(Map("userId" -> s("user#1"), "name" -> s("U1"), "accept" -> bool(true))),
          map(Map("userId" -> s("user#2"), "name" -> s("U2"), "accept" -> bool(true)))
        ))
        val item = Map(
          "groupArn" -> s("group#1"),
          "tripArn" -> s("trip#1"),
          "groupName" -> s("G"),
          "start" -> s("Start"),
          "destination" -> s("Dest"),
          "pickupTime" -> n(1710000001L),
          "users" -> users,
          "version" -> nInt(1)
        ).asJava
        GetItemResponse.builder().item(item).build()
      } else if (table == "UserTripsTest") {
        // Return a user trip marked Upcoming
        val arnAttr = req.key().get("arn")
        val arnStr = Option(arnAttr).flatMap(a => Option(a.s())).getOrElse("")
        val item = Map(
          "arn" -> s(arnStr),
          "userStatusKey" -> s("user-any-Upcoming"),
          "tripDateTime" -> n(1710000001L),
          "tripStatus" -> s("Upcoming"),
          "start" -> s("Start"),
          "destination" -> s("Dest"),
          "departureDateTime" -> n(1710000001L),
          "isDriver" -> bool(false),
          "driverConfirmed" -> bool(false),
          "version" -> nInt(2)
        ).asJava
        GetItemResponse.builder().item(item).build()
      } else GetItemResponse.builder().build()
    })

    when(client.query(any(classOf[QueryRequest]))).thenAnswer { invocation =>
      val req = invocation.getArgument[QueryRequest](0)
      if (req.tableName() == "UserTripsTest" && req.indexName() == "gsiTripArn") {
        val userTrip1 = Map(
          "arn" -> s("trip#1:user#1"),
          "tripArn" -> s("trip#1"),
          "userStatusKey" -> s("user#1-Upcoming"),
          "tripDateTime" -> n(1710000001L),
          "tripStatus" -> s("Upcoming"),
          "start" -> s("Start"),
          "destination" -> s("Dest"),
          "departureDateTime" -> n(1710000001L),
          "isDriver" -> bool(false),
          "driverConfirmed" -> bool(false),
          "version" -> nInt(2)
        ).asJava
        val userTrip2 = Map(
          "arn" -> s("trip#1:user#2"),
          "tripArn" -> s("trip#1"),
          "userStatusKey" -> s("user#2-Upcoming"),
          "tripDateTime" -> n(1710000001L),
          "tripStatus" -> s("Upcoming"),
          "start" -> s("Start"),
          "destination" -> s("Dest"),
          "departureDateTime" -> n(1710000001L),
          "isDriver" -> bool(false),
          "driverConfirmed" -> bool(false),
          "version" -> nInt(2)
        ).asJava
        QueryResponse.builder().items(userTrip1, userTrip2).build()
      } else {
        QueryResponse.builder().build()
      }
    }

    val dao = new TripDAO(client, "TripMetaTest", "UserTripsTest", "GroupsTest")
    dao.setCurrentStopAndSyncStatuses("trip#1", "Start", expectedTripVersion = 3)

    // Verify trip status update to InProgress
    val updateCaptor = ArgumentCaptor.forClass(classOf[UpdateItemRequest])
    verify(client, atLeastOnce()).updateItem(updateCaptor.capture())
    val updates = updateCaptor.getAllValues.asScala.toList
    val tripUpdates = updates.filter(_.tableName() == "TripMetaTest")
    tripUpdates.exists(u => Option(u.expressionAttributeValues().get(":status")).exists(_.s() == "InProgress")) shouldBe true

    // Verify userTrip updates to InProgress for both users
    val userUpdates = updates.filter(_.tableName() == "UserTripsTest")
    userUpdates should have size 2
    userUpdates.foreach { u =>
      Option(u.expressionAttributeValues().get(":ts")).map(_.s()) shouldBe Some("InProgress")
    }
  }
}