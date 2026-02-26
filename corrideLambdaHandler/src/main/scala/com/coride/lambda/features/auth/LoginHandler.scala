package com.coride.lambda.features.auth

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.lambda.util.JsonUtils
import com.coride.lambda.services.CognitoClient
import com.coride.lambda.util.Logger
import com.coride.lambda.util.RateLimitUtils
import com.coride.lambda.util.RateLimitConfig
import com.coride.ratelimitdao.RateLimitDAO
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, GetItemRequest, PutItemRequest}

object LoginHandler {
  private val cognito = new CognitoClient()
  private val rateDao = new RateLimitDAO()
  private val ddb = {
    val regionName: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
    DynamoDbClient.builder()
      .region(Region.of(regionName))
      .httpClient(UrlConnectionHttpClient.builder().build())
      .build()
  }
  private val rateTableName: String = Option(System.getenv("RATE_LIMIT_TABLE")).getOrElse("")
  private def normalizeEmail(e: String): String = e.trim.toLowerCase
  private def normalizePhone(p: String): String = p.trim.replaceAll("\\s+", "")

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    // Explicitly forbid client-supplied userId in login payloads
    if (JsonUtils.get(node, "userId").nonEmpty)
      throw new com.coride.lambda.router.ValidationException("Field 'userId' is not allowed; use 'email' or 'phone_number'.")
    // Do not allow client-supplied username; identify by email or phone_number only
    if (JsonUtils.get(node, "username").nonEmpty)
      throw new com.coride.lambda.router.ValidationException("Field 'username' is not allowed; provide email or phone_number.")
    val emailOpt = JsonUtils.get(node, "email")
    val phoneOpt = JsonUtils.get(node, "phone_number")
    val providedCount = List(emailOpt, phoneOpt).count(_.isDefined)
    if (providedCount == 0) throw new com.coride.lambda.router.ValidationException("Missing field: email or phone_number")
    if (providedCount > 1) throw new com.coride.lambda.router.ValidationException("Provide only one of 'email' or 'phone_number'")
    // Choose identifier and validate format when applicable
    emailOpt.foreach(e => if (!com.coride.lambda.util.Validation.isValidEmail(normalizeEmail(e))) throw new com.coride.lambda.router.ValidationException("Invalid email format"))
    phoneOpt.foreach(p => if (!com.coride.lambda.util.Validation.isValidPhoneE164(normalizePhone(p))) throw new com.coride.lambda.router.ValidationException("Invalid phone format (E.164 expected)"))
    val username = emailOpt.map(normalizeEmail).orElse(phoneOpt.map(normalizePhone)).get
    val password = JsonUtils.require(node, "password")
    val now = (System.currentTimeMillis() / 1000L).toLong
    val lockKey = s"login:lock:user:$username"

    // Pre-check: if account is locked, short-circuit before hitting Cognito
    val lockGetReq = GetItemRequest.builder()
      .tableName(rateTableName)
      .key(java.util.Map.of("key", AttributeValue.builder().s(lockKey).build()))
      .consistentRead(true)
      .build()
    val lockItem = Option(ddb.getItem(lockGetReq).item()).map(_.get("ttl")).flatMap(a => Option(a)).flatMap(a => Option(a.n())).map(_.toLong)
    lockItem.foreach { ttl =>
      if (ttl > now) {
        val retry = Math.max(0L, ttl - now).toInt
        Logger.warn(s"Login locked for user=$username retryAfter=$retry")
        return Responses.json(423, s"""{"error":"Account Locked","retryAfter":%d}""".format(retry))
      }
    }
    // Extract client IP from request context and apply rate limit from env
    if (!RateLimitUtils.checkIp(event, "login", RateLimitConfig.loginLimit, RateLimitConfig.loginWindow)) {
      return Responses.json(429, s"""{"error":"Too Many Requests","retryAfter":${RateLimitConfig.loginWindow}}""")
    }
    Logger.info(s"Login attempt for identifier=$username")
    try {
      val auth = cognito.initiatePasswordAuth(username, password)
      val body = s"""{
        "status":"ok",
        "idToken":"${auth.getIdToken}",
        "accessToken":"${auth.getAccessToken}",
        "refreshToken":"${auth.getRefreshToken}"
      }"""
      Responses.json(200, body)
    } catch {
      case _: Throwable =>
        // Track failed attempts per user; lock for 30 min after 5 failures
        val decision = rateDao.checkAndIncrement(s"login:fail:user:$username", 1800, 5)
        if (!decision.allowed) {
          val lockFor = Math.max(1, decision.retryAfterSeconds)
          val putItem = java.util.Map.of(
            "key", AttributeValue.builder().s(lockKey).build(),
            "count", AttributeValue.builder().n("1").build(),
            "ttl", AttributeValue.builder().n((now + lockFor).toString).build()
          )
          val putReq = PutItemRequest.builder().tableName(rateTableName).item(putItem).build()
          ddb.putItem(putReq)
          Logger.warn(s"Account locked for user=$username for ${lockFor}s after failed attempts")
          Responses.json(423, s"""{"error":"Account Locked","retryAfter":%d}""".format(lockFor))
        } else {
          Responses.json(401, """{"error":"Unauthorized","message":"Invalid credentials"}""")
        }
    }
  }
}