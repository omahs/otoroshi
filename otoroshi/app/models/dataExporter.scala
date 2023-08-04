package otoroshi.models

import akka.stream.scaladsl.{Sink, Source}
import com.google.common.hash.Hashing
import otoroshi.env.Env
import otoroshi.events.Exporters._
import otoroshi.events._
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script._
import otoroshi.storage.drivers.inmemory.S3Configuration
import otoroshi.utils.mailer._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait Exporter {
  def toJson: JsValue
}

object Exporter {

  case object NoneExporter extends Exporter {
    def toJson: JsValue = JsNull
  }

}

case class DataExporterConfigFiltering(include: Seq[JsObject] = Seq.empty, exclude: Seq[JsObject] = Seq.empty)

case class FileSettings(path: String, maxNumberOfFile: Option[Int], maxFileSize: Long = 10L * 1024L * 1024L)
    extends Exporter {
  override def toJson: JsValue =
    Json.obj(
      "path"            -> path,
      "maxFileSize"     -> maxFileSize,
      "maxNumberOfFile" -> maxNumberOfFile.map(n => JsNumber(n)).getOrElse(JsNull).asValue
    )
}

case class S3ExporterSettings(
    maxFileSize: Long = 10L * 1024L * 1024L,
    maxNumberOfFile: Option[Int],
    config: S3Configuration
) extends Exporter {
  override def toJson: JsValue = config.json.asObject ++ Json.obj(
    "maxFileSize"     -> maxFileSize,
    "maxNumberOfFile" -> maxNumberOfFile.map(n => JsNumber(n)).getOrElse(JsNull).asValue
  )
}

object S3ExporterSettings {
  val format = new Format[S3ExporterSettings] {
    override def reads(json: JsValue): JsResult[S3ExporterSettings] = Try {
      S3ExporterSettings(
        maxFileSize = json.select("maxFileSize").asOpt[Long].getOrElse(10L * 1024L * 1024L),
        maxNumberOfFile = json.select("maxNumberOfFile").asOpt[Int].filter(_ > 0),
        config = S3Configuration.format.reads(json).get
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: S3ExporterSettings): JsValue             = o.toJson
  }
}

case class GoReplayFileSettings(
    path: String,
    maxFileSize: Long = 10L * 1024L * 1024L,
    captureRequests: Boolean,
    captureResponses: Boolean,
    preferBackendRequest: Boolean,
    preferBackendResponse: Boolean,
    methods: Seq[String]
) extends Exporter {
  override def toJson: JsValue =
    Json.obj(
      "path"                  -> path,
      "maxFileSize"           -> maxFileSize,
      "captureRequests"       -> captureRequests,
      "captureResponses"      -> captureResponses,
      "preferBackendRequest"  -> preferBackendRequest,
      "preferBackendResponse" -> preferBackendResponse,
      "methods"               -> JsArray(methods.map(JsString.apply))
    )
}

case class GoReplayS3Settings(
    s3: S3Configuration,
    maxFileSize: Long = 10L * 1024L * 1024L,
    captureRequests: Boolean,
    captureResponses: Boolean,
    preferBackendRequest: Boolean,
    preferBackendResponse: Boolean,
    methods: Seq[String]
) extends Exporter {
  override def toJson: JsValue =
    Json.obj(
      "s3"                    -> s3.json,
      "captureRequests"       -> captureRequests,
      "captureResponses"      -> captureResponses,
      "preferBackendRequest"  -> preferBackendRequest,
      "preferBackendResponse" -> preferBackendResponse,
      "methods"               -> JsArray(methods.map(JsString.apply))
    )
}

case class ExporterRef(ref: String, config: JsValue) extends Exporter {
  override def toJson: JsValue =
    Json.obj(
      "ref"    -> ref,
      "config" -> config
    )
}

case class ConsoleSettings() extends Exporter {
  override def toJson: JsValue = Json.obj()
}

case class EcoMetricsSettings() extends Exporter {
  override def toJson: JsValue = Json.obj()
}

case class MetricsSettings(labels: Map[String, String] = Map()) extends Exporter {
  override def toJson: JsValue =
    Json.obj(
      "labels" -> labels
    )
}

object DataExporterConfig {

