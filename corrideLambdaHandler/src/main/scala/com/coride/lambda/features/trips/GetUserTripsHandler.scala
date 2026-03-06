package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils, TokenUtils, JwtUtils}
import com.coride.tripdao.{TripDAO, TripMetadata, UserTrip, UserGroupRecord, GroupUser}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import scala.jdk.CollectionConverters._

object GetUserTripsHandler {
  private val dao = TripDAO()
  private val mapper = new ObjectMapper()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    if (tokenOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized","message":"Missing bearer token"}""")

    val verifiedOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok))
    val verified = verifiedOpt.getOrElse(return Responses.json(400, """{"error":"Bad Request","message":"Invalid or missing token claims"}"""))
    // Caller identity is always from JWT (verified.sub). UserTrips are keyed by userStatusKey = "{userArn}-uncompleted" and userArn = Cognito sub.
    val arnsToQuery = List(verified.sub)
    // Required query parameter: completed (boolean) — true = completed trips, false = uncompleted
    val qs = Option(event.getQueryStringParameters).map(_.asScala.toMap).getOrElse(Map.empty)
    val completedOpt = qs.get("completed").map(_.toLowerCase)
    val tripStatus = completedOpt match {
      case Some("true") => "completed"
      case Some("false") => "uncompleted"
      case None => return Responses.json(400, """{"error":"Bad Request","message":"Query parameter 'completed' is required (true or false)"}""")
      case _ => return Responses.json(400, """{"error":"Bad Request","message":"Invalid 'completed' value; use true or false"}""")
    }

    val trips: List[UserTrip] = arnsToQuery.flatMap(arn => dao.queryUserTripsByStatus(arn, tripStatus, None, 200, ascending = false))
      .foldLeft(List.empty[UserTrip]) { (acc, ut) => if (acc.exists(_.arn == ut.arn)) acc else acc :+ ut }
      .sortBy(ut => (-ut.tripDateTime, ut.arn))

    // Build list from UserTrip query only (no per-trip metadata fetch)
    val root: ObjectNode = mapper.createObjectNode()
    val items: ArrayNode = mapper.createArrayNode()
    trips.foreach { ut =>
      val node = userTripToListJson(ut)
      items.add(node)
    }
    root.set("trips", items)
    Responses.json(200, mapper.writeValueAsString(root))
  }

  private def userTripToListJson(ut: UserTrip): ObjectNode = {
    val node = mapper.createObjectNode()
    node.put("tripArn", ut.tripArn)
    node.put("startTime", ut.tripDateTime)
    node.put("status", ut.tripStatus)
    node.put("start", ut.start)
    node.put("destination", ut.destination)
    node.put("isDriver", ut.isDriver)
    node.put("driverConfirmed", ut.driverConfirmed)
    node.put("userTripArn", ut.arn)
    node.put("userTripStatus", ut.tripStatus)
    ut.userGroupArn.foreach(node.put("userGroupArn", _))
    node
  }

  def toJson(t: TripMetadata): ObjectNode = {
    val node = mapper.createObjectNode()
    node.put("tripArn", t.tripArn)
    node.put("startTime", t.startTime)
    node.put("status", t.status)
    if (t.completionTime.isDefined) node.put("completionTime", t.completionTime.get)
    if (t.currentStop.isDefined) node.put("currentStop", t.currentStop.get)
    if (t.driver.isDefined) node.put("driver", t.driver.get)
    if (t.driverName.isDefined) node.put("driverName", t.driverName.get)
    if (t.driverPhotoUrl.isDefined) node.put("driverPhotoUrl", t.driverPhotoUrl.get)
    if (t.driverConfirmed.isDefined) node.put("driverConfirmed", t.driverConfirmed.get)
    if (t.notes.isDefined) node.put("notes", t.notes.get)

    // Use stored locations directly (pickupGroups/dropOffGroups are group names for human-readable payload)
    val locs = mapper.createArrayNode()
    t.locations.foreach { loc =>
      val ln = mapper.createObjectNode()
      ln.put("locationName", loc.locationName)
      ln.put("plannedTime", loc.plannedTime)
      val pickupsArr = mapper.createArrayNode()
      loc.pickupGroups.sorted.foreach(name => pickupsArr.add(name))
      ln.set("pickupGroups", pickupsArr)
      val dropsArr = mapper.createArrayNode()
      loc.dropOffGroups.sorted.foreach(name => dropsArr.add(name))
      ln.set("dropOffGroups", dropsArr)
      ln.put("arrived", loc.arrived)
      if (loc.arrivedTime.isDefined) ln.put("arrivedTime", loc.arrivedTime.get)
      locs.add(ln)
    }
    node.set("locations", locs)

    if (t.car.isDefined) {
      val c = t.car.get
      val cn = mapper.createObjectNode()
      if (c.plateNumber.isDefined) cn.put("plateNumber", c.plateNumber.get)
      if (c.color.isDefined) cn.put("color", c.color.get)
      if (c.model.isDefined) cn.put("model", c.model.get)
      node.set("car", cn)
    }

    // Use stored usergroups summary from trip metadata
    val ugArr = mapper.createArrayNode()
    t.usergroups.getOrElse(Nil).foreach { ug =>
      val gn = mapper.createObjectNode()
      gn.put("groupId", ug.groupId)
      gn.put("groupName", ug.groupName)
      gn.put("groupSize", ug.groupSize)
      ugArr.add(gn)
    }
    node.set("usergroups", ugArr)

    t.users match {
      case Some(us) if us.nonEmpty =>
        val arr = mapper.createArrayNode()
        us.foreach { u =>
          val un = mapper.createObjectNode()
          if (u.userId.isDefined) un.put("userId", u.userId.get)
          un.put("name", u.name)
          if (u.imageUrl.isDefined) un.put("imageUrl", u.imageUrl.get)
          arr.add(un)
        }
        node.set("users", arr)
      case _ =>
        ()
    }
    node.put("version", t.version)
    node
  }

  def groupToJson(g: UserGroupRecord): ObjectNode = {
    val gn = mapper.createObjectNode()
    gn.put("groupArn", g.arn)
    gn.put("tripArn", g.tripArn)
    gn.put("groupName", g.groupName)
    gn.put("start", g.start)
    gn.put("destination", g.destination)
    gn.put("pickupTime", g.pickupTime)
    val users = mapper.createArrayNode()
    g.users.foreach { u =>
      val un = mapper.createObjectNode()
      un.put("userId", u.userId)
      un.put("name", u.name)
      u.imageUrl.foreach(url => un.put("imageUrl", url))
      un.put("accept", u.accept)
      users.add(un)
    }
    gn.set("users", users)
    gn.put("version", g.version)
    gn
  }
}
