package com.coride.lambda.features.garage

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.userdao.User
import com.coride.lambda.dao.GarageDAO
import com.fasterxml.jackson.databind.ObjectMapper

class ListGarageCarsHandler(garageDAO: GarageDAO) {
  private val mapper = new ObjectMapper()

  def handle(user: User, event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val limit = Option(event.getQueryStringParameters).flatMap(m => Option(m.get("limit"))).flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(100)
    val cars = garageDAO.listByUserArn(user.userArn, limit)
    val arr = mapper.createArrayNode()
    cars.foreach(c => arr.add(carToNode(c)))
    val root = mapper.createObjectNode()
    root.set("cars", arr)
    Responses.json(200, mapper.writeValueAsString(root))
  }

  private def carToNode(c: GarageCar): com.fasterxml.jackson.databind.JsonNode = {
    val n = mapper.createObjectNode()
    n.put("carArn", c.carArn)
    n.put("userArn", c.userArn)
    c.make.foreach(n.put("make", _))
    c.model.foreach(n.put("model", _))
    c.color.foreach(n.put("color", _))
    c.carPlate.foreach(n.put("carPlate", _))
    c.stateRegistered.foreach(n.put("stateRegistered", _))
    n
  }
}