  import scala.concurrent.duration._

  private val log = Logger("otoroshi-data-exporter-config")

  def fromJsons(value: JsValue): DataExporterConfig =
    try {
      format.reads(value).get
    } catch {
      case e: Throwable => {
        log.error(s"Try to deserialize ${Json.prettyPrint(value)}")
        throw e
      }
    }

  val format = new Format[DataExporterConfig] {
    override def writes(o: DataExporterConfig): JsValue = {
      o.location.jsonWithKey ++ Json.obj(
        "type"          -> o.typ.name,
        "enabled"       -> o.enabled,
        "id"            -> o.id,
        "name"          -> o.name,
        "desc"          -> o.desc,
        "metadata"      -> o.metadata,
        "tags"          -> JsArray(o.tags.map(JsString.apply)),
        "bufferSize"    -> o.bufferSize,
        "jsonWorkers"   -> o.jsonWorkers,
        "sendWorkers"   -> o.sendWorkers,
        "groupSize"     -> o.groupSize,
        "groupDuration" -> o.groupDuration.toMillis,
        "projection"    -> o.projection,
        "filtering"     -> Json.obj(
          "include" -> JsArray(o.filtering.include),
          "exclude" -> JsArray(o.filtering.exclude)
        ),
        "config"        -> o.config.toJson
      )
    }
    override def reads(json: JsValue): JsResult[DataExporterConfig] =
      Try {
        DataExporterConfig(
          typ = DataExporterConfigType.parse((json \ "type").as[String]),
          location = EntityLocation.readFromKey(json),
          enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
          id = (json \ "id").as[String],
          name = (json \ "name").as[String],
          desc = (json \ "desc").asOpt[String].getOrElse("--"),
          metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
          tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
          bufferSize = (json \ "bufferSize").asOpt[Int].getOrElse(5000),
          jsonWorkers = (json \ "jsonWorkers").asOpt[Int].getOrElse(1),
          sendWorkers = (json \ "sendWorkers").asOpt[Int].getOrElse(5),
          groupSize = (json \ "groupSize").asOpt[Int].getOrElse(100),
          groupDuration = (json \ "groupDuration").asOpt[Int].map(_.millis).getOrElse(30.seconds),
          projection = (json \ "projection").asOpt[JsObject].getOrElse(Json.obj()),
          filtering = DataExporterConfigFiltering(
            include = (json \ "filtering" \ "include").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
            exclude = (json \ "filtering" \ "exclude").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          ),
          config = (json \ "type").as[String] match {
            case "elastic"       => ElasticAnalyticsConfig.format.reads((json \ "config").as[JsObject]).get
            case "webhook"       => Webhook.format.reads((json \ "config").as[JsObject]).get
            case "kafka"         => KafkaConfig.format.reads((json \ "config").as[JsObject]).get
            case "pulsar"        => PulsarConfig.format.reads((json \ "config").as[JsObject]).get
            case "file"          =>
              FileSettings(
                path = (json \ "config" \ "path").as[String],
                maxNumberOfFile = (json \ "config" \ "maxNumberOfFile").asOpt[Int].filter(_ > 0),
                maxFileSize = (json \ "config" \ "maxFileSize").as[Long]
              )
            case "s3"            =>
              (json \ "config").as(S3ExporterSettings.format)
            case "goreplays3"    =>
              GoReplayS3Settings(
                (json \ "config" \ "s3").as(S3Configuration.format),
                (json \ "config" \ "maxFileSize").asOpt[Long].getOrElse(10L * 1024L * 1024L),
                (json \ "config" \ "captureRequests").asOpt[Boolean].getOrElse(true),
                (json \ "config" \ "captureResponses").asOpt[Boolean].getOrElse(false),
                (json \ "config" \ "preferBackendRequest").asOpt[Boolean].getOrElse(false),
                (json \ "config" \ "preferBackendResponse").asOpt[Boolean].getOrElse(false),
                (json \ "config" \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)
              )
            case "goreplayfile"  =>
              GoReplayFileSettings(
                (json \ "config" \ "path").as[String],
                (json \ "config" \ "maxFileSize").asOpt[Long].getOrElse(10L * 1024L * 1024L),
                (json \ "config" \ "captureRequests").asOpt[Boolean].getOrElse(true),
                (json \ "config" \ "captureResponses").asOpt[Boolean].getOrElse(false),
                (json \ "config" \ "preferBackendRequest").asOpt[Boolean].getOrElse(false),
                (json \ "config" \ "preferBackendResponse").asOpt[Boolean].getOrElse(false),
                (json \ "config" \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)
              )
            case "mailer"        => MailerSettings.format.reads((json \ "config").as[JsObject]).get
            case "custom"        => ExporterRef((json \ "config" \ "ref").as[String], (json \ "config" \ "config").as[JsValue])
            case "console"       => ConsoleSettings()
            case "metrics"       => MetricsSettings((json \ "config" \ "labels").as[Map[String, String]])
            case "custommetrics" => CustomMetricsSettings.format.reads((json \ "config").as[JsObject]).get
            case "ecometrics"    => EcoMetricsSettings()
            case "wasm"          => WasmExporterSettings.format.reads((json \ "config").as[JsObject]).get
            case "otlp-metrics"  => OtlpMetricsExporterSettings.format.reads((json \ "config").as[JsObject]).get
            case "otlp-logs"     => OtlpLogsExporterSettings.format.reads((json \ "config").as[JsObject]).get
            case _               => throw new RuntimeException("Bad config type")
          }
        )
      } match {
        case Failure(e) => JsError(e.getMessage)
        case Success(e) => JsSuccess(e)
      }
  }
}

sealed trait DataExporterConfigType {
  def name: String
}

object DataExporterConfigType {

