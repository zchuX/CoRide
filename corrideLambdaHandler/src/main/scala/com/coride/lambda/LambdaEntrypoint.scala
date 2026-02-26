package com.coride.lambda

import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import com.coride.lambda.router.ApiRouter
import com.coride.lambda.util.Logger

class LambdaEntrypoint extends RequestHandler[APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent] {
  private val router = new ApiRouter()

  override def handleRequest(event: APIGatewayProxyRequestEvent, context: Context): APIGatewayProxyResponseEvent = {
    val started = System.currentTimeMillis()
    Logger.logStart(event, context)
    val response = router.handleRequest(event, context)
    val status = Option(response.getStatusCode).map(_.intValue()).getOrElse(0)
    Logger.logEnd(event, context, status, started)
    response
  }
}