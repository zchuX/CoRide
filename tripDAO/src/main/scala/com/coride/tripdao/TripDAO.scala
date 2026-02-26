package com.coride.tripdao

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import scala.jdk.CollectionConverters._
import org.slf4j.LoggerFactory

object Attrs {
  def s(v: String): AttributeValue = AttributeValue.builder().s(v).build()
  def n(v: Long): AttributeValue = AttributeValue.builder().n(v.toString).build()
  def nInt(v: Int): AttributeValue = AttributeValue.builder().n(v.toString).build()
  def bool(v: Boolean): AttributeValue = AttributeValue.builder().bool(v).build()
  def list(vs: List[AttributeValue]): AttributeValue = AttributeValue.builder().l(vs.asJava).build()
  def map(m: Map[String, AttributeValue]): AttributeValue = AttributeValue.builder().m(m.asJava).build()
}

/** Helpers to convert models to DynamoDB AttributeValues */
object ModelCodec {
  import Attrs._

  def locationToAttr(loc: Location): AttributeValue = map(Map(
    "locationName" -> s(loc.locationName),
    "pickupGroups" -> list(loc.pickupGroups.map(s)),
    "dropOffGroups" -> list(loc.dropOffGroups.map(s)),
    "arrived" -> bool(loc.arrived),
    "arrivedTime" -> (loc.arrivedTime.map(n).getOrElse(AttributeValue.builder().nul(true).build()))
  ))

  def carToAttr(car: Car): AttributeValue = map(Map(
    "plateNumber" -> car.plateNumber.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "color" -> car.color.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "model" -> car.model.map(s).getOrElse(AttributeValue.builder().nul(true).build())
  ))

  def userGroupToAttr(g: UserGroup): AttributeValue = map(Map(
    "groupId" -> s(g.groupId),
    "groupName" -> s(g.groupName),
    "groupSize" -> nInt(g.groupSize),
    "imageUrl" -> g.imageUrl.map(s).getOrElse(AttributeValue.builder().nul(true).build())
  ))

  def tripUserToAttr(u: TripUser): AttributeValue = map(Map(
    "userId" -> u.userId.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "name" -> s(u.name),
    "imageUrl" -> u.imageUrl.map(s).getOrElse(AttributeValue.builder().nul(true).build())
  ))

  def locationsToAttr(locs: List[Location]): AttributeValue = list(locs.map(locationToAttr))

  def usergroupsToAttr(ugs: List[UserGroup]): AttributeValue = list(ugs.map(userGroupToAttr))

  def tripMetadataToItem(t: TripMetadata): java.util.Map[String, AttributeValue] = Map(
    "tripArn" -> s(t.tripArn),
    "locations" -> locationsToAttr(t.locations),
    "startTime" -> n(t.startTime),
    "completionTime" -> t.completionTime.map(n).getOrElse(AttributeValue.builder().nul(true).build()),
    "status" -> s(t.status),
    "currentStop" -> t.currentStop.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "driver" -> t.driver.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "driverName" -> t.driverName.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "driverPhotoUrl" -> t.driverPhotoUrl.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "driverConfirmed" -> t.driverConfirmed.map(bool).getOrElse(AttributeValue.builder().nul(true).build()),
    "car" -> t.car.map(carToAttr).getOrElse(AttributeValue.builder().nul(true).build()),
    "usergroups" -> t.usergroups.map(usergroupsToAttr).getOrElse(AttributeValue.builder().nul(true).build()),
    "users" -> t.users.map(us => list(us.map(tripUserToAttr))).getOrElse(AttributeValue.builder().nul(true).build()),
    "notes" -> t.notes.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
    "version" -> nInt(t.version)
  ).asJava

  def userTripToItem(t: UserTrip): java.util.Map[String, AttributeValue] = Map(
    "arn" -> s(t.arn),
    "tripArn" -> s(t.tripArn),
    "userStatusKey" -> s(t.userStatusKey),
    "tripDateTime" -> n(t.tripDateTime),
    "tripStatus" -> s(t.tripStatus),
    "start" -> s(t.start),
    "destination" -> s(t.destination),
    "departureDateTime" -> n(t.departureDateTime),
    "isDriver" -> bool(t.isDriver),
    "driverConfirmed" -> bool(t.driverConfirmed),
    "version" -> nInt(t.version)
  ).asJava

  // UserGroups table codecs
  def groupUserToAttr(u: GroupUser): AttributeValue = map(Map(
    "userId" -> s(u.userId),
    "name" -> s(u.name),
    "accept" -> bool(u.accept)
  ))

  def userGroupRecordToItem(g: UserGroupRecord): java.util.Map[String, AttributeValue] = Map(
    // Table key is groupArn, model exposes "arn"
    "groupArn" -> s(g.arn),
    "tripArn" -> s(g.tripArn),
    "groupName" -> s(g.groupName),
    "start" -> s(g.start),
    "destination" -> s(g.destination),
    "pickupTime" -> n(g.pickupTime),
    "users" -> list(g.users.map(groupUserToAttr)),
    "numAnonymousUsers" -> nInt(g.numAnonymousUsers),
    "version" -> nInt(g.version)
  ).asJava
}

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

class TripDAO(client: DynamoDbClient, tripMetadataTable: String, userTripsTable: String, userGroupsTable: String) {
  import ModelCodec._
  import Attrs._
  private val logger = LoggerFactory.getLogger(classOf[TripDAO])

  private def deriveEffectiveStatus(trip: TripMetadata): String = trip.status

  def listUserGroupRecordsByTripArn(tripArn: String, limit: Int = 100): List[UserGroupRecord] = {
    val names = Map("#tripArn" -> "tripArn").asJava
    val values = Map(":ta" -> s(tripArn)).asJava
    val req = QueryRequest.builder()
      .tableName(userGroupsTable)
      .indexName("gsiTripArn") // Assuming a GSI on tripArn
      .keyConditionExpression("#tripArn = :ta")
      .expressionAttributeNames(names)
      .expressionAttributeValues(values)
      .limit(limit)
      .build()
    val res = client.query(req)
    Option(res.items()).map(_.asScala.toList).getOrElse(Nil).flatMap(it => parseUserGroupRecord(it.asScala.toMap))
  }

