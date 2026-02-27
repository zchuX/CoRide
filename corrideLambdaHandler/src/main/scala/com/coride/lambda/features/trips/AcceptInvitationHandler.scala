package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, TokenUtils, JwtUtils}
import com.coride.tripdao.TripDAO
import com.fasterxml.jackson.databind.ObjectMapper

object AcceptInvitationHandler {
  private val dao = TripDAO()
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent, groupArn: String): APIGatewayProxyResponseEvent = {
    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    val userIdOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok).map(_.sub))
    if (userIdOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized"}""")
    val userId = userIdOpt.get

    dao.getUserGroup(groupArn) match {
      case None => Responses.json(404, """{"error":"Group not found"}""")
      case Some(g) =>
        val userInGroup = g.users.find(_.userId == userId)
        if (userInGroup.isEmpty) {
          return Responses.json(403, """{"error":"Forbidden","message":"User is not in this group"}""")
        }
        if (userInGroup.get.accept) {
          return Responses.json(400, """{"error":"Bad Request","message":"Invitation already accepted"}""")
        }

        val utArn = dao.userTripArn(g.tripArn, userId)
        dao.getUserTrip(utArn) match {
          case Some(ut) if ut.tripStatus.equalsIgnoreCase("Invitation") =>
            try {
              dao.acceptUserInvitation(g.tripArn, g.arn, userId, g.version)
              val updatedGroup = dao.getUserGroup(groupArn).get
              Responses.json(200, mapper.writeValueAsString(GetUserTripsHandler.groupToJson(updatedGroup)))
            } catch {
              case _: Throwable => Responses.json(409, """{"error":"Version conflict"}""")
            }
          case _ =>
            Responses.json(403, """{"error":"Forbidden","message":"No Invitation userTrip found to accept"}""")
        }
    }
  }
}
