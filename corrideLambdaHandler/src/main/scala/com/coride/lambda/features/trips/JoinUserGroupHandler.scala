package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils, TokenUtils, JwtUtils}
import com.coride.tripdao.{TripDAO, GroupUser}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode

object JoinUserGroupHandler {
  private val dao = TripDAO()
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    val tripArn = JsonUtils.require(node, "tripArn")
    val groupArn = JsonUtils.require(node, "groupArn")
    // Do not use body/VersioningUtils for join: we use the group we just read so the version is correct.

    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    val userIdOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok).map(_.sub))
    userIdOpt match {
      case None => Responses.json(401, """{"error":"Unauthorized"}""")
      case Some(userId) =>
        dao.getUserGroup(groupArn) match {
          case None => return Responses.json(404, """{"error":"Group not found"}""")
          case Some(current) =>
            // Validation: cannot join if already a member
            if (current.users.exists(_.userId == userId)) {
              return Responses.json(409, """{"error":"Already member of this group"}""")
            }

            val gu = GroupUser(userId = userId, name = Option(node.get("name")).map(_.asText()).getOrElse(""), imageUrl = Option(node.get("imageUrl")).filter(n => n != null && !n.isNull).map(_.asText()), accept = true)
            val expectedTrip = dao.getTripMetadata(current.tripArn).getOrElse(return Responses.json(404, """{"error":"Trip not found"}"""))
            try {
              dao.updateUserGroup(groupArn, current.version, expectedTrip.version, None, None, None, None, Some(current.users :+ gu))
            } catch {
              case _: Throwable => return Responses.json(409, """{"error":"Version conflict"}""")
            }
            val updatedGroup = dao.getUserGroup(groupArn)
            updatedGroup match {
              case None => Responses.json(500, """{"error":"Group retrieval failed"}""")
              case Some(g) => Responses.json(200, mapper.writeValueAsString(groupToJson(g)))
            }
        }
    }
  }

  private def groupToJson(g: com.coride.tripdao.UserGroupRecord): com.fasterxml.jackson.databind.node.ObjectNode = {
    val node = mapper.createObjectNode()
    node.put("arn", g.arn)
    node.put("tripArn", g.tripArn)
    node.put("groupName", g.groupName)
    node.put("start", g.start)
    node.put("destination", g.destination)
    node.put("pickupTime", g.pickupTime)
    val usersNode = mapper.createArrayNode()
    g.users.foreach {
      user => usersNode.add(mapper.valueToTree[JsonNode](user))
    }
    node.set("users", usersNode)
    node.put("version", g.version)
    node
  }
}