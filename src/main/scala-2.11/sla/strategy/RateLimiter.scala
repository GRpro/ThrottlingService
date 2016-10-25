package sla.strategy

import java.util.concurrent.atomic.AtomicLong

import scala.annotation.tailrec

trait RateLimiter {
  val rps: Int

  def isRequestAllowed: Boolean
}

trait RateLimiterFactory {
  def create(rps: Int): RateLimiter
}

object RateLimiterFactory {
  def tokenBucketRateLimiterFactory(supplyStrategy: RefillStrategy): RateLimiterFactory =
    new RateLimiterFactory {
      override def create(rps: Int): RateLimiter = new TokenBucketRateLimiter(rps, supplyStrategy)
    }
}

/**
  * Implementation of token-bucket algorithm
  * with plugable token supply strategy
  *
  * @param rps            maximum allowed requests per second
  * @param supplyStrategy concrete token supply strategy
  */
class TokenBucketRateLimiter(val rps: Int, val supplyStrategy: RefillStrategy) extends RateLimiter {
  val size: AtomicLong = new AtomicLong(0L)

  override def isRequestAllowed: Boolean = {
    val newTokens: Long = math.max(0, supplyStrategy.refill)

    @tailrec
    def check: Boolean = {
      val existingSize: Long = size.get
      var newValue: Long = math.max(0, math.min(existingSize + newTokens, rps))
      if (newValue > 0) {
        newValue -= 1
        if (size.compareAndSet(existingSize, newValue)) true
        else check
      } else false
    }

    check
  }
}

trait RefillStrategy {
  def refill: Long
}

/**
  * Refill to #numTokens every #interval
  *
  * @param numTokens number of tokens to supply
  * @param interval  time in milliseconds
  */
class FixedRefillStrategy(val numTokens: Int, val interval: Int, val time: Time = Time.default) extends RefillStrategy {
  val nextRefillTime: AtomicLong = new AtomicLong(0L)

  @tailrec
  final override def refill: Long = {
    val now: Long = time.currentMillis
    val refillTime: Long = nextRefillTime.get
    if (now < refillTime) 0
    else {
      if (nextRefillTime.compareAndSet(refillTime, now + interval)) numTokens
      else refill
    }
  }
}

trait Time {
  def currentMillis: Long

  def sleep(millis: Long)
}

object Time {
  def default = new Time {
    override def currentMillis: Long = System.currentTimeMillis()

    override def sleep(millis: Long): Unit = Thread.sleep(millis)
  }
}

