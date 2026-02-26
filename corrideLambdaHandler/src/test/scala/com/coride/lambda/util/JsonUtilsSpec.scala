package com.coride.lambda.util

import org.scalatest.funsuite.AnyFunSuite

class JsonUtilsSpec extends AnyFunSuite {
  test("parse returns empty object for null or empty body") {
    val n1 = JsonUtils.parse(null)
    val n2 = JsonUtils.parse("")
    assert(n1.size() == 0)
    assert(n2.size() == 0)
  }

  test("get and require work with existing fields") {
    val node = JsonUtils.parse("""{"a":"b"}""")
    assert(JsonUtils.get(node, "a").contains("b"))
    assert(JsonUtils.require(node, "a") == "b")
  }

  test("require throws for missing field") {
    val node = JsonUtils.parse("{}")
    assertThrows[RuntimeException] {
      JsonUtils.require(node, "missing")
    }
  }
}