  case object Kafka extends DataExporterConfigType {
    def name: String = "kafka"
  }

  case object Pulsar extends DataExporterConfigType {
    def name: String = "pulsar"
  }

  case object Elastic extends DataExporterConfigType {
    def name: String = "elastic"
  }

  case object Webhook extends DataExporterConfigType {
    def name: String = "webhook"
  }

  case object File extends DataExporterConfigType {
    def name: String = "file"
  }

  case object GoReplayFile extends DataExporterConfigType {
    def name: String = "goreplayfile"
  }

  case object S3File extends DataExporterConfigType {
    def name: String = "s3"
  }

  case object GoReplayS3 extends DataExporterConfigType {
    def name: String = "goreplays3"
  }

  case object EcoMetricsConfigType extends DataExporterConfigType {
    def name: String = "ecometrics"
  }

  case object Mailer extends DataExporterConfigType {
    def name: String = "mailer"
  }

  case object Custom extends DataExporterConfigType {
    def name: String = "custom"
  }

  case object None extends DataExporterConfigType {
    def name: String = "none"
  }

  case object Console extends DataExporterConfigType {
    def name: String = "console"
  }

  case object Metrics extends DataExporterConfigType {
    def name: String = "metrics"
  }

  case object CustomMetrics extends DataExporterConfigType {
    def name: String = "custommetrics"
  }

  case object Wasm extends DataExporterConfigType {
    def name: String = "wasm"
  }

  case object OtlpLogs extends DataExporterConfigType {
    def name: String = "otlp-logs"
  }

  case object OtlpMetrics extends DataExporterConfigType {
    def name: String = "otlp-metrics"
  }

