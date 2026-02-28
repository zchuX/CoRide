package com.coride.tripdao

import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import scala.jdk.CollectionConverters._

object Attrs {
  def s(v: String): AttributeValue = AttributeValue.builder().s(v).build()
  def n(v: Long): AttributeValue = AttributeValue.builder().n(v.toString).build()
  def nInt(v: Int): AttributeValue = AttributeValue.builder().n(v.toString).build()
  def bool(v: Boolean): AttributeValue = AttributeValue.builder().bool(v).build()
  def list(vs: List[AttributeValue]): AttributeValue = AttributeValue.builder().l(vs.asJava).build()
  def map(m: Map[String, AttributeValue]): AttributeValue = AttributeValue.builder().m(m.asJava).build()
}
