package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils, TokenUtils, VersioningUtils, JwtUtils}
import com.coride.lambda.dao.UserGroupsDAO
import com.coride.tripdao.{TripDAO, TripMetadata}
import com.fasterxml.jackson.databind.ObjectMapper

object FlipLocationArrivalHandler {
  private val dao = TripDAO()
  private val groupsDAO = new UserGroupsDAO()
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    val tripArn = JsonUtils.require(node, "tripArn")
    val locationName = JsonUtils.require(node, "locationName")
    val arrived = Option(node.get("arrived")).exists(n => n != null && !n.isNull && n.asBoolean())

    val expected = VersioningUtils.tripExpectedVersion(event, dao, tripArn)
    val tmOpt = dao.getTripMetadata(tripArn)
    tmOpt match {
      case None => Responses.json(404, """{"error":"Trip not found"}""")
      case Some(tm) =>
        // Time gate: only allow if now >= startDate
        val now = System.currentTimeMillis()
        if (now < tm.startTime) {
          return Responses.json(403, """{"error":"Forbidden","message":"Trip not started"}""")
        }

        // Auth: only driver or a member of group correlated with the location
        val tokenOpt = TokenUtils.bearer(event.getHeaders)
        val userIdOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok).map(_.sub))

        userIdOpt match {
          case None => Responses.json(401, """{"error":"Unauthorized"}""")
          case Some(userId) =>
            val isDriver = tm.driver.contains(userId)
            val groups = groupsDAO.listUserGroupRecordsByTripArn(tripArn, 200)
            val inCorrelatedGroup = groups.exists { g =>
              (g.start == locationName || g.destination == locationName) && g.users.exists(_.userId == userId)
            }
            if (!isDriver && !inCorrelatedGroup) {
              return Responses.json(403, """{"error":"Forbidden","message":"Not allowed for this location"}""")
            }

            try {
              if (arrived) {
                val updatedLocationsWithArrival = tm.locations.map {
                  case l if l.locationName == locationName => l.copy(arrived = true, arrivedTime = Some(System.currentTimeMillis()))
                  case l => l
                }

                val (arrivedLocations, notArrivedLocations) = updatedLocationsWithArrival.partition(_.arrived)
                val sortedArrived = arrivedLocations.sortBy(_.arrivedTime.getOrElse(Long.MaxValue))
                val finalLocations = sortedArrived ++ notArrivedLocations

                var updatedTrip = tm.copy(locations = finalLocations, currentStop = Some(locationName))

                if (finalLocations.forall(_.arrived)) {
                  updatedTrip = updatedTrip.copy(status = "Completed", completionTime = Some(System.currentTimeMillis()))
                  dao.completeTripTransaction(updatedTrip, expected)
                } else {
                  dao.updateTripMetadata(updatedTrip, expected)
                }
                val body = GetUserTripsHandler.toJson(updatedTrip)
                Responses.json(200, mapper.writeValueAsString(body))
              } else {
                // For now, we only handle arriving at a location, not "un-arriving"
                Responses.json(400, """{"error":"Bad Request","message":"Only arriving at a location is supported"}""")
              }
            } catch {
              case _: Throwable => Responses.json(409, """{"error":"Version conflict"}""")
            }
        }
    }
  }
}