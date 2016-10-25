package sla

import scala.concurrent.{ExecutionContext, Future}

/**
  * If SLA information is changed quite rarely and SlaService is quite
  * costly to call this proxy should be considered for caching SLA requests.
  * The service is not queried, if the same token request is already in progress.
  *
  * @param slaService SLA Service to proxy
  * @param executionContext
  */
class SlaServiceProxy(val slaService: SlaService)(implicit val executionContext: ExecutionContext) extends SlaService {

  var cache: Map[String, Future[Sla]] = Map()

  override def getSlaByToken(token: String): Future[Sla] = {
    cache get token match {
      case Some(sla) => sla
      case None =>
        val slaFuture: Future[Sla] = slaService.getSlaByToken(token)
        // add request to processed
        cache = cache + (token -> slaFuture)
        slaFuture.onFailure {
          case ex =>
            // remove element on exception
            cache = cache - token
        }
        slaFuture
    }
  }
}
