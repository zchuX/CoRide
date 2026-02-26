package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.tripdao.TripDAO
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode

object ListTripUsersHandler {
  private val dao = TripDAO()
  private val mapper = new ObjectMapper()

  def handle(event: APIGatewayProxyRequestEvent, tripArn: String): APIGatewayProxyResponseEvent = {
    val users = dao.listUsersByTrip(tripArn)
    val body = mapper.createObjectNode()
    val usersNode = mapper.createArrayNode()
    users.foreach {
      user => usersNode.add(mapper.valueToTree[JsonNode](user))
    }
    body.set("users", usersNode)
    Responses.json(200, mapper.writeValueAsString(body))
  }
}
