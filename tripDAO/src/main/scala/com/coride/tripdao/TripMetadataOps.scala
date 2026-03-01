package com.coride.tripdao

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import scala.jdk.CollectionConverters._
import Attrs._
import ModelCodec._

class TripMetadataOps(client: DynamoDbClient, tripMetadataTable: String) {

  def getTripMetadata(tripArn: String): Option[TripMetadata] = {
    val req = GetItemRequest.builder()
      .tableName(tripMetadataTable)
      .key(Map("tripArn" -> s(tripArn)).asJava)
      .consistentRead(true)
      .build()
    val res = client.getItem(req)
    Option(res.item()).map(_.asScala).flatMap(item => parseTripMetadata(item.toMap))
  }

  def parseTripMetadata(item: Map[String, AttributeValue]): Option[TripMetadata] = {
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
          plannedTime = m.get("plannedTime").flatMap(a => Option(a.n())).map(_.toLong).getOrElse(0L),
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

  def putTripMetadata(t: TripMetadata): Unit = {
    val req = PutItemRequest.builder()
      .tableName(tripMetadataTable)
      .item(tripMetadataToItem(t))
      .build()
    client.putItem(req)
  }

  def updateTripMetadata(t: TripMetadata, expectedVersion: Int): Unit = {
    val names = Map(
      "#locations" -> "locations", "#startTime" -> "startTime", "#completionTime" -> "completionTime",
      "#status" -> "status", "#currentStop" -> "currentStop", "#driver" -> "driver", "#driverName" -> "driverName",
      "#driverPhotoUrl" -> "driverPhotoUrl", "#driverConfirmed" -> "driverConfirmed", "#car" -> "car",
      "#usergroups" -> "usergroups", "#users" -> "users", "#notes" -> "notes", "#version" -> "version"
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
      ":zero" -> nInt(0), ":inc" -> nInt(1), ":expected" -> nInt(expectedVersion)
    ).asJava
    val sets = List(
      "#locations = :locations", "#startTime = :startTime", "#completionTime = :completionTime",
      "#status = :status", "#currentStop = :currentStop", "#driver = :driver", "#driverName = :driverName",
      "#driverPhotoUrl = :driverPhotoUrl", "#driverConfirmed = :driverConfirmed", "#car = :car",
      "#usergroups = :usergroups", "#users = :users", "#notes = :notes",
      "#version = if_not_exists(#version, :zero) + :inc"
    ).mkString(", ")

    val req = UpdateItemRequest.builder()
      .tableName(tripMetadataTable)
      .key(Map("tripArn" -> s(t.tripArn)).asJava)
      .expressionAttributeNames(names)
      .expressionAttributeValues(values)
      .updateExpression(s"SET $sets")
      .conditionExpression("#version = :expected")
      .build()
    client.updateItem(req)
  }

  def updateTripStatus(tripArn: String, status: String, expectedVersion: Int, completionTime: Option[Long] = None, currentStop: Option[String] = None): Unit = {
    val names = scala.collection.mutable.Map[String, String]("#status" -> "status", "#version" -> "version")
    val values = scala.collection.mutable.Map[String, AttributeValue](":status" -> s(status), ":zero" -> nInt(0), ":inc" -> nInt(1), ":expected" -> nInt(expectedVersion))
    val sets = scala.collection.mutable.ListBuffer[String]("#status = :status", "#version = if_not_exists(#version, :zero) + :inc")
    completionTime.foreach { ct => names += ("#completionTime" -> "completionTime"); values += (":ct" -> n(ct)); sets += "#completionTime = :ct" }
    currentStop.foreach { cs => names += ("#currentStop" -> "currentStop"); values += (":cs" -> s(cs)); sets += "#currentStop = :cs" }

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
        ":zero" -> nInt(0), ":inc" -> nInt(1), ":expected" -> nInt(expectedVersion)
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
      .expressionAttributeNames(Map("#status" -> "status", "#completionTime" -> "completionTime", "#users" -> "users", "#version" -> "version").asJava)
      .expressionAttributeValues(Map(
        ":status" -> s("Completed"),
        ":ct" -> n(completionTime),
        ":users" -> Attrs.list(users.map(ModelCodec.tripUserToAttr)),
        ":zero" -> nInt(0), ":inc" -> nInt(1), ":expected" -> nInt(expectedVersion)
      ).asJava)
      .updateExpression("SET #status = :status, #completionTime = :ct, #users = :users, #version = if_not_exists(#version, :zero) + :inc")
      .conditionExpression("#version = :expected")
      .build()
    client.updateItem(req)
  }
}
