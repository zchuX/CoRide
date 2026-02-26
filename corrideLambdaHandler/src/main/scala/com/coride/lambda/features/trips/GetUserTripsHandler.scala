package com.coride.lambda.features.trips

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.{Responses, JsonUtils, TokenUtils, JwtUtils}
import com.coride.lambda.dao.UserGroupsDAO
import com.coride.tripdao.{TripDAO, TripMetadata, UserTrip, UserGroupRecord, GroupUser}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import scala.jdk.CollectionConverters._

object GetUserTripsHandler {
  private val dao = TripDAO()
  private val mapper = new ObjectMapper()
  private val groupsDAO = new UserGroupsDAO()
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val tokenOpt = TokenUtils.bearer(event.getHeaders)
    if (tokenOpt.isEmpty) return Responses.json(401, """{"error":"Unauthorized","message":"Missing bearer token"}""")

    val subOpt = tokenOpt.flatMap(tok => jwt.verifyIdToken(tok).map(_.sub))
    val userId = subOpt.getOrElse(return Responses.json(400, """{"error":"Bad Request","message":"Missing 'sub' claim"}"""))
    // Determine filter from query parameters: status=uncompleted|completed|all, or completed=true|false
    val qs = Option(event.getQueryStringParameters).map(_.asScala.toMap).getOrElse(Map.empty)
    val statusParamOpt = qs.get("status").map(_.toLowerCase)
    val completedParamOpt = qs.get("completed").map(_.toLowerCase)

    val statuses: List[String] = statusParamOpt match {
      case Some("completed") => List("completed")
      case Some("uncompleted") => List("uncompleted")
      case Some("all") => List("completed", "uncompleted")
      case Some(_) => return Responses.json(400, """{"error":"Bad Request","message":"Invalid 'status' value"}""")
      case None =>
        completedParamOpt match {
          case Some("true") => List("completed")
          case Some("false") => List("uncompleted")
          case Some(_) => return Responses.json(400, """{"error":"Bad Request","message":"Invalid 'completed' boolean"}""")
          case None => List("uncompleted")
        }
    }

    val trips: List[UserTrip] = statuses.flatMap(st => dao.queryUserTripsByStatus(userId, st, None, 200, ascending = false))

    // Collect full trip metadata for each user trip
    val root: ObjectNode = mapper.createObjectNode()
    val items: ArrayNode = mapper.createArrayNode()

    trips.foreach { ut =>
      val tripArn = ut.arn.split("#").headOption.getOrElse("")
      dao.getTripMetadata(tripArn).foreach { tm =>
        val node = toJson(tm)
        node.put("userTripArn", ut.arn)
        node.put("userTripStatus", ut.tripStatus)
        node.put("userStatusKey", ut.userStatusKey)
        items.add(node)
      }
    }
    root.set("trips", items)
    Responses.json(200, mapper.writeValueAsString(root))
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

    // Reconstruct usergroups and locations from UserGroupRecord listings via local query
    val groups = listUserGroupRecordsByTripArn(t.tripArn)
    val locs = mapper.createArrayNode()
    val byName = scala.collection.mutable.Map.empty[String, (scala.collection.mutable.Set[String], scala.collection.mutable.Set[String])]
    groups.foreach { g =>
      val sEntry = byName.getOrElseUpdate(g.start, (scala.collection.mutable.Set.empty[String], scala.collection.mutable.Set.empty[String]))
      sEntry._1 += g.arn
      val dEntry = byName.getOrElseUpdate(g.destination, (scala.collection.mutable.Set.empty[String], scala.collection.mutable.Set.empty[String]))
      dEntry._2 += g.arn
    }
    byName.keys.toList.sorted.foreach { name =>
      val (pickups, drops) = byName(name)
      val ln = mapper.createObjectNode()
      ln.put("locationName", name)
      val pickupsArr = mapper.createArrayNode(); pickups.toList.sorted.foreach(ga => pickupsArr.add(ga)); ln.set("pickupGroups", pickupsArr)
      val dropsArr = mapper.createArrayNode(); drops.toList.sorted.foreach(ga => dropsArr.add(ga)); ln.set("dropOffGroups", dropsArr)
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

    // Build usergroups summary from UserGroupRecord listings
    val ugArr = mapper.createArrayNode()
    groups.foreach { gr =>
      val gn = mapper.createObjectNode()
      gn.put("groupId", gr.arn)
      gn.put("groupName", gr.groupName)
      gn.put("groupSize", gr.users.size + gr.numAnonymousUsers)
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

  // Local GSI query helper to list user groups by tripArn
  private def listUserGroupRecordsByTripArn(tripArn: String, limit: Int = 100): List[UserGroupRecord] = groupsDAO.listUserGroupRecordsByTripArn(tripArn, limit)

  def groupToJson(g: UserGroupRecord): ObjectNode = {
    val gn = mapper.createObjectNode()
    gn.put("groupArn", g.arn)
    gn.put("tripArn", g.tripArn)
    gn.put("groupName", g.groupName)
    gn.put("start", g.start)
    gn.put("destination", g.destination)
    gn.put("pickupTime", g.pickupTime)
    // Only include detailed entries for users with a name; anonymous users are counted
    val users = mapper.createArrayNode()
    val namedUsers = g.users.filter(u => Option(u.name).exists(_.trim.nonEmpty))
    namedUsers.foreach { u =>
      val un = mapper.createObjectNode()
      un.put("userId", u.userId)
      un.put("name", u.name)
      u.imageUrl.foreach(url => un.put("imageUrl", url))
      un.put("accept", u.accept)
      users.add(un)
    }
    val anonCount = g.users.size - namedUsers.size
    gn.set("users", users)
    gn.put("numAnonymousUser", anonCount)
    gn.put("version", g.version)
    gn
  }
}
