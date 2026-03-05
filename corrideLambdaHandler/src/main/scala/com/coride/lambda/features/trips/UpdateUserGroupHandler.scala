package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils, VersioningUtils, TokenUtils, JwtUtils}
import com.coride.tripdao.{TripDAO, UserGroupRecord, GroupUser}
import com.fasterxml.jackson.databind.ObjectMapper

object UpdateUserGroupHandler {
  private val dao = TripDAO()
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    val groupArn = JsonUtils.require(node, "groupArn")

    // Authenticate and resolve acting user
    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    val userIdOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok).map(_.sub))
    if (userIdOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized"}""")
    val actingUserId = userIdOpt.get

    val current = dao.getUserGroup(groupArn)
    current match {
      case None => Responses.json(404, """{"error":"Group not found"}""")
      case Some(g) =>
        // Allow group members or the trip driver to modify the group
        val isMember = g.users.exists(_.userId == actingUserId)
        val tripOpt = dao.getTripMetadata(g.tripArn)
        val isDriver = tripOpt.exists(_.driver.contains(actingUserId))
        if (!isMember && !isDriver) return Responses.json(403, """{"error":"Forbidden","message":"Not a member of this userGroup"}""")

        val groupName = Option(node.get("groupName")).map(_.asText())
        val start = Option(node.get("start")).map(_.asText())
        val destination = Option(node.get("destination")).map(_.asText())
        val pickupTime = Option(node.get("pickupTime")).map(_.asLong())
        val users = Option(node.get("users")).filter(n => n != null && n.isArray).map { arr =>
          val it = arr.elements()
          val buff = scala.collection.mutable.ListBuffer[GroupUser]()
          while (it.hasNext) {
            val un = it.next()
            buff += GroupUser(
              userId = un.get("userId").asText(),
              name = un.get("name").asText(),
              imageUrl = Option(un.get("imageUrl")).map(_.asText()),
              accept = Option(un.get("accept")).map(_.asBoolean()).getOrElse(false)
            )
          }
          buff.toList
        }
        val newUsersList = users.getOrElse(g.users)
        if (tripOpt.isDefined) {
          val otherGroups = dao.listUserGroupRecordsByTripArn(g.tripArn).filter(_.arn != groupArn)
          val syntheticGroups = otherGroups :+ g.copy(users = newUsersList)
          TripValidation.validateNoDuplicateUsersInTrip(tripOpt.get.driver, syntheticGroups).foreach { msg =>
            val escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"")
            return Responses.json(400, s"""{"error":"Bad Request","message":"$escaped"}""")
          }
        }

        val expectedTrip = VersioningUtils.tripExpectedVersion(event, dao, g.tripArn)
        val expectedGroup = VersioningUtils.groupExpectedVersion(event, dao, groupArn)

        try {
          dao.updateUserGroup(groupArn, expectedGroup, expectedTrip, groupName, start, destination, pickupTime, users)
          val updatedGroup = dao.getUserGroup(groupArn).get
          Responses.json(200, mapper.writeValueAsString(GetUserTripsHandler.groupToJson(updatedGroup)))
        } catch {
          case e: IllegalArgumentException =>
            val msg = Option(e.getMessage).getOrElse("Invalid request").replace("\\", "\\\\").replace("\"", "\\\"")
            Responses.json(400, s"""{"error":"Bad Request","message":"$msg"}""")
          case _: Throwable => Responses.json(409, """{"error":"Version conflict"}""")
        }
    }
  }
}