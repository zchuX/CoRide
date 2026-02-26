package com.coride.lambda.util

object TokenUtils {
  def bearer(headers: java.util.Map[String, String]): Option[String] = {
    if (headers == null) return None
    val auth = Option(headers.get("Authorization")).orElse(Option(headers.get("authorization")))
    auth.map(_.replace("Bearer ", "").trim).filter(_.nonEmpty)
  }
}