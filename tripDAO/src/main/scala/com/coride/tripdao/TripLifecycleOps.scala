package com.coride.tripdao

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import scala.jdk.CollectionConverters._
import org.slf4j.LoggerFactory
import Attrs._
import ModelCodec._

trait TripDAOForLifecycleOps {
  def getTripMetadata(tripArn: String): Option[TripMetadata]
  def listUserGroupRecordsByTripArn(tripArn: String, limit: Int = 100): List[UserGroupRecord]
}

class TripLifecycleOps(
  client: DynamoDbClient,
  tripMetadataTable: String,
  userTripsTable: String,
  userGroupsTable: String,
  dao: TripDAOForLifecycleOps
) {
  private val logger = LoggerFactory.getLogger(classOf[TripLifecycleOps])

  def createTrip(base: TripMetadata, groups: List[UserGroupRecord]): Unit = {
    val summaries = TripDAOLocationsHelper.summarizeUserGroupsFromRecords(groups)
    val locs = TripDAOLocationsHelper.orderedLocationsFromRecords(base, groups)
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
        val ut = TripDAOLocationsHelper.buildUserTripFromRecord(trip, g, u)
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
    val summaries = TripDAOLocationsHelper.summarizeUserGroupsFromRecords(groups)
    val locs = TripDAOLocationsHelper.orderedLocationsFromRecords(base, groups)
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

    val driverId = base.driver
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
        if (driverId.exists(_ == u.userId)) ()
        else {
          val ut = TripDAOLocationsHelper.buildUserTripFromRecord(trip, g, u)
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
    }
    val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
    client.transactWriteItems(req)
  }

  def addUserGroup(tripArn: String, newGroup: UserGroupRecord, expectedTripVersion: Int): Unit = {
    dao.getTripMetadata(tripArn).foreach { current =>
      val existing = dao.listUserGroupRecordsByTripArn(tripArn)
      val allGroups = existing :+ newGroup
      val summaries = TripDAOLocationsHelper.summarizeUserGroupsFromRecords(allGroups)
      val locs = TripDAOLocationsHelper.orderedLocationsFromRecords(current, allGroups)

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
        val ut = TripDAOLocationsHelper.buildUserTripFromRecord(current, newGroup, u)
        writes.add(TransactWriteItem.builder().put(Put.builder().tableName(userTripsTable).item(userTripToItem(ut)).build()).build())
        logger.info(s"Inserted UserTrip ${ut.arn} for new group with status=Invitation")
      }
      val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
      client.transactWriteItems(req)
    }
  }

  def deleteTrip(tripArn: String): Unit = {
    val groups = dao.listUserGroupRecordsByTripArn(tripArn)
    val writes = new java.util.ArrayList[TransactWriteItem]()

    writes.add(
      TransactWriteItem.builder().delete(
        Delete.builder()
          .tableName(tripMetadataTable)
          .key(Map("tripArn" -> s(tripArn)).asJava)
          .build()
      ).build()
    )

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
        val utArn = TripDAOLocationsHelper.userTripArn(tripArn, u.userId)
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
}
