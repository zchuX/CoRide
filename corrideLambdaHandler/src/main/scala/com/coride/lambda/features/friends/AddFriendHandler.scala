package com.coride.lambda.features.friends

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{JsonUtils, Responses}
import com.coride.userdao.User
import com.coride.userdao.UserDAO
import com.coride.userfriendsdao.UserFriendsDAO

class AddFriendHandler(userDao: UserDAO, userFriendsDAO: UserFriendsDAO) {

  def handle(user: User, event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    val friendUserArnOpt = Option(node.get("friendUserArn")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty)

    friendUserArnOpt match {
      case None =>
        return Responses.json(400, """{"error":"Bad Request","message":"friendUserArn is required"}""")
      case Some(friendArn) if friendArn == user.userArn =>
        return Responses.json(400, """{"error":"Bad Request","message":"friendUserArn must not be the caller"}""")
      case Some(friendArn) =>
        userDao.getUser(friendArn) match {
          case None =>
            return Responses.json(404, """{"error":"Not Found","message":"User not found"}""")
          case Some(friendUser) =>
            if (userFriendsDAO.areFriends(user.userArn, friendArn)) {
              return Responses.json(200, """{"status":"ok","message":"Already friends"}""")
            }
            userFriendsDAO.addFriendship(user.userArn, user.name, friendArn, friendUser.name)
            Responses.json(200, """{"status":"ok","message":"Friendship added"}""")
        }
    }
  }
}
