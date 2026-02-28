package com.coride.tripdao

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import scala.jdk.CollectionConverters._
import Attrs._
import ModelCodec._

trait TripDAOForStatusTransitionOps {
  def listUsersByTrip(tripArn: String, limit: Int = 100): List[UserTrip]
}

class TripStatusTransitionOps(
  client: DynamoDbClient,
  tripMetadataTable: String,
  userTripsTable: String,
  dao: TripDAOForStatusTransitionOps
) {

  def startTripTransaction(updatedTrip: TripMetadata, expectedVersion: Int): Unit = {
    val names = Map(
      "#locations" -> "locations", "#startTime" -> "startTime", "#completionTime" -> "completionTime",
      "#status" -> "status", "#currentStop" -> "currentStop", "#driver" -> "driver", "#driverName" -> "driverName",
      "#driverPhotoUrl" -> "driverPhotoUrl", "#driverConfirmed" -> "driverConfirmed", "#car" -> "car",
      "#usergroups" -> "usergroups", "#users" -> "users", "#notes" -> "notes", "#version" -> "version"
    ).asJava
    val values = Map(
      ":locations" -> locationsToAttr(updatedTrip.locations),
      ":startTime" -> n(updatedTrip.startTime),
      ":completionTime" -> updatedTrip.completionTime.map(n).getOrElse(AttributeValue.builder().nul(true).build()),
      ":status" -> s(updatedTrip.status),
      ":currentStop" -> updatedTrip.currentStop.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":driver" -> updatedTrip.driver.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":driverName" -> updatedTrip.driverName.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":driverPhotoUrl" -> updatedTrip.driverPhotoUrl.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":driverConfirmed" -> updatedTrip.driverConfirmed.map(bool).getOrElse(AttributeValue.builder().nul(true).build()),
      ":car" -> updatedTrip.car.map(ModelCodec.carToAttr).getOrElse(AttributeValue.builder().nul(true).build()),
      ":usergroups" -> updatedTrip.usergroups.map(ugs => usergroupsToAttr(ugs)).getOrElse(AttributeValue.builder().nul(true).build()),
      ":users" -> updatedTrip.users.map(us => Attrs.list(us.map(ModelCodec.tripUserToAttr))).getOrElse(AttributeValue.builder().nul(true).build()),
      ":notes" -> updatedTrip.notes.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":zero" -> nInt(0), ":inc" -> nInt(1), ":expected" -> nInt(expectedVersion)
    ).asJava
    val sets = List(
      "#locations = :locations", "#startTime = :startTime", "#completionTime = :completionTime",
      "#status = :status", "#currentStop = :currentStop", "#driver = :driver", "#driverName = :driverName",
      "#driverPhotoUrl = :driverPhotoUrl", "#driverConfirmed = :driverConfirmed", "#car = :car",
      "#usergroups = :usergroups", "#users = :users", "#notes = :notes",
      "#version = if_not_exists(#version, :zero) + :inc"
    ).mkString(", ")

    val tripUpdate = Update.builder()
      .tableName(tripMetadataTable)
      .key(Map("tripArn" -> s(updatedTrip.tripArn)).asJava)
      .updateExpression(s"SET $sets")
      .conditionExpression("#version = :expected")
      .expressionAttributeNames(names)
      .expressionAttributeValues(values)
      .build()

    val userTrips = dao.listUsersByTrip(updatedTrip.tripArn, 100)
    val writes = new java.util.ArrayList[TransactWriteItem]()
    writes.add(TransactWriteItem.builder().update(tripUpdate).build())
    userTrips.foreach { ut =>
      val utUpdate = Update.builder()
        .tableName(userTripsTable)
        .key(Map("arn" -> s(ut.arn)).asJava)
        .updateExpression("SET #tripStatus = :ts, #version = if_not_exists(#version, :zero) + :inc")
        .conditionExpression("#version = :expected")
        .expressionAttributeNames(Map("#tripStatus" -> "tripStatus", "#version" -> "version").asJava)
        .expressionAttributeValues(Map(
          ":ts" -> s("InProgress"),
          ":zero" -> nInt(0), ":inc" -> nInt(1), ":expected" -> nInt(ut.version)
        ).asJava)
        .build()
      writes.add(TransactWriteItem.builder().update(utUpdate).build())
    }
    val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
    client.transactWriteItems(req)
  }

  def completeTripTransaction(updatedTrip: TripMetadata, expectedVersion: Int): Unit = {
    val names = Map(
      "#locations" -> "locations", "#startTime" -> "startTime", "#completionTime" -> "completionTime",
      "#status" -> "status", "#currentStop" -> "currentStop", "#driver" -> "driver", "#driverName" -> "driverName",
      "#driverPhotoUrl" -> "driverPhotoUrl", "#driverConfirmed" -> "driverConfirmed", "#car" -> "car",
      "#usergroups" -> "usergroups", "#users" -> "users", "#notes" -> "notes", "#version" -> "version"
    ).asJava
    val values = Map(
      ":locations" -> locationsToAttr(updatedTrip.locations),
      ":startTime" -> n(updatedTrip.startTime),
      ":completionTime" -> updatedTrip.completionTime.map(n).getOrElse(AttributeValue.builder().nul(true).build()),
      ":status" -> s(updatedTrip.status),
      ":currentStop" -> updatedTrip.currentStop.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":driver" -> updatedTrip.driver.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":driverName" -> updatedTrip.driverName.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":driverPhotoUrl" -> updatedTrip.driverPhotoUrl.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":driverConfirmed" -> updatedTrip.driverConfirmed.map(bool).getOrElse(AttributeValue.builder().nul(true).build()),
      ":car" -> updatedTrip.car.map(ModelCodec.carToAttr).getOrElse(AttributeValue.builder().nul(true).build()),
      ":usergroups" -> updatedTrip.usergroups.map(ugs => usergroupsToAttr(ugs)).getOrElse(AttributeValue.builder().nul(true).build()),
      ":users" -> updatedTrip.users.map(us => Attrs.list(us.map(ModelCodec.tripUserToAttr))).getOrElse(AttributeValue.builder().nul(true).build()),
      ":notes" -> updatedTrip.notes.map(s).getOrElse(AttributeValue.builder().nul(true).build()),
      ":zero" -> nInt(0), ":inc" -> nInt(1), ":expected" -> nInt(expectedVersion)
    ).asJava
    val sets = List(
      "#locations = :locations", "#startTime = :startTime", "#completionTime = :completionTime",
      "#status = :status", "#currentStop = :currentStop", "#driver = :driver", "#driverName = :driverName",
      "#driverPhotoUrl = :driverPhotoUrl", "#driverConfirmed = :driverConfirmed", "#car = :car",
      "#usergroups = :usergroups", "#users = :users", "#notes = :notes",
      "#version = if_not_exists(#version, :zero) + :inc"
    ).mkString(", ")

    val tripUpdate = Update.builder()
      .tableName(tripMetadataTable)
      .key(Map("tripArn" -> s(updatedTrip.tripArn)).asJava)
      .updateExpression(s"SET $sets")
      .conditionExpression("#version = :expected")
      .expressionAttributeNames(names)
      .expressionAttributeValues(values)
      .build()

    val userTrips = dao.listUsersByTrip(updatedTrip.tripArn, 100)
    val writes = new java.util.ArrayList[TransactWriteItem]()
    writes.add(TransactWriteItem.builder().update(tripUpdate).build())
    userTrips.foreach { ut =>
      val userId = if (ut.arn.contains(":")) ut.arn.drop(ut.arn.indexOf(':') + 1) else ut.arn
      val usk = s"$userId-completed"
      val utUpdate = Update.builder()
        .tableName(userTripsTable)
        .key(Map("arn" -> s(ut.arn)).asJava)
        .updateExpression("SET #tripStatus = :ts, #userStatusKey = :usk, #version = if_not_exists(#version, :zero) + :inc")
        .conditionExpression("#version = :expected")
        .expressionAttributeNames(Map("#tripStatus" -> "tripStatus", "#userStatusKey" -> "userStatusKey", "#version" -> "version").asJava)
        .expressionAttributeValues(Map(
          ":ts" -> s("Completed"),
          ":usk" -> s(usk),
          ":zero" -> nInt(0), ":inc" -> nInt(1), ":expected" -> nInt(ut.version)
        ).asJava)
        .build()
      writes.add(TransactWriteItem.builder().update(utUpdate).build())
    }
    val req = TransactWriteItemsRequest.builder().transactItems(writes).build()
    client.transactWriteItems(req)
  }
}
