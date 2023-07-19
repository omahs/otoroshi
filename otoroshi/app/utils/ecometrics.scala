package utils

import com.codahale.metrics.UniformReservoir
import otoroshi.env.Env
import play.api.Logger

import java.util.{Timer => _}
import scala.collection.concurrent.TrieMap

case class EcoScore(dataIn: Long,
                    dataOut: Long,
                    overhead: Long,
                    overheadWithoutCircuitBreaker: Long,
                    circuitBreakerDuration: Long,
                    duration: Long,
                    headers: Long,
                    headersOut: Long)

class EcoMetrics(env: Env)  {

  private implicit val ev = env
  private implicit val ec = env.otoroshiExecutionContext

  private val logger = Logger("otoroshi-eco-metrics")

  private val registry = new TrieMap[String, EcoScore]()

  def update(dataIn: Long,
             dataOut: Long,
             overhead: Long,
             overheadWoCb: Long,
             cbDuration: Long,
             duration: Long,
             serviceId: String,
             headersSize: Long,
             headersOutSize: Long) = {

    val newScore = EcoScore(
      dataIn,
      dataOut,
      overhead,
      overheadWoCb,
      cbDuration,
      duration,
      headersSize,
      headersOutSize
    )

    registry.put(
      serviceId,
      registry.get(serviceId) match {
        case Some(value) => value :+ newScore
        case None => Seq(newScore)
      }
    )

    println(s"""
      $serviceId,    
      $dataIn,       // size in body
      $dataOut,      // size out body
      $overhead,     // otoroshi computation with CB
      $overheadWoCb, // otoroshi computation with without CB - can be a little pipe
      $cbDuration,   // CB duration
      $duration,     // request duration
      $headersSize,  // headers in size
      $headersOutSize, // headers out in size
      """)     // complete duration of a request
  }
}