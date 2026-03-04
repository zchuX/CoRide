package com.coride.lambda.features.garage

import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{JsonUtils, Responses}
import com.coride.userdao.User
import com.coride.lambda.dao.GarageDAO
import com.fasterxml.jackson.databind.ObjectMapper

class AddCarToGarageHandler(garageDAO: GarageDAO) {
  private val mapper = new ObjectMapper()

  def handle(user: User, event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val body = Option(event.getBody).getOrElse("")
    val node = JsonUtils.parse(body)
    val make = Option(node.get("make")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty)
    val model = Option(node.get("model")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty)
    val color = Option(node.get("color")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty)
    val carPlate = Option(node.get("carPlate")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty)
    val stateRegistered = Option(node.get("stateRegistered")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty)

    val carArn = "car:" + UUID.randomUUID().toString
    val car = GarageCar(carArn = carArn, userArn = user.userArn, make = make, model = model, color = color, carPlate = carPlate, stateRegistered = stateRegistered)
    garageDAO.put(car)
    val root = mapper.createObjectNode()
    root.put("carArn", car.carArn)
    root.put("userArn", car.userArn)
    make.foreach(root.put("make", _))
    model.foreach(root.put("model", _))
    color.foreach(root.put("color", _))
    carPlate.foreach(root.put("carPlate", _))
    stateRegistered.foreach(root.put("stateRegistered", _))
    Responses.json(200, mapper.writeValueAsString(root))
  }
}
