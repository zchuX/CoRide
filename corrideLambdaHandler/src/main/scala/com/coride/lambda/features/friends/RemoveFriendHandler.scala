package com.coride.lambda.features.friends

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.userdao.User
import com.coride.userfriendsdao.UserFriendsDAO

class RemoveFriendHandler(userFriendsDAO: UserFriendsDAO) {

  def handle(user: User, event: APIGatewayProxyRequestEvent, friendUserArnPath: String): APIGatewayProxyResponseEvent = {
    val friendUserArn = try {
      URLDecoder.decode(friendUserArnPath, StandardCharsets.UTF_8.name())
    } catch {
      case _: Exception => friendUserArnPath
    }
    userFriendsDAO.removeFriendship(user.userArn, friendUserArn)
    Responses.json(200, """{"status":"ok","message":"Friendship removed"}""")
  }
}
