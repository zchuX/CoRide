package com.coride.tripdao

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import scala.jdk.CollectionConverters._
import org.slf4j.LoggerFactory
import Attrs._
import ModelCodec._

trait TripDAOForUserGroupOps {
  def getTripMetadata(tripArn: String): Option[TripMetadata]
  def getUserGroup(arn: String): Option[UserGroupRecord]
  def listUserGroupRecordsByTripArn(tripArn: String, limit: Int = 100): List[UserGroupRecord]
}

class UserGroupOps(
  client: DynamoDbClient,
  userGroupsTable: String,
  userTripsTable: String,
  tripMetadataTable: String,
  dao: TripDAOForUserGroupOps
) {
  private val logger = LoggerFactory.getLogger(classOf[UserGroupOps])

  def listUserGroupRecordsByTripArn(tripArn: String, limit: Int = 100): List[UserGroupRecord] = {
    val names = Map("#tripArn" -> "tripArn").asJava
    val values = Map(":ta" -> s(tripArn)).asJava
    val req = QueryRequest.builder()
      .tableName(userGroupsTable)
      .indexName("gsiTripArn")
      .keyConditionExpression("#tripArn = :ta")
      .expressionAttributeNames(names)
      .expressionAttributeValues(values)
      .limit(limit)
      .build()
    val res = client.query(req)
    Option(res.items()).map(_.asScala.toList).getOrElse(Nil).flatMap(it => parseUserGroupRecord(it.asScala.toMap))
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

  def putUserGroup(g: UserGroupRecord): Unit = {
    val req = PutItemRequest.builder()
      .tableName(userGroupsTable)
      .item(userGroupRecordToItem(g))
      .build()
    client.putItem(req)
  }

  def parseUserGroupRecord(item: Map[String, AttributeValue]): Option[UserGroupRecord] = {
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

  def updateUserGroup(
    groupArn: String,
    expectedGroupVersion: Int,
    expectedTripVersion: Int,
    groupName: Option[String],
    start: Option[String],
    destination: Option[String],
    pickupTime: Option[Long],
    users: Option[List[GroupUser]]
  ): Unit = {
    val groupOpt = getUserGroup(groupArn)
    val tripOpt = groupOpt.flatMap(g => dao.getTripMetadata(g.tripArn))

    (tripOpt, groupOpt) match {
      case (Some(trip), Some(group)) =>
        val updatedGroup = group.copy(
          groupName = groupName.getOrElse(group.groupName),
          start = start.getOrElse(group.start),
          destination = destination.getOrElse(group.destination),
          pickupTime = pickupTime.getOrElse(group.pickupTime),
          users = users.getOrElse(group.users),
          version = group.version + 1
        )

        val writes = new java.util.ArrayList[TransactWriteItem]()

        val groupUpdate = Update.builder()
          .tableName(userGroupsTable)
          .key(Map("groupArn" -> s(group.arn)).asJava)
          .updateExpression("SET #groupName = :groupName, #start = :start, #destination = :destination, #pickupTime = :pickupTime, #users = :users, #version = :newVersion")
          .conditionExpression("#version = :expectedVersion")
          .expressionAttributeNames(Map(
            "#groupName" -> "groupName", "#start" -> "start", "#destination" -> "destination", "#pickupTime" -> "pickupTime",
            "#users" -> "users", "#version" -> "version"
          ).asJava)
          .expressionAttributeValues(Map(
            ":groupName" -> s(updatedGroup.groupName),
            ":start" -> s(updatedGroup.start),
            ":destination" -> s(updatedGroup.destination),
            ":pickupTime" -> n(updatedGroup.pickupTime),
            ":users" -> list(updatedGroup.users.map(groupUserToAttr)),
            ":newVersion" -> nInt(updatedGroup.version),
            ":expectedVersion" -> nInt(expectedGroupVersion)
          ).asJava)
          .build()
        writes.add(TransactWriteItem.builder().update(groupUpdate).build())

        val originalUserIds = group.users.map(_.userId).toSet
        val updatedUserIds = updatedGroup.users.map(_.userId).toSet
        val addedUsers = updatedGroup.users.filter(u => !originalUserIds.contains(u.userId))
        val removedUserIds = originalUserIds -- updatedUserIds

        addedUsers.foreach { user =>
          val userTrip = TripDAOLocationsHelper.buildUserTripFromRecord(trip, updatedGroup, user)
          writes.add(TransactWriteItem.builder().put(Put.builder().tableName(userTripsTable).item(userTripToItem(userTrip)).build()).build())
        }
        removedUserIds.foreach { userId =>
          val utArn = TripDAOLocationsHelper.userTripArn(trip.tripArn, userId)
          writes.add(TransactWriteItem.builder().delete(Delete.builder().tableName(userTripsTable).key(Map("arn" -> s(utArn)).asJava).build()).build())
        }

        val allGroups = dao.listUserGroupRecordsByTripArn(trip.tripArn).map(g => if (g.arn == updatedGroup.arn) updatedGroup else g)
        val summaries = TripDAOLocationsHelper.summarizeUserGroupsFromRecords(allGroups)
        val locs = TripDAOLocationsHelper.orderedLocationsFromRecords(trip, allGroups)
        TripDAOLocationsHelper.validateDropoffAfterPickup(locs, allGroups).foreach { msg =>
          throw new IllegalArgumentException(msg)
        }

        val tripUpdate = Update.builder()
          .tableName(tripMetadataTable)
          .key(Map("tripArn" -> s(trip.tripArn)).asJava)
          .updateExpression("SET #usergroups = :usergroups, #locations = :locations, #version = :newVersion")
          .conditionExpression("#version = :expectedVersion")
          .expressionAttributeNames(Map("#usergroups" -> "usergroups", "#locations" -> "locations", "#version" -> "version").asJava)
          .expressionAttributeValues(Map(
            ":usergroups" -> usergroupsToAttr(summaries),
            ":locations" -> locationsToAttr(locs),
            ":newVersion" -> nInt(trip.version + 1),
            ":expectedVersion" -> nInt(expectedTripVersion)
          ).asJava)
          .build()
        writes.add(TransactWriteItem.builder().update(tripUpdate).build())

        val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
        client.transactWriteItems(req)

      case _ =>
        logger.warn(s"updateUserGroup: trip or group not found for groupArn=${groupArn}")
    }
  }

  def removeUserGroup(groupArn: String, expectedTripVersion: Int, expectedGroupVersion: Int): Unit = {
    getUserGroup(groupArn).foreach { g =>
      val remaining = dao.listUserGroupRecordsByTripArn(g.tripArn).filterNot(_.arn == g.arn)
      val tripOpt = dao.getTripMetadata(g.tripArn)
      val (summaries, locs) = tripOpt match {
        case Some(t) => (TripDAOLocationsHelper.summarizeUserGroupsFromRecords(remaining), TripDAOLocationsHelper.orderedLocationsFromRecords(t, remaining))
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
      val deleteGroup = Delete.builder()
        .tableName(userGroupsTable)
        .key(Map("groupArn" -> s(groupArn)).asJava)
        .expressionAttributeNames(Map("#version" -> "version").asJava)
        .expressionAttributeValues(Map(":expectedGroup" -> nInt(expectedGroupVersion)).asJava)
        .conditionExpression("#version = :expectedGroup")
        .build()
      writes.add(TransactWriteItem.builder().delete(deleteGroup).build())
      g.users.foreach { u =>
        val arn = TripDAOLocationsHelper.userTripArn(g.tripArn, u.userId)
        writes.add(TransactWriteItem.builder().delete(Delete.builder().tableName(userTripsTable).key(Map("arn" -> s(arn)).asJava).build()).build())
      }
      val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
      client.transactWriteItems(req)
    }
  }

  def acceptUserInvitation(tripArn: String, groupArn: String, userId: String, expectedGroupVersion: Int): Unit = {
    (dao.getTripMetadata(tripArn), getUserGroup(groupArn)) match {
      case (Some(trip), Some(group)) =>
        val userIndex = group.users.indexWhere(_.userId == userId)
        if (userIndex == -1) {
          logger.warn(s"User $userId not found in group $groupArn")
          return
        }
        val updatedUsers = group.users.updated(userIndex, group.users(userIndex).copy(accept = true))
        val writes = new java.util.ArrayList[TransactWriteItem]()

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

        val userTripArn = TripDAOLocationsHelper.userTripArn(tripArn, userId)
        val newTripStatus = TripDAOLocationsHelper.deriveEffectiveStatus(trip)
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
}
