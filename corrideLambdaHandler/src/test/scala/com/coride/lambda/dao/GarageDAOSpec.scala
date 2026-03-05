package com.coride.lambda.dao

import java.util.Collections

import scala.jdk.CollectionConverters._

import com.coride.lambda.features.garage.GarageCar
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{mock, verify, when}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

class GarageDAOSpec extends AnyFunSuite with Matchers {

  test("get returns None when item missing") {
    val mockDdb = mock(classOf[DynamoDbClient])
    when(mockDdb.getItem(any(classOf[GetItemRequest]))).thenReturn(GetItemResponse.builder().build())

    val dao = new GarageDAO(mockDdb, "Garage")
    dao.get("car:abc-123") shouldBe None
  }

  test("get returns Some(car) when item has required attributes") {
    val mockDdb = mock(classOf[DynamoDbClient])
    val item = Map(
      "carArn" -> AttributeValue.builder().s("car:abc-123").build(),
      "userArn" -> AttributeValue.builder().s("user-1").build()
    ).asJava
    when(mockDdb.getItem(any(classOf[GetItemRequest]))).thenReturn(GetItemResponse.builder().item(item).build())

    val dao = new GarageDAO(mockDdb, "Garage")
    val car = dao.get("car:abc-123")
    car shouldBe Some(GarageCar("car:abc-123", "user-1", None, None, None, None, None))
  }

  test("get returns Some(car) with optional fields when present") {
    val mockDdb = mock(classOf[DynamoDbClient])
    val item = Map(
      "carArn" -> AttributeValue.builder().s("car:xyz").build(),
      "userArn" -> AttributeValue.builder().s("user-2").build(),
      "make" -> AttributeValue.builder().s("Honda").build(),
      "model" -> AttributeValue.builder().s("Civic").build(),
      "color" -> AttributeValue.builder().s("Blue").build(),
      "carPlate" -> AttributeValue.builder().s("ABC-1234").build(),
      "stateRegistered" -> AttributeValue.builder().s("CA").build()
    ).asJava
    when(mockDdb.getItem(any(classOf[GetItemRequest]))).thenReturn(GetItemResponse.builder().item(item).build())

    val dao = new GarageDAO(mockDdb, "Garage")
    val car = dao.get("car:xyz")
    car shouldBe Some(GarageCar(
      "car:xyz", "user-2",
      make = Some("Honda"), model = Some("Civic"), color = Some("Blue"),
      carPlate = Some("ABC-1234"), stateRegistered = Some("CA")
    ))
  }

  test("listByUserArn returns empty when no items") {
    val mockDdb = mock(classOf[DynamoDbClient])
    when(mockDdb.query(any(classOf[QueryRequest]))).thenReturn(QueryResponse.builder().items(Collections.emptyList()).build())

    val dao = new GarageDAO(mockDdb, "Garage")
    dao.listByUserArn("user-1") shouldBe Nil
  }

  test("listByUserArn returns parsed cars when query returns items") {
    val mockDdb = mock(classOf[DynamoDbClient])
    val item = Map(
      "carArn" -> AttributeValue.builder().s("car:one").build(),
      "userArn" -> AttributeValue.builder().s("user-1").build(),
      "make" -> AttributeValue.builder().s("Toyota").build()
    ).asJava
    when(mockDdb.query(any(classOf[QueryRequest]))).thenReturn(
      QueryResponse.builder().items(java.util.List.of(item)).build()
    )

    val dao = new GarageDAO(mockDdb, "Garage")
    val cars = dao.listByUserArn("user-1", 50)
    cars should have size 1
    cars.head.carArn shouldBe "car:one"
    cars.head.userArn shouldBe "user-1"
    cars.head.make shouldBe Some("Toyota")
    cars.head.model shouldBe None
  }

  test("put calls putItem with correct table name") {
    val mockDdb = mock(classOf[DynamoDbClient])
    when(mockDdb.putItem(any(classOf[PutItemRequest]))).thenReturn(PutItemResponse.builder().build())

    val dao = new GarageDAO(mockDdb, "GarageTable")
    dao.put(GarageCar("car:new", "user-1", make = Some("Ford")))

    verify(mockDdb).putItem(any(classOf[PutItemRequest]))
  }

  test("update calls putItem") {
    val mockDdb = mock(classOf[DynamoDbClient])
    when(mockDdb.putItem(any(classOf[PutItemRequest]))).thenReturn(PutItemResponse.builder().build())

    val dao = new GarageDAO(mockDdb, "Garage")
    dao.update(GarageCar("car:existing", "user-1", model = Some("Accord")))

    verify(mockDdb).putItem(any(classOf[PutItemRequest]))
  }

  test("delete calls deleteItem with carArn key") {
    val mockDdb = mock(classOf[DynamoDbClient])
    when(mockDdb.deleteItem(any(classOf[DeleteItemRequest]))).thenReturn(DeleteItemResponse.builder().build())

    val dao = new GarageDAO(mockDdb, "Garage")
    dao.delete("car:to-delete")

    verify(mockDdb).deleteItem(any(classOf[DeleteItemRequest]))
  }
}
