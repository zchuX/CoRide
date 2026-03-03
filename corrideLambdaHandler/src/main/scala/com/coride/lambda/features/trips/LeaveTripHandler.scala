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
        val groups = groupsDAO.listUserGroupRecordsByTripArn(tripArn, 200)
        val myGroupOpt = groups.find(g => g.users.exists(_.userId == userId))
        val isDriverOnly = tm.driver.contains(userId) && myGroupOpt.isEmpty

        if (myGroupOpt.isDefined) {
          // User is in a group: remove them from that group, or delete the group if they are the last member
          val g = myGroupOpt.get
          val expectedTrip = VersioningUtils.tripExpectedVersion(event, dao, tripArn)
          val expectedGroup = VersioningUtils.groupExpectedVersion(event, dao, g.arn)
          val updated = g.copy(users = g.users.filterNot(_.userId == userId))
          val isLastInGroup = updated.users.isEmpty && g.numAnonymousUsers == 0
          try {
            if (isLastInGroup) {
              // Last user (and no anonymous): delete the user group and remove it from trip metadata
              dao.removeUserGroup(g.arn, expectedTrip, expectedGroup)
            } else {
              dao.updateUserGroup(g.arn, expectedGroup, expectedTrip, None, None, None, None, Some(updated.users), None)
            }
            val remainingGroups = groupsDAO.listUserGroupRecordsByTripArn(tripArn, 200)
            if (!remainingGroups.exists(_.users.nonEmpty)) dao.deleteTrip(tripArn)
            Responses.json(200, """{"message":"Successfully left trip"}""")
          } catch {
            case _: Throwable => Responses.json(409, """{"error":"Version conflict"}""")
          }
        } else if (isDriverOnly) {
          // User is the driver and not in any group: clear driver and remove their UserTrip
          val expectedTrip = VersioningUtils.tripExpectedVersion(event, dao, tripArn)
          try {
            val updatedTm = tm.copy(driver = None, driverName = None, driverPhotoUrl = None, driverConfirmed = Some(false))
            dao.updateTripMetadata(updatedTm, expectedTrip)
            dao.deleteUserTrip(dao.userTripArn(tripArn, userId))
            val remainingGroups = groupsDAO.listUserGroupRecordsByTripArn(tripArn, 200)
            if (!remainingGroups.exists(_.users.nonEmpty)) dao.deleteTrip(tripArn)
            Responses.json(200, """{"message":"Successfully left trip"}""")
          } catch {
            case _: Throwable => Responses.json(409, """{"error":"Version conflict"}""")
          }
        } else {
          Responses.json(404, """{"error":"Not Found","message":"User not in any group for this trip"}""")
        }
    }
  }
}