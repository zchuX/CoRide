package com.coride.lambda.util

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.coride.tripdao.TripDAO

object VersioningUtils {
  private def parseExpectedVersion(event: APIGatewayProxyRequestEvent): Option[Int] = {
    val body = Option(event.getBody).getOrElse("")
    val json = JsonUtils.parse(body)
    val v = json.get("version")
    if (v != null && !v.isNull) {
      try { Some(v.asInt()) } catch { case _: Throwable => None }
    } else None
  }

  def tripExpectedVersion(event: APIGatewayProxyRequestEvent, dao: TripDAO, tripArn: String): Int = {
    parseExpectedVersion(event).orElse(dao.getTripMetadata(tripArn).map(_.version)).getOrElse(1)
  }

  def groupExpectedVersion(event: APIGatewayProxyRequestEvent, dao: TripDAO, groupArn: String): Int = {
    parseExpectedVersion(event).orElse(dao.getUserGroup(groupArn).map(_.version)).getOrElse(1)
  }

  def userTripExpectedVersion(event: APIGatewayProxyRequestEvent, dao: TripDAO, userTripArn: String): Int = {
    parseExpectedVersion(event).orElse(dao.getUserTrip(userTripArn).map(_.version)).getOrElse(1)
  }
}