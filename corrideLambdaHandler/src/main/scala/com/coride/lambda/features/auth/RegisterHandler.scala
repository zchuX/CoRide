package com.coride.lambda.features.auth

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.lambda.util.JsonUtils
import com.coride.lambda.util.Validation
import com.coride.lambda.util.{RateLimitUtils, RateLimitConfig}
import com.coride.lambda.services.CognitoClient
import com.coride.lambda.router.ValidationException
import com.amazonaws.services.cognitoidp.model.UsernameExistsException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, PutItemRequest}
// NOTE: Persistence occurs only after OTP validation in VerifyCodeHandler

object RegisterHandler {
  private val cognito = new CognitoClient()
  // Shared rate limiter utils
  // DAO intentionally not used here to avoid committing before OTP verification
  private val awsRegion: String = Option(System.getenv("AWS_REGION")).getOrElse("us-east-1")
  private val ddb = DynamoDbClient.builder()
    .region(Region.of(awsRegion))
    .httpClient(UrlConnectionHttpClient.builder().build())
    .build()
  private val rateTableName: String = Option(System.getenv("RATE_LIMIT_TABLE")).getOrElse("")
  private def normalizeEmail(e: String): String = e.trim.toLowerCase
  private def normalizePhone(p: String): String = p.trim.replaceAll("\\s+", "")

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    // Explicitly forbid client-supplied userId; it is internal and auto-generated
    if (JsonUtils.get(node, "userId").nonEmpty)
      throw new ValidationException("Field 'userId' is not allowed; provide email or phone_number.")
    if (JsonUtils.get(node, "username").nonEmpty)
      throw new ValidationException("Field 'username' is not allowed; provide email or phone_number.")
    val password = JsonUtils.require(node, "password")
    val email = JsonUtils.get(node, "email")
    val phone = JsonUtils.get(node, "phone_number")
    val nameOpt = JsonUtils.get(node, "name")
    val name = nameOpt.getOrElse(throw new ValidationException("Missing field: name"))

    if (!Validation.isValidPassword(password)) throw new ValidationException("Password must be at least 6 characters")
    email.foreach(e => if (!Validation.isValidEmail(e)) throw new ValidationException("Invalid email format"))
    phone.foreach(p => if (!Validation.isValidPhoneE164(p)) throw new ValidationException("Invalid phone format (E.164 expected)") )
    val providedCount = List(email, phone).count(_.isDefined)
    if (providedCount == 0) throw new ValidationException("One of 'email' or 'phone_number' is required")
    if (providedCount > 1) throw new ValidationException("Provide only one of 'email' or 'phone_number'")
    val username = email.map(normalizeEmail).orElse(phone.map(normalizePhone)).get
    if (!Validation.nonEmpty(name)) throw new ValidationException("Name must not be empty")

    // Minimal per-IP rate limit to avoid API spam
    val allowed = RateLimitUtils.checkIp(event, namespace = "reg", limit = 5, windowSeconds = 300)
    if (!allowed) return Responses.json(429, """{"error":"Too Many Requests","retryAfter":300}""")

    // OTP issuance: email-only rate limit (loose cap)
    email.foreach { e =>
      if (!RateLimitUtils.checkUser(e, namespace = "otp-email", limit = RateLimitConfig.otpEmailLimit, windowSeconds = RateLimitConfig.otpEmailWindow))
        return Responses.json(429, s"""{"error":"Too Many Requests","retryAfter":${RateLimitConfig.otpEmailWindow}}""")
    }
    // Optional: phone OTP issuance rate limit
    phone.foreach { p =>
      if (!RateLimitUtils.checkUser(p, namespace = "otp-phone", limit = RateLimitConfig.otpPhoneLimit, windowSeconds = RateLimitConfig.otpPhoneWindow))
        return Responses.json(429, s"""{"error":"Too Many Requests","retryAfter":${RateLimitConfig.otpPhoneWindow}}""")
    }

    try {
      val _ = cognito.signUp(username, password, email, phone, Some(name))
      // Gate OTP validity to 30 minutes from issuance via DynamoDB TTL marker
      val now = (System.currentTimeMillis() / 1000L).toLong
      val putItem = java.util.Map.of(
        "key", AttributeValue.builder().s(s"otp:issued:user:$username").build(),
        "count", AttributeValue.builder().n("1").build(),
        "ttl", AttributeValue.builder().n((now + 1800).toString).build()
      )
      val putReq = PutItemRequest.builder().tableName(rateTableName).item(putItem).build()
      ddb.putItem(putReq)
      Responses.json(200, """{"status":"ok","message":"verification code sent"}""")
    } catch {
      // If the user already exists and is UNCONFIRMED, resend the confirmation code and return 200 idempotently
      case _: UsernameExistsException =>
        val now = (System.currentTimeMillis() / 1000L).toLong
        try {
          val adminUser = cognito.adminGetUser(username)
          val status = Option(adminUser.getUserStatus).map(_.toString).getOrElse("")
          if (status == "UNCONFIRMED") {
            val _ = cognito.resendConfirmationCode(username)
            // Mark OTP issuance TTL to keep verification window consistent
            val putItem = java.util.Map.of(
              "key", AttributeValue.builder().s(s"otp:issued:user:$username").build(),
              "count", AttributeValue.builder().n("1").build(),
              "ttl", AttributeValue.builder().n((now + 1800).toString).build()
            )
            val putReq = PutItemRequest.builder().tableName(rateTableName).item(putItem).build()
            ddb.putItem(putReq)
            return Responses.json(200, """{"status":"ok","message":"verification code resent"}""")
          } else {
            return Responses.json(409, """{"error":"Conflict","message":"User already exists and is confirmed. Please login or reset password."}""")
          }
        } catch {
          case _: Throwable =>
            return Responses.json(409, """{"error":"Conflict","message":"User already exists. Please login or reset password."}""")
        }
      case _: Throwable =>
        Responses.json(500, """{"error":"Internal Server Error","message":"Registration failed"}""")
    }
  }
}