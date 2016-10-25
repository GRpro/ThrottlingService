package sla.unit

import java.util.concurrent.CountDownLatch

import org.scalatest.FunSuite
import org.scalamock.scalatest.MockFactory
import sla._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class SlaServiceProxyTest extends FunSuite with MockFactory {

  implicit val ec = ExecutionContext.global

  trait Config {
    val slaService = mock[SlaService]
    val slaProxy = new SlaServiceProxy(slaService)
    val token = "token1"
    val userSla = new Sla("user1", 10)
  }

  test("query the same token second time should not query sla service") {
    new Config {
      val num = 10

      inSequence {
        (slaService getSlaByToken _).expects(token).returning(Future {userSla})
        (slaService getSlaByToken _).expects(token).throwing(
          new AssertionError("invocation is not expected")).anyNumberOfTimes
      }

      for (i <- 1 to num) {
        val tokenFuture: Future[Sla] = slaProxy.getSlaByToken(token)
        tokenFuture.onComplete {
          case Success(sla) => assert(sla === userSla)
          case Failure(e) => fail("unknown error " + e)
        }
      }
    }
  }

  test("service should not be queried if request for token is in progress") {
    new Config {
      val cdl = new CountDownLatch(1)

      inSequence {
        (slaService getSlaByToken _).expects(token).returning(
          Future {
            // long running future blocks until
            // someone calls cdl.release
            cdl.await()
            userSla
          })
        (slaService getSlaByToken _).expects(token).throwing(
          new AssertionError("invocation is not expected")).anyNumberOfTimes
      }

      val firstFuture: Future[Sla] = slaProxy.getSlaByToken(token)
      // still in progress
      val secondFuture = slaProxy.getSlaByToken(token)
      cdl.countDown()
    }
  }

}
