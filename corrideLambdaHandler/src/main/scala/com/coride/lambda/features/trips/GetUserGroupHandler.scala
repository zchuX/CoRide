package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, TokenUtils, JwtUtils}
import com.coride.tripdao.TripDAO
import com.fasterxml.jackson.databind.ObjectMapper

object GetUserGroupHandler {
  private val dao = TripDAO()
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent, groupArn: String): APIGatewayProxyResponseEvent = {
    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    if (tokenOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized"}""")

    dao.getUserGroup(groupArn) match {
      case None => Responses.json(404, """{"error":"Not Found"}""")
      case Some(g) =>
        Responses.json(200, mapper.writeValueAsString(GetUserTripsHandler.groupToJson(g)))
    }
  }
}
