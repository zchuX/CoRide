package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{JsonUtils, JwtUtils, Logger, Responses, TokenUtils, VersioningUtils}
import com.coride.tripdao.{Car, Location, TripDAO, TripMetadata}
import com.coride.lambda.dao.UserGroupsDAO
import com.fasterxml.jackson.databind.JsonNode
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import com.coride.userdao.UserDAO

class UpdateTripMetadataHandler(tripDao: TripDAO, userDao: UserDAO, groupsDAO: UserGroupsDAO) {
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(userId: String, event: APIGatewayProxyRequestEvent, tripArn: String): APIGatewayProxyResponseEvent = {
    val body = event.getBody
    Logger.info(s"UpdateTripMetadata: tripArn=$tripArn bodyLength=${Option(body).map(_.length).getOrElse(0)}")
    val node: JsonNode = JsonUtils.parse(body)
    val expected = VersioningUtils.tripExpectedVersion(event, tripDao, tripArn)
    Logger.info(s"UpdateTripMetadata: tripArn=$tripArn expectedVersion=$expected")

    val current = tripDao.getTripMetadata(tripArn)
    current match {
      case None =>
        Logger.info(s"UpdateTripMetadata: tripArn=$tripArn trip not found")
        Responses.json(404, """{"error":"Trip not found"}""")
      case Some(tm) =>
        Logger.info(s"UpdateTripMetadata: tripArn=$tripArn currentLocations=${tm.locations.map(_.locationName).mkString(",")} currentVersion=${tm.version}")
        // Authenticate and gate: only driver or group member may update
        val isDriver = tm.driver.contains(userId)
        val inAnyGroup = groupsDAO.listUserGroupRecordsByTripArn(tripArn).exists(_.users.exists(_.userId == userId))
        if (!isDriver && !inAnyGroup) {
          return Responses.json(403, """{"error":"Forbidden","message":"Only driver or group members can modify trip metadata"}""")
        }

        // Parse array by index; explicit JsonNode types everywhere to avoid Java interop -> Scala Nothing inference (Lambda ClassLoader)
        val locationsNode: JsonNode = node.get("locations")
        val newLocationNames: Option[List[String]] =
          if (locationsNode != null && locationsNode.isArray) {
            val size = locationsNode.size()
            Some((0 until size).map { i =>
              val elem: JsonNode = locationsNode.get(i)
              elem.asText()
            }.toList)
          } else None
        Logger.info(s"UpdateTripMetadata: tripArn=$tripArn requestLocationNames=${newLocationNames.map(_.mkString(",")).getOrElse("(none)")}")

        val updatedLocations = try {
          newLocationNames.map { names =>
            val originalLocations = tm.locations.map(_.locationName).toSet
            if (names.toSet != originalLocations) {
              Logger.warn(s"UpdateTripMetadata: tripArn=$tripArn location set mismatch request=${names.toSet} current=$originalLocations")
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

            val groups = groupsDAO.listUserGroupRecordsByTripArn(tripArn)
            Option(tripDao.validateDropoffAfterPickupByNames(names, groups)).flatten.foreach { msg =>
              throw new IllegalArgumentException(msg)
            }

            // Recompute locations from groups so plannedTime is consistent (only pickup locations get earliest plannedTime; driver-only/dropoff-only get 0L)
            tripDao.locationsForOrderAndGroups(names, groups, tm.locations)
          }.getOrElse(tm.locations)
        } catch {
          case e: IllegalArgumentException =>
            Logger.warn(s"UpdateTripMetadata: tripArn=$tripArn locations validation failed ex=${e.getClass.getSimpleName} message=${Option(e.getMessage).getOrElse("")}")
            val msg = Option(e.getMessage).getOrElse("Invalid request").replace("\\", "\\\\").replace("\"", "\\\"")
            return Responses.json(400, s"""{"error":"Bad Request","message":"$msg"}""")
          case e: Throwable =>
            Logger.warn(s"UpdateTripMetadata: tripArn=$tripArn locations validation failed ex=${e.getClass.getSimpleName} message=${Option(e.getMessage).getOrElse("")}")
            return conflictJson(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        }

        val carNode: JsonNode = node.get("car")
        val carFromBody: Option[Option[Car]] =
          if (carNode == null) None
          else if (carNode.isNull) Some(None)
          else Some(Some(Car(
            plateNumber = textOpt(carNode, "plateNumber"),
            color = textOpt(carNode, "color"),
            model = textOpt(carNode, "model")
          )))

        val startTimeNode: JsonNode = node.get("startTime")
        val newStartTime = if (startTimeNode != null && !startTimeNode.isNull) startTimeNode.asLong() else tm.startTime
        val updated = tm.copy(
          startTime = newStartTime,
          locations = updatedLocations,
          car = carFromBody.getOrElse(tm.car)
        )

        try {
          Logger.info(s"UpdateTripMetadata: tripArn=$tripArn calling updateTripMetadata expectedVersion=$expected")
          tripDao.updateTripMetadata(updated, expected)
          // Re-fetch and return full trip; build JSON as string to avoid Lambda ClassLoader/Jackson tree casts
          tripDao.getTripMetadata(tripArn) match {
            case Some(refreshed) =>
              Logger.info(s"UpdateTripMetadata: tripArn=$tripArn success")
              Responses.json(200, buildGetStyleResponse(refreshed, userId))
            case None =>
              Logger.info(s"UpdateTripMetadata: tripArn=$tripArn success (trip not found after update)")
              Responses.json(200, buildGetStyleResponse(updated, userId))
          }
        } catch {
          case e: Throwable =>
            Logger.warn(s"UpdateTripMetadata: tripArn=$tripArn updateTripMetadata failed ex=${e.getClass.getSimpleName} message=${Option(e.getMessage).getOrElse("")}")
            conflictJson(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        }
    }
  }

  private def textOpt(node: JsonNode, field: String): Option[String] = {
    val n: JsonNode = node.get(field)
    if (n != null && !n.isNull) Some(n.asText()) else None
  }

  /** 409 response with message escaped for JSON (no broken payload from quotes or backslashes). */
  private def conflictJson(message: String): APIGatewayProxyResponseEvent = {
    val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
    Responses.json(409, s"""{"error":"Conflict","message":"$escaped"}""")
  }

  /** Same shape as GET /api/trips/:id: {"trip":{...},"status":{"userTripStatus":...}}. String build to avoid Lambda Jackson casts. */
  private def buildGetStyleResponse(tm: TripMetadata, userId: String): String = {
    val tripJson = tripMetadataToJsonString(tm)
    val utArn = tripDao.userTripArn(tm.tripArn, userId)
    val userTripStatus = tripDao.getUserTrip(utArn).map(ut => q(ut.tripStatus)).getOrElse("null")
    val statusJson = s"""{"userTripStatus":$userTripStatus}"""
    s"""{"trip":$tripJson,"status":$statusJson}"""
  }

  private def q(s: String): String = {
    def esc(x: String): String = x.replace("\\", "\\\\").replace("\"", "\\\"")
    "\"" + esc(s) + "\""
  }

  /** Full trip payload matching GetUserTripsHandler.toJson (string build to avoid Lambda Jackson tree casts). */
  private def tripMetadataToJsonString(tm: TripMetadata): String = {
    def qs(s: String): String = q(s)
    val completionTime = tm.completionTime.map(_.toString).getOrElse("null")
    val currentStop = tm.currentStop.map(qs).getOrElse("null")
    val driver = tm.driver.map(qs).getOrElse("null")
    val driverName = tm.driverName.map(qs).getOrElse("null")
    val driverPhotoUrl = tm.driverPhotoUrl.map(qs).getOrElse("null")
    val driverConfirmed = tm.driverConfirmed.map(_.toString).getOrElse("null")
    val notes = tm.notes.map(qs).getOrElse("null")
    val locs = tm.locations.map { loc =>
      val pickupArr = loc.pickupGroups.sorted.map(qs).mkString("[", ",", "]")
      val dropArr = loc.dropOffGroups.sorted.map(qs).mkString("[", ",", "]")
      val arrivedTime = loc.arrivedTime.map(_.toString).getOrElse("null")
      s"""{"locationName":${qs(loc.locationName)},"plannedTime":${loc.plannedTime},"pickupGroups":$pickupArr,"dropOffGroups":$dropArr,"arrived":${loc.arrived},"arrivedTime":$arrivedTime}"""
    }.mkString("[", ",", "]")
    val carJson = tm.car match {
      case Some(c) =>
        val pn = c.plateNumber.map(qs).getOrElse("null")
        val col = c.color.map(qs).getOrElse("null")
        val mod = c.model.map(qs).getOrElse("null")
        s"""{"plateNumber":$pn,"color":$col,"model":$mod}"""
      case None => "null"
    }
    val ugArr = tm.usergroups.getOrElse(Nil).map { ug =>
      s"""{"groupId":${qs(ug.groupId)},"groupName":${qs(ug.groupName)},"groupSize":${ug.groupSize}}"""
    }.mkString("[", ",", "]")
    val usersArr = tm.users match {
      case Some(us) if us.nonEmpty =>
        us.map { u =>
          val uid = u.userId.map(qs).getOrElse("null")
          val img = u.imageUrl.map(qs).getOrElse("null")
          s"""{"userId":$uid,"name":${qs(u.name)},"imageUrl":$img}"""
        }.mkString("[", ",", "]")
      case _ => "[]"
    }
    s"""{"tripArn":${qs(tm.tripArn)},"startTime":${tm.startTime},"status":${qs(tm.status)},"completionTime":$completionTime,"currentStop":$currentStop,"driver":$driver,"driverName":$driverName,"driverPhotoUrl":$driverPhotoUrl,"driverConfirmed":$driverConfirmed,"notes":$notes,"locations":$locs,"car":$carJson,"usergroups":$ugArr,"users":$usersArr,"version":${tm.version}}"""
  }
}