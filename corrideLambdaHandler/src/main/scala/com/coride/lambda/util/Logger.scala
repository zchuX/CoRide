package com.coride.lambda.util

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

object Logger {
  def logStart(event: APIGatewayProxyRequestEvent, context: Context): Unit = {
    val method = Option(event.getHttpMethod).getOrElse("")
    val path = Option(event.getResource).orElse(Option(event.getPath)).getOrElse("")
    val reqId = Option(context.getAwsRequestId).getOrElse("")
    println(s"{" +
      s"\"event\":\"start\"," +
      s"\"requestId\":\"$reqId\"," +
      s"\"method\":\"$method\"," +
      s"\"path\":\"$path\"" +
      s"}")
  }

  def logEnd(event: APIGatewayProxyRequestEvent, context: Context, status: Int, startedAtMillis: Long): Unit = {
    val duration = System.currentTimeMillis() - startedAtMillis
    val reqId = Option(context.getAwsRequestId).getOrElse("")
    println(s"{" +
      s"\"event\":\"end\"," +
      s"\"requestId\":\"$reqId\"," +
      s"\"status\":$status," +
      s"\"durationMs\":$duration" +
      s"}")
  }

  def info(msg: String): Unit = println(s"{\"level\":\"INFO\",\"message\":\"${escape(msg)}\"}")
  def warn(msg: String): Unit = println(s"{\"level\":\"WARN\",\"message\":\"${escape(msg)}\"}")
  def error(msg: String): Unit = println(s"{\"level\":\"ERROR\",\"message\":\"${escape(msg)}\"}")

  private def escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}