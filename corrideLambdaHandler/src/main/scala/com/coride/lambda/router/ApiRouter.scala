package com.coride.lambda.router

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.features.auth._
import com.coride.lambda.util.{JsonUtils, Responses, JwtUtils}
import com.coride.lambda.features.trips._
import com.coride.lambda.features.friends._
import com.coride.lambda.features.garage._
import com.coride.tripdao.TripDAO
import com.coride.userdao.UserDAO
import com.coride.userfriendsdao.UserFriendsDAO
import com.coride.lambda.dao.{UserGroupsDAO, GarageDAO}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient

class ValidationException(message: String) extends RuntimeException(message)
class UnauthorizedException(message: String) extends RuntimeException(message)

class ApiRouter(ddb: DynamoDbClient, tripDao: TripDAO, userDao: UserDAO, userGroupsDAO: UserGroupsDAO, userFriendsDAO: UserFriendsDAO, garageDAO: GarageDAO, jwt: JwtUtils) extends RequestHandler[APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent] {

  /** Decode path segment so that group:uuid in URL as group%3Auuid is looked up as group:uuid. */
  private def decodePathSegment(s: String): String =
    try { URLDecoder.decode(s, StandardCharsets.UTF_8.name()) } catch { case _: Exception => s }

  def this() = this(
    DynamoDbClient.builder().region(Region.of(Option(System.getenv("AWS_REGION")).getOrElse("us-east-1"))).httpClient(UrlConnectionHttpClient.builder().build()).build(),
    TripDAO(),
    UserDAO(),
    new UserGroupsDAO(),
    UserFriendsDAO(),
    new GarageDAO(),
    new JwtUtils(Option(System.getenv("USER_POOL_ID")).getOrElse(""), Option(System.getenv("AWS_REGION")).getOrElse("us-east-1"), Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse(""))
  )
  private val createTripHandler = new CreateTripHandler()
  private val updateTripMetadataHandler = new UpdateTripMetadataHandler(tripDao, userDao, userGroupsDAO)
  private val listFriendsHandler = new ListFriendsHandler(userFriendsDAO)
  private val getFriendsProfileHandler = new GetFriendsProfileHandler(userFriendsDAO)
  private val addFriendHandler = new AddFriendHandler(userDao, userFriendsDAO)
  private val removeFriendHandler = new RemoveFriendHandler(userFriendsDAO)
  private val addCarToGarageHandler = new AddCarToGarageHandler(garageDAO)
  private val listGarageCarsHandler = new ListGarageCarsHandler(garageDAO)
  private val getCarHandler = new GetCarHandler(garageDAO)
  private val updateCarHandler = new UpdateCarHandler(garageDAO)
  private val deleteCarHandler = new DeleteCarHandler(garageDAO)

