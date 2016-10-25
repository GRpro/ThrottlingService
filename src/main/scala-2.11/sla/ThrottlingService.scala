package sla


import sla.strategy.{FixedRefillStrategy, RateLimiter, RateLimiterFactory, TokenBucketRateLimiter}

import scala.concurrent.ExecutionContext
import scala.util.Success

trait ThrottlingService {
  val graceRps: Int

  val slaService: SlaService

  // Should return true if the request is within allowed RPS.
  def isRequestAllowed(token: Option[String]): Boolean
}

object ThrottlingService {

  def create(slaService: SlaService, properties: Map[String, String])(implicit executionContext: ExecutionContext) = {
    val graceRps = properties getOrElse(Config.graceRpcConfig, Config.graceRpcDefault)

    new ThrottlingServiceImpl(slaService, graceRps.toInt, new RateLimiterFactory {
      val refillFixedInterval = properties getOrElse(Config.refillFixedIntervalMsConfig, Config.refillFixedIntervalMsDefault)

      override def create(rps: Int): RateLimiter = {
        val numOfTokensPerBatch = (rps * (refillFixedInterval.toDouble / 1000)).toInt
        require(numOfTokensPerBatch > 0, "Wrong configuration. Cannot generate less than one token per interval")
        new TokenBucketRateLimiter(rps,
          new FixedRefillStrategy(
            numOfTokensPerBatch,
            refillFixedInterval.toInt))
      }

    })
  }
}


class ThrottlingServiceImpl(override val slaService: SlaService,
                            override val graceRps: Int,
                            val rateLimiterFactory: RateLimiterFactory) extends ThrottlingService {

  var authorizedUserToThrottleStrategy: Map[String, RateLimiter] = Map()
  val unauthorizedThrottleStrategy = rateLimiterFactory.create(graceRps)

  // Should return true if the request is within allowed RPS.
  override def isRequestAllowed(token: Option[String]): Boolean = token match {
    case None =>
      // assume client is unauthorized
      unauthorizedThrottleStrategy.isRequestAllowed
    case Some(tokenString) =>
      val slaFuture = slaService.getSlaByToken(tokenString)
      if (slaFuture.isCompleted) {
        slaFuture.value match {
          case Some(Success(sla)) =>
            val user = sla.user
            authorizedUserToThrottleStrategy get user match {
              case Some(thr) => thr.isRequestAllowed
              case None =>
                val thr = rateLimiterFactory.create(sla.rps)
                authorizedUserToThrottleStrategy = authorizedUserToThrottleStrategy + (user -> thr)
                thr.isRequestAllowed
            }
          case _ => false // return false on error
        }
      } else {
        // track as unauthorized request
        unauthorizedThrottleStrategy.isRequestAllowed
      }
  }
}