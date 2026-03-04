package com.coride.lambda.features.garage

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.userdao.User
import com.coride.lambda.dao.GarageDAO

class DeleteCarHandler(garageDAO: GarageDAO) {

  def handle(user: User, event: APIGatewayProxyRequestEvent, carArn: String): APIGatewayProxyResponseEvent = {
    garageDAO.get(carArn) match {
      case None =>
        Responses.json(404, """{"error":"Not Found","message":"Car not found"}""")
      case Some(car) if car.userArn != user.userArn =>
        Responses.json(403, """{"error":"Forbidden","message":"Not your car"}""")
      case Some(_) =>
        garageDAO.delete(carArn)
        Responses.json(200, """{"status":"ok","message":"Car deleted"}""")
    }
  }
}
