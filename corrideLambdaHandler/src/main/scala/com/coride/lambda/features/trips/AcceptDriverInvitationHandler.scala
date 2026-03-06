package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, VersioningUtils, TokenUtils, JwtUtils}
import com.coride.tripdao.{TripDAO, TripDAOLocationsHelper}
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Accepts the driver invitation: caller must be the trip's driver with driverConfirmed = false.
 * Sets driverConfirmed = true on trip metadata and updates the driver's UserTrip from
 * Invitation to Upcoming.
 */
object AcceptDriverInvitationHandler {
  private val dao = TripDAO()
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent, tripArn: String): APIGatewayProxyResponseEvent = {
    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    val userIdOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok).map(_.sub))
    if (userIdOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized"}""")
    val userId = userIdOpt.get

    val tmOpt = dao.getTripMetadata(tripArn)
    tmOpt match {
      case None => Responses.json(404, """{"error":"Trip not found"}""")
      case Some(tm) =>
        if (!tm.driver.contains(userId))
          return Responses.json(403, """{"error":"Forbidden","message":"You are not the invited driver for this trip"}""")
        if (tm.driverConfirmed.contains(true))
          return Responses.json(400, """{"error":"Bad Request","message":"Driver invitation already accepted"}""")

        val utArn = dao.userTripArn(tripArn, userId)
        val utOpt = dao.getUserTrip(utArn)
        if (utOpt.isEmpty)
          return Responses.json(404, """{"error":"Not Found","message":"Driver UserTrip not found"}""")
        if (!utOpt.get.tripStatus.equalsIgnoreCase("Invitation"))
          return Responses.json(400, """{"error":"Bad Request","message":"No driver invitation to accept"}""")

        val expectedTrip = VersioningUtils.tripExpectedVersion(event, dao, tripArn)
        try {
          val updatedTm = tm.copy(driverConfirmed = Some(true))
          dao.updateTripMetadata(updatedTm, expectedTrip)
          val ut = utOpt.get
          val newStatus = TripDAOLocationsHelper.deriveEffectiveStatus(tm)
          dao.updateUserTripStatus(utArn, newStatus, ut.version, driverConfirmed = Some(true))
          val refreshed = dao.getTripMetadata(tripArn).getOrElse(updatedTm)
          Responses.json(200, mapper.writeValueAsString(GetUserTripsHandler.toJson(refreshed)))
        } catch {
          case _: Throwable => Responses.json(409, """{"error":"Version conflict"}""")
        }
    }
  }
}
