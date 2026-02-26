package com.coride.lambda.ttl

import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import scala.jdk.CollectionConverters._

class TtlHandler extends RequestHandler[DynamodbEvent, Unit] {
  override def handleRequest(event: DynamodbEvent, context: Context): Unit = {
    val logger = context.getLogger
    val records = Option(event.getRecords).map(_.asScala).getOrElse(Seq.empty)
    for (record <- records) {
      val eventName = record.getEventName
      if (eventName == "REMOVE") {
        val keys = Option(record.getDynamodb).flatMap(d => Option(d.getKeys)).orNull
        logger.log(s"TTL deletion observed: keys=$keys source=DynamoDBStream")
      } else {
        logger.log(s"Stream record: $eventName")
      }
    }
  }
}