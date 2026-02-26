package com.coride.lambda.features.auth

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.util.Responses
import com.coride.lambda.util.JsonUtils
import com.coride.lambda.util.Validation
import com.coride.lambda.util.{RateLimitUtils, RateLimitConfig}
import com.coride.lambda.services.CognitoClient
import com.coride.lambda.router.ValidationException
// no additional AWS imports needed

object ResetPasswordHandler {
  private val cognito = new CognitoClient()
  private def normalizeEmail(e: String): String = e.trim.toLowerCase
  private def normalizePhone(p: String): String = p.trim.replaceAll("\\s+", "")

  def handle(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    val node = JsonUtils.parse(event.getBody)
    // Forbid client-supplied userId; use email/phone_number
    if (JsonUtils.get(node, "userId").nonEmpty)
      throw new ValidationException("Field 'userId' is not allowed; use 'email' or 'phone_number'.")
    // Do not allow client-supplied username
    if (JsonUtils.get(node, "username").nonEmpty)
      throw new ValidationException("Field 'username' is not allowed; provide email or phone_number.")
    // Accept exactly ONE of: email, phone_number
    val emailOpt = JsonUtils.get(node, "email")
    val phoneOpt = JsonUtils.get(node, "phone_number")
    val providedCount = List(emailOpt, phoneOpt).count(_.isDefined)
    if (providedCount == 0) throw new ValidationException("One of 'email' or 'phone_number' is required")
    if (providedCount > 1) throw new ValidationException("Provide only one of 'email' or 'phone_number'")
    val targetId: String = emailOpt.map(normalizeEmail).orElse(phoneOpt.map(normalizePhone)).get

    // Validate formats when present
    emailOpt.foreach(e => if (!Validation.isValidEmail(normalizeEmail(e))) throw new ValidationException("Invalid email format"))
    phoneOpt.foreach(p => if (!Validation.isValidPhoneE164(normalizePhone(p))) throw new ValidationException("Invalid phone format (E.164 expected)"))

    val codeOpt = JsonUtils.get(node, "code")
    val newPasswordOpt = JsonUtils.get(node, "newPassword")

    (codeOpt, newPasswordOpt) match {
      case (Some(code), Some(newPassword)) =>
        if (!Validation.isValidPassword(newPassword)) throw new ValidationException("Password must be at least 6 characters")
        // Minimal per-IP limit for confirm attempts
        if (!RateLimitUtils.checkIp(event, namespace = "reset-confirm", limit = RateLimitConfig.resetConfirmLimit, windowSeconds = RateLimitConfig.resetConfirmWindow))
          return Responses.json(429, s"""{"error":"Too Many Requests","retryAfter":${RateLimitConfig.resetConfirmWindow}}""")
        val _ = cognito.confirmForgotPassword(targetId, code, newPassword)
        Responses.json(200, """{"status":"ok","message":"password updated"}""")
      case _ =>
        // Minimal per-IP limit for sending reset codes
        val allowed = RateLimitUtils.checkIp(event, namespace = "reset", limit = RateLimitConfig.resetSendLimit, windowSeconds = RateLimitConfig.resetSendWindow)
        if (!allowed) return Responses.json(429, s"""{"error":"Too Many Requests","retryAfter":${RateLimitConfig.resetSendWindow}}""")

        val _ = cognito.forgotPassword(targetId)
        Responses.json(200, """{"status":"ok","message":"reset code sent"}""")
    }
  }
}