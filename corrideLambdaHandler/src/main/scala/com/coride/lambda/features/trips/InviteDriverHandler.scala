package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils, VersioningUtils, TokenUtils, JwtUtils}
import com.coride.lambda.dao.UserGroupsDAO
import com.coride.tripdao.{TripDAO, UserTrip}
import com.coride.userdao.UserDAO
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Invites a user to be the driver: sets driver (unconfirmed) on trip metadata and creates
 * a UserTrip for them with status Invitation. Caller must be the current confirmed driver
 * or a group member. Rejects if trip already has a confirmed driver.
 */
object InviteDriverHandler {
  private val dao = TripDAO()
  private val userDao = UserDAO()
  private val groupsDAO = new UserGroupsDAO()
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent, tripArn: String): APIGatewayProxyResponseEvent = {
    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    val callerIdOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok).map(_.sub))
    if (callerIdOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized"}""")
    val callerId = callerIdOpt.get

    val node = Option(event.getBody).filter(_.nonEmpty).map(JsonUtils.parse).getOrElse(JsonUtils.parse("{}"))
    val driverUserId = Option(node.get("driver")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty)
      .orElse(Option(node.get("driverUserId")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty))
    if (driverUserId.isEmpty) return Responses.json(400, """{"error":"Bad Request","message":"driver or driverUserId required"}""")
    val driverId = driverUserId.get

    val tmOpt = dao.getTripMetadata(tripArn)
    tmOpt match {
      case None => Responses.json(404, """{"error":"Trip not found"}""")
      case Some(tm) =>
        if (tm.driverConfirmed.contains(true))
          return Responses.json(400, """{"error":"Bad Request","message":"Trip already has a confirmed driver"}""")

        val groups = groupsDAO.listUserGroupRecordsByTripArn(tripArn, 200)
        val isConfirmedDriver = tm.driver.contains(callerId) && tm.driverConfirmed.contains(true)
        val isGroupMember = groups.exists(_.users.exists(_.userId == callerId))
        if (!isConfirmedDriver && !isGroupMember)
          return Responses.json(403, """{"error":"Forbidden","message":"Only the confirmed driver or a group member may invite a driver"}""")

        val driverUser = userDao.getUser(driverId)
        val start = tm.locations.headOption.map(_.locationName).getOrElse("")
        val destination = tm.locations.lastOption.map(_.locationName).getOrElse("")
        val expected = VersioningUtils.tripExpectedVersion(event, dao, tripArn)

        try {
          if (tm.driver.isDefined && tm.driver.get != driverId) {
            dao.deleteUserTrip(dao.userTripArn(tripArn, tm.driver.get))
          }
          val updatedTm = tm.copy(
            driver = Some(driverId),
            driverName = driverUser.map(_.name),
            driverPhotoUrl = driverUser.flatMap(_.photoUrl),
            driverConfirmed = Some(false)
          )
          dao.updateTripMetadata(updatedTm, expected)
          val driverTrip = UserTrip(
            arn = dao.userTripArn(tripArn, driverId),
            tripArn = tripArn,
            userStatusKey = s"$driverId-uncompleted",
            tripDateTime = tm.startTime,
            tripStatus = "Invitation",
            start = start,
            destination = destination,
            departureDateTime = tm.startTime,
            isDriver = true,
            driverConfirmed = false,
            version = 1
          )
          dao.putUserTrip(driverTrip)
          Responses.json(200, mapper.writeValueAsString(GetUserTripsHandler.toJson(updatedTm)))
        } catch {
          case _: Throwable => Responses.json(409, """{"error":"Version conflict"}""")
        }
    }
  }
}
