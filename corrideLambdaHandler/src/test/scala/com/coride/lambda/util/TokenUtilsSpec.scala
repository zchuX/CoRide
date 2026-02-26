package com.coride.lambda.util

import org.scalatest.funsuite.AnyFunSuite

class TokenUtilsSpec extends AnyFunSuite {
  test("bearer extracts token from Authorization header") {
    val headers = new java.util.HashMap[String, String]()
    headers.put("Authorization", "Bearer abc123")
    assert(TokenUtils.bearer(headers).contains("abc123"))
  }

  test("bearer handles lowercase authorization header and trims") {
    val headers = new java.util.HashMap[String, String]()
    headers.put("authorization", "Bearer   xyz ")
    assert(TokenUtils.bearer(headers).contains("xyz"))
  }

  test("bearer returns None when header missing or empty") {
    val headers = new java.util.HashMap[String, String]()
    assert(TokenUtils.bearer(headers).isEmpty)
    val headers2 = new java.util.HashMap[String, String]()
    headers2.put("Authorization", "")
    assert(TokenUtils.bearer(headers2).isEmpty)
  }
}