  def parse(str: String): DataExporterConfigType = {
    str.toLowerCase() match {
      case "kafka"         => Kafka
      case "pulsar"        => Pulsar
      case "elastic"       => Elastic
      case "webhook"       => Webhook
      case "file"          => File
      case "goreplayfile"  => GoReplayFile
      case "goreplays3"    => GoReplayS3
      case "s3"            => S3File
      case "mailer"        => Mailer
      case "none"          => None
      case "custom"        => Custom
      case "console"       => Console
      case "metrics"       => Metrics
      case "custommetrics" => CustomMetrics
      case "ecometrics"    => EcoMetricsConfigType
      case "wasm"          => Wasm
      case "otlp-metrics"  => OtlpMetrics
      case "otlp-logs"     => OtlpLogs
      case _               => None
    }
  }
}

case class DataExporterConfig(
    enabled: Boolean,
    typ: DataExporterConfigType,
    id: String,
    name: String,
    desc: String,
    metadata: Map[String, String] = Map.empty,
    tags: Seq[String] = Seq.empty,
    location: EntityLocation = EntityLocation(),
    bufferSize: Int = 5000,
    jsonWorkers: Int = 1,
    sendWorkers: Int = 5,
    groupSize: Int = 100,
    groupDuration: FiniteDuration = 30.seconds,
    filtering: DataExporterConfigFiltering,
    projection: JsObject,
    config: Exporter
) extends EntityLocationSupport {

  override def json: JsValue = DataExporterConfig.format.writes(this)

  override def internalId: String      = id
  def theDescription: String           = desc
  def theMetadata: Map[String, String] = metadata
  def theName: String                  = name
  def theTags: Seq[String]             = tags

  def save()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    env.datastores.dataExporterConfigDataStore.set(this)
  }

  def exporter()(implicit ec: ExecutionContext, env: Env): DataExporter = {
    config match {
      case c: KafkaConfig                 => new KafkaExporter(this)
      case c: PulsarConfig                => new PulsarExporter(this)
      case c: ElasticAnalyticsConfig      => new ElasticExporter(this)
      case c: Webhook                     => new WebhookExporter(this)
      case c: FileSettings                => new FileAppenderExporter(this)
      case c: S3ExporterSettings          => new S3Exporter(this)
      case c: GoReplayFileSettings        => new GoReplayFileAppenderExporter(this)
      case c: GoReplayS3Settings          => new GoReplayS3Exporter(this)
      case c: NoneMailerSettings          => new GenericMailerExporter(this)
      case c: ConsoleMailerSettings       => new GenericMailerExporter(this)
      case c: MailjetSettings             => new GenericMailerExporter(this)
      case c: MailgunSettings             => new GenericMailerExporter(this)
      case c: SendgridSettings            => new GenericMailerExporter(this)
      case c: GenericMailerSettings       => new GenericMailerExporter(this)
      case c: ExporterRef                 => new CustomExporter(this)
      case c: ConsoleSettings             => new ConsoleExporter(this)
      case c: EcoMetricsSettings     => new EcoMetricsExporter(this)
      case c: MetricsSettings             => new MetricsExporter(this)
      case c: CustomMetricsSettings       => new CustomMetricsExporter(this)
      case c: WasmExporterSettings        => new WasmExporter(this)
      case c: OtlpMetricsExporterSettings => new OtlpMetricsExporter(this)
      case c: OtlpLogsExporterSettings    => new OtlpLogExporter(this)
      case _                              => throw new RuntimeException("unsupported exporter type")
    }
  }
}

object DataExporterConfigMigrationJob {

  def cleanupGlobalConfig(env: Env): Future[Unit] = {
    implicit val ev = env
    implicit val ec = env.otoroshiExecutionContext
    env.datastores.globalConfigDataStore.findById("global").map {
      case Some(config) =>
        env.datastores.globalConfigDataStore.set(
          config.copy(
            analyticsWebhooks = Seq.empty,
            alertsWebhooks = Seq.empty,
            elasticWritesConfigs = Seq.empty,
            kafkaConfig = None,
            mailerSettings = None
          )
        )
      case None         => ()
    }
  }

