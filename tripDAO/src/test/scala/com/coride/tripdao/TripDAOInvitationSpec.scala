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

class TripDAOInvitationSpec extends AnyFunSuite with Matchers {

  private def s(v: String): AttributeValue = AttributeValue.builder().s(v).build()
  private def n(v: Long): AttributeValue = AttributeValue.builder().n(v.toString).build()
  private def nInt(v: Int): AttributeValue = AttributeValue.builder().n(v.toString).build()
  private def bool(v: Boolean): AttributeValue = AttributeValue.builder().bool(v).build()
  private def list(vs: List[AttributeValue]): AttributeValue = AttributeValue.builder().l(vs.asJava).build()
  private def map(m: Map[String, AttributeValue]): AttributeValue = AttributeValue.builder().m(m.asJava).build()

  test("createTrip inserts user trips with Invitation status") {
    val client = Mockito.mock(classOf[DynamoDbClient])
    when(client.transactWriteItems(any(classOf[TransactWriteItemsRequest]))).thenReturn(TransactWriteItemsResponse.builder().build())

    val dao = new TripDAO(client, "TripMetaTest", "UserTripsTest", "GroupsTest")

    val base = TripMetadata(
      tripArn = "trip#1",
      locations = List(Location("Start")),
      startTime = 1710000000L,
      completionTime = None,
      status = "Upcoming",
      currentStop = None,
      driver = None,
      driverPhotoUrl = None,
      driverConfirmed = Some(false),
      car = None,
      usergroups = None,
      users = None,
      notes = None,
      version = 1
    )
    val group = UserGroupRecord(
      arn = "group#1",
      tripArn = base.tripArn,
      groupName = "G",
      start = "Start",
      destination = "Dest",
      pickupTime = 1710000001L,
      users = List(GroupUser("user#1", "User 1", accept = false)),
      version = 1
    )

    dao.createTrip(base, List(group))

    val captor = ArgumentCaptor.forClass(classOf[TransactWriteItemsRequest])
    verify(client, times(1)).transactWriteItems(captor.capture())
    val req = captor.getValue
    val items = req.transactItems().asScala.toList
    val userTripPuts = items.flatMap(i => Option(i.put())).filter(_.tableName() == "UserTripsTest")
    userTripPuts should have size 1
    val putItem = userTripPuts.head.item().asScala.toMap
    putItem("tripStatus").s() shouldBe "Invitation"
    putItem("userStatusKey").s() shouldBe "user#1-uncompleted"
  }

  test("acceptUserInvitation moves user-trip to actual trip status") {
    val client = Mockito.mock(classOf[DynamoDbClient])
    when(client.transactWriteItems(any(classOf[TransactWriteItemsRequest]))).thenReturn(TransactWriteItemsResponse.builder().build())

    // getItem should return TripMetadata for trip table and UserGroupRecord for group table
    when(client.getItem(any(classOf[GetItemRequest]))).thenAnswer(invocation => {
      val req = invocation.getArgument[GetItemRequest](0)
      req.tableName() match {
        case "TripMetaTest" =>
          val item = Map(
            "tripArn" -> s("trip#1"),
            "locations" -> list(List(map(Map("locationName" -> s("Start"))))),
            "startTime" -> n(1710000000L),
            "status" -> s("Upcoming"),
            "version" -> nInt(2)
          ).asJava
          GetItemResponse.builder().item(item).build()
        case "GroupsTest" =>
          val users = List(map(Map("userId" -> s("user#1"), "name" -> s("User 1"), "accept" -> bool(false))))
          val item = Map(
            "groupArn" -> s("group#1"),
            "tripArn" -> s("trip#1"),
            "groupName" -> s("G"),
            "start" -> s("Start"),
            "destination" -> s("Dest"),
            "pickupTime" -> n(1710000001L),
            "users" -> list(users),
            "version" -> nInt(1)
          ).asJava
          GetItemResponse.builder().item(item).build()
        case "UserTripsTest" =>
          val item = Map(
            "arn" -> s("trip#1#group#1#user#1"),
            "tripArn" -> s("trip#1"),
            "userStatusKey" -> s("user#1-Invitation"),
            "tripDateTime" -> n(1710000001L),
            "tripStatus" -> s("Invitation"),
            "start" -> s("Start"),
            "destination" -> s("Dest"),
            "departureDateTime" -> n(1710000001L),
            "isDriver" -> bool(false),
            "driverConfirmed" -> bool(false),
            "version" -> nInt(1)
          ).asJava
          GetItemResponse.builder().item(item).build()
        case _ => GetItemResponse.builder().build()
      }
    })

    val dao = new TripDAO(client, "TripMetaTest", "UserTripsTest", "GroupsTest")
    dao.acceptUserInvitation("trip#1", "group#1", "user#1", expectedGroupVersion = 1)

    val captor = ArgumentCaptor.forClass(classOf[TransactWriteItemsRequest])
    verify(client, times(1)).transactWriteItems(captor.capture())
    val req = captor.getValue
    val items = req.transactItems().asScala.toList
    val userUpdate = items.flatMap(i => Option(i.update())).find(_.tableName() == "UserTripsTest").get
    val eavs = userUpdate.expressionAttributeValues().asScala.toMap
    eavs(":tripStatus").s() shouldBe "Upcoming" // actual trip status at acceptance
  }
}