  // -------- Helpers to generate TripMetadata.usergroups and locations --------
  private def summarizeUserGroupsFromRecords(records: List[UserGroupRecord]): List[UserGroup] = {
    records.map { r =>
      val groupSize = r.users.size + r.numAnonymousUsers
      UserGroup(groupId = r.arn, groupName = r.groupName, groupSize = groupSize, numAnonymousUser = r.numAnonymousUsers, imageUrl = None)
    }
  }

  def orderedLocationsFromRecords(trip: TripMetadata, records: List[UserGroupRecord]): List[Location] = {
    if (records.isEmpty) {
      return trip.locations
    }

    // Find trip-level start and destination
    val groupLocationNames = (records.map(_.start) ++ records.map(_.destination)).toSet
    val tripStart = trip.locations.find(l => !groupLocationNames.contains(l.locationName))
    val tripDest = trip.locations.find(l => !groupLocationNames.contains(l.locationName) && Some(l) != tripStart)

    // Original logic for ordering group locations
    val driverIdOpt = trip.driver
    val driverGroupOpt = driverIdOpt.flatMap { did =>
      records.find(_.users.exists(u => u.userId == did))
    }
    val driverStart = driverGroupOpt.map(_.start).getOrElse(records.minBy(_.pickupTime).start)
    val driverDest = driverGroupOpt.map(_.destination).getOrElse(records.maxBy(_.pickupTime).destination)
    val pickupsOrdered = records.sortBy(_.pickupTime).map(_.start).foldLeft(List.empty[String]) { (acc, s) => if (acc.contains(s)) acc else acc :+ s }
    val dropoffsOrdered = records.sortBy(_.pickupTime).map(_.destination).distinct
    val core = (driverStart +: pickupsOrdered.filterNot(_ == driverStart)) ++ dropoffsOrdered.filterNot(_ == driverStart)
    val groupOrderedNames = core.filterNot(_ == driverDest) :+ driverDest

    // Combine
    val orderedLocationNames = (tripStart.map(_.locationName).toList ++ groupOrderedNames ++ tripDest.map(_.locationName).toList).distinct

    // Build final list of Location objects
    val allLocations = (trip.locations ++ records.flatMap { ug =>
      List(
        Location(locationName = ug.start, pickupGroups = List(ug.arn), dropOffGroups = Nil, arrived = false, arrivedTime = None),
        Location(locationName = ug.destination, pickupGroups = Nil, dropOffGroups = List(ug.arn), arrived = false, arrivedTime = None)
      )
    }).groupBy(_.locationName).map { case (name, locs) =>
      val reducedLoc = locs.reduce { (a, b) =>
        a.copy(
          pickupGroups = (a.pickupGroups ++ b.pickupGroups).distinct,
          dropOffGroups = (a.dropOffGroups ++ b.dropOffGroups).distinct
        )
      }
      reducedLoc.locationName -> reducedLoc
    }.toMap
    
    orderedLocationNames.flatMap(allLocations.get)
  }

  private def mergeSingleGroupIntoLocations(base: List[Location], start: String, destination: String, gid: String): List[Location] = {
    val byName = scala.collection.mutable.Map.from(base.map(l => l.locationName -> l))
    val sLoc = byName.getOrElse(start, Location(locationName = start))
    byName.update(start, sLoc.copy(pickupGroups = (sLoc.pickupGroups :+ gid).distinct))
    val dLoc = byName.getOrElse(destination, Location(locationName = destination))
    byName.update(destination, dLoc.copy(dropOffGroups = (dLoc.dropOffGroups :+ gid).distinct))
    byName.values.toList.sortBy(_.locationName)
  }

  private def removeSingleGroupFromLocations(base: List[Location], start: String, destination: String, gid: String): List[Location] = {
    val byName = scala.collection.mutable.Map.from(base.map(l => l.locationName -> l))
    byName.get(start).foreach { sLoc =>
      byName.update(start, sLoc.copy(pickupGroups = sLoc.pickupGroups.filterNot(_ == gid)))
    }
    byName.get(destination).foreach { dLoc =>
      byName.update(destination, dLoc.copy(dropOffGroups = dLoc.dropOffGroups.filterNot(_ == gid)))
    }
    // Drop empty locations with no pickup/drop groups
    byName.values.filter(l => l.pickupGroups.nonEmpty || l.dropOffGroups.nonEmpty).toList.sortBy(_.locationName)
  }

  // TripMetadata CRUD
  def putTripMetadata(t: TripMetadata): Unit = {
    val req = PutItemRequest.builder()
      .tableName(tripMetadataTable)
      .item(tripMetadataToItem(t))
      .build()
    client.putItem(req)
  }

  // -------- Transactional operations --------
  // Compose a deterministic UserTrip ARN from trip, group, and user
  def userTripArn(tripArn: String, userId: String): String = s"$tripArn:$userId"

  private def buildUserTripFromRecord(trip: TripMetadata, group: UserGroupRecord, user: GroupUser): UserTrip = {
    val arn = userTripArn(trip.tripArn, user.userId)
    // Initialize user-trip status based on the group's acceptance flag.
    // Accepted users start at the trip's effective status; others start as Invitation.
    val initialStatus = if (user.accept) deriveEffectiveStatus(trip) else "Invitation"
    val usk = s"${user.userId}-${if (initialStatus == "Completed") "completed" else "uncompleted"}"
    UserTrip(
      arn = arn,
      tripArn = trip.tripArn,
      userStatusKey = usk,
      tripDateTime = group.pickupTime,
      tripStatus = initialStatus,
      start = group.start,
      destination = group.destination,
      departureDateTime = group.pickupTime,
      isDriver = trip.driver.exists(_ == user.userId),
      driverConfirmed = trip.driverConfirmed.getOrElse(false),
      version = 1
    )
  }

