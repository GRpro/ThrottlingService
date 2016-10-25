package sla

import scala.concurrent.Future

/**
  * Service Level Agreement (SLA) for a specific user
  *
  * @param user username
  * @param rps maximum allowed requests per second
  */
case class Sla(user: String, rps: Int)

trait SlaService {
  def getSlaByToken(token: String): Future[Sla]
}
