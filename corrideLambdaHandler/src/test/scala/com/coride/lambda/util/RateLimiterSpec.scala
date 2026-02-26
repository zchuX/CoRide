package com.coride.lambda.util

import org.scalatest.funsuite.AnyFunSuite
import com.coride.ratelimitdao.{RateLimitDAO, RateLimitDecision}

class RateLimiterSpec extends AnyFunSuite {
  class StubRateLimitDAO(decision: RateLimitDecision) extends RateLimitDAO() {
    override def checkAndIncrement(key: String, windowSeconds: Int, maxCount: Int): RateLimitDecision = decision
  }

  test("RateLimiter delegates decision to DAO and returns allowed flag") {
    val daoAllow = new StubRateLimitDAO(RateLimitDecision(allowed = true, retryAfterSeconds = 0))
    val limiterAllow = new RateLimiter(daoAllow)
    val dec1 = limiterAllow.checkAndIncrement("k", 60, 5)
    assert(dec1.allowed)

    val daoDeny = new StubRateLimitDAO(RateLimitDecision(allowed = false, retryAfterSeconds = 10))
    val limiterDeny = new RateLimiter(daoDeny)
    val dec2 = limiterDeny.checkAndIncrement("k", 60, 5)
    assert(!dec2.allowed)
  }
}