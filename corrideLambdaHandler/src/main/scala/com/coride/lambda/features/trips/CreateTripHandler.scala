package com.coride.lambda.features.trips

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

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    // Require authenticated user
    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    val userIdOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok).map(_.sub))
    if (userIdOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized"}""")

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

    val startOpt = Option(node.get("start")).filter(n => n != null && !n.isNull).map(_.asText())
    val destOpt = Option(node.get("destination")).filter(n => n != null && !n.isNull).map(_.asText())

    val carOpt = Option(node.get("car")).filter(n => n != null && !n.isNull).map { cn =>
      Car(
        plateNumber = Option(cn.get("plateNumber")).filter(n => n != null && !n.isNull).map(_.asText()),
        color = Option(cn.get("color")).filter(n => n != null && !n.isNull).map(_.asText()),
        model = Option(cn.get("model")).filter(n => n != null && !n.isNull).map(_.asText())
      )
    }

    val driverIdOpt = Option(node.get("driver")).filter(n => n != null && !n.isNull).map(_.asText())
    val driverOpt = driverIdOpt.flatMap(userDao.getUser)

    val base = try {
      TripMetadata(
        tripArn = tripArn,
        startTime = startTime,
        completionTime = None,
        status = "Upcoming",
        currentStop = None,
        driver = driverIdOpt,
        driverName = driverOpt.map(_.name),
        driverPhotoUrl = driverOpt.flatMap(_.photoUrl),
        driverConfirmed = Some(driverIdOpt.isDefined),
        car = carOpt,
        users = None,
        notes = Option(node.get("notes")).filter(n => n != null && !n.isNull).map(_.asText()),
        version = 1,
        locations = Nil,
        usergroups = None
      )
    } catch {
      case e: Throwable =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        val cls = e.getClass.getName
        return Responses.json(400, s"""{"error":"Bad Request","message":"CreateTripHandler.base: $cls: $msg"}""")
    }

    val groups: List[UserGroupRecord] = if (driverIdOpt.isDefined) {
      Nil
    } else {
      try {
        val groupsNode = node.get("groups")
        if (groupsNode != null && groupsNode.isArray) {
          val it = groupsNode.elements()
          val buff = scala.collection.mutable.ListBuffer[UserGroupRecord]()
          while (it.hasNext) {
            val gn = it.next()
            val arn = gn.get("arn").asText()
            val groupName = gn.get("groupName").asText()
            val start = gn.get("start").asText()
            val destination = gn.get("destination").asText()
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
    }

    // Ensure the acting user is marked as confirmed in any provided group.
    val actingUserId = userIdOpt.get
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

    // Create locations from groups
    val locationsFromGroups = groupsAdjusted.flatMap { ug =>
      List(
        Location(locationName = ug.start, pickupGroups = List(ug.groupName), dropOffGroups = Nil, arrived = false, arrivedTime = None),
        Location(locationName = ug.destination, pickupGroups = Nil, dropOffGroups = List(ug.groupName), arrived = false, arrivedTime = None)
      )
    }

    // Also include top-level start/dest if they exist
    val topLevelLocations = (startOpt, destOpt) match {
        case (Some(s), Some(d)) => List(
            Location(locationName = s, pickupGroups = Nil, dropOffGroups = Nil, arrived = false, arrivedTime = None),
            Location(locationName = d, pickupGroups = Nil, dropOffGroups = Nil, arrived = false, arrivedTime = None)
        )
        case _ => Nil
    }

    // Merge all locations, ensuring uniqueness and combining group associations
    val allInitialLocations = (locationsFromGroups ++ topLevelLocations)
      .filter(_.locationName.nonEmpty)
      .groupBy(_.locationName)
      .map { case (name, locs) =>
        locs.head.copy(
          pickupGroups = locs.flatMap(_.pickupGroups).distinct,
          dropOffGroups = locs.flatMap(_.dropOffGroups).distinct
        )
      }.toList

    val baseWithInitialLocs = base.copy(locations = allInitialLocations)
    val finalLocations = tripDao.orderedLocationsFromRecords(baseWithInitialLocs, groupsAdjusted)
    val finalBase = baseWithInitialLocs.copy(locations = finalLocations)

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
        tripDao.createTripWithDriver(finalBase, groupsAdjusted, driverTrip)
      } else {
        tripDao.createTrip(finalBase, groupsAdjusted)
      }
    } catch {
      case e: Throwable =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        val cls = e.getClass.getName
        val st = e.getStackTrace.take(12).map(_.toString).mkString("\\n")
        val err = mapper.createObjectNode()
        err.put("error", "Internal Server Error")
        err.put("message", s"CreateTripHandler.createTrip: $cls: $msg")
        err.put("stack", st)
        return Responses.json(500, mapper.writeValueAsString(err))
    }

    val created: TripMetadata = try {
      tripDao.getTripMetadata(tripArn).getOrElse(base)
    } catch {
      case e: Throwable =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        val cls = e.getClass.getName
        val st = e.getStackTrace.take(12).map(_.toString).mkString("\\n")
        val err = mapper.createObjectNode()
        err.put("error", "Internal Server Error")
        err.put("message", s"CreateTripHandler.getTripMetadata: $cls: $msg")
        err.put("stack", st)
        return Responses.json(500, mapper.writeValueAsString(err))
    }

    val bodyNode = GetUserTripsHandler.toJson(created)

    try {
      Responses.json(200, mapper.writeValueAsString(bodyNode))
    } catch {
      case e: Throwable =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        val cls = e.getClass.getName
        val st = e.getStackTrace.take(12).map(_.toString).mkString("\\n")
        val err = mapper.createObjectNode()
        err.put("error", "Internal Server Error")
        err.put("message", s"CreateTripHandler.serialize: $cls: $msg")
        err.put("stack", st)
        Responses.json(500, mapper.writeValueAsString(err))
    }
  }
}
