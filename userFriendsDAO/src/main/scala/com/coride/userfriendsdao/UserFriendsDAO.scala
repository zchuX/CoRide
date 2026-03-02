package com.coride.userfriendsdao

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._
import scala.jdk.CollectionConverters._
import UserFriendsDAOKeys._

object UserFriendsDAO {
  def apply(): UserFriendsDAO = {
    val regionName = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
    val ddb = DynamoDbClient.builder()
      .region(software.amazon.awssdk.regions.Region.of(regionName))
      .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder().build())
      .build()
    val tableName = Option(System.getenv("USER_FRIENDS_TABLE")).getOrElse("")
    new UserFriendsDAO(ddb, tableName)
  }
}

/**
 * Single-table design: PK = userArn, SK = PROFILE | FRIEND#<friendUserArn>.
 * One profile item per user; each accepted friendship is stored under both users' partitions
 * with denormalized friend userArn and name for efficient listing without joins.
 */
class UserFriendsDAO(ddb: DynamoDbClient, tableName: String) {

  def getProfile(userArn: String): Option[UserFriendProfile] = {
    val req = GetItemRequest.builder()
      .tableName(tableName)
      .key(Map(
        "userArn" -> AttributeValue.builder().s(userArn).build(),
        "sk" -> AttributeValue.builder().s(SkProfile).build()
      ).asJava)
      .build()
    val res = ddb.getItem(req)
    if (res.hasItem) Some(profileFromItem(res.item())) else None
  }

  def putProfile(profile: UserFriendProfile): Unit = {
    val item = Map(
      "userArn" -> AttributeValue.builder().s(profile.userArn).build(),
      "sk" -> AttributeValue.builder().s(SkProfile).build(),
      "name" -> AttributeValue.builder().s(profile.name).build()
    ).asJava
    ddb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build())
  }

  /** List accepted friends for a user (single Query, no GSI). Items are denormalized with friendUserArn and friendName. */
  def listFriends(userArn: String, limit: Int = 100): List[FriendRecord] = {
    val req = QueryRequest.builder()
      .tableName(tableName)
      .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
      .expressionAttributeNames(Map("#pk" -> "userArn", "#sk" -> "sk").asJava)
      .expressionAttributeValues(Map(
        ":pk" -> AttributeValue.builder().s(userArn).build(),
        ":prefix" -> AttributeValue.builder().s("FRIEND#").build()
      ).asJava)
      .limit(limit)
      .build()
    val res = ddb.query(req)
    Option(res.items()).map(_.asScala.toList).getOrElse(Nil).flatMap(friendRecordFromItem)
  }

  /** Add accepted friendship: write two records (one under each user's partition) with denormalized name. */
  def addFriendship(userArnA: String, nameA: String, userArnB: String, nameB: String): Unit = {
    val now = System.currentTimeMillis()
    val items = java.util.List.of(
      TransactWriteItem.builder()
        .put(Put.builder()
          .tableName(tableName)
          .item(friendRecordToItem(userArnA, skFriend(userArnB), userArnB, nameB, now))
          .build())
        .build(),
      TransactWriteItem.builder()
        .put(Put.builder()
          .tableName(tableName)
          .item(friendRecordToItem(userArnB, skFriend(userArnA), userArnA, nameA, now))
          .build())
        .build()
    )
    ddb.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(items).build())
  }

  /** Remove friendship: delete both records. */
  def removeFriendship(userArnA: String, userArnB: String): Unit = {
    val items = java.util.List.of(
      TransactWriteItem.builder()
        .delete(Delete.builder()
          .tableName(tableName)
          .key(Map(
            "userArn" -> AttributeValue.builder().s(userArnA).build(),
            "sk" -> AttributeValue.builder().s(skFriend(userArnB)).build()
          ).asJava)
          .build())
        .build(),
      TransactWriteItem.builder()
        .delete(Delete.builder()
          .tableName(tableName)
          .key(Map(
            "userArn" -> AttributeValue.builder().s(userArnB).build(),
            "sk" -> AttributeValue.builder().s(skFriend(userArnA)).build()
          ).asJava)
          .build())
        .build()
    )
    ddb.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(items).build())
  }

  /** Check if a friendship exists (either direction). */
  def areFriends(userArnA: String, userArnB: String): Boolean = {
    val req = GetItemRequest.builder()
      .tableName(tableName)
      .key(Map(
        "userArn" -> AttributeValue.builder().s(userArnA).build(),
        "sk" -> AttributeValue.builder().s(skFriend(userArnB)).build()
      ).asJava)
      .build()
    ddb.getItem(req).hasItem
  }

  private def profileFromItem(m: java.util.Map[String, AttributeValue]): UserFriendProfile = {
    def getS(name: String): String = Option(m.get(name)).flatMap(av => Option(av.s())).getOrElse("")
    UserFriendProfile(userArn = getS("userArn"), name = getS("name"))
  }

  private def friendRecordToItem(partitionUserArn: String, sk: String, friendUserArn: String, friendName: String, createdAt: Long): java.util.Map[String, AttributeValue] =
    Map(
      "userArn" -> AttributeValue.builder().s(partitionUserArn).build(),
      "sk" -> AttributeValue.builder().s(sk).build(),
      "friendUserArn" -> AttributeValue.builder().s(friendUserArn).build(),
      "friendName" -> AttributeValue.builder().s(friendName).build(),
      "createdAt" -> AttributeValue.builder().n(createdAt.toString).build()
    ).asJava

  private def friendRecordFromItem(m: java.util.Map[String, AttributeValue]): Option[FriendRecord] = {
    def getS(name: String): Option[String] = Option(m.get(name)).flatMap(av => Option(av.s())).filter(_.nonEmpty)
    def getN(name: String): Long = Option(m.get(name)).flatMap(av => Option(av.n())).map(_.toLong).getOrElse(0L)
    for {
      arn <- getS("friendUserArn")
      name <- getS("friendName")
    } yield FriendRecord(friendUserArn = arn, friendName = name, createdAt = getN("createdAt"))
  }
}
