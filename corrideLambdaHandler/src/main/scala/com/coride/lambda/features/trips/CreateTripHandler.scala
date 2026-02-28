package com.coride.lambda.features.trips

import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils, TokenUtils, JwtUtils}
import com.coride.tripdao.{TripDAO, TripMetadata, Location, Car, TripUser, UserGroup, GroupUser, UserGroupRecord, UserTrip}
import com.coride.userdao.UserDAO
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import scala.jdk.CollectionConverters._
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient

class CreateTripHandler(tripDao: TripDAO, userDao: UserDAO, jwt: JwtUtils) {
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private lazy val ddb: DynamoDbClient = DynamoDbClient.builder().region(Region.of(awsRegion)).httpClient(UrlConnectionHttpClient.builder().build()).build()
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")

  def this() = this(TripDAO(), UserDAO(), new JwtUtils(Option(System.getenv("USER_POOL_ID")).getOrElse(""), Option(System.getenv("AWS_REGION")).getOrElse("us-east-1"), Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")))
  
  private def generateTripArn(): String = {
    val chars = ('A' to 'Z') ++ ('0' to '9')
    (1 to 6).map(_ => chars(scala.util.Random.nextInt(chars.length))).mkString
  }

  private def generateGroupArn(): String = s"group:${UUID.randomUUID().toString.replace("-", "").toLowerCase}"

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    // Require authenticated user
    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    val verifiedOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok))
    if (verifiedOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized"}""")
    val verified = verifiedOpt.get

    val node = try { JsonUtils.parse(event.getBody) } catch {
      case e: Throwable =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        val cls = e.getClass.getName
        return Responses.json(400, s"""{"error":"Bad Request","message":"CreateTripHandler.parse: $cls: $msg"}""")
    }
    val startTime = try {
      JsonUtils.require(node, "startTime").toLong
    } catch {
      case e: Throwable =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        val cls = e.getClass.getName
        return Responses.json(400, s"""{"error":"Bad Request","message":"CreateTripHandler.fields: $cls: $msg"}""")
    }

    val tripArn = generateTripArn()

    val startOpt = Option(node.get("start")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty)
    val destOpt = Option(node.get("destination")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty)

    val carOpt = Option(node.get("car")).filter(n => n != null && !n.isNull).map { cn =>
      Car(
        plateNumber = Option(cn.get("plateNumber")).filter(n => n != null && !n.isNull).map(_.asText()),
        color = Option(cn.get("color")).filter(n => n != null && !n.isNull).map(_.asText()),
        model = Option(cn.get("model")).filter(n => n != null && !n.isNull).map(_.asText())
      )
    }

    // Caller identity from JWT only; driver must be the caller when present.
    val currentUserArn = verified.sub
    var driverIdOpt = Option(node.get("driver")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty)
    driverIdOpt.foreach { driverId =>
      if (driverId != verified.sub) {
        return Responses.json(403, """{"error":"Forbidden","message":"Driver must be the authenticated user"}""")
      }
    }
    // When client sends driver-only trip (groups empty) but omits driver, treat current user as driver
    if (driverIdOpt.isEmpty && (!node.has("groups") || node.get("groups").isEmpty)) driverIdOpt = Some(verified.sub)
    // Driver name from JWT when driver is caller; no UserDAO lookup needed for trip creation
    val driverNameOpt = driverIdOpt.map(_ => verified.name).flatten
    val driverPhotoUrlOpt: Option[String] = None

    // Trip-level start/destination: evaluate and dedupe (order: start then destination)
    val topLevelLocations = (startOpt.toList ++ destOpt.toList).distinct.map { name =>
      Location(locationName = name, pickupGroups = Nil, dropOffGroups = Nil, arrived = false, arrivedTime = None)
    }

    val base = try {
      TripMetadata(
        tripArn = tripArn,
        startTime = startTime,
        completionTime = None,
        status = "Upcoming",
        currentStop = None,
        driver = driverIdOpt,
        driverName = driverNameOpt,
        driverPhotoUrl = driverPhotoUrlOpt,
        driverConfirmed = Some(driverIdOpt.isDefined),
        car = carOpt,
        users = None,
        notes = Option(node.get("notes")).filter(n => n != null && !n.isNull).map(_.asText()),
        version = 1,
        locations = topLevelLocations,
        usergroups = None
      )
    } catch {
      case e: Throwable =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        val cls = e.getClass.getName
        return Responses.json(400, s"""{"error":"Bad Request","message":"CreateTripHandler.base: $cls: $msg"}""")
    }

