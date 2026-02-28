package com.coride.tripdao

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

object TripDAO {
  def apply(): TripDAO = {
    val regionName: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
    val ddb = DynamoDbClient.builder()
      .region(Region.of(regionName))
      .httpClient(UrlConnectionHttpClient.builder().build())
      .build()
    val tripMetadataTable: String = Option(System.getenv("TRIP_METADATA_TABLE")).getOrElse("")
    val userTripsTable: String = Option(System.getenv("USER_TRIPS_TABLE")).getOrElse("")
    val userGroupsTable: String = Option(System.getenv("USERGROUPS_TABLE")).orElse(Option(System.getenv("USER_GROUPS_TABLE"))).getOrElse("")
    new TripDAO(ddb, tripMetadataTable, userTripsTable, userGroupsTable)
  }
}

/**
 * Facade that delegates to helper classes. Public API unchanged.
 */
class TripDAO(client: DynamoDbClient, tripMetadataTable: String, userTripsTable: String, userGroupsTable: String)
  extends TripDAOUpdateTripStatus
  with TripDAOForUserGroupOps
  with TripDAOForLifecycleOps
  with TripDAOForStatusTransitionOps {

  private val tripMeta = new TripMetadataOps(client, tripMetadataTable)
  private val userGroups = new UserGroupOps(client, userGroupsTable, userTripsTable, tripMetadataTable, this)
  private val userTrips = new UserTripOps(client, userTripsTable, this)
  private val lifecycle = new TripLifecycleOps(client, tripMetadataTable, userTripsTable, userGroupsTable, this)
  private val statusTransition = new TripStatusTransitionOps(client, tripMetadataTable, userTripsTable, this)

  // --- TripMetadataOps
  override def getTripMetadata(tripArn: String): Option[TripMetadata] = tripMeta.getTripMetadata(tripArn)
  def putTripMetadata(t: TripMetadata): Unit = tripMeta.putTripMetadata(t)
  def updateTripMetadata(t: TripMetadata, expectedVersion: Int): Unit = tripMeta.updateTripMetadata(t, expectedVersion)
  override def updateTripStatus(tripArn: String, status: String, expectedVersion: Int, completionTime: Option[Long] = None, currentStop: Option[String] = None): Unit =
    tripMeta.updateTripStatus(tripArn, status, expectedVersion, completionTime, currentStop)
  def setDriverInfo(tripArn: String, expectedVersion: Int, driver: Option[String], driverPhotoUrl: Option[String], driverConfirmed: Option[Boolean], car: Option[Car]): Unit =
    tripMeta.setDriverInfo(tripArn, expectedVersion, driver, driverPhotoUrl, driverConfirmed, car)
  def appendUserGroup(tripArn: String, group: UserGroup, expectedVersion: Int): Unit = tripMeta.appendUserGroup(tripArn, group, expectedVersion)
  def markTripCompleted(tripArn: String, completionTime: Long, users: List[TripUser], expectedVersion: Int): Unit =
    tripMeta.markTripCompleted(tripArn, completionTime, users, expectedVersion)

  // --- TripDAOLocationsHelper (public helpers)
  def userTripArn(tripArn: String, userId: String): String = TripDAOLocationsHelper.userTripArn(tripArn, userId)
  def orderedLocationsFromRecords(trip: TripMetadata, groups: List[UserGroupRecord]): List[Location] =
    TripDAOLocationsHelper.orderedLocationsFromRecords(trip, groups)

  // --- UserGroupOps
  override def listUserGroupRecordsByTripArn(tripArn: String, limit: Int = 100): List[UserGroupRecord] =
    userGroups.listUserGroupRecordsByTripArn(tripArn, limit)
  override def getUserGroup(arn: String): Option[UserGroupRecord] = userGroups.getUserGroup(arn)
  def putUserGroup(g: UserGroupRecord): Unit = userGroups.putUserGroup(g)
  def updateUserGroupInfo(arn: String, expectedVersion: Int, groupName: Option[String] = None, start: Option[String] = None, destination: Option[String] = None, pickupTime: Option[Long] = None): Unit =
    userGroups.updateUserGroupInfo(arn, expectedVersion, groupName, start, destination, pickupTime)
  def updateUserGroup(
    groupArn: String,
    expectedGroupVersion: Int,
    expectedTripVersion: Int,
    groupName: Option[String],
    start: Option[String],
    destination: Option[String],
    pickupTime: Option[Long],
    users: Option[List[GroupUser]],
    numAnonymousUsers: Option[Int]
  ): Unit = userGroups.updateUserGroup(groupArn, expectedGroupVersion, expectedTripVersion, groupName, start, destination, pickupTime, users, numAnonymousUsers)
  def removeUserGroup(groupArn: String, expectedTripVersion: Int, expectedGroupVersion: Int): Unit =
    userGroups.removeUserGroup(groupArn, expectedTripVersion, expectedGroupVersion)
  def joinGroup(groupArn: String, newUser: GroupUser, expectedVersion: Int): Unit = userGroups.joinGroup(groupArn, newUser, expectedVersion)
  def acceptUserInvitation(tripArn: String, groupArn: String, userId: String, expectedGroupVersion: Int): Unit =
    userGroups.acceptUserInvitation(tripArn, groupArn, userId, expectedGroupVersion)

  // --- UserTripOps
  def putUserTrip(t: UserTrip): Unit = userTrips.putUserTrip(t)
  def getUserTrip(arn: String): Option[UserTrip] = userTrips.getUserTrip(arn)
  override def listUsersByTrip(tripArn: String, limit: Int = 100): List[UserTrip] = userTrips.listUsersByTrip(tripArn, limit)
  def queryUserTripsByStatus(userId: String, tripStatus: String, fromDateTime: Option[Long] = None, limit: Int = 50, ascending: Boolean = false): List[UserTrip] =
    userTrips.queryUserTripsByStatus(userId, tripStatus, fromDateTime, limit, ascending)
  def updateUserTripStatus(arn: String, tripStatus: String, expectedVersion: Int, driverConfirmed: Option[Boolean] = None): Unit =
    userTrips.updateUserTripStatus(arn, tripStatus, expectedVersion, driverConfirmed)
  def deleteUserTrip(arn: String): Unit = userTrips.deleteUserTrip(arn)
  def setUserTripStatusesForTrip(tripArn: String, newStatus: String): Unit = userTrips.setUserTripStatusesForTrip(tripArn, newStatus)
  def setCurrentStopAndSyncStatuses(tripArn: String, currentStop: String, expectedTripVersion: Int): Unit =
    userTrips.setCurrentStopAndSyncStatuses(tripArn, currentStop, expectedTripVersion)

  // --- TripLifecycleOps
  def createTrip(base: TripMetadata, groups: List[UserGroupRecord]): Unit = lifecycle.createTrip(base, groups)
  def createTripWithDriver(base: TripMetadata, groups: List[UserGroupRecord], driverTrip: UserTrip): Unit =
    lifecycle.createTripWithDriver(base, groups, driverTrip)
  def addUserGroup(tripArn: String, newGroup: UserGroupRecord, expectedTripVersion: Int): Unit =
    lifecycle.addUserGroup(tripArn, newGroup, expectedTripVersion)
  def deleteTrip(tripArn: String): Unit = lifecycle.deleteTrip(tripArn)

  // --- TripStatusTransitionOps
  def startTripTransaction(updatedTrip: TripMetadata, expectedVersion: Int): Unit = statusTransition.startTripTransaction(updatedTrip, expectedVersion)
  def completeTripTransaction(updatedTrip: TripMetadata, expectedVersion: Int): Unit = statusTransition.completeTripTransaction(updatedTrip, expectedVersion)
}
