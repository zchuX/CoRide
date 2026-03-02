package com.coride.lambda.features.friends

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.userdao.User
import com.coride.userfriendsdao.UserFriendsDAO
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}

class ListFriendsHandler(userFriendsDAO: UserFriendsDAO) {
  private val mapper = new ObjectMapper()

  def handle(user: User, event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val limit = Option(event.getQueryStringParameters).flatMap(m => Option(m.get("limit"))).flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(100)
    val friends = userFriendsDAO.listFriends(user.userArn, limit)
    val arr = mapper.createArrayNode()
    friends.foreach { f =>
      val obj = mapper.createObjectNode()
      obj.put("userArn", f.friendUserArn)
      obj.put("name", f.friendName)
      arr.add(obj)
    }
    val root = mapper.createObjectNode()
    root.set("friends", arr)
    Responses.json(200, mapper.writeValueAsString(root))
  }
}
