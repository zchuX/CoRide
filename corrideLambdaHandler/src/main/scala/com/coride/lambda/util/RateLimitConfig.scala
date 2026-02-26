package com.coride.lambda.util

object RateLimitConfig {
  private def readInt(name: String, default: Int): Int = {
    val v = sys.env.get(name)
    v.flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(default)
  }

  val loginLimit: Int = readInt("LOGIN_LIMIT", 5)
  val loginWindow: Int = readInt("LOGIN_WINDOW", 60)

  // OTP issuance by email: loose cap, e.g., 20 per 12 hours
  val otpEmailLimit: Int = readInt("OTP_EMAIL_LIMIT", 20)
  val otpEmailWindow: Int = readInt("OTP_EMAIL_WINDOW", 43200)

  val otpPhoneLimit: Int = readInt("OTP_PHONE_LIMIT", 2)
  val otpPhoneWindow: Int = readInt("OTP_PHONE_WINDOW", 60)

  val resetSendLimit: Int = readInt("RESET_SEND_LIMIT", 2)
  val resetSendWindow: Int = readInt("RESET_SEND_WINDOW", 60)

  val resetConfirmLimit: Int = readInt("RESET_CONFIRM_LIMIT", 10)
  val resetConfirmWindow: Int = readInt("RESET_CONFIRM_WINDOW", 300)

  // Verification confirm attempts per IP (minimal API protection)
  val verifyConfirmLimit: Int = readInt("VERIFY_CONFIRM_LIMIT", 10)
  val verifyConfirmWindow: Int = readInt("VERIFY_CONFIRM_WINDOW", 300)
}