package object sla {

  /**
    * Common configuration constants
    */
  object Config {
    val graceRpcConfig = "grace.rpc"
    val graceRpcDefault = "1000"

    val refillFixedIntervalMsConfig = "refill.strategy.fixed.interval.ms"
    val refillFixedIntervalMsDefault = "100"
  }


}
