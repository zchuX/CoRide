package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.coride.lambda.dao.UserGroupsDAO
import com.coride.tripdao.{Car, GroupUser, Location, TripDAO, TripMetadata, UserGroupRecord}
import com.coride.userdao.{User, UserDAO}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

class UpdateTripMetadataHandlerSpec extends AnyFunSuite with Matchers with MockitoSugar {

  private def createMockEvent(body: String): APIGatewayProxyRequestEvent = {
    val event = new APIGatewayProxyRequestEvent()
    event.setBody(body)
    event
  }

  test("handle should return 404 if trip not found") {
    val mockTripDAO = mock[TripDAO]
    when(mockTripDAO.getTripMetadata("trip-123")).thenReturn(None)

    val handler = new UpdateTripMetadataHandler(mockTripDAO, mock[UserDAO], mock[UserGroupsDAO])
    val event = createMockEvent("""{"startTime": 123456789}""")
    val response = handler.handle("user-123", event, "trip-123")

    response.getStatusCode shouldBe 404
  }

  test("handle should return 403 if user is not driver or in a group") {
    val mockTripDAO = mock[TripDAO]
    val mockUserGroupsDAO = mock[UserGroupsDAO]
    val trip = TripMetadata("trip-123", List(), 0L, None, "Upcoming", None, Some("driver-456"), None, None, None, None, None, None, None, 1)
    when(mockTripDAO.getTripMetadata("trip-123")).thenReturn(Some(trip))
    when(mockUserGroupsDAO.listUserGroupRecordsByTripArn("trip-123")).thenReturn(List())

    val handler = new UpdateTripMetadataHandler(mockTripDAO, mock[UserDAO], mockUserGroupsDAO)
    val event = createMockEvent("""{"startTime": 123456789}""")
    val response = handler.handle("user-123", event, "trip-123")

    response.getStatusCode shouldBe 403
  }

  test("handle should allow valid location reordering") {
    val mockTripDAO = mock[TripDAO]
    val mockUserGroupsDAO = mock[UserGroupsDAO]
    val locations = List(
      Location("A", arrived = true),
      Location("B"),
      Location("C", arrived = true),
      Location("D")
    )
    val trip = TripMetadata("trip-123", locations, 0L, None, "Upcoming", None, Some("driver-456"), None, None, None, None, None, None, None, 1)
    val group = UserGroupRecord("group-1", "trip-123", "Group 1", "A", "D", 0L, List(GroupUser("user-123", "Test", None, true)), 1)
    when(mockTripDAO.getTripMetadata("trip-123")).thenReturn(Some(trip))
    when(mockUserGroupsDAO.listUserGroupRecordsByTripArn("trip-123")).thenReturn(List(group))
    when(mockTripDAO.userTripArn("trip-123", "user-123")).thenReturn("trip-123:user-123")
    when(mockTripDAO.getUserTrip("trip-123:user-123")).thenReturn(None)

    val handler = new UpdateTripMetadataHandler(mockTripDAO, mock[UserDAO], mockUserGroupsDAO)
    val event = createMockEvent("""{"locations": ["A", "C", "B", "D"]}""")
    val response = handler.handle("user-123", event, "trip-123")

    response.getStatusCode shouldBe 200
  }

  test("handle should reject invalid location reordering") {
    val mockTripDAO = mock[TripDAO]
    val mockUserGroupsDAO = mock[UserGroupsDAO]
    val locations = List(
      Location("A", arrived = true), 
      Location("B"), 
      Location("C", arrived = true), 
      Location("D")
    )
    val trip = TripMetadata("trip-123", locations, 0L, None, "Upcoming", None, Some("driver-456"), None, None, None, None, None, None, None, 1)
    val group = UserGroupRecord("group-1", "trip-123", "Group 1", "A", "D", 0L, List(GroupUser("user-123", "Test", None, true)), 1)
    when(mockTripDAO.getTripMetadata("trip-123")).thenReturn(Some(trip))
    when(mockUserGroupsDAO.listUserGroupRecordsByTripArn("trip-123")).thenReturn(List(group))

    val handler = new UpdateTripMetadataHandler(mockTripDAO, mock[UserDAO], mockUserGroupsDAO)
    val event = createMockEvent("""{"locations": ["B", "A", "C", "D"]}""")
    val response = handler.handle("user-123", event, "trip-123")

    response.getStatusCode shouldBe 409
  }

  test("handle should reject mismatched location list") {
    val mockTripDAO = mock[TripDAO]
    val mockUserGroupsDAO = mock[UserGroupsDAO]
    val locations = List(Location("A"), Location("B"))
    val trip = TripMetadata("trip-123", locations, 0L, None, "Upcoming", None, Some("driver-456"), None, None, None, None, None, None, None, 1)
    val group = UserGroupRecord("group-1", "trip-123", "Group 1", "A", "B", 0L, List(GroupUser("user-123", "Test", None, true)), 1)
    when(mockTripDAO.getTripMetadata("trip-123")).thenReturn(Some(trip))
    when(mockUserGroupsDAO.listUserGroupRecordsByTripArn("trip-123")).thenReturn(List(group))

    val handler = new UpdateTripMetadataHandler(mockTripDAO, mock[UserDAO], mockUserGroupsDAO)
    val event = createMockEvent("""{"locations": ["A", "C"]}""")
    val response = handler.handle("user-123", event, "trip-123")

    response.getStatusCode shouldBe 409
  }
}
