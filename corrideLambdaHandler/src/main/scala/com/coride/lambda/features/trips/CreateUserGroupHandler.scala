package com.coride.lambda.features.trips

import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils, VersioningUtils, TokenUtils, JwtUtils}
import com.coride.tripdao.{TripDAO, UserGroupRecord, GroupUser}
import com.fasterxml.jackson.databind.ObjectMapper

object CreateUserGroupHandler {
  private val dao = TripDAO()
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  private def generateGroupArn(): String = s"group:${UUID.randomUUID().toString.replace("-", "").toLowerCase}"

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    // Require authenticated user
    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    val userIdOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok).map(_.sub))
    if (userIdOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized"}""")

    val node = JsonUtils.parse(event.getBody)
    val tripArn = JsonUtils.require(node, "tripArn")
    val groupArn = generateGroupArn()
    val groupName = JsonUtils.require(node, "groupName")
    val start = JsonUtils.require(node, "start")
    val destination = JsonUtils.require(node, "destination")
    val pickupTime = JsonUtils.require(node, "pickupTime").toLong

    val users = Option(node.get("users")).filter(n => n != null && n.isArray).map { arr =>
      val it = arr.elements()
      val buff = scala.collection.mutable.ListBuffer[GroupUser]()
      while (it.hasNext) {
        val un = it.next()
        buff += GroupUser(
          userId = un.get("userId").asText(),
          name = un.get("name").asText(),
          imageUrl = Option(un.get("imageUrl")).filter(nn => nn != null && !nn.isNull).map(_.asText()),
          accept = Option(un.get("accept")).map(_.asBoolean()).getOrElse(false)
        )
      }
      buff.toList
    }.getOrElse(Nil)

    val rec = UserGroupRecord(
      arn = groupArn,
      tripArn = tripArn,
      groupName = groupName,
      start = start,
      destination = destination,
      pickupTime = pickupTime,
      users = users,
      version = 1
    )

    val tripOpt = dao.getTripMetadata(tripArn)
    if (tripOpt.isEmpty) return Responses.json(404, """{"error":"Trip not found"}""")
    val existingGroups = dao.listUserGroupRecordsByTripArn(tripArn)
    val allGroups = existingGroups :+ rec
    TripValidation.validateNoDuplicateUsersInTrip(tripOpt.get.driver, allGroups).foreach { msg =>
      val escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"")
      return Responses.json(400, s"""{"error":"Bad Request","message":"$escaped"}""")
    }

    val expectedTrip = VersioningUtils.tripExpectedVersion(event, dao, tripArn)
    try {
      dao.addUserGroup(tripArn, rec, expectedTrip)
      Responses.json(200, mapper.writeValueAsString(GetUserTripsHandler.groupToJson(rec)))
    } catch {
      case _: Throwable => Responses.json(409, """{"error":"Group already exists or version conflict"}""")
    }
  }
}
