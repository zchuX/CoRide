package com.coride.lambda.features.garage

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.userdao.User
import com.coride.lambda.dao.GarageDAO
import com.fasterxml.jackson.databind.ObjectMapper

class GetCarHandler(garageDAO: GarageDAO) {
  private val mapper = new ObjectMapper()

  def handle(user: User, event: APIGatewayProxyRequestEvent, carArn: String): APIGatewayProxyResponseEvent = {
    garageDAO.get(carArn) match {
      case None =>
        Responses.json(404, """{"error":"Not Found","message":"Car not found"}""")
      case Some(car) if car.userArn != user.userArn =>
        Responses.json(403, """{"error":"Forbidden","message":"Not your car"}""")
      case Some(car) =>
        val n = mapper.createObjectNode()
        n.put("carArn", car.carArn)
        n.put("userArn", car.userArn)
        car.make.foreach(n.put("make", _))
        car.model.foreach(n.put("model", _))
        car.color.foreach(n.put("color", _))
        car.carPlate.foreach(n.put("carPlate", _))
        car.stateRegistered.foreach(n.put("stateRegistered", _))
        Responses.json(200, mapper.writeValueAsString(n))
    }
  }
}
