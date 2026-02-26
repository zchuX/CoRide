package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils, VersioningUtils, TokenUtils, JwtUtils}
import com.coride.tripdao.{TripDAO, TripMetadata}
import com.fasterxml.jackson.databind.ObjectMapper

object StartTripHandler {
  private val tripDao = TripDAO()
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent, tripArn: String): APIGatewayProxyResponseEvent = {
    val expected = VersioningUtils.tripExpectedVersion(event, tripDao, tripArn)

    val current = tripDao.getTripMetadata(tripArn)
    current match {
      case None => Responses.json(404, """{"error":"Trip not found"}""")
      case Some(tm) =>
        // Authenticate and gate: only driver may start a trip
        val tokenOpt = TokenUtils.bearer(event.getHeaders)
        val userIdOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok).map(_.sub))
        if (userIdOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized"}""")
        val userId = userIdOpt.get
        if (!tm.driver.contains(userId)) {
          return Responses.json(403, """{"error":"Forbidden","message":"Only the driver can start the trip"}""")
        }

        if (tm.status != "Upcoming") {
          return Responses.json(400, """{"error":"Bad Request","message":"Trip has already started"}""")
        }

        var updatedTrip = tm.copy(status = "InProgress")

        tm.locations.headOption.foreach { firstLocation =>
          if (firstLocation.pickupGroups.isEmpty) {
            val updatedLocations = tm.locations.map(l => if (l.locationName == firstLocation.locationName) l.copy(arrived = true, arrivedTime = Some(System.currentTimeMillis())) else l)
            updatedTrip = updatedTrip.copy(locations = updatedLocations, currentStop = Some(firstLocation.locationName))
          }
        }

        try {
          tripDao.updateTripMetadata(updatedTrip, expected)
          val body = GetUserTripsHandler.toJson(updatedTrip)
          Responses.json(200, mapper.writeValueAsString(body))
        } catch {
          case _: Throwable => Responses.json(409, """{"error":"Version conflict"}""")
        }
    }
  }
}
