package sla.integration

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import akka.actor.ActorSystem
import akka.io.IO
import org.scalamock.scalatest.MockFactory
import org.scalatest.FunSuite
import sla.{Sla, SlaService, SlaServiceProxy, ThrottlingService}

import scala.concurrent.{Await, Future}
import sla.Config._
import spray.can.Http
import spray.http._
import spray.httpx.RequestBuilding
import spray.routing.SimpleRoutingApp

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

/**
  * Stress/Integration tests for Throttling service.
  *
  * Assert that no more than (numUsers * rpsPerUser * time)
  * requests are allowed.
  *
  * Perform benchmark estimate of throttled web service versus pure one.
  */
class ThrottlingServiceTest extends FunSuite with MockFactory {

  abstract class Config(val numUsers: Int, val rps: Int, val timeMs: Long) {
    implicit val ec = ExecutionContext.global
    val es = Executors.newFixedThreadPool(numUsers)

    val props: Map[String, String] = Map(
      refillFixedIntervalMsConfig -> 100.toString,
      graceRpcConfig -> rps.toString
    )

    val tokenMap: Map[String, Future[Sla]] =
      (for (i <- 1 to numUsers) yield {
        val slaFuture: Future[Sla] = Future(new Sla("user" + i, rps))
        // complete future
        Await.ready(slaFuture, 1.second)
        "token" + i -> slaFuture
      }).toMap

    // all users must be authorized
    // to perform accurate calculation
    val slaService = new SlaService {
      override def getSlaByToken(token: String): Future[Sla] = tokenMap get token match {
        case Some(slaFuture) => slaFuture
        case None => fail("not expected token")
      }
    }

    val throttlingService = ThrottlingService.create(
      new SlaServiceProxy(slaService), props)
  }


  /**
    * Simple REST service that calculates number of requests
    */
  class RESTService extends SimpleRoutingApp {
    implicit val actorSystem = ActorSystem()

    val requestNum = new AtomicLong(0)

    def start(port: Int) = startServer(interface = "localhost", port = port) {
      path("metric") {
        complete {
          // returns number of request
          requestNum.incrementAndGet().toString
        }
      }
    }

    def stop() = actorSystem.terminate()

    def metric() = requestNum.get()

    def clearMetric() = requestNum.set(0)
  }


  test("stress testing with minimal latency between invocations of isRequestAllowed") {
    // increasing the number of users will make impact on
    // responsiveness of isRequestAllowed method. So increasing latency
    // and decreasing accuracy of service (less requests are allowed than needed).
    new Config(
      numUsers = 5,
      rps = 100,
      timeMs = 5000) {

      val cdl = new CountDownLatch(1)
      val succeded = new AtomicLong(0)
      val all = new AtomicLong(0)

      for (token <- tokenMap.keys) {
        es.submit(new Runnable {
          override def run(): Unit = {
            cdl.await()
            val endTime = System.currentTimeMillis + timeMs
            while (System.currentTimeMillis < endTime) {
              if (throttlingService.isRequestAllowed(Some(token))) {
                succeded.incrementAndGet()
              }
              all.incrementAndGet()
            }
          }
        })
      }

      cdl.countDown()
      es.shutdown()
      es.awaitTermination(Long.MaxValue, TimeUnit.NANOSECONDS)

      val expected = (numUsers * rps * timeMs).toDouble / 1000
      val actual = succeded.get()

      assert(actual < expected)
      println(
        "actual = " + actual +
          "\nexpected = " + expected +
          "\nall = " + all.get)
    }
  }

  test("benchmark test with REST service") {
    new Config(
      numUsers = 10,
      rps = 10,
      timeMs = 5000) with RequestBuilding {

      import spray.client.pipelining.sendReceive

      implicit val system = ActorSystem("spray-client")
      implicit val executionContext = system.dispatcher

      val clientPipeline = sendReceive

      def performRequests(port: Int, throttlingEnabled: Boolean) = {
        val cdl = new CountDownLatch(1)
        val cdl2 = new CountDownLatch(tokenMap.size)

        for (token <- tokenMap.keys) {
          es.submit(new Runnable {
            override def run(): Unit = {
              cdl.await()
              val endTime = System.currentTimeMillis + timeMs
              while (System.currentTimeMillis < endTime) {

                var requestAllowed = true
                if (throttlingEnabled)
                  requestAllowed = throttlingService.isRequestAllowed(Some(token))

                if (requestAllowed) {
                  val startTimestamp = System.currentTimeMillis
                  val response = clientPipeline {
                    Get(s"http://localhost:$port/metric")
                  }
                  response.onComplete(_ =>
                    println(s"Request completed in ${System.currentTimeMillis - startTimestamp} millis."))
                  // block until complete
                  Await.ready(response, 2.seconds)
                }
              }

              cdl2.countDown()
            }
          })
        }
        cdl.countDown()
        // wait for tasks completion
        cdl2.await()
      }

      // start REST-ful service
      val port = 6769
      val rest = new RESTService
      rest.start(port)

      // throttling requests
      performRequests(port, throttlingEnabled = true)
      val throttledActual = rest.metric()
      rest.clearMetric()

      // test without throttling
      performRequests(port, throttlingEnabled = false)
      val pureActual = rest.metric()

      rest.stop()


      es.shutdown()
      es.awaitTermination(Long.MaxValue, TimeUnit.NANOSECONDS)

      assert(throttledActual < pureActual)

      val throttledExpected = (numUsers * rps * timeMs).toDouble / 1000
      println(
        "throttledExpected = " + throttledExpected +
          "\nthrottledActual = " + throttledActual +
          "\npureActual = " + pureActual +
          "\noverhead = " + (pureActual.toDouble / throttledActual))
    }

  }

}