  override def handleRequest(event: APIGatewayProxyRequestEvent, context: Context): APIGatewayProxyResponseEvent = {
    val method = Option(event.getHttpMethod).getOrElse("")
    // Prefer the concrete request path; fall back to resource template. Strip stage prefix if present.
    val rawPath = Option(event.getPath).orElse(Option(event.getResource)).getOrElse("")
    val stageOpt = Option(event.getRequestContext).flatMap(rc => Option(rc.getStage))
    val path = stageOpt match {
      case Some(stage) if rawPath.startsWith(s"/${stage}/") => rawPath.stripPrefix(s"/${stage}")
      case Some(stage) if rawPath == s"/${stage}" => "/"
      case _ => rawPath
    }

    try {
      (method, path) match {
        // ---------- CORS Preflight ----------
        case ("OPTIONS", _) =>
          // Respond OK to all preflight requests; headers added by Responses
          Responses.json(200, """{"status":"ok"}""")
        // ---------- Authentication Endpoints ----------
        case ("POST", "/auth/register") =>
          RegisterHandler.handle(event)

        case ("POST", "/auth/verify-code") =>
          VerifyCodeHandler.handle(event)

        case ("POST", "/auth/login") =>
          LoginHandler.handle(event)

        case ("POST", "/auth/logout") =>
          LogoutHandler.handle(event)

        // Phone OTP login disabled
        case ("POST", "/auth/login-otp/send") =>
          Responses.json(410, """{"error":"Gone","message":"Phone OTP login has been disabled"}""")

        case ("POST", "/auth/login-otp/verify") =>
          Responses.json(410, """{"error":"Gone","message":"Phone OTP login has been disabled"}""")

        case ("POST", "/auth/reset-password") =>
          ResetPasswordHandler.handle(event)

        case ("POST", "/auth/reset-password/confirm") =>
          ResetPasswordHandler.handle(event)

        case ("POST", "/auth/refresh-token") =>
          RefreshTokenHandler.handle(event)

      case ("GET", "/auth/me") =>
          MeHandler.handle(event)

        // ---------- Friends Endpoints ----------
        case ("GET", "/api/friends/profile") =>
          val user = MeHandler.decode(event)
          getFriendsProfileHandler.handle(user, event)

        case ("GET", "/api/friends") =>
          val user = MeHandler.decode(event)
          listFriendsHandler.handle(user, event)

        case ("POST", "/api/friends") =>
          val user = MeHandler.decode(event)
          addFriendHandler.handle(user, event)

        case ("DELETE", p) if p.startsWith("/api/friends/") =>
          val friendUserArn = p.stripPrefix("/api/friends/")
          val user = MeHandler.decode(event)
          removeFriendHandler.handle(user, event, friendUserArn)

        // ---------- Garage Endpoints ----------
        case ("GET", "/api/garage") =>
          val user = MeHandler.decode(event)
          listGarageCarsHandler.handle(user, event)

        case ("POST", "/api/garage") =>
          val user = MeHandler.decode(event)
          addCarToGarageHandler.handle(user, event)

        case ("GET", p) if p.startsWith("/api/garage/") =>
          val carArn = decodePathSegment(p.stripPrefix("/api/garage/"))
          val user = MeHandler.decode(event)
          getCarHandler.handle(user, event, carArn)

        case ("PUT", p) if p.startsWith("/api/garage/") =>
          val carArn = decodePathSegment(p.stripPrefix("/api/garage/"))
          val user = MeHandler.decode(event)
          updateCarHandler.handle(user, event, carArn)

        case ("DELETE", p) if p.startsWith("/api/garage/") =>
          val carArn = decodePathSegment(p.stripPrefix("/api/garage/"))
          val user = MeHandler.decode(event)
          deleteCarHandler.handle(user, event, carArn)

        // ---------- Trip Endpoints ----------
        case ("GET", "/api/trips") =>
          GetUserTripsHandler.handle(event)

        case ("GET", p) if p.matches("/api/trips/.+/users") =>
          val tripId = p.stripPrefix("/api/trips/").stripSuffix("/users")
          ListTripUsersHandler.handle(event, tripId)

        case ("GET", p) if p.startsWith("/api/trips/") =>
          val tripId = p.stripPrefix("/api/trips/")
          GetTripByIdHandler.handle(event, tripId)

        case ("POST", "/api/trips") =>
          createTripHandler.handle(event)

        case ("PUT", p) if p.startsWith("/api/trips/") =>
          val tripArn = p.stripPrefix("/api/trips/")
          val user = MeHandler.decode(event)
          updateTripMetadataHandler.handle(user.userArn, event, tripArn)

        case ("POST", p) if p.matches("/api/trips/.+/locations/.+/arrival") =>
          val parts = p.stripPrefix("/api/trips/").split("/locations/")
          val tripArn = parts(0)
          val locationName = parts(1).stripSuffix("/arrival")
          FlipLocationArrivalHandler.handle(event)

        case ("GET", p) if p.startsWith("/user-groups/") || p.startsWith("/api/user-groups/") =>
          val groupArn = decodePathSegment(p.stripPrefix("/api/user-groups/").stripPrefix("/user-groups/"))
          GetUserGroupHandler.handle(event, groupArn)

        case ("PUT", p) if p.startsWith("/user-groups/") || p.startsWith("/api/user-groups/") =>
          val groupArn = decodePathSegment(p.stripPrefix("/api/user-groups/").stripPrefix("/user-groups/"))
          UpdateUserGroupHandler.handle(event)

        case ("POST", "/user-groups") | ("POST", "/api/user-groups") =>
          CreateUserGroupHandler.handle(event)

        case ("POST", p) if p.matches("/user-groups/.+/accept") || p.matches("/api/user-groups/.+/accept") =>
          val groupArn = decodePathSegment(p.stripPrefix("/api/user-groups/").stripPrefix("/user-groups/").stripSuffix("/accept"))
          AcceptInvitationHandler.handle(event, groupArn)

        case ("POST", p) if p.matches("/user-groups/.+/join") || p.matches("/api/user-groups/.+/join") =>
          val groupArn = decodePathSegment(p.stripPrefix("/api/user-groups/").stripPrefix("/user-groups/").stripSuffix("/join"))
          JoinUserGroupHandler.handle(event)

        case ("POST", p) if p.matches("/api/trips/.+/start") =>
          val tripArn = p.stripPrefix("/api/trips/").stripSuffix("/start")
          StartTripHandler.handle(event, tripArn)

        case ("POST", p) if p.startsWith("/api/trips/") && p.endsWith("/leave") =>
          val tripArn = p.stripPrefix("/api/trips/").stripSuffix("/leave")
          LeaveTripHandler.handle(event, tripArn)

        case ("POST", p) if p.matches("/api/trips/.+/driver") =>
          val tripArn = p.stripPrefix("/api/trips/").stripSuffix("/driver")
          BecomeDriverHandler.handle(event)

        case ("POST", p) if p.matches("/api/trips/.+/invite-driver") =>
          val tripArn = p.stripPrefix("/api/trips/").stripSuffix("/invite-driver")
          InviteDriverHandler.handle(event, tripArn)

        case ("POST", p) if p.matches("/api/trips/.+/accept-driver-invitation") =>
          val tripArn = p.stripPrefix("/api/trips/").stripSuffix("/accept-driver-invitation")
          AcceptDriverInvitationHandler.handle(event, tripArn)

        // ---------- Fallback for unknown routes ----------
        case _ =>
          Responses.json(404, """{"error":"Not Found"}""")
      }
    } catch {
      case ve: ValidationException =>
        Responses.json(400, s"""{"error":"Bad Request","message":"${ve.getMessage}"}""")
      case ue: UnauthorizedException =>
        Responses.json(401, s"""{"error":"Unauthorized","message":"${ue.getMessage}"}""")
      case e: Exception =>
        val cls = e.getClass.getName
        val msg = Option(e.getMessage).getOrElse(e.toString)
        val stack = Option(e.getStackTrace).map(_.take(8).map(st => s"${st.getClassName}.${st.getMethodName}:${st.getLineNumber}").mkString(" | ")).getOrElse("")
        Responses.json(500, s"""{"error":"Internal Server Error","message":"%s: %s","stack":"%s"}""".format(cls, msg, stack))
    }
  }
}
