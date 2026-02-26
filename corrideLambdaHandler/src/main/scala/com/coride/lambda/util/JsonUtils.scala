package com.coride.lambda.util

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

object JsonUtils {
  private val mapper = new ObjectMapper()

  def parse(body: String): JsonNode = {
    if (body == null || body.isEmpty) mapper.createObjectNode()
    else mapper.readTree(body)
  }

  def get(node: JsonNode, field: String): Option[String] = {
    val n = node.get(field)
    if (n != null && !n.isNull) Option(n.asText()) else None
  }

  def require(node: JsonNode, field: String): String = {
    get(node, field).getOrElse(throw new RuntimeException(s"Missing field: $field"))
  }
}