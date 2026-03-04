package com.coride.lambda.dao

import com.coride.lambda.features.garage.GarageCar
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import scala.jdk.CollectionConverters._

class GarageDAO(ddb: DynamoDbClient, tableName: String) {

  def this() = this(
    DynamoDbClient.builder()
      .region(Region.of(Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")))
      .httpClient(UrlConnectionHttpClient.builder().build())
      .build(),
    Option(System.getenv("GARAGE_TABLE")).getOrElse("")
  )

  def put(car: GarageCar): Unit = {
    val item = toItem(car)
    ddb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build())
  }

  def get(carArn: String): Option[GarageCar] = {
    val req = GetItemRequest.builder()
      .tableName(tableName)
      .key(Map("carArn" -> AttributeValue.builder().s(carArn).build()).asJava)
      .build()
    val res = ddb.getItem(req)
    Option(res.item()).filter(_.isEmpty == false).flatMap(fromItem)
  }

  def listByUserArn(userArn: String, limit: Int = 100): List[GarageCar] = {
    val req = QueryRequest.builder()
      .tableName(tableName)
      .indexName("gsiUserArn")
      .keyConditionExpression("#ua = :ua")
      .expressionAttributeNames(Map("#ua" -> "userArn").asJava)
      .expressionAttributeValues(Map(":ua" -> AttributeValue.builder().s(userArn).build()).asJava)
      .limit(limit)
      .build()
    val res = ddb.query(req)
    Option(res.items()).map(_.asScala.toList).getOrElse(Nil).flatMap(fromItem)
  }

  def update(car: GarageCar): Unit = {
    val item = toItem(car)
    ddb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build())
  }

  def delete(carArn: String): Unit = {
    ddb.deleteItem(DeleteItemRequest.builder()
      .tableName(tableName)
      .key(Map("carArn" -> AttributeValue.builder().s(carArn).build()).asJava)
      .build())
  }

  private def toItem(c: GarageCar): java.util.Map[String, AttributeValue] = {
    val m = new java.util.HashMap[String, AttributeValue]()
    m.put("carArn", AttributeValue.builder().s(c.carArn).build())
    m.put("userArn", AttributeValue.builder().s(c.userArn).build())
    c.make.foreach(v => m.put("make", AttributeValue.builder().s(v).build()))
    c.model.foreach(v => m.put("model", AttributeValue.builder().s(v).build()))
    c.color.foreach(v => m.put("color", AttributeValue.builder().s(v).build()))
    c.carPlate.foreach(v => m.put("carPlate", AttributeValue.builder().s(v).build()))
    c.stateRegistered.foreach(v => m.put("stateRegistered", AttributeValue.builder().s(v).build()))
    m
  }

  private def fromItem(item: java.util.Map[String, AttributeValue]): Option[GarageCar] = {
    val map = item.asScala.toMap
    for {
      arn <- map.get("carArn").flatMap(av => Option(av.s()))
      userArn <- map.get("userArn").flatMap(av => Option(av.s()))
    } yield GarageCar(
      carArn = arn,
      userArn = userArn,
      make = map.get("make").flatMap(av => Option(av.s())).filter(_.nonEmpty),
      model = map.get("model").flatMap(av => Option(av.s())).filter(_.nonEmpty),
      color = map.get("color").flatMap(av => Option(av.s())).filter(_.nonEmpty),
      carPlate = map.get("carPlate").flatMap(av => Option(av.s())).filter(_.nonEmpty),
      stateRegistered = map.get("stateRegistered").flatMap(av => Option(av.s())).filter(_.nonEmpty)
    )
  }
}
