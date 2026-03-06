package com.coride.tripdao

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import scala.jdk.CollectionConverters._
import Attrs._
import ModelCodec._

/** DAO facade needs only updateTripStatus for setCurrentStopAndSyncStatuses. */
trait TripDAOUpdateTripStatus {
  def updateTripStatus(tripArn: String, status: String, expectedVersion: Int, completionTime: Option[Long] = None, currentStop: Option[String] = None): Unit
}

class UserTripOps(client: DynamoDbClient, userTripsTable: String, tripMeta: TripDAOUpdateTripStatus) {

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
    Option(res.item()).map(_.asScala).flatMap(m => parseUserTrip(m))
  }

  def parseUserTrip(attrs: scala.collection.mutable.Map[String, AttributeValue]): Option[UserTrip] = {
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
      val tripArn = getS("tripArn").getOrElse(arn.split(":", 2).head)
      val userGroupArn = getS("userGroupArn").filter(_.nonEmpty)
      UserTrip(arn, tripArn, userStatusKey, tripDateTime, tripStatus, start, destination, departureDateTime, isDriver, driverConfirmed, version, userGroupArn)
    }
  }

  def queryUserTripsByStatus(userId: String, tripStatus: String, fromDateTime: Option[Long] = None, limit: Int = 50, ascending: Boolean = false): List[UserTrip] = {
    val usk = s"${userId}-${tripStatus}"
    val names = if (fromDateTime.isDefined) Map("#usk" -> "userStatusKey", "#dt" -> "tripDateTime").asJava else Map("#usk" -> "userStatusKey").asJava
    val valuesBase = new java.util.HashMap[String, AttributeValue]()
    valuesBase.put(":usk", s(usk))
    val kce = new StringBuilder("#usk = :usk")
    fromDateTime.foreach { dt => valuesBase.put(":from", n(dt)); kce.append(" AND #dt >= :from") }

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
    val items = Option(res.items()).map(_.asScala.toList).getOrElse(Nil)
    items.flatMap(it => parseUserTrip(it.asScala))
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

  def updateUserTripGroupArn(arn: String, groupArn: String): Unit = {
    val req = UpdateItemRequest.builder()
      .tableName(userTripsTable)
      .key(Map("arn" -> s(arn)).asJava)
      .updateExpression("SET #g = :g")
      .expressionAttributeNames(Map("#g" -> "userGroupArn").asJava)
      .expressionAttributeValues(Map(":g" -> s(groupArn)).asJava)
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

  /** Scan all UserTrip items (paginated) and call process for each. Used for backfill. */
  def processAllUserTrips(process: UserTrip => Unit): Unit = {
    var lastKey: Option[java.util.Map[String, AttributeValue]] = None
    do {
      val reqBuilder = ScanRequest.builder().tableName(userTripsTable)
      lastKey.foreach(reqBuilder.exclusiveStartKey)
      val res = client.scan(reqBuilder.build())
      res.items().asScala.foreach(it => parseUserTrip(it.asScala.to(scala.collection.mutable.Map)).foreach(process))
      lastKey = Option(res.lastEvaluatedKey()).filter(m => m != null && !m.isEmpty())
    } while (lastKey.isDefined)
  }

  def setUserTripStatusesForTrip(tripArn: String, newStatus: String): Unit = {
    val userTrips = listUsersByTrip(tripArn)
    userTrips.foreach { ut => updateUserTripStatus(ut.arn, newStatus, ut.version) }
  }

  def setCurrentStopAndSyncStatuses(tripArn: String, currentStop: String, expectedTripVersion: Int): Unit = {
    tripMeta.updateTripStatus(tripArn, "InProgress", expectedTripVersion, currentStop = Some(currentStop))
    val userTrips = listUsersByTrip(tripArn)
    userTrips.foreach { ut =>
      if (ut.tripStatus == "Upcoming") updateUserTripStatus(ut.arn, "InProgress", ut.version)
    }
  }
}