    val groups: List[UserGroupRecord] = try {
        val groupsNode = node.get("groups")
        if (groupsNode != null && groupsNode.isArray) {
          val it = groupsNode.elements()
          val buff = scala.collection.mutable.ListBuffer[UserGroupRecord]()
          while (it.hasNext) {
            val gn = it.next()
            val arn = generateGroupArn()
            val groupName = gn.get("groupName").asText()
            val start = Option(gn.get("start")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty).getOrElse("")
            val destination = Option(gn.get("destination")).filter(n => n != null && !n.isNull).map(_.asText()).filter(_.nonEmpty).getOrElse("")
            val pickupTime = gn.get("pickupTime").asLong()
            val numAnonymousUsers = Option(gn.get("numAnonymousUsers")).map(_.asInt).getOrElse(0)
            val usersNode = gn.get("users")
            val users = if (usersNode != null && usersNode.isArray) {
              val uit = usersNode.elements()
              val ub = scala.collection.mutable.ListBuffer[GroupUser]()
              while (uit.hasNext) {
                val un = uit.next()
                val uid = un.get("userId").asText()
                val name = un.get("name").asText()
                val image = Option(un.get("imageUrl")).filter(n => n != null && !n.isNull).map(_.asText())
                val accept = Option(un.get("accept")).map(_.asBoolean()).getOrElse(false)
                ub += GroupUser(uid, name, image, accept)
              }
              ub.toList
            } else Nil
            buff += UserGroupRecord(arn = arn, tripArn = tripArn, groupName = groupName, start = start, destination = destination, pickupTime = pickupTime, users = users, numAnonymousUsers = numAnonymousUsers, version = 1)
          }
          buff.toList
        } else Nil
      } catch {
        case e: Throwable =>
          val msg = Option(e.getMessage).getOrElse(e.toString)
          val cls = e.getClass.getName
          return Responses.json(400, s"""{"error":"Bad Request","message":"CreateTripHandler.groups: $cls: $msg"}""")
      }

    // Ensure the acting user is marked as confirmed in any provided group.
    val actingUserId = currentUserArn
    val groupsAdjusted: List[UserGroupRecord] = groups.map { g =>
      val updatedUsers = g.users.map { u =>
        if (u.userId == actingUserId) u.copy(accept = true) else u
      }
      g.copy(users = updatedUsers)
    }

    val (start, dest) = (startOpt, destOpt) match {
      case (Some(s), Some(d)) => (s, d)
      case _ =>
        val firstGroup = groups.headOption
        (firstGroup.map(_.start).getOrElse(""), firstGroup.map(_.destination).getOrElse(""))
    }

    TripValidation.validateNoDuplicateUsersInTrip(driverIdOpt, groupsAdjusted).foreach { msg =>
      val escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"")
      return Responses.json(400, s"""{"error":"Bad Request","message":"$escaped"}""")
    }

    try {
      if (driverIdOpt.isDefined) {
        val driverTrip = UserTrip(
          arn = tripDao.userTripArn(tripArn, driverIdOpt.get),
          tripArn = tripArn,
          userStatusKey = s"${driverIdOpt.get}-uncompleted",
          tripDateTime = startTime,
          tripStatus = "Upcoming",
          start = start,
          destination = dest,
          departureDateTime = startTime,
          isDriver = true,
          driverConfirmed = true,
          version = 1
        )
        tripDao.createTripWithDriver(base, groupsAdjusted, driverTrip)
      } else {
        tripDao.createTrip(base, groupsAdjusted)
      }
    } catch {
      case e: Throwable =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        val cls = e.getClass.getName
        val st = e.getStackTrace.take(12).map(_.toString).mkString("\n")
        return Responses.json(500, s"""{"error":"Internal Server Error", "message":"$cls: $msg", "stack":"$st"}""")
    }
    
    val responseBody = mapper.createObjectNode()
    responseBody.put("tripArn", tripArn)
    responseBody.put("startTime", base.startTime)
    responseBody.put("status", base.status)
    driverIdOpt.foreach(responseBody.put("driver", _))
    driverNameOpt.foreach(responseBody.put("driverName", _))
    responseBody.put("driverConfirmed", driverIdOpt.isDefined)

    val metaOpt = tripDao.getTripMetadata(tripArn)
    val finalLocations = metaOpt.map(_.locations).getOrElse(Nil)
    val locationsNode = mapper.createArrayNode()
    finalLocations.foreach { loc =>
        val locNode = mapper.createObjectNode()
        locNode.put("locationName", loc.locationName)
        val pickupGroupsNode = mapper.createArrayNode()
        loc.pickupGroups.foreach(s => { pickupGroupsNode.add(s); () })
        locNode.set("pickupGroups", pickupGroupsNode)
        val dropOffGroupsNode = mapper.createArrayNode()
        loc.dropOffGroups.foreach(s => { dropOffGroupsNode.add(s); () })
        locNode.set("dropOffGroups", dropOffGroupsNode)
        locNode.put("arrived", loc.arrived)
        loc.arrivedTime.foreach(t => { locNode.put("arrivedTime", t); () })
        locationsNode.add(locNode)
    }
    responseBody.set("locations", locationsNode)

    carOpt.foreach { c =>
        val carNode = mapper.createObjectNode()
        c.plateNumber.foreach(carNode.put("plateNumber", _))
        c.color.foreach(carNode.put("color", _))
        c.model.foreach(carNode.put("model", _))
        responseBody.set("car", carNode)
        ()
    }

    val userGroupsNode = mapper.createArrayNode()
    metaOpt.flatMap(_.usergroups).getOrElse(Nil).foreach { ug =>
        val ugNode = mapper.createObjectNode()
        ugNode.put("groupId", ug.groupId)
        ugNode.put("groupName", ug.groupName)
        ugNode.put("groupSize", ug.groupSize)
        ug.imageUrl.foreach(url => { ugNode.put("imageUrl", url); () })
        userGroupsNode.add(ugNode)
    }
    responseBody.set("usergroups", userGroupsNode)
    responseBody.put("version", 1)

    Responses.json(200, responseBody.toString)
  }
}
