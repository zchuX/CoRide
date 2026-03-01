package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{JsonUtils, JwtUtils, Responses, TokenUtils, VersioningUtils}
import com.coride.tripdao.{Location, TripDAO, TripMetadata}
import com.coride.lambda.dao.UserGroupsDAO
import com.fasterxml.jackson.databind.ObjectMapper
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import com.coride.userdao.UserDAO

import scala.jdk.CollectionConverters.IterableHasAsScala

class UpdateTripMetadataHandler(tripDao: TripDAO, userDao: UserDAO, groupsDAO: UserGroupsDAO) {
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(userId: String, event: APIGatewayProxyRequestEvent, tripArn: String): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    val expected = VersioningUtils.tripExpectedVersion(event, tripDao, tripArn)

    val current = tripDao.getTripMetadata(tripArn)
    current match {
      case None => Responses.json(404, """{"error":"Trip not found"}""")
      case Some(tm) =>
        // Authenticate and gate: only driver or group member may update
        val isDriver = tm.driver.contains(userId)
        val inAnyGroup = groupsDAO.listUserGroupRecordsByTripArn(tripArn).exists(_.users.exists(_.userId == userId))
        if (!isDriver && !inAnyGroup) {
          return Responses.json(403, """{"error":"Forbidden","message":"Only driver or group members can modify trip metadata"}""")
        }

        val newLocationNames = Option(node.get("locations")).filter(n => n != null && n.isArray).map {
          _.asScala.map(_.asText()).toList
        }

        val updatedLocations = try {
          newLocationNames.map { names =>
            val originalLocations = tm.locations.map(_.locationName).toSet
            if (names.toSet != originalLocations) {
              throw new Exception("New locations must be a permutation of the original locations")
            }

            val arrivedLocations = tm.locations.filter(_.arrived)
            val validationError = arrivedLocations.find { loc =>
              val oldIndex = tm.locations.indexWhere(_.locationName == loc.locationName)
              val newIndex = names.indexOf(loc.locationName)
              newIndex > oldIndex
            }

            if (validationError.isDefined) {
              throw new Exception(s"Cannot move arrived location ${validationError.get.locationName} to a later position")
            }

            names.map(name => tm.locations.find(_.locationName == name).get)
          }.getOrElse(tm.locations)
        } catch {
          case e: Throwable => return Responses.json(409, s"""{"error":"Conflict","message":"${e.getMessage}"}""")
        }

        val updated = tm.copy(
          startTime = Option(node.get("startTime")).filter(n => n != null && !n.isNull).map(_.asLong()).getOrElse(tm.startTime),
          locations = updatedLocations
        )

        try {
          tripDao.updateTripMetadata(updated, expected)
          val body = toJson(updated)
          Responses.json(200, mapper.writeValueAsString(body))
        } catch {
          case e: Throwable => Responses.json(409, s"""{"error":"Conflict","message":"${e.getMessage}"}""")
        }
    }
  }

  private def toJson(tm: TripMetadata): com.fasterxml.jackson.databind.node.ObjectNode = {
    val node = mapper.createObjectNode()
    node.put("tripArn", tm.tripArn)
    node.put("startTime", tm.startTime)
    node.put("status", tm.status)
    tm.driver.foreach(node.put("driver", _))
    val locationsNode = mapper.createArrayNode()
    tm.locations.foreach(l => locationsNode.add(l.locationName))
    node.set("locations", locationsNode)
    node
  }
}