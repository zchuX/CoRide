package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, TokenUtils, JwtUtils}
import com.coride.tripdao.{TripDAO, TripMetadata, UserGroupRecord, GroupUser}
import com.coride.lambda.dao.UserGroupsDAO
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import scala.jdk.CollectionConverters._

object GetTripByIdHandler {
  private val dao = TripDAO()
  private val mapper = new ObjectMapper()
  private val groupsDAO = new UserGroupsDAO()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent, tripArn: String): APIGatewayProxyResponseEvent = {
    // Require authenticated user before retrieving trip details
    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    if (tokenOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized"}""")

    val tmOpt = dao.getTripMetadata(tripArn)
    tmOpt match {
      case None => Responses.json(404, """{"error":"Not Found"}""")
      case Some(tm) =>
        val bodyNode = GetUserTripsHandler.toJson(tm)
        // Determine current user's tripStatus if any
        val statusNode = mapper.createObjectNode()
        val userStatus = tokenOpt.flatMap { tok =>
          jwt.verifyIdToken(tok).flatMap { claims =>
            val userId = claims.sub
            val utArn = dao.userTripArn(tripArn, userId)
            dao.getUserTrip(utArn).map(_.tripStatus)
          }
        }
        userStatus match {
          case Some(s) => statusNode.put("userTripStatus", s)
          case None => statusNode.putNull("userTripStatus")
        }
        val root = mapper.createObjectNode()
        root.set("trip", bodyNode)
        root.set("status", statusNode)
        Responses.json(200, mapper.writeValueAsString(root))
    }
  }
  // Local GSI query helper to list user groups by tripArn
  private def listUserGroupRecordsByTripArn(tripArn: String, limit: Int = 100): List[UserGroupRecord] = groupsDAO.listUserGroupRecordsByTripArn(tripArn, limit)
}