  def createTrip(base: TripMetadata, groups: List[UserGroupRecord]): Unit = {
    // Compute derived usergroups and ordered locations
    val summaries = summarizeUserGroupsFromRecords(groups)
    val locs = orderedLocationsFromRecords(base, groups)
    val trip = base.copy(usergroups = Some(summaries), locations = locs)

    val writes = new java.util.ArrayList[TransactWriteItem]()
    // Put TripMetadata with conditional insert to avoid overwrite
    writes.add(
      TransactWriteItem.builder().put(
        Put.builder()
          .tableName(tripMetadataTable)
          .item(tripMetadataToItem(trip))
          .conditionExpression("attribute_not_exists(tripArn)")
          .build()
      ).build()
    )
    // Put UserGroupRecords
    groups.foreach { g =>
      writes.add(
        TransactWriteItem.builder().put(
          Put.builder()
            .tableName(userGroupsTable)
            .item(userGroupRecordToItem(g))
            .conditionExpression("attribute_not_exists(groupArn)")
            .build()
        ).build()
      )
      // Put UserTrip records for group users
      g.users.foreach { u =>
        val ut = buildUserTripFromRecord(trip, g, u)
        writes.add(
          TransactWriteItem.builder().put(
            Put.builder()
              .tableName(userTripsTable)
              .item(userTripToItem(ut))
              .conditionExpression("attribute_not_exists(arn)")
              .build()
          ).build()
        )
        logger.info(s"Inserted UserTrip ${ut.arn} with status=Invitation")
      }
    }
    val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
    client.transactWriteItems(req)
  }

  def createTripWithDriver(base: TripMetadata, groups: List[UserGroupRecord], driverTrip: UserTrip): Unit = {
    val summaries = summarizeUserGroupsFromRecords(groups)
    val locs = orderedLocationsFromRecords(base, groups)
    val trip = base.copy(usergroups = Some(summaries), locations = locs)

    val writes = new java.util.ArrayList[TransactWriteItem]()
    writes.add(
      TransactWriteItem.builder().put(
        Put.builder()
          .tableName(tripMetadataTable)
          .item(tripMetadataToItem(trip))
          .conditionExpression("attribute_not_exists(tripArn)")
          .build()
      ).build()
    )

    writes.add(
      TransactWriteItem.builder().put(
        Put.builder()
          .tableName(userTripsTable)
          .item(userTripToItem(driverTrip))
          .conditionExpression("attribute_not_exists(arn)")
          .build()
      ).build()
    )

    groups.foreach { g =>
      writes.add(
        TransactWriteItem.builder().put(
          Put.builder()
            .tableName(userGroupsTable)
            .item(userGroupRecordToItem(g))
            .conditionExpression("attribute_not_exists(groupArn)")
            .build()
        ).build()
      )
      g.users.foreach { u =>
        val ut = buildUserTripFromRecord(trip, g, u)
        writes.add(
          TransactWriteItem.builder().put(
            Put.builder()
              .tableName(userTripsTable)
              .item(userTripToItem(ut))
              .conditionExpression("attribute_not_exists(arn)")
              .build()
          ).build()
        )
        logger.info(s"Inserted UserTrip ${ut.arn} with status=Invitation")
      }
    }
    val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
    client.transactWriteItems(req)
  }

  def addUserGroup(tripArn: String, newGroup: UserGroupRecord, expectedTripVersion: Int): Unit = {
    // Write group and user trips; recompute aggregates and bump trip version
    getTripMetadata(tripArn).foreach { current =>
      val existing = listUserGroupRecordsByTripArn(tripArn)
      val allGroups = existing :+ newGroup
      val summaries = summarizeUserGroupsFromRecords(allGroups)
      val locs = orderedLocationsFromRecords(current, allGroups)

      val names = Map("#version" -> "version", "#usergroups" -> "usergroups", "#locations" -> "locations").asJava
      val values = Map(
        ":ugs" -> usergroupsToAttr(summaries),
        ":locs" -> locationsToAttr(locs),
        ":zero" -> nInt(0), ":inc" -> nInt(1), ":expectedTrip" -> nInt(expectedTripVersion)
      ).asJava
      val update = Update.builder()
        .tableName(tripMetadataTable)
        .key(Map("tripArn" -> s(tripArn)).asJava)
        .expressionAttributeNames(names)
        .expressionAttributeValues(values)
        .updateExpression("SET #usergroups = :ugs, #locations = :locs, #version = if_not_exists(#version, :zero) + :inc")
        .conditionExpression("#version = :expectedTrip")
        .build()

      val writes = new java.util.ArrayList[TransactWriteItem]()
      writes.add(TransactWriteItem.builder().update(update).build())
      writes.add(TransactWriteItem.builder().put(Put.builder().tableName(userGroupsTable).item(userGroupRecordToItem(newGroup)).build()).build())
      newGroup.users.foreach { u =>
        val ut = buildUserTripFromRecord(current, newGroup, u)
        writes.add(TransactWriteItem.builder().put(Put.builder().tableName(userTripsTable).item(userTripToItem(ut)).build()).build())
        logger.info(s"Inserted UserTrip ${ut.arn} for new group with status=Invitation")
      }
      val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
      client.transactWriteItems(req)
    }
  }

