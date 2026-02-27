package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils, VersioningUtils, TokenUtils, JwtUtils}
import com.coride.tripdao.TripDAO
import com.coride.userdao.UserDAO
import com.fasterxml.jackson.databind.ObjectMapper

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
            // driverConfirmed is true when the user has accepted (BecomeDriver). It is only false when driver is None or an invitation was sent but not yet accepted.
            val driverUser = userDao.getUser(userId)
            val updated = tm.copy(
              driver = Some(userId),
              driverName = driverUser.map(_.name),
              driverPhotoUrl = driverUser.flatMap(_.photoUrl),
              driverConfirmed = Some(true)
            )
            try {
              dao.updateTripMetadata(updated, expected)
              Responses.json(200, mapper.writeValueAsString(GetUserTripsHandler.toJson(updated)))
            } catch {
              case _: Throwable => Responses.json(409, """{"error":"Version conflict"}""")
            }
        }
    }
  }
}