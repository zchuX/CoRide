package com.coride.lambda.features.auth

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.lambda.router.UnauthorizedException
import com.coride.lambda.util.TokenUtils
import com.coride.lambda.util.JwtUtils
import com.coride.userdao.UserDAO
import com.coride.lambda.services.CognitoClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, GetItemRequest}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient

object MeHandler {
  private val usersTableName: String = Option(System.getenv("USERS_TABLE_NAME")).getOrElse("Users")
  private val contactIndexTableName: String = Option(System.getenv("USER_CONTACT_INDEX_TABLE_NAME")).getOrElse("UserContactIndex")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val userPoolId: String = Option(System.getenv("USER_POOL_ID")).getOrElse("")
  private val userPoolClientId: String = Option(System.getenv("USER_POOL_CLIENT_ID")).getOrElse("")
  private val ddb: DynamoDbClient = DynamoDbClient.builder()
    .region(Region.of(awsRegion))
    .httpClient(UrlConnectionHttpClient.builder().build())
    .build()
  private val dao = new UserDAO(ddb, usersTableName, contactIndexTableName)
  private val jwt = new JwtUtils(userPoolId, awsRegion, userPoolClientId)
  private val cognito = new CognitoClient()

  def decode(event: APIGatewayProxyRequestEvent): com.coride.userdao.User = {
    val token = TokenUtils.bearer(event.getHeaders).getOrElse(throw new UnauthorizedException("Missing bearer token"))
    val verified = jwt.verifyIdToken(token).getOrElse(throw new UnauthorizedException("Invalid or expired ID token"))
    val keyOpt = verified.username.filter(_.nonEmpty).orElse(Some(verified.sub))
    val uOpt = keyOpt.flatMap(dao.getUser)
    uOpt.getOrElse(throw new UnauthorizedException("User profile not initialized"))
  }

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    try {
      val u = decode(event)
      val emailVal = u.email.map(e => s"\"$e\"").getOrElse("null")
      val phoneVal = u.phone.map(p => s"\"$p\"").getOrElse("null")
      val descVal = u.description.map(d => s"\"$d\"").getOrElse("null")
      val photoVal = u.photoUrl.map(p => s"\"$p\"").getOrElse("null")
      val body = s"""{"status":"ok","user":{"userArn":""" + u.userArn + ""","name":""" + u.name + ""","email":""" + emailVal + ""","phone_number": """ + phoneVal + ""","description": """ + descVal + ""","photoUrl": """ + photoVal + ""","totalPassengerDelivered": """ + u.totalPassengerDelivered + """, "totalCarpoolJoined": """ + u.totalCarpoolJoined + """}}"""
      Responses.json(200, body)
    } catch {
      case e: UnauthorizedException => Responses.json(401, s"""{"error":"Unauthorized","message":"${e.getMessage}"}""")
      case e: Exception => Responses.json(500, s"""{"error":"Internal Server Error","message":"${e.getMessage}"}""")
    }
  }
}