  def deleteTrip(tripArn: String): Unit = {
    val groups = listUserGroupRecordsByTripArn(tripArn)
    val writes = new java.util.ArrayList[TransactWriteItem]()

    // Delete TripMetadata
    writes.add(
      TransactWriteItem.builder().delete(
        Delete.builder()
          .tableName(tripMetadataTable)
          .key(Map("tripArn" -> s(tripArn)).asJava)
          .build()
      ).build()
    )

    // Delete UserGroupRecords and UserTrip records
    groups.foreach { g =>
      writes.add(
        TransactWriteItem.builder().delete(
          Delete.builder()
            .tableName(userGroupsTable)
            .key(Map("groupArn" -> s(g.arn)).asJava)
            .build()
        ).build()
      )
      g.users.foreach { u =>
        val utArn = userTripArn(tripArn, u.userId)
        writes.add(
          TransactWriteItem.builder().delete(
            Delete.builder()
              .tableName(userTripsTable)
              .key(Map("arn" -> s(utArn)).asJava)
              .build()
          ).build()
        )
      }
    }

    if (writes.size() > 0) {
      val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
      client.transactWriteItems(req)
    }
  }

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
  ): Unit = {
    val tripOpt = getTripMetadata(groupArn.split("#").head)
    val groupOpt = getUserGroup(groupArn)

    (tripOpt, groupOpt) match {
      case (Some(trip), Some(group)) =>
        val updatedGroup = group.copy(
          groupName = groupName.getOrElse(group.groupName),
          start = start.getOrElse(group.start),
          destination = destination.getOrElse(group.destination),
          pickupTime = pickupTime.getOrElse(group.pickupTime),
          users = users.getOrElse(group.users),
          numAnonymousUsers = numAnonymousUsers.getOrElse(group.numAnonymousUsers),
          version = group.version + 1
        )

        val writes = new java.util.ArrayList[TransactWriteItem]()

        // 1. Update UserGroupRecord
        val groupUpdate = Update.builder()
          .tableName(userGroupsTable)
          .key(Map("groupArn" -> s(group.arn)).asJava)
          .updateExpression("SET #groupName = :groupName, #start = :start, #destination = :destination, #pickupTime = :pickupTime, #users = :users, #numAnonymousUsers = :numAnonymousUsers, #version = :newVersion")
          .conditionExpression("#version = :expectedVersion")
          .expressionAttributeNames(Map(
            "#groupName" -> "groupName",
            "#start" -> "start",
            "#destination" -> "destination",
            "#pickupTime" -> "pickupTime",
            "#users" -> "users",
            "#numAnonymousUsers" -> "numAnonymousUsers",
            "#version" -> "version"
          ).asJava)
          .expressionAttributeValues(Map(
            ":groupName" -> s(updatedGroup.groupName),
            ":start" -> s(updatedGroup.start),
            ":destination" -> s(updatedGroup.destination),
            ":pickupTime" -> n(updatedGroup.pickupTime),
            ":users" -> list(updatedGroup.users.map(groupUserToAttr)),
            ":numAnonymousUsers" -> nInt(updatedGroup.numAnonymousUsers),
            ":newVersion" -> nInt(updatedGroup.version),
            ":expectedVersion" -> nInt(expectedGroupVersion)
          ).asJava)
          .build()
        writes.add(TransactWriteItem.builder().update(groupUpdate).build())

        // 2. Update UserTrip records
        val originalUserIds = group.users.map(_.userId).toSet
        val updatedUserIds = updatedGroup.users.map(_.userId).toSet

        val addedUsers = updatedGroup.users.filter(u => !originalUserIds.contains(u.userId))
        val removedUserIds = originalUserIds -- updatedUserIds

        addedUsers.foreach { user =>
          val userTrip = buildUserTripFromRecord(trip, updatedGroup, user)
          val put = Put.builder()
            .tableName(userTripsTable)
            .item(userTripToItem(userTrip))
            .build()
          writes.add(TransactWriteItem.builder().put(put).build())
        }

        removedUserIds.foreach { userId =>
          val arn = userTripArn(trip.tripArn, userId)
          val delete = Delete.builder()
            .tableName(userTripsTable)
            .key(Map("arn" -> s(arn)).asJava)
            .build()
          writes.add(TransactWriteItem.builder().delete(delete).build())
        }

        // 3. Update TripMetadata
        val allGroups = listUserGroupRecordsByTripArn(trip.tripArn).map(g => if (g.arn == updatedGroup.arn) updatedGroup else g)
        val summaries = summarizeUserGroupsFromRecords(allGroups)
        val locs = orderedLocationsFromRecords(trip, allGroups)
        logger.info(s"summaries: ${summaries}")
        logger.info(s"locs: ${locs}")

        val tripUpdate = Update.builder()
          .tableName(tripMetadataTable)
          .key(Map("tripArn" -> s(trip.tripArn)).asJava)
          .updateExpression("SET #usergroups = :usergroups, #locations = :locations, #version = :newVersion")
          .conditionExpression("#version = :expectedVersion")
          .expressionAttributeNames(Map(
            "#usergroups" -> "usergroups",
            "#locations" -> "locations",
            "#version" -> "version"
          ).asJava)
          .expressionAttributeValues(Map(
            ":usergroups" -> usergroupsToAttr(summaries),
            ":locations" -> locationsToAttr(locs),
            ":newVersion" -> nInt(trip.version + 1),
            ":expectedVersion" -> nInt(expectedTripVersion)
          ).asJava)
          .build()
        logger.info(s"tripUpdate: ${tripUpdate}")
        writes.add(TransactWriteItem.builder().update(tripUpdate).build())

        val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
        client.transactWriteItems(req)

      case _ =>
        logger.warn(s"updateUserGroup: trip or group not found for groupArn=${groupArn}")
    }
  }

  def removeUserGroup(groupArn: String, expectedTripVersion: Int, expectedGroupVersion: Int): Unit = {
    // Fetch group to know tripArn and users
    getUserGroup(groupArn).foreach { g =>
      // Recompute aggregates after removal
      val remaining = listUserGroupRecordsByTripArn(g.tripArn).filterNot(_.arn == g.arn)
      val tripOpt = getTripMetadata(g.tripArn)
      val (summaries, locs) = tripOpt match {
        case Some(t) => (summarizeUserGroupsFromRecords(remaining), orderedLocationsFromRecords(t, remaining))
        case None => (Nil, Nil)
      }
      val tripUpdate = Update.builder()
        .tableName(tripMetadataTable)
        .key(Map("tripArn" -> s(g.tripArn)).asJava)
        .expressionAttributeNames(Map("#version" -> "version", "#usergroups" -> "usergroups", "#locations" -> "locations").asJava)
        .expressionAttributeValues(Map(
          ":ugs" -> usergroupsToAttr(summaries),
          ":locs" -> locationsToAttr(locs),
          ":zero" -> nInt(0), ":inc" -> nInt(1), ":expectedTrip" -> nInt(expectedTripVersion)
        ).asJava)
        .updateExpression("SET #usergroups = :ugs, #locations = :locs, #version = if_not_exists(#version, :zero) + :inc")
        .conditionExpression("#version = :expectedTrip")
        .build()

        val writes = new java.util.ArrayList[TransactWriteItem]()
        writes.add(TransactWriteItem.builder().update(tripUpdate).build())
        // Delete the group record with optimistic concurrency on version
        val delNames = Map("#version" -> "version").asJava
        val delValues = Map(":expectedGroup" -> nInt(expectedGroupVersion)).asJava
        val deleteGroup = Delete.builder()
          .tableName(userGroupsTable)
          .key(Map("groupArn" -> s(groupArn)).asJava)
          .expressionAttributeNames(delNames)
          .expressionAttributeValues(delValues)
          .conditionExpression("#version = :expectedGroup")
          .build()
        writes.add(TransactWriteItem.builder().delete(deleteGroup).build())
        // Delete all user trips for this group
        g.users.foreach { u =>
          val arn = userTripArn(g.tripArn, u.userId)
          val del = Delete.builder().tableName(userTripsTable).key(Map("arn" -> s(arn)).asJava).build()
          writes.add(TransactWriteItem.builder().delete(del).build())
        }
        val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
        client.transactWriteItems(req)
      }
    }

  def updateTripMetadata(t: TripMetadata, expectedVersion: Int): Unit = {
    val names = Map(
      "#locations" -> "locations",
      "#startTime" -> "startTime",
      "#completionTime" -> "completionTime",
      "#status" -> "status",
      "#currentStop" -> "currentStop",
      "#driver" -> "driver",
      "#driverName" -> "driverName",
      "#driverPhotoUrl" -> "driverPhotoUrl",
      "#driverConfirmed" -> "driverConfirmed",
      "#car" -> "car",
      "#usergroups" -> "usergroups",
      "#users" -> "users",
      "#notes" -> "notes",
      "#version" -> "version"
    ).asJava
    val values = Map(
      ":locations" -> locationsToAttr(t.locations),
      ":startTime" -> n(t.startTime),
      ":completionTime" -> t.completionTime.map(n).getOrElse(AttributeValue.builder().nul(true).build()),
      ":status" -> s(t.status),
      ":currentStop" -> t.currentStop.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":driver" -> t.driver.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":driverName" -> t.driverName.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":driverPhotoUrl" -> t.driverPhotoUrl.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":driverConfirmed" -> t.driverConfirmed.map(bool).getOrElse(AttributeValue.builder().nul(true).build()),
      ":car" -> t.car.map(ModelCodec.carToAttr).getOrElse(AttributeValue.builder().nul(true).build()),
      ":usergroups" -> t.usergroups.map(ugs => usergroupsToAttr(ugs)).getOrElse(AttributeValue.builder().nul(true).build()),
      ":users" -> t.users.map(us => Attrs.list(us.map(ModelCodec.tripUserToAttr))).getOrElse(AttributeValue.builder().nul(true).build()),
      ":notes" -> t.notes.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":zero" -> nInt(0),
      ":inc" -> nInt(1),
      ":expected" -> nInt(expectedVersion)
    ).asJava
    val sets = List(
      "#locations = :locations",
      "#startTime = :startTime",
      "#completionTime = :completionTime",
      "#status = :status",
      "#currentStop = :currentStop",
      "#driver = :driver",
      "#driverName = :driverName",
      "#driverPhotoUrl = :driverPhotoUrl",
      "#driverConfirmed = :driverConfirmed",
      "#car = :car",
      "#usergroups = :usergroups",
      "#users = :users",
      "#notes = :notes",
      "#version = if_not_exists(#version, :zero) + :inc"
    ).mkString(", ")

    val req = UpdateItemRequest.builder()
      .tableName(tripMetadataTable)
      .key(Map("tripArn" -> s(t.tripArn)).asJava)
      .expressionAttributeNames(names)
      .expressionAttributeValues(values)
      .updateExpression(s"SET ${sets}")
      .conditionExpression("#version = :expected")
      .build()
    client.updateItem(req)
  }

  def joinGroup(groupArn: String, newUser: GroupUser, expectedVersion: Int): Unit = {
    val req = UpdateItemRequest.builder()
      .tableName(userGroupsTable)
      .key(Map("groupArn" -> s(groupArn)).asJava)
      .expressionAttributeNames(Map("#users" -> "users", "#numAnonymousUsers" -> "numAnonymousUsers", "#version" -> "version").asJava)
      .expressionAttributeValues(Map(
        ":newUser" -> Attrs.list(List(groupUserToAttr(newUser))),
        ":inc" -> nInt(1),
        ":dec" -> nInt(-1),
        ":expected" -> nInt(expectedVersion)
      ).asJava)
      .updateExpression("SET #users = list_append(#users, :newUser), #numAnonymousUsers = #numAnonymousUsers + :inc, #version = #version + :inc")
      .conditionExpression("#version = :expected")
      .build()
    client.updateItem(req)
  }

  def getTripMetadata(tripArn: String): Option[TripMetadata] = {
    val req = GetItemRequest.builder()
      .tableName(tripMetadataTable)
      .key(Map("tripArn" -> s(tripArn)).asJava)
      .consistentRead(true)
      .build()
    val res = client.getItem(req)
    Option(res.item()).map(_.asScala).flatMap(item => parseTripMetadata(item.toMap))
  }

  private def parseTripMetadata(item: Map[String, AttributeValue]): Option[TripMetadata] = {
    def getS(name: String): Option[String] = item.get(name).flatMap(a => Option(a.s()))
    def getN(name: String): Option[Long] = item.get(name).flatMap(a => Option(a.n())).map(_.toLong)
    def getB(name: String): Option[Boolean] = item.get(name).flatMap(a => Option(a.bool()))
    def getMap(name: String): Option[java.util.Map[String, AttributeValue]] = item.get(name).flatMap(a => Option(a.m()))
    def getList(name: String): List[AttributeValue] = item.get(name).flatMap(a => Option(a.l())).map(_.asScala.toList).getOrElse(Nil)

    val tripArn = getS("tripArn")
    tripArn.map { arn =>
      val locations: List[Location] = getList("locations").flatMap { av => Option(av.m()) }.map(_.asScala.toMap).map { m =>
        Location(
          locationName = m.get("locationName").flatMap(a => Option(a.s())).getOrElse(""),
          pickupGroups = m.get("pickupGroups").flatMap(a => Option(a.l())).map(_.asScala.toList.flatMap(v => Option(v.s()))).getOrElse(Nil),
          dropOffGroups = m.get("dropOffGroups").flatMap(a => Option(a.l())).map(_.asScala.toList.flatMap(v => Option(v.s()))).getOrElse(Nil),
          arrived = m.get("arrived").flatMap(a => Option(a.bool())).map(_.booleanValue()).getOrElse(false),
          arrivedTime = m.get("arrivedTime").flatMap(a => Option(a.n())).map(_.toLong)
        )
      }
      val carOpt = getMap("car").map(_.asScala.toMap).map { m =>
        Car(
          plateNumber = m.get("plateNumber").flatMap(a => Option(a.s())),
          color = m.get("color").flatMap(a => Option(a.s())),
          model = m.get("model").flatMap(a => Option(a.s()))
        )
      }
      val usergroupsOpt: Option[List[UserGroup]] = item.get("usergroups").flatMap(a => Option(a.l())).map(_.asScala.toList).map { lst =>
        val maps: List[Map[String, AttributeValue]] = lst.flatMap(av => Option(av.m())).map(_.asScala.toMap)
        maps.map { m =>
          UserGroup(
            groupId = m.get("groupId").flatMap(a => Option(a.s())).getOrElse(""),
            groupName = m.get("groupName").flatMap(a => Option(a.s())).getOrElse(""),
            groupSize = m.get("groupSize").flatMap(a => Option(a.n())).map(_.toInt).getOrElse(0),
            numAnonymousUser = m.get("numAnonymousUser").flatMap(a => Option(a.n())).map(_.toInt).getOrElse(0),
            imageUrl = m.get("imageUrl").flatMap(a => Option(a.s()))
          )
        }
      }
      val usersOpt = item.get("users").flatMap(a => Option(a.l())).map(_.asScala.toList).map { lst =>
        val maps: List[Map[String, AttributeValue]] = lst.flatMap(av => Option(av.m())).map(_.asScala.toMap)
        maps.map { m =>
          TripUser(
            userId = m.get("userId").flatMap(a => Option(a.s())),
            name = m.get("name").flatMap(a => Option(a.s())).getOrElse(""),
            imageUrl = m.get("imageUrl").flatMap(a => Option(a.s()))
          )
        }
      }

      TripMetadata(
        tripArn = arn,
        locations = locations,
        startTime = getN("startTime").getOrElse(0L),
        completionTime = getN("completionTime"),
        status = getS("status").getOrElse(""),
        currentStop = getS("currentStop"),
        driver = getS("driver"),
        driverName = getS("driverName"),
        driverPhotoUrl = getS("driverPhotoUrl"),
        driverConfirmed = getB("driverConfirmed"),
        car = carOpt,
        usergroups = usergroupsOpt,
        users = usersOpt,
        notes = getS("notes"),
        version = item.get("version").flatMap(a => Option(a.n())).map(_.toInt).getOrElse(1)
      )
    }
  }

  def updateTripStatus(tripArn: String, status: String, expectedVersion: Int, completionTime: Option[Long] = None, currentStop: Option[String] = None): Unit = {
    val names = scala.collection.mutable.Map[String, String]("#status" -> "status", "#version" -> "version")
    val values = scala.collection.mutable.Map[String, AttributeValue](":status" -> s(status), ":zero" -> nInt(0), ":inc" -> nInt(1), ":expected" -> nInt(expectedVersion))
    val sets = scala.collection.mutable.ListBuffer[String]("#status = :status", "#version = if_not_exists(#version, :zero) + :inc")

    completionTime.foreach { ct =>
      names += ("#completionTime" -> "completionTime")
      values += (":ct" -> n(ct))
      sets += "#completionTime = :ct"
    }
    currentStop.foreach { cs =>
      names += ("#currentStop" -> "currentStop")
      values += (":cs" -> s(cs))
      sets += "#currentStop = :cs"
    }

    val req = UpdateItemRequest.builder()
      .tableName(tripMetadataTable)
      .key(Map("tripArn" -> s(tripArn)).asJava)
      .expressionAttributeNames(names.asJava)
      .expressionAttributeValues(values.asJava)
      .updateExpression(s"SET ${sets.mkString(", ")}")
      .conditionExpression("#version = :expected")
      .build()
    client.updateItem(req)
  }

  def setDriverInfo(tripArn: String, expectedVersion: Int, driver: Option[String], driverPhotoUrl: Option[String], driverConfirmed: Option[Boolean], car: Option[Car]): Unit = {
    val names = scala.collection.mutable.Map[String, String]("#version" -> "version")
    val values = scala.collection.mutable.Map[String, AttributeValue](":zero" -> nInt(0), ":inc" -> nInt(1), ":expected" -> nInt(expectedVersion))
    val sets = scala.collection.mutable.ListBuffer[String]("#version = if_not_exists(#version, :zero) + :inc")

    driver.foreach { d => names += ("#driver" -> "driver"); values += (":driver" -> s(d)); sets += "#driver = :driver" }
    driverPhotoUrl.foreach { url => names += ("#driverPhotoUrl" -> "driverPhotoUrl"); values += (":dp" -> s(url)); sets += "#driverPhotoUrl = :dp" }
    driverConfirmed.foreach { dc => names += ("#driverConfirmed" -> "driverConfirmed"); values += (":dc" -> bool(dc)); sets += "#driverConfirmed = :dc" }
    car.foreach { c => names += ("#car" -> "car"); values += (":car" -> carToAttr(c)); sets += "#car = :car" }

    if (sets.nonEmpty) {
      val req = UpdateItemRequest.builder()
        .tableName(tripMetadataTable)
        .key(Map("tripArn" -> s(tripArn)).asJava)
        .expressionAttributeNames(names.asJava)
        .expressionAttributeValues(values.asJava)
        .updateExpression(s"SET ${sets.mkString(", ")}")
        .conditionExpression("#version = :expected")
        .build()
      client.updateItem(req)
    }
  }

  def appendUserGroup(tripArn: String, group: UserGroup, expectedVersion: Int): Unit = {
    val req = UpdateItemRequest.builder()
      .tableName(tripMetadataTable)
      .key(Map("tripArn" -> s(tripArn)).asJava)
      .expressionAttributeNames(Map("#usergroups" -> "usergroups", "#version" -> "version").asJava)
      .expressionAttributeValues(Map(
        ":empty" -> Attrs.list(Nil),
        ":new" -> Attrs.list(List(ModelCodec.userGroupToAttr(group))),
        ":zero" -> nInt(0),
        ":inc" -> nInt(1),
        ":expected" -> nInt(expectedVersion)
      ).asJava)
      .updateExpression("SET #usergroups = list_append(if_not_exists(#usergroups, :empty), :new), #version = if_not_exists(#version, :zero) + :inc")
      .conditionExpression("#version = :expected")
      .build()
    client.updateItem(req)
  }

  def markTripCompleted(tripArn: String, completionTime: Long, users: List[TripUser], expectedVersion: Int): Unit = {
    val req = UpdateItemRequest.builder()
      .tableName(tripMetadataTable)
      .key(Map("tripArn" -> s(tripArn)).asJava)
      .expressionAttributeNames(Map(
        "#status" -> "status",
        "#completionTime" -> "completionTime",
        "#users" -> "users",
        "#version" -> "version"
      ).asJava)
      .expressionAttributeValues(Map(
        ":status" -> s("Completed"),
        ":ct" -> n(completionTime),
        ":users" -> Attrs.list(users.map(ModelCodec.tripUserToAttr)),
        ":zero" -> nInt(0),
        ":inc" -> nInt(1),
        ":expected" -> nInt(expectedVersion)
      ).asJava)
      .updateExpression("SET #status = :status, #completionTime = :ct, #users = :users, #version = if_not_exists(#version, :zero) + :inc")
      .conditionExpression("#version = :expected")
      .build()
    client.updateItem(req)
  }

  // UserTrips operations
  def putUserTrip(t: UserTrip): Unit = {
    val req = PutItemRequest.builder()
      .tableName(userTripsTable)
      .item(userTripToItem(t))
      .conditionExpression("attribute_not_exists(arn)")
      .build()
    client.putItem(req)
  }

  def getUserTrip(arn: String): Option[UserTrip] = {
    val req = GetItemRequest.builder().tableName(userTripsTable).key(Map("arn" -> s(arn)).asJava).consistentRead(true).build()
    val res = client.getItem(req)
    Option(res.item()).map(_.asScala).flatMap(parseUserTrip)
  }

  private def parseUserTrip(attrs: scala.collection.mutable.Map[String, AttributeValue]): Option[UserTrip] = {
    def getS(name: String): Option[String] = attrs.get(name).flatMap(a => Option(a.s()))
    def getN(name: String): Option[Long] = attrs.get(name).flatMap(a => Option(a.n())).map(_.toLong)
    def getB(name: String): Option[Boolean] = attrs.get(name).flatMap(a => Option(a.bool()))
    for {
      arn <- getS("arn")
      userStatusKey <- getS("userStatusKey")
      tripDateTime <- getN("tripDateTime")
      tripStatus <- getS("tripStatus")
      start <- getS("start")
      destination <- getS("destination")
      departureDateTime <- getN("departureDateTime")
      isDriver <- getB("isDriver")
      driverConfirmed <- getB("driverConfirmed")
    } yield {
      val version = attrs.get("version").flatMap(a => Option(a.n())).map(_.toInt).getOrElse(1)
      val tripArn = getS("tripArn").getOrElse(arn.split('#').head)
      UserTrip(arn, tripArn, userStatusKey, tripDateTime, tripStatus, start, destination, departureDateTime, isDriver, driverConfirmed, version)
    }
  }

  def queryUserTripsByStatus(userId: String, tripStatus: String, fromDateTime: Option[Long] = None, limit: Int = 50, ascending: Boolean = false): List[UserTrip] = {
    // tripStatus is expected to be "completed" or "uncompleted"
    val usk = s"${userId}-${tripStatus}"
    val names = Map("#usk" -> "userStatusKey", "#dt" -> "tripDateTime").asJava
    val valuesBase = new java.util.HashMap[String, AttributeValue]()
    valuesBase.put(":usk", s(usk))
    val kce = new StringBuilder("#usk = :usk")
    fromDateTime.foreach { dt =>
      valuesBase.put(":from", n(dt))
      kce.append(" AND #dt >= :from")
    }

    val req = QueryRequest.builder()
      .tableName(userTripsTable)
      .indexName("gsiUserTripStatusDateTime")
      .keyConditionExpression(kce.toString())
      .expressionAttributeNames(names)
      .expressionAttributeValues(valuesBase)
      .limit(limit)
      .scanIndexForward(ascending)
      .build()
    val res = client.query(req)
    Option(res.items()).map(_.asScala.toList).getOrElse(Nil).flatMap(it => parseUserTrip(it.asScala))
  }



  def listUsersByTrip(tripArn: String, limit: Int = 100): List[UserTrip] = {
    val names = Map("#tripArn" -> "tripArn").asJava
    val values = Map(":ta" -> s(tripArn)).asJava
    val req = QueryRequest.builder()
      .tableName(userTripsTable)
      .indexName("gsiTripArn")
      .keyConditionExpression("#tripArn = :ta")
      .expressionAttributeNames(names)
      .expressionAttributeValues(values)
      .limit(limit)
      .build()
    val res = client.query(req)
    Option(res.items()).map(_.asScala.toList).getOrElse(Nil).flatMap(it => parseUserTrip(it.asScala))
  }

  def updateUserTripStatus(arn: String, tripStatus: String, expectedVersion: Int, driverConfirmed: Option[Boolean] = None): Unit = {
    val names = scala.collection.mutable.Map[String, String]("#tripStatus" -> "tripStatus", "#version" -> "version")
    val values = scala.collection.mutable.Map[String, AttributeValue](":ts" -> s(tripStatus), ":zero" -> nInt(0), ":inc" -> nInt(1), ":expected" -> nInt(expectedVersion))
    val sets = scala.collection.mutable.ListBuffer[String]("#tripStatus = :ts", "#version = if_not_exists(#version, :zero) + :inc")
    driverConfirmed.foreach { dc => names += ("#driverConfirmed" -> "driverConfirmed"); values += (":dc" -> bool(dc)); sets += "#driverConfirmed = :dc" }
    val req = UpdateItemRequest.builder()
      .tableName(userTripsTable)
      .key(Map("arn" -> s(arn)).asJava)
      .expressionAttributeNames(names.asJava)
      .expressionAttributeValues(values.asJava)
      .updateExpression(s"SET ${sets.mkString(", ")}")
      .conditionExpression("#version = :expected")
      .build()
    client.updateItem(req)
  }

  def deleteUserTrip(arn: String): Unit = {
    val req = DeleteItemRequest.builder()
      .tableName(userTripsTable)
      .key(Map("arn" -> s(arn)).asJava)
      .build()
    client.deleteItem(req)
  }

  // Removed: anonymous summary is not stored on TripMetadata anymore

  // UserGroups operations
  def putUserGroup(g: UserGroupRecord): Unit = {
    val req = PutItemRequest.builder()
      .tableName(userGroupsTable)
      .item(userGroupRecordToItem(g))
      .build()
    client.putItem(req)
  }

  def getUserGroup(arn: String): Option[UserGroupRecord] = {
    val req = GetItemRequest.builder()
      .tableName(userGroupsTable)
.key(Map("groupArn" -> s(arn)).asJava)
      .consistentRead(true)
      .build()
    val res = client.getItem(req)
    Option(res.item()).map(_.asScala).flatMap(item => parseUserGroupRecord(item.toMap))
  }

  private def parseUserGroupRecord(item: Map[String, AttributeValue]): Option[UserGroupRecord] = {
    def getS(name: String): Option[String] = item.get(name).flatMap(av => Option(av.s()))
    def getN(name: String): Option[Long] = item.get(name).flatMap(av => Option(av.n())).map(_.toLong)
    def getList(name: String): List[AttributeValue] = item.get(name).flatMap(av => Option(av.l())).map(_.asScala.toList).getOrElse(Nil)

    val arn = getS("groupArn")
    arn.map { a =>
      val userMaps: List[Map[String, AttributeValue]] = getList("users").flatMap(av => Option(av.m())).map(_.asScala.toMap)
      val users = userMaps.map { m =>
        GroupUser(
          userId = m.get("userId").flatMap(av => Option(av.s())).getOrElse(""),
          name = m.get("name").flatMap(av => Option(av.s())).getOrElse(""),
          accept = m.get("accept").flatMap(av => Option(av.bool())).map(_.booleanValue()).getOrElse(false)
        )
      }
      UserGroupRecord(
        arn = a,
        tripArn = getS("tripArn").getOrElse(""),
        groupName = getS("groupName").getOrElse(""),
        start = getS("start").getOrElse(""),
        destination = getS("destination").getOrElse(""),
        pickupTime = getN("pickupTime").getOrElse(0L),
        users = users,
        numAnonymousUsers = item.get("numAnonymousUsers").flatMap(av => Option(av.n())).map(_.toInt).getOrElse(0),
        version = item.get("version").flatMap(av => Option(av.n())).map(_.toInt).getOrElse(1)
      )
    }
  }

  def updateUserGroupInfo(arn: String, expectedVersion: Int, groupName: Option[String] = None, start: Option[String] = None, destination: Option[String] = None, pickupTime: Option[Long] = None): Unit = {
    val names = scala.collection.mutable.Map[String, String]("#version" -> "version")
    val values = scala.collection.mutable.Map[String, AttributeValue](":zero" -> nInt(0), ":inc" -> nInt(1), ":expected" -> nInt(expectedVersion))
    val sets = scala.collection.mutable.ListBuffer[String]("#version = if_not_exists(#version, :zero) + :inc")

    groupName.foreach { gn => names += ("#groupName" -> "groupName"); values += (":gn" -> s(gn)); sets += "#groupName = :gn" }
    start.foreach { s => names += ("#start" -> "start"); values += (":s" -> Attrs.s(s)); sets += "#start = :s" }
    destination.foreach { d => names += ("#destination" -> "destination"); values += (":d" -> Attrs.s(d)); sets += "#destination = :d" }
    pickupTime.foreach { pt => names += ("#pickupTime" -> "pickupTime"); values += (":pt" -> n(pt)); sets += "#pickupTime = :pt" }

    if (sets.size > 1) {
      val req = UpdateItemRequest.builder()
        .tableName(userGroupsTable)
        .key(Map("groupArn" -> s(arn)).asJava)
        .expressionAttributeNames(names.asJava)
        .expressionAttributeValues(values.asJava)
        .updateExpression(s"SET ${sets.mkString(", ")}")
        .conditionExpression("#version = :expected")
        .build()
      client.updateItem(req)
    }
  }

  def acceptUserInvitation(tripArn: String, groupArn: String, userId: String, expectedGroupVersion: Int): Unit = {
    (getTripMetadata(tripArn), getUserGroup(groupArn)) match {
      case (Some(trip), Some(group)) =>
        val userIndex = group.users.indexWhere(_.userId == userId)
        if (userIndex == -1) {
          logger.warn(s"User $userId not found in group $groupArn")
          return
        }
        val updatedUsers = group.users.updated(userIndex, group.users(userIndex).copy(accept = true))
        val writes = new java.util.ArrayList[TransactWriteItem]()

        // Update UserGroup
        val groupUpdate = Update.builder()
          .tableName(userGroupsTable)
          .key(Map("groupArn" -> s(groupArn)).asJava)
          .updateExpression("SET #users = :users, #version = #version + :inc")
          .conditionExpression("#version = :expectedVersion")
          .expressionAttributeNames(Map("#users" -> "users", "#version" -> "version").asJava)
          .expressionAttributeValues(Map(
            ":users" -> list(updatedUsers.map(groupUserToAttr)),
            ":inc" -> nInt(1),
            ":expectedVersion" -> nInt(expectedGroupVersion)
          ).asJava)
          .build()
        writes.add(TransactWriteItem.builder().update(groupUpdate).build())

        // Update UserTrip
        val userTripArn = this.userTripArn(tripArn, userId)
        val newTripStatus = deriveEffectiveStatus(trip)
        val userTripUpdate = Update.builder()
          .tableName(userTripsTable)
          .key(Map("arn" -> s(userTripArn)).asJava)
          .updateExpression("SET #tripStatus = :tripStatus, #userStatusKey = :usk, #version = #version + :inc")
          .expressionAttributeNames(Map("#tripStatus" -> "tripStatus", "#userStatusKey" -> "userStatusKey", "#version" -> "version").asJava)
          .expressionAttributeValues(Map(
            ":tripStatus" -> s(newTripStatus),
            ":usk" -> s(s"$userId-${if (newTripStatus == "Completed") "completed" else "uncompleted"}"),
            ":inc" -> nInt(1)
          ).asJava)
          .build()
        writes.add(TransactWriteItem.builder().update(userTripUpdate).build())

        val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
        client.transactWriteItems(req)

      case _ => logger.warn(s"acceptUserInvitation: trip or group not found")
    }
  }

  def setCurrentStopAndSyncStatuses(tripArn: String, currentStop: String, expectedTripVersion: Int): Unit = {
    // 1. Update trip status to InProgress and set current stop
    updateTripStatus(tripArn, "InProgress", expectedTripVersion, currentStop = Some(currentStop))

    // 2. Update user trips to InProgress
    val userTrips = listUsersByTrip(tripArn)
    userTrips.foreach { ut =>
      if (ut.tripStatus == "Upcoming") {
        updateUserTripStatus(ut.arn, "InProgress", ut.version)
      }
    }
  }
}
