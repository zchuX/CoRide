package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils, VersioningUtils, TokenUtils, JwtUtils}
import com.coride.tripdao.{TripDAO, UserTrip}
import com.coride.userdao.UserDAO
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Claims the driver role when the trip has no driver. Sets caller as driver (confirmed) on trip
 * metadata and creates the driver's UserTrip with status Upcoming if they don't have one.
 * For accepting an invitation use AcceptDriverInvitation instead.
 */
object BecomeDriverHandler {
  private val dao = TripDAO()
  private val userDao = UserDAO()
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    val tripArn = JsonUtils.require(node, "tripArn")
    val expected = VersioningUtils.tripExpectedVersion(event, dao, tripArn)

    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    val userIdOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok).map(_.sub))

    userIdOpt match {
      case None => Responses.json(401, """{"error":"Unauthorized"}""")
      case Some(userId) =>
        val current = dao.getTripMetadata(tripArn)
        current match {
          case None => Responses.json(404, """{"error":"Trip not found"}""")
          case Some(tm) =>
            if (tm.driver.isDefined)
              return Responses.json(400, """{"error":"Bad Request","message":"Trip already has a driver; use accept-driver-invitation if you were invited, or leave the driver role first"}""")
            val driverUser = userDao.getUser(userId)
            val updated = tm.copy(
              driver = Some(userId),
              driverName = driverUser.map(_.name),
              driverPhotoUrl = driverUser.flatMap(_.photoUrl),
              driverConfirmed = Some(true)
            )
            try {
              dao.updateTripMetadata(updated, expected)
              val utArn = dao.userTripArn(tripArn, userId)
              if (dao.getUserTrip(utArn).isEmpty) {
                val start = tm.locations.headOption.map(_.locationName).getOrElse("")
                val dest = tm.locations.lastOption.map(_.locationName).getOrElse("")
                val driverTrip = UserTrip(
                  arn = utArn,
                  tripArn = tripArn,
                  userStatusKey = s"$userId-uncompleted",
                  tripDateTime = tm.startTime,
                  tripStatus = "Upcoming",
                  start = start,
                  destination = dest,
                  departureDateTime = tm.startTime,
                  isDriver = true,
                  driverConfirmed = true,
                  version = 1
                )
                dao.putUserTrip(driverTrip)
              }
              Responses.json(200, mapper.writeValueAsString(GetUserTripsHandler.toJson(updated)))
            } catch {
              case _: Throwable => Responses.json(409, """{"error":"Version conflict"}""")
            }
        }
    }
  }
}