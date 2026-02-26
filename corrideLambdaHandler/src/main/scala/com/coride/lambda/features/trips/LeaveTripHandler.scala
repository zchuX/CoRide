package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, VersioningUtils, TokenUtils, JwtUtils}
import com.coride.tripdao.TripDAO
import com.coride.lambda.dao.UserGroupsDAO
import com.fasterxml.jackson.databind.ObjectMapper

object LeaveTripHandler {
  private val dao = TripDAO()
  private val mapper = new ObjectMapper()
  private val groupsDAO = new UserGroupsDAO()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent, tripArn: String): APIGatewayProxyResponseEvent = {
    // Authenticate acting user
    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    val userIdOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok).map(_.sub))
    if (userIdOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized"}""")
    val userId = userIdOpt.get

    // Fetch trip and basic state validation
    val tmOpt = dao.getTripMetadata(tripArn)
    tmOpt match {
      case None => Responses.json(404, """{"error":"Trip not found"}""")
      case Some(tm) =>
        // Find the group in this trip that includes the user
        val groups = groupsDAO.listUserGroupRecordsByTripArn(tripArn, 200)
        val myGroupOpt = groups.find(g => g.users.exists(_.userId == userId))
        myGroupOpt match {
          case None => Responses.json(404, """{"error":"Not Found","message":"User not in any group for this trip"}""")
          case Some(g) =>
            val expectedTrip = VersioningUtils.tripExpectedVersion(event, dao, tripArn)
            val expectedGroup = VersioningUtils.groupExpectedVersion(event, dao, g.arn)
            val updated = g.copy(users = g.users.filterNot(_.userId == userId))
            try {
              dao.updateUserGroup(g.arn, expectedGroup, expectedTrip, None, None, None, None, Some(updated.users), None)

              // After leaving, check if the trip should be cancelled
              val remainingGroups = groupsDAO.listUserGroupRecordsByTripArn(tripArn, 200)
              val registeredUsersLeft = remainingGroups.exists(_.users.nonEmpty)

              if (!registeredUsersLeft) {
                // Last registered user left, so cancel the trip
                dao.deleteTrip(tripArn)
              }

              Responses.json(200, """{"message":"Successfully left trip"}""")
            } catch {
              case _: Throwable => Responses.json(409, """{"error":"Version conflict"}""")
            }
        }
    }
  }
}