  def saveExporters(configs: Seq[DataExporterConfig], env: Env): Future[Unit] = {

    implicit val ev  = env
    implicit val ec  = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer

    Source(configs.toList)
      .mapAsync(1)(ex => {
        env.datastores.dataExporterConfigDataStore.set(ex)
      })
      .runWith(Sink.ignore)
      .map(_ => ())
  }
  def extractExporters(env: Env): Future[Seq[DataExporterConfig]] = {

    implicit val ev  = env
    implicit val ec  = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer

    val alertDataExporterConfigFiltering     = DataExporterConfigFiltering(
      include = Seq(Json.obj("@type" -> Json.obj("$regex" -> "Alert.*")))
    )
    val analyticsDataExporterConfigFiltering = DataExporterConfigFiltering(
      include = Seq()
    )

    def toDataExporterConfig(
        name: String,
        ex: Exporter,
        typ: DataExporterConfigType,
        filter: DataExporterConfigFiltering
    ): DataExporterConfig = {
      DataExporterConfig(
        enabled = true,
        id = Hashing.sha256().hashString(name, StandardCharsets.UTF_8).toString,
        name = name,
        desc = "--",
        tags = Seq.empty,
        metadata = Map.empty,
        location = EntityLocation(),
        filtering = filter,
        projection = Json.obj(),
        typ = typ,
        config = ex
      )
    }

    env.datastores.globalConfigDataStore
      .findById("global")
      .map {
        case None               => Seq.empty
        case Some(globalConfig) =>
          val analyticsWebhooksExporters                         = globalConfig.analyticsWebhooks.zipWithIndex.map(t =>
            toDataExporterConfig(
              s"Analytics webhook exporter ${t._2 + 1} from Danger Zone",
              t._1,
              DataExporterConfigType.Webhook,
              analyticsDataExporterConfigFiltering
            )
          )
          val alertsWebhooksExporters: Seq[DataExporterConfig]   = globalConfig.alertsWebhooks.zipWithIndex.map(t =>
            toDataExporterConfig(
              s"Alerts webhook exporter ${t._2 + 1} from Danger Zone",
              t._1,
              DataExporterConfigType.Webhook,
              alertDataExporterConfigFiltering
            )
          )
          val analyticsElasticExporters: Seq[DataExporterConfig] =
            globalConfig.elasticWritesConfigs.zipWithIndex.map(t =>
              toDataExporterConfig(
                s"Elastic exporter ${t._2 + 1} from Danger Zone",
                t._1,
                DataExporterConfigType.Elastic,
                analyticsDataExporterConfigFiltering
              )
            )
          val kafkaExporter: Option[DataExporterConfig]          = globalConfig.kafkaConfig
            .filter(c => c.servers.nonEmpty)
            .filter(c => c.sendEvents)
            .map(c =>
              toDataExporterConfig(
                "Kafka exporter from Danger Zone",
                c,
                DataExporterConfigType.Kafka,
                analyticsDataExporterConfigFiltering
              )
            )
          val alertMailerExporter: Option[DataExporterConfig]    = globalConfig.mailerSettings
            .filter(setting => setting.typ != "none")
            .map(c =>
              toDataExporterConfig(
                "Mail exporter from Danger Zone",
                c,
                DataExporterConfigType.Mailer,
                alertDataExporterConfigFiltering
              )
            )

          analyticsWebhooksExporters ++
          alertsWebhooksExporters ++
          analyticsElasticExporters ++
          kafkaExporter.fold(Seq.empty[DataExporterConfig])(e => Seq(e)) ++
          alertMailerExporter.fold(Seq.empty[DataExporterConfig])(e => Seq(e))
      }
  }
}

class DataExporterConfigMigrationJob extends Job {

  private val logger = Logger("otoroshi-data-exporter-config-migration-job")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.core.models.DataExporterConfigMigrationJob")

  override def name: String = "Otoroshi data exporter config migration job"

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledOnce

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiCluster

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = None

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    DataExporterConfigMigrationJob
      .extractExporters(env)
      .flatMap(configs => DataExporterConfigMigrationJob.saveExporters(configs, env))
      .andThen {
        case Success(_) => DataExporterConfigMigrationJob.cleanupGlobalConfig(env)
        case Failure(e) => logger.error("Data exporter migration job failed", e)
      }
  }
}
