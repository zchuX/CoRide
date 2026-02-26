package com.coride.lambda.util

import com.coride.ratelimitdao.{RateLimitDAO, RateLimitDecision}

class RateLimiter(private val dao: RateLimitDAO = new RateLimitDAO()) {
  def checkAndIncrement(key: String, windowSeconds: Int, maxCount: Int): RateLimitDecision = {
    dao.checkAndIncrement(key, windowSeconds, maxCount)
  }
}

object RateLimiter {
  private val singleton = new RateLimiter()
  def checkRateLimit(key: String, limit: Int, windowSeconds: Int): Boolean = {
    val decision = singleton.checkAndIncrement(key, windowSeconds, limit)
    decision.allowed
  }
}