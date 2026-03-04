package com.coride.lambda.features.garage

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{JsonUtils, Responses}
import com.coride.userdao.User
import com.coride.lambda.dao.GarageDAO
import com.fasterxml.jackson.databind.ObjectMapper

class UpdateCarHandler(garageDAO: GarageDAO) {
  private val mapper = new ObjectMapper()

  def handle(user: User, event: APIGatewayProxyRequestEvent, carArn: String): APIGatewayProxyResponseEvent = {
    garageDAO.get(carArn) match {
      case None =>
        Responses.json(404, """{"error":"Not Found","message":"Car not found"}""")
      case Some(existing) if existing.userArn != user.userArn =>
        Responses.json(403, """{"error":"Forbidden","message":"Not your car"}""")
      case Some(existing) =>
        val node = JsonUtils.parse(event.getBody)
        val make = optText(node, "make").orElse(existing.make)
        val model = optText(node, "model").orElse(existing.model)
        val color = optText(node, "color").orElse(existing.color)
        val carPlate = optText(node, "carPlate").orElse(existing.carPlate)
        val stateRegistered = optText(node, "stateRegistered").orElse(existing.stateRegistered)

        val updated = existing.copy(make = make, model = model, color = color, carPlate = carPlate, stateRegistered = stateRegistered)
        garageDAO.update(updated)
        val n = mapper.createObjectNode()
        n.put("carArn", updated.carArn)
        n.put("userArn", updated.userArn)
        updated.make.foreach(n.put("make", _))
        updated.model.foreach(n.put("model", _))
        updated.color.foreach(n.put("color", _))
        updated.carPlate.foreach(n.put("carPlate", _))
        updated.stateRegistered.foreach(n.put("stateRegistered", _))
        Responses.json(200, mapper.writeValueAsString(n))
    }
  }

  private def optText(node: com.fasterxml.jackson.databind.JsonNode, field: String): Option[String] =
    Option(node.get(field)).filter(n => n != null && !n.isNull).map(_.asText())
}
