package utils

import akka.actor.Cancellable
import akka.http.scaladsl.util.FastFuture
import com.codahale.metrics.jmx.JmxReporter
import com.codahale.metrics.json.MetricsModule
import com.codahale.metrics.jvm.{GarbageCollectorMetricSet, JvmAttributeGaugeSet, MemoryUsageGaugeSet, ThreadStatesGaugeSet}
import com.codahale.metrics._
import com.fasterxml.jackson.databind.ObjectMapper
import com.spotify.metrics.core.{MetricId, SemanticMetricRegistry, SemanticMetricSet}
import com.spotify.metrics.jvm.{CpuGaugeSet, FileDescriptorGaugeSet}
import io.prometheus.client.exporter.common.TextFormat
import otoroshi.cluster.{ClusterMode, StatsView}
import otoroshi.env.Env
import otoroshi.events.StatsDReporter
import otoroshi.utils.RegexPool
import otoroshi.utils.cache.types.LegitConcurrentHashMap
import otoroshi.utils.prometheus.CustomCollector
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import java.io.StringWriter
import java.lang.management.ManagementFactory
import java.util
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.{Timer => _, _}
import javax.management.{Attribute, ObjectName}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.{mapAsJavaMapConverter, mapAsScalaMapConverter}

case class EcoScore(dataIn: Long,
                    dataOut: Long,
                    overhead: Long,
                    overheadWithoutCircuitBreaker: Long,
                    circuitBreaker: Long)

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
             serviceId: String) = {

    registry.put(
      serviceId,
      registry.map
    )
    println(serviceId, dataIn,
      dataOut,
      overhead,
      overheadWoCb,
      cbDuration)
  }
}