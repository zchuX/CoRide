package com.coride.lambda.features.auth

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.lambda.util.JsonUtils
import com.coride.lambda.util.RateLimitUtils
import com.coride.lambda.util.RateLimitConfig
import com.coride.lambda.services.CognitoClient
import com.coride.userdao.{UserDAO, User}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, GetItemRequest, PutItemRequest, DeleteItemRequest}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient

object VerifyCodeHandler {
  private val cognito = new CognitoClient()
  // Shared rate limiter utils
  private val usersTableName: String = Option(System.getenv("USERS_TABLE_NAME")).getOrElse("Users")
  private val contactIndexTableName: String = Option(System.getenv("USER_CONTACT_INDEX_TABLE_NAME")).getOrElse("UserContactIndex")
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val ddb: DynamoDbClient = DynamoDbClient.builder()
    .region(Region.of(awsRegion))
    .httpClient(UrlConnectionHttpClient.builder().build())
    .build()
  private val dao = new UserDAO(ddb, usersTableName, contactIndexTableName)
  private val rateTableName: String = Option(System.getenv("RATE_LIMIT_TABLE")).getOrElse("")
  private def normalizeEmail(e: String): String = e.trim.toLowerCase
  private def normalizePhone(p: String): String = p.trim.replaceAll("\\s+", "")

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    val code = JsonUtils.require(node, "code")
    // Forbid client-supplied userId in verification payloads
    if (JsonUtils.get(node, "userId").nonEmpty)
      return Responses.json(400, """{"error":"Bad Request","message":"'userId' is internal-only and must not be provided"}""")
    // Do not allow client-supplied username; use email or phone_number
    if (JsonUtils.get(node, "username").nonEmpty)
      throw new com.coride.lambda.router.ValidationException("Field 'username' is not allowed; provide email or phone_number.")
    val emailOpt = JsonUtils.get(node, "email")
    val phoneOpt = JsonUtils.get(node, "phone_number")
    val providedCount = List(emailOpt, phoneOpt).count(_.isDefined)
    if (providedCount == 0) throw new com.coride.lambda.router.ValidationException("One of 'email' or 'phone_number' is required")
    if (providedCount > 1) throw new com.coride.lambda.router.ValidationException("Provide only one of 'email' or 'phone_number'")
    emailOpt.foreach(e => if (!com.coride.lambda.util.Validation.isValidEmail(normalizeEmail(e))) throw new com.coride.lambda.router.ValidationException("Invalid email format"))
    phoneOpt.foreach(p => if (!com.coride.lambda.util.Validation.isValidPhoneE164(normalizePhone(p))) throw new com.coride.lambda.router.ValidationException("Invalid phone format (E.164 expected)"))
    val username = emailOpt.map(normalizeEmail).orElse(phoneOpt.map(normalizePhone)).get
    val now = (System.currentTimeMillis() / 1000L).toLong
    val issuedKey = s"otp:issued:user:$username"
    val usedKey = s"otp:used:user:$username"
    // Minimal per-IP rate limit to avoid brute-force OTP confirmation
    val allowed = RateLimitUtils.checkIp(event, namespace = "verify", limit = RateLimitConfig.verifyConfirmLimit, windowSeconds = RateLimitConfig.verifyConfirmWindow)
    if (!allowed) return Responses.json(429, """{"error":"Too Many Requests","retryAfter":300}""")
    // Idempotency: if already CONFIRMED in Cognito, treat as success and ensure profile persistence
    val preAdminUser = cognito.adminGetUser(username)
    val preStatus = Option(preAdminUser.getUserStatus).map(_.toString).getOrElse("")
    if (preStatus == "CONFIRMED") {
      val attrs = preAdminUser.getUserAttributes
      val arr = attrs.toArray(new Array[com.amazonaws.services.cognitoidp.model.AttributeType](attrs.size()))
      val subAttr = arr.find(_.getName == "sub").map(_.getValue)
      val emailAttr = arr.find(_.getName == "email").map(_.getValue)
      val phoneAttr = arr.find(_.getName == "phone_number").map(_.getValue)
      val nameAttr = arr.find(_.getName == "name").map(_.getValue)
      // userArn = Cognito sub so JWT sub matches our DB; no lookup needed for auth
      val userArn = subAttr.filter(_.nonEmpty).getOrElse(preAdminUser.getUsername)

      val user = User(
        userArn = userArn,
        name = nameAttr.getOrElse(username),
        email = Option(emailAttr.orNull),
        phone = Option(phoneAttr.orNull)
      )
      dao.createUser(user)
      return Responses.json(200, """{"status":"ok","message":"account already verified"}""")
    }
    // Enforce 30-minute expiration window from latest issuance
    val issuedItemTtlOpt = Option(ddb.getItem(
      GetItemRequest.builder().tableName(rateTableName).key(java.util.Map.of("key", AttributeValue.builder().s(issuedKey).build())).consistentRead(true).build()
    ).item()).map(_.get("ttl")).flatMap(a => Option(a)).flatMap(a => Option(a.n())).map(_.toLong)
    if (!issuedItemTtlOpt.exists(_ > now)) {
      return Responses.json(400, """{"error":"Invalid OTP","message":"Code expired"}""")
    }
    val _ = cognito.confirmSignUp(username, code)
    // Mark OTP as used and clear issuance marker
    val putItem = java.util.Map.of(
      "key", AttributeValue.builder().s(usedKey).build(),
      "count", AttributeValue.builder().n("1").build(),
      "ttl", AttributeValue.builder().n((now + 1800).toString).build()
    )
    ddb.putItem(PutItemRequest.builder().tableName(rateTableName).item(putItem).build())
    val delKey = java.util.Map.of("key", AttributeValue.builder().s(issuedKey).build())
    ddb.deleteItem(DeleteItemRequest.builder().tableName(rateTableName).key(delKey).build())

    // Persist Users and contact index only AFTER successful OTP confirmation
    val adminUser = cognito.adminGetUser(username)
    val attrs = adminUser.getUserAttributes
    val arr = attrs.toArray(new Array[com.amazonaws.services.cognitoidp.model.AttributeType](attrs.size()))
    val subAttr = arr.find(_.getName == "sub").map(_.getValue)
    val emailAttr2 = arr.find(_.getName == "email").map(_.getValue)
    val phoneAttr2 = arr.find(_.getName == "phone_number").map(_.getValue)
    val nameAttr2 = arr.find(_.getName == "name").map(_.getValue)
    // userArn = Cognito sub so JWT sub matches our DB; caller identity is always from token
    val userArn = subAttr.filter(_.nonEmpty).getOrElse(adminUser.getUsername)

    val user = User(
      userArn = userArn,
      name = nameAttr2.getOrElse(username),
      email = Option(emailAttr2.orNull),
      phone = Option(phoneAttr2.orNull)
    )
    dao.createUser(user)
    Responses.json(200, """{"status":"ok","message":"account verified"}""")
  }
}