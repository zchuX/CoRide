package com.coride.ratelimitdao

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import scala.jdk.CollectionConverters._

final case class RateLimitDecision(allowed: Boolean, retryAfterSeconds: Int)
final case class RateLimitItem(count: Int, ttl: Long)

class RateLimitDAO(
  private val client: DynamoDbClient = {
    val regionName: String = Option(System.getenv("AWS_REGION")).getOrElse("us-west-2")
    DynamoDbClient.builder()
      .region(Region.of(regionName))
      .httpClient(UrlConnectionHttpClient.builder().build())
      .build()
  },
  private val tableName: String = Option(System.getenv("RATE_LIMIT_TABLE")).getOrElse("")
) {

  def checkAndIncrement(key: String, windowSeconds: Int, maxCount: Int): RateLimitDecision = {
    val now = (System.currentTimeMillis() / 1000L).toLong

    val keyMap = Map("key" -> AttributeValue.builder().s(key).build()).asJava

    val updateReq = UpdateItemRequest.builder()
      .tableName(tableName)
      .key(keyMap)
      .expressionAttributeNames(Map(
        "#count" -> "count",
        "#ttl" -> "ttl"
      ).asJava)
      .expressionAttributeValues(Map(
        ":inc" -> AttributeValue.builder().n("1").build(),
        ":now" -> AttributeValue.builder().n(now.toString).build()
      ).asJava)
      .updateExpression("ADD #count :inc")
      .conditionExpression("attribute_exists(#ttl) AND #ttl > :now")
      .returnValues(ReturnValue.ALL_NEW)
      .build()

    try {
      val res = client.updateItem(updateReq)
      val attrs = Option(res.attributes()).map(_.asScala).getOrElse(scala.collection.mutable.Map.empty[String, AttributeValue])
      val count = attrs.get("count").flatMap(a => Option(a.n())).map(_.toInt).getOrElse(1)
      if (count > maxCount) {
        val ttl = attrs.get("ttl").flatMap(a => Option(a.n())).map(_.toLong).getOrElse(now + windowSeconds)
        val retry = Math.max(0L, ttl - now).toInt
        RateLimitDecision(allowed = false, retryAfterSeconds = retry)
      } else {
        RateLimitDecision(allowed = true, retryAfterSeconds = 0)
      }
    } catch {
      case _: ConditionalCheckFailedException =>
        val putItem = Map(
          "key" -> AttributeValue.builder().s(key).build(),
          "count" -> AttributeValue.builder().n("1").build(),
          "ttl" -> AttributeValue.builder().n((now + windowSeconds).toString).build()
        ).asJava
        val putReq = PutItemRequest.builder()
          .tableName(tableName)
          .item(putItem)
          .build()
        client.putItem(putReq)
        RateLimitDecision(allowed = true, retryAfterSeconds = 0)
    }
  }

  /** Retrieve current item (count and ttl) if present */
  def get(key: String): Option[RateLimitItem] = {
    val keyMap = Map("key" -> AttributeValue.builder().s(key).build()).asJava
    val req = GetItemRequest.builder()
      .tableName(tableName)
      .key(keyMap)
      .consistentRead(true)
      .build()
    val res = client.getItem(req)
    val item = Option(res.item()).map(_.asScala)
    item.flatMap { attrs =>
      for {
        countAttr <- attrs.get("count")
        ttlAttr <- attrs.get("ttl")
        count <- Option(countAttr.n()).map(_.toInt)
        ttl <- Option(ttlAttr.n()).map(_.toLong)
      } yield RateLimitItem(count, ttl)
    }
  }

  /** Put or refresh an item with ttl window (count initialized to 1) */
  def putWithTtl(key: String, windowSeconds: Int): Unit = {
    val now = (System.currentTimeMillis() / 1000L).toLong
    val item = Map(
      "key" -> AttributeValue.builder().s(key).build(),
      "count" -> AttributeValue.builder().n("1").build(),
      "ttl" -> AttributeValue.builder().n((now + windowSeconds).toString).build()
    ).asJava
    val req = PutItemRequest.builder().tableName(tableName).item(item).build()
    client.putItem(req)
  }

  /** Delete an item by key */
  def delete(key: String): Unit = {
    val keyMap = Map("key" -> AttributeValue.builder().s(key).build()).asJava
    val req = DeleteItemRequest.builder().tableName(tableName).key(keyMap).build()
    client.deleteItem(req)
  }
}
