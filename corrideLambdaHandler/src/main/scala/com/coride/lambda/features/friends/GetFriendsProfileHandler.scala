package com.coride.lambda.features.friends

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.userdao.User
import com.coride.userfriendsdao.UserFriendsDAO
import com.fasterxml.jackson.databind.ObjectMapper

class GetFriendsProfileHandler(userFriendsDAO: UserFriendsDAO) {
  private val mapper = new ObjectMapper()

  def handle(user: User, event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    userFriendsDAO.getProfile(user.userArn) match {
      case Some(profile) =>
        val root = mapper.createObjectNode()
        root.put("userArn", profile.userArn)
        root.put("name", profile.name)
        Responses.json(200, mapper.writeValueAsString(root))
      case None =>
        Responses.json(404, """{"error":"Not Found","message":"Profile not found in friends table"}""")
    }
  }
}
