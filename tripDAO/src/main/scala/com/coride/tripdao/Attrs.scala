package com.coride.tripdao

import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import scala.jdk.CollectionConverters._

object Attrs {
  def s(v: String): AttributeValue = AttributeValue.builder().s(v).build()
  def n(v: Long): AttributeValue = AttributeValue.builder().n(v.toString).build()
  def nInt(v: Int): AttributeValue = AttributeValue.builder().n(v.toString).build()
  def bool(v: Boolean): AttributeValue = AttributeValue.builder().bool(v).build()
  // Use explicit Java collections to avoid Scala .asJava -> Lambda ClassLoader Nothing inference
  def list(vs: List[AttributeValue]): AttributeValue = {
    val jlist = new java.util.ArrayList[AttributeValue](vs.size)
    vs.foreach(v => jlist.add(v))
    AttributeValue.builder().l(jlist).build()
  }
  def map(m: Map[String, AttributeValue]): AttributeValue = {
    val jmap = new java.util.HashMap[String, AttributeValue](m.size)
    m.foreach { case (k, v) => jmap.put(k, v) }
    AttributeValue.builder().m(jmap).build()
  }
}
