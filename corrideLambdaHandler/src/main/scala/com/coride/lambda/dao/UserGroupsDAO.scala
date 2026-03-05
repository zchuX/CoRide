package com.coride.lambda.dao

import com.coride.tripdao.{UserGroupRecord, GroupUser}
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{QueryRequest, AttributeValue}
import scala.jdk.CollectionConverters._

class UserGroupsDAO() {
  private val regionName: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userGroupsTable: String = Option(System.getenv("USERGROUPS_TABLE")).orElse(Option(System.getenv("USER_GROUPS_TABLE"))).getOrElse("")
  private lazy val ddb: DynamoDbClient = DynamoDbClient.builder().region(Region.of(regionName)).httpClient(UrlConnectionHttpClient.builder().build()).build()

  def listUserGroupRecordsByTripArn(tripArn: String, limit: Int = 100): List[UserGroupRecord] = {
    val names = Map("#tripArn" -> "tripArn").asJava
    val values = Map(":ta" -> AttributeValue.builder().s(tripArn).build()).asJava
    val req = QueryRequest.builder()
      .tableName(userGroupsTable)
      .indexName("gsiTripArn")
      .keyConditionExpression("#tripArn = :ta")
      .expressionAttributeNames(names)
      .expressionAttributeValues(values)
      .limit(limit)
      .scanIndexForward(true)
      .build()
    val res = ddb.query(req)
    Option(res.items()).map(_.asScala.toList).getOrElse(Nil).flatMap { it =>
      val m = it.asScala.toMap
      for {
        arnAttr <- m.get("groupArn"); arn <- Option(arnAttr.s())
        taAttr <- m.get("tripArn"); ta <- Option(taAttr.s())
        gnAttr <- m.get("groupName"); gn <- Option(gnAttr.s())
        sAttr <- m.get("start"); s <- Option(sAttr.s())
        dAttr <- m.get("destination"); d <- Option(dAttr.s())
        ptAttr <- m.get("pickupTime"); pt <- Option(ptAttr.n())
        vAttr <- m.get("version"); v <- Option(vAttr.n())
      } yield {
        val users: List[GroupUser] = m.get("users").flatMap(av => Option(av.l())).map(_.asScala.toList).map { lst =>
          val maps: List[Map[String, AttributeValue]] = lst.flatMap(av => Option(av.m())).map(_.asScala.toMap)
          maps.map { um =>
            val uid = um.get("userId").flatMap(a => Option(a.s())).getOrElse("")
            val name = um.get("name").flatMap(a => Option(a.s())).getOrElse("")
            val image = um.get("imageUrl").flatMap(a => Option(a.s()))
            val accept = um.get("accept").flatMap(a => Option(a.bool())).map(_.booleanValue()).getOrElse(false)
            GroupUser(uid, name, image, accept)
          }
        }.getOrElse(Nil)
        UserGroupRecord(arn = arn, tripArn = ta, groupName = gn, start = s, destination = d, pickupTime = pt.toLong, users = users, version = v.toInt)
      }
    }
  }
}