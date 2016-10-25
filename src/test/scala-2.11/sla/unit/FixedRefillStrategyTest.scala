package sla.unit

import java.util.concurrent.{ExecutorService, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicLong

import org.scalatest._
import sla.MockTime
import sla.strategy._


class FixedRefillStrategyTest extends FunSuite {

  trait Config {
    val time = new MockTime
    val refillStrategy: RefillStrategy =
      new FixedRefillStrategy(10, 4, time)
  }

  test("expect maximum number of tokens the first time") {
    new Config {
      assert(10 === refillStrategy.refill)
      time.sleep(1)
      assert(0 === refillStrategy.refill)
      time.sleep(3)
      // new portion of tokens after interval 4 ms
      assert(10 === refillStrategy.refill)
    }
  }

  test("at any time expect no more tokens than configured") {
    new Config {
      time.sleep(1000)
      assert(10 === refillStrategy.refill)
      time.sleep(1)
      assert(0 === refillStrategy.refill)
    }
  }

  test("only one concurrent thread should obtain tokens") {
    new Config {
      val threadNum = 10
      val tokenPerBatch = 10
      val interval = 50
      val endTime = interval * 100 - 1
      val receivedTokens = new AtomicLong(0)

      override val refillStrategy: RefillStrategy =
        new FixedRefillStrategy(tokenPerBatch, interval, time)

      val es: ExecutorService = Executors.newFixedThreadPool(threadNum)

      for (i <- 0 until threadNum) {
        es.submit(new Runnable {
          override def run(): Unit = {
            while (time.currentMillis < endTime) {
              receivedTokens.addAndGet(refillStrategy.refill)
              time.sleep(1)
            }
          }
        })
      }

      es.shutdown()
      es.awaitTermination(Long.MaxValue, TimeUnit.NANOSECONDS)

      assert(tokenPerBatch + (endTime / interval) * tokenPerBatch === receivedTokens.get())
    }
  }

}
