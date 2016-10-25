package sla.unit

import org.scalatest.FunSuite
import sla.MockTime
import sla.strategy.{FixedRefillStrategy, RefillStrategy, TokenBucketRateLimiter}

class TokenBucketRateLimiterTest extends FunSuite {

  trait Config {
    val rps: Int = 100
    val interval = 10  // 1/10 of a second
    val numTokens = rps / 10
    val time = new MockTime
    val refillStrategy: RefillStrategy =
      new FixedRefillStrategy(numTokens, interval, time)
    val rateLimiter = new TokenBucketRateLimiter(rps, refillStrategy)
  }

  test("expect correct request limiting") {
    new Config {
      for(i <- 0 until interval) {
        assert(rateLimiter.isRequestAllowed)
      }
      // rate limit reached and request must be denied
      assert(!rateLimiter.isRequestAllowed)

      time.sleep(interval)
      assert(rateLimiter.isRequestAllowed)
    }
  }

  test("allowed rps should not exceed after long delay") {
    new Config {
      time.sleep(10000)
      for(i <- 0 until interval) {
        assert(rateLimiter.isRequestAllowed)
      }
      // rate limit reached and request must be denied
      assert(!rateLimiter.isRequestAllowed)
    }
  }

}
