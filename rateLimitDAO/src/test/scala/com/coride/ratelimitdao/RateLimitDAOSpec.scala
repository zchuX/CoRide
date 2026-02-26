package com.coride.ratelimitdao

import org.scalatest.funsuite.AnyFunSuite
import org.mockito.Mockito.{mock, when, verify}
import org.mockito.ArgumentMatchers.any
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, UpdateItemResponse, ConditionalCheckFailedException, PutItemResponse}
import scala.jdk.CollectionConverters._

class RateLimitDAOSpec extends AnyFunSuite {
  test("checkAndIncrement allows when count <= maxCount") {
    val mockClient = mock(classOf[DynamoDbClient])
    val now = (System.currentTimeMillis() / 1000L).toLong
    val attrs = Map(
      "count" -> AttributeValue.builder().n("3").build(),
      "ttl" -> AttributeValue.builder().n((now + 60).toString).build()
    ).asJava
    val resp = UpdateItemResponse.builder().attributes(attrs).build()
    when(mockClient.updateItem(any(classOf[software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest]))).thenReturn(resp)

    val dao = new RateLimitDAO(mockClient, "RateLimit")
    val decision = dao.checkAndIncrement("k", 60, 5)
    assert(decision.allowed)
    assert(decision.retryAfterSeconds == 0)
  }

  test("checkAndIncrement denies when count > maxCount and computes retry") {
    val mockClient = mock(classOf[DynamoDbClient])
    val now = (System.currentTimeMillis() / 1000L).toLong
    val ttl = now + 30
    val attrs = Map(
      "count" -> AttributeValue.builder().n("6").build(),
      "ttl" -> AttributeValue.builder().n(ttl.toString).build()
    ).asJava
    val resp = UpdateItemResponse.builder().attributes(attrs).build()
    when(mockClient.updateItem(any(classOf[software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest]))).thenReturn(resp)

    val dao = new RateLimitDAO(mockClient, "RateLimit")
    val decision = dao.checkAndIncrement("k", 60, 5)
    assert(!decision.allowed)
    assert(decision.retryAfterSeconds >= 0 && decision.retryAfterSeconds <= 60)
  }

  test("checkAndIncrement initializes item on missing/expired window") {
    val mockClient = mock(classOf[DynamoDbClient])
    when(mockClient.updateItem(any(classOf[software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest]))).
      thenThrow(ConditionalCheckFailedException.builder().message("expired").build())
    when(mockClient.putItem(any(classOf[software.amazon.awssdk.services.dynamodb.model.PutItemRequest]))).
      thenReturn(PutItemResponse.builder().build())

    val dao = new RateLimitDAO(mockClient, "RateLimit")
    val decision = dao.checkAndIncrement("k", 60, 5)
    assert(decision.allowed)
    verify(mockClient).putItem(any(classOf[software.amazon.awssdk.services.dynamodb.model.PutItemRequest]))
  }
}