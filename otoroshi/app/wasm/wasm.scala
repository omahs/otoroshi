package otoroshi.wasm

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.util.ByteString
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.otoroshi._
import org.extism.sdk.wasm.WasmSourceResolver
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.models.{WSProxyServerJson, WasmManagerSettings}
import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.proxywasm.VmData
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.{DefaultWSCookie, WSCookie}
import play.api.mvc.Cookie

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationLong, FiniteDuration, MILLISECONDS}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

case class WasmDataRights(read: Boolean = false, write: Boolean = false)

object WasmDataRights {
  def fmt =
    new Format[WasmDataRights] {
      override def writes(o: WasmDataRights) =
        Json.obj(
          "read"  -> o.read,
          "write" -> o.write
        )

      override def reads(json: JsValue) =
        Try {
          JsSuccess(
            WasmDataRights(
              read = (json \ "read").asOpt[Boolean].getOrElse(false),
              write = (json \ "write").asOpt[Boolean].getOrElse(false)
            )
          )
        } recover { case e =>
          JsError(e.getMessage)
        } get
    }
}

sealed trait WasmSourceKind {
  def name: String
  def json: JsValue                                                                                               = JsString(name)
  def getWasm(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]]
  def getConfig(path: String, opts: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Option[WasmConfig]] =
    None.vfuture
}
object WasmSourceKind       {
  case object Unknown     extends WasmSourceKind {
    def name: String = "Unknown"
    def getWasm(path: String, opts: JsValue)(implicit
                                             env: Env,
                                             ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      Left(Json.obj("error" -> "unknown source")).vfuture
    }
  }
  case object Base64      extends WasmSourceKind {
    def name: String = "Base64"
    def getWasm(path: String, opts: JsValue)(implicit
                                             env: Env,
                                             ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      ByteString(path.replace("base64://", "")).decodeBase64.right.future
    }
  }
  case object Http        extends WasmSourceKind {
    def name: String = "Http"
    def getWasm(path: String, opts: JsValue)(implicit
                                             env: Env,
                                             ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      val method         = opts.select("method").asOpt[String].getOrElse("GET")
      val headers        = opts.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
      val timeout        = opts.select("timeout").asOpt[Long].getOrElse(10000L).millis
      val followRedirect = opts.select("followRedirect").asOpt[Boolean].getOrElse(true)
      val proxy          = opts.select("proxy").asOpt[JsObject].flatMap(v => WSProxyServerJson.proxyFromJson(v))
      val tlsConfig      =
        opts.select("tls").asOpt(NgTlsConfig.format).map(_.legacy).orElse(opts.select("tls").asOpt(MtlsConfig.format))
      (tlsConfig match {
        case None      => env.Ws.url(path)
        case Some(cfg) => env.MtlsWs.url(path, cfg)
      })
        .withMethod(method)
        .withFollowRedirects(followRedirect)
        .withHttpHeaders(headers.toSeq: _*)
        .withRequestTimeout(timeout)
        .applyOnWithOpt(proxy) { case (req, proxy) =>
          req.withProxyServer(proxy)
        }
        .execute()
        .map { resp =>
          if (resp.status == 200) {
            val body = resp.bodyAsBytes
            Right(body)
          } else {
            val body: String = resp.body
            Left(
              Json.obj(
                "error"   -> "bad response",
                "status"  -> resp.status,
                "headers" -> resp.headers.mapValues(_.last),
                "body"    -> body
              )
            )
          }
        }
    }
  }
  case object WasmManager extends WasmSourceKind {
    def name: String = "WasmManager"
    def getWasm(path: String, opts: JsValue)(implicit
                                             env: Env,
                                             ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
        globalConfig.wasmManagerSettings match {
          case Some(WasmManagerSettings(url, clientId, clientSecret, kind)) => {
            env.Ws
              .url(s"$url/wasm/$path")
              .withFollowRedirects(false)
              .withRequestTimeout(FiniteDuration(5 * 1000, MILLISECONDS))
              .withHttpHeaders(
                "Accept"                 -> "application/json",
                "Otoroshi-Client-Id"     -> clientId,
                "Otoroshi-Client-Secret" -> clientSecret,
                "kind"                   -> kind.getOrElse("*")
              )
              .get()
              .flatMap { resp =>
                if (resp.status == 400) {
                  Left(Json.obj("error" -> "missing signed plugin url")).vfuture
                } else {
                  Right(resp.bodyAsBytes).vfuture
                }
              }
          }
          case _                                                            =>
            Left(Json.obj("error" -> "missing wasm manager url")).vfuture
        }
      }

    }
  }
  case object Local       extends WasmSourceKind {
    def name: String = "Local"
    override def getWasm(path: String, opts: JsValue)(implicit
                                                      env: Env,
                                                      ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      env.proxyState.wasmPlugin(path) match {
        case None         => Left(Json.obj("error" -> "resource not found")).vfuture
        case Some(plugin) => plugin.config.source.getWasm()
      }
    }
    override def getConfig(path: String, opts: JsValue)(implicit
                                                        env: Env,
                                                        ec: ExecutionContext
    ): Future[Option[WasmConfig]] = {
      env.proxyState.wasmPlugin(path).map(_.config).vfuture
    }
  }
  case object File        extends WasmSourceKind {
    def name: String = "File"
    def getWasm(path: String, opts: JsValue)(implicit
                                             env: Env,
                                             ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      Right(ByteString(Files.readAllBytes(Paths.get(path.replace("file://", ""))))).vfuture
    }
  }

  def apply(value: String): WasmSourceKind = value.toLowerCase match {
    case "base64"      => Base64
    case "http"        => Http
    case "wasmmanager" => WasmManager
    case "local"       => Local
    case "file"        => File
    case _             => Unknown
  }
}

case class WasmSource(kind: WasmSourceKind, path: String, opts: JsValue = Json.obj()) {
  def json: JsValue                                                                    = WasmSource.format.writes(this)
  def cacheKey                                                                         = s"${kind.name.toLowerCase}://${path}"
  def getConfig()(implicit env: Env, ec: ExecutionContext): Future[Option[WasmConfig]] = kind.getConfig(path, opts)
  def getWasm()(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = {
    val cache = WasmUtils.scriptCache(env)
    def fetchAndAddToCache(): Future[Either[JsValue, ByteString]] = {
      val promise = Promise[Either[JsValue, ByteString]]()
      cache.put(cacheKey, CacheableWasmScript.FetchingWasmScript(promise.future))
      kind.getWasm(path, opts).map {
        case Left(err) =>
          promise.trySuccess(err.left)
          err.left
        case Right(bs) => {
          cache.put(cacheKey, CacheableWasmScript.CachedWasmScript(bs, System.currentTimeMillis()))
          promise.trySuccess(bs.right)
          bs.right
        }
      }
    }
    cache.get(cacheKey) match {
      case None                                                  => fetchAndAddToCache()
      case Some(CacheableWasmScript.FetchingWasmScript(fu))      => fu
      case Some(CacheableWasmScript.CachedWasmScript(script, createAt))
        if createAt + env.wasmCacheTtl < System.currentTimeMillis =>
        fetchAndAddToCache()
        script.right.vfuture
      case Some(CacheableWasmScript.CachedWasmScript(script, _)) => script.right.vfuture
    }
  }
}
object WasmSource                                                                     {
  val format = new Format[WasmSource] {
    override def writes(o: WasmSource): JsValue             = Json.obj(
      "kind" -> o.kind.json,
      "path" -> o.path,
      "opts" -> o.opts
    )
    override def reads(json: JsValue): JsResult[WasmSource] = Try {
      WasmSource(
        kind = json.select("kind").asOpt[String].map(WasmSourceKind.apply).getOrElse(WasmSourceKind.Unknown),
        path = json.select("path").asString,
        opts = json.select("opts").asOpt[JsValue].getOrElse(Json.obj())
      )
    } match {
      case Success(s) => JsSuccess(s)
      case Failure(e) => JsError(e.getMessage)
    }
  }
}

case class WasmAuthorizations(
                               httpAccess: Boolean = false,
                               globalDataStoreAccess: WasmDataRights = WasmDataRights(),
                               pluginDataStoreAccess: WasmDataRights = WasmDataRights(),
                               globalMapAccess: WasmDataRights = WasmDataRights(),
                               pluginMapAccess: WasmDataRights = WasmDataRights(),
                               proxyStateAccess: Boolean = false,
                               configurationAccess: Boolean = false,
                               proxyHttpCallTimeout: Int = 5000
                             ) {
  def json: JsValue = WasmAuthorizations.format.writes(this)
}

object WasmAuthorizations {
  val format = new Format[WasmAuthorizations] {
    override def writes(o: WasmAuthorizations): JsValue             = Json.obj(
      "httpAccess"            -> o.httpAccess,
      "proxyHttpCallTimeout"  -> o.proxyHttpCallTimeout,
      "globalDataStoreAccess" -> WasmDataRights.fmt.writes(o.globalDataStoreAccess),
      "pluginDataStoreAccess" -> WasmDataRights.fmt.writes(o.pluginDataStoreAccess),
      "globalMapAccess"       -> WasmDataRights.fmt.writes(o.globalMapAccess),
      "pluginMapAccess"       -> WasmDataRights.fmt.writes(o.pluginMapAccess),
      "proxyStateAccess"      -> o.proxyStateAccess,
      "configurationAccess"   -> o.configurationAccess
    )
    override def reads(json: JsValue): JsResult[WasmAuthorizations] = Try {
      WasmAuthorizations(
        httpAccess = (json \ "httpAccess").asOpt[Boolean].getOrElse(false),
        proxyHttpCallTimeout = (json \ "proxyHttpCallTimeout").asOpt[Int].getOrElse(5000),
        globalDataStoreAccess = (json \ "globalDataStoreAccess")
          .asOpt[WasmDataRights](WasmDataRights.fmt.reads)
          .getOrElse(WasmDataRights()),
        pluginDataStoreAccess = (json \ "pluginDataStoreAccess")
          .asOpt[WasmDataRights](WasmDataRights.fmt.reads)
          .getOrElse(WasmDataRights()),
        globalMapAccess = (json \ "globalMapAccess")
          .asOpt[WasmDataRights](WasmDataRights.fmt.reads)
          .getOrElse(WasmDataRights()),
        pluginMapAccess = (json \ "pluginMapAccess")
          .asOpt[WasmDataRights](WasmDataRights.fmt.reads)
          .getOrElse(WasmDataRights()),
        proxyStateAccess = (json \ "proxyStateAccess").asOpt[Boolean].getOrElse(false),
        configurationAccess = (json \ "configurationAccess").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

sealed trait WasmVmLifetime {
  def name: String
  def json: JsValue = JsString(name)
}
object WasmVmLifetime       {

  case object Invocation    extends WasmVmLifetime { def name: String = "Invocation" }
  case object Request       extends WasmVmLifetime { def name: String = "Request"    }
  case object Forever       extends WasmVmLifetime { def name: String = "Forever"    }

  def parse(str: String): Option[WasmVmLifetime] = str.toLowerCase() match {
    case "invocation"     => Invocation.some
    case "request"        => Request.some
    case "forever"        => Forever.some
    case _                => None
  }
}

case class WasmConfig(
                       source: WasmSource = WasmSource(WasmSourceKind.Unknown, "", Json.obj()),
                       memoryPages: Int = 4,
                       functionName: Option[String] = None,
                       config: Map[String, String] = Map.empty,
                       allowedHosts: Seq[String] = Seq.empty,
                       allowedPaths: Map[String, String] = Map.empty,
                       ////
                       lifetime: WasmVmLifetime = WasmVmLifetime.Forever,
                       wasi: Boolean = false,
                       opa: Boolean = false,
                       importDefaultHostFunctions: Boolean = true,
                       instances: Int = 1,
                       authorizations: WasmAuthorizations = WasmAuthorizations()
                     ) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "source"         -> source.json,
    "memoryPages"    -> memoryPages,
    "functionName"   -> functionName,
    "config"         -> config,
    "allowedHosts"   -> allowedHosts,
    "allowedPaths"   -> allowedPaths,
    "wasi"           -> wasi,
    "opa"            -> opa,
    "importDefaultHostFunctions" -> importDefaultHostFunctions,
    "lifetime"       -> lifetime.json,
    "authorizations" -> authorizations.json,
    "instances"      -> instances
  )
}

object WasmConfig {
  val format = new Format[WasmConfig] {
    override def reads(json: JsValue): JsResult[WasmConfig] = Try {
      val compilerSource = json.select("compiler_source").asOpt[String]
      val rawSource      = json.select("raw_source").asOpt[String]
      val sourceOpt      = json.select("source").asOpt[JsObject]
      val source         = if (sourceOpt.isDefined) {
        WasmSource.format.reads(sourceOpt.get).get
      } else {
        compilerSource match {
          case Some(source) => WasmSource(WasmSourceKind.WasmManager, source)
          case None         =>
            rawSource match {
              case Some(source) if source.startsWith("http://")   => WasmSource(WasmSourceKind.Http, source)
              case Some(source) if source.startsWith("https://")  => WasmSource(WasmSourceKind.Http, source)
              case Some(source) if source.startsWith("file://")   =>
                WasmSource(WasmSourceKind.File, source.replace("file://", ""))
              case Some(source) if source.startsWith("base64://") =>
                WasmSource(WasmSourceKind.Base64, source.replace("base64://", ""))
              case Some(source) if source.startsWith("entity://") =>
                WasmSource(WasmSourceKind.Local, source.replace("entity://", ""))
              case Some(source) if source.startsWith("local://")  =>
                WasmSource(WasmSourceKind.Local, source.replace("local://", ""))
              case Some(source)                                   => WasmSource(WasmSourceKind.Base64, source)
              case _                                              => WasmSource(WasmSourceKind.Unknown, "")
            }
        }
      }
      WasmConfig(
        source = source,
        memoryPages = (json \ "memoryPages").asOpt[Int].getOrElse(4),
        functionName = (json \ "functionName").asOpt[String].filter(_.nonEmpty),
        config = (json \ "config").asOpt[Map[String, String]].getOrElse(Map.empty),
        allowedHosts = (json \ "allowedHosts").asOpt[Seq[String]].getOrElse(Seq.empty),
        allowedPaths = (json \ "allowedPaths").asOpt[Map[String, String]].getOrElse(Map.empty),
        wasi = (json \ "wasi").asOpt[Boolean].getOrElse(false),
        opa = (json \ "opa").asOpt[Boolean].getOrElse(false),
        importDefaultHostFunctions = (json \ "importDefaultHostFunctions").asOpt[Boolean].getOrElse(true),
        lifetime = json
          .select("lifetime")
          .asOpt[String]
          .flatMap(WasmVmLifetime.parse)
          .orElse(
            (json \ "preserve").asOpt[Boolean].map {
              case true  => WasmVmLifetime.Request
              case false => WasmVmLifetime.Forever
            }
          )
          .getOrElse(WasmVmLifetime.Forever),
        authorizations = (json \ "authorizations")
          .asOpt[WasmAuthorizations](WasmAuthorizations.format.reads)
          .orElse((json \ "accesses").asOpt[WasmAuthorizations](WasmAuthorizations.format.reads))
          .getOrElse {
            WasmAuthorizations()
          },
        instances = json.select("instances").asOpt[Int].getOrElse(1)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: WasmConfig): JsValue             = o.json
  }
}

object WasmContextSlot                                                 {
  private val _currentContext                     = new ThreadLocal[Any]()
  def getCurrentContext(): Option[Any]            = Option(_currentContext.get())
  private def setCurrentContext(value: Any): Unit = _currentContext.set(value)
  private def clearCurrentContext(): Unit         = _currentContext.remove()
}
object ResultsWrapper                                                  {
  def apply(results: OtoroshiResults, instance: WasmContextSlotId, poolId: String): ResultsWrapper                           = new ResultsWrapper(results, None, instance, poolId)
  def apply(results: OtoroshiResults, plugin: OtoroshiInstance, instance: WasmContextSlotId, poolId: String): ResultsWrapper = new ResultsWrapper(results, Some(plugin), instance, poolId)
}
case class ResultsWrapper(results: OtoroshiResults, pluginOpt: Option[OtoroshiInstance], instance: WasmContextSlotId, poolId: String) {
  def free(): Unit = try {
    if (results.getLength > 0) {
      pluginOpt.foreach(_.freeResults(results))
    }
  } catch {
    case t: Throwable =>
      t.printStackTrace()
      ()
  }
}

sealed abstract class WasmFunctionParameters {
  def shouldBeCalledOnce: Boolean
  def functionName: String
  def input: Option[String]
  def parameters: Option[OtoroshiParameters]
  def resultSize: Option[Int]

  def call(plugin: OtoroshiInstance, instance: WasmContextSlotId, poolId: String): Either[JsValue, (String, ResultsWrapper)]
  def withInput(input: Option[String]): WasmFunctionParameters
  def withFunctionName(functionName: String): WasmFunctionParameters
}

object WasmFunctionParameters {
  def from(shouldBeCalledOnce: Boolean = false, functionName: String, input: Option[String], parameters: Option[OtoroshiParameters], resultSize: Option[Int]) = {
    (input, parameters, resultSize) match {
      case (_, Some(p), Some(s))        => BothParamsResults(shouldBeCalledOnce, functionName, p, s)
      case (_, Some(p), None)           => NoResult(shouldBeCalledOnce, functionName, p)
      case (_, None, Some(s))           => NoParams(shouldBeCalledOnce, functionName, s)
      case (Some(in), None, None)       => ExtismFuntionCall(shouldBeCalledOnce, functionName, in)
      case _                            => UnknownCombination()
    }
  }

  case class UnknownCombination(shouldBeCalledOnce: Boolean = false,
                                functionName: String = "unknown",
                                input: Option[String] = None,
                                parameters: Option[OtoroshiParameters] = None,
                                resultSize: Option[Int] = None)
    extends WasmFunctionParameters {
    override def call(plugin: OtoroshiInstance, instance: WasmContextSlotId, poolId: String): Either[JsValue, (String, ResultsWrapper)] = {
      Left(Json.obj("error" -> "bad call combination"))
    }
    def withInput(input: Option[String]): WasmFunctionParameters = this.copy(input = input)
    def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class NoResult(shouldBeCalledOnce: Boolean = false,
                      functionName: String, params: OtoroshiParameters,
                      input: Option[String] = None,
                      resultSize: Option[Int] = None) extends WasmFunctionParameters {
    override def parameters: Option[OtoroshiParameters] = Some(params)
    override def call(plugin: OtoroshiInstance, instance: WasmContextSlotId, poolId: String): Either[JsValue, (String, ResultsWrapper)] = {
      plugin.callWithoutResults(functionName, parameters.get)
      Right[JsValue, (String, ResultsWrapper)](("", ResultsWrapper(new OtoroshiResults(0), plugin, instance, poolId)))
    }
    override def withInput(input: Option[String]): WasmFunctionParameters = this.copy(input = input)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class NoParams(shouldBeCalledOnce: Boolean = false,
                      functionName: String, result: Int,
                      input: Option[String] = None,
                      parameters: Option[OtoroshiParameters] = None) extends WasmFunctionParameters {
    override def resultSize: Option[Int] = Some(result)
    override def call(plugin: OtoroshiInstance, instance: WasmContextSlotId, poolId: String): Either[JsValue, (String, ResultsWrapper)] = {
      plugin.callWithoutParams(functionName, resultSize.get)
        .right
        .map(_ => ("", ResultsWrapper(new OtoroshiResults(0), plugin, instance, poolId)))
    }
    override def withInput(input: Option[String]): WasmFunctionParameters = this.copy(input = input)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class BothParamsResults(shouldBeCalledOnce: Boolean = false,
                               functionName: String, params: OtoroshiParameters, result: Int,
                               input: Option[String] = None) extends WasmFunctionParameters {
    override def parameters: Option[OtoroshiParameters] = Some(params)
    override def resultSize: Option[Int] = Some(result)
    override def call(plugin: OtoroshiInstance, instance: WasmContextSlotId, poolId: String): Either[JsValue, (String, ResultsWrapper)] = {
      plugin.call(functionName, parameters.get, resultSize.get)
        .right
        .map(res => ("", ResultsWrapper(res, plugin, instance, poolId)))
    }
    override def withInput(input: Option[String]): WasmFunctionParameters = this.copy(input = input)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class ExtismFuntionCall(shouldBeCalledOnce: Boolean = false,
                               functionName: String,
                               in: String,
                               parameters: Option[OtoroshiParameters] = None,
                               resultSize: Option[Int] = None) extends WasmFunctionParameters {
    override def input: Option[String] = Some(in)
    override def call(plugin: OtoroshiInstance, instance: WasmContextSlotId, poolId: String): Either[JsValue, (String, ResultsWrapper)] = {
      plugin.extismCall(functionName, input.get.getBytes(StandardCharsets.UTF_8))
        .right
        .map(str => (str, ResultsWrapper(new OtoroshiResults(0), plugin, instance, poolId)))
    }

    override def withInput(input: Option[String]): WasmFunctionParameters = this.copy(in = input.get)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }
}

class WasmContextSlot(
                       id: String,
                       val poolId: String,
                       val instance: WasmContextSlotId,
                       val plugin: OtoroshiInstance,
                       val cfg: WasmConfig,
                       val wasm: ByteString,
                       instanceId: String,
                       functions: Array[OtoroshiHostFunction[_ <: OtoroshiHostUserData]],
                       release: () => Unit
                     ) {

  def callSync(wasmFunctionParameters: WasmFunctionParameters, context: Option[VmData])
              (implicit env: Env, ec: ExecutionContext): Either[JsValue, (String, ResultsWrapper)] = {
    try {
      context.foreach(ctx => WasmContextSlot.setCurrentContext(ctx))
      if (WasmUtils.logger.isDebugEnabled) {
        WasmUtils.logger.debug(s"calling instance $id-$instance")
      }
      WasmUtils.debugLog.debug(s"calling '${wasmFunctionParameters.functionName}' on instance '$id-$instance'")

      val res: Either[JsValue, (String, ResultsWrapper)] = env.metrics.withTimer("otoroshi.wasm.core.call", display = true) {
        wasmFunctionParameters.call(plugin, instance, poolId)
      }

      val optError = plugin.getError
      if(optError != null) {
        println(s"some error could have occurred: ${optError}")

        return Left(JsString(optError))
      }

      env.metrics.withTimer("otoroshi.wasm.core.count-thunks") {
        WasmUtils.logger.debug(s"thunks: ${functions.size}")
      }
      res
    } catch {
      case e: Throwable if e.getMessage.contains("wasm backtrace") =>
        WasmUtils.logger.error(s"error while invoking wasm function '${wasmFunctionParameters.functionName}'", e)
        Json
          .obj(
            "error"             -> "wasm_error",
            "error_description" -> JsArray(e.getMessage.split("\\n").filter(_.trim.nonEmpty).map(JsString.apply))
          )
          .left
      case e: Throwable                                            =>
        WasmUtils.logger.error(s"error while invoking wasm function '${wasmFunctionParameters.functionName}'", e)
        Json.obj("error" -> "wasm_error", "error_description" -> JsString(e.getMessage)).left
    } finally {
      context.foreach(ctx => WasmContextSlot.clearCurrentContext())
    }
  }

  def callOpaSync(input: String)(implicit env: Env, ec: ExecutionContext): Either[JsValue, String] = {
    try {
      val res = env.metrics.withTimer("otoroshi.wasm.core.call-opa") {
        OPA.evaluate(plugin, input)
      }
      res
    } catch {
      case e: Throwable if e.getMessage.contains("wasm backtrace") =>
        WasmUtils.logger.error(s"error while invoking wasm function 'opa'", e)
        Json
          .obj(
            "error"             -> "wasm_error",
            "error_description" -> JsArray(e.getMessage.split("\\n").filter(_.trim.nonEmpty).map(JsString.apply))
          )
          .left
      case e: Throwable                                            =>
        WasmUtils.logger.error(s"error while invoking wasm function 'opa'", e)
        Json.obj("error" -> "wasm_error", "error_description" -> JsString(e.getMessage)).left
    }
  }

  def call(
            wasmFunctionParameters: WasmFunctionParameters,
            context: Option[VmData]
          )(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, (String, ResultsWrapper)]] = {
    val promise = Promise.apply[Either[JsValue, (String, ResultsWrapper)]]()
    WasmUtils
      .getInvocationQueueFor(poolId)
      .offer(WasmAction.WasmInvocation(() =>
        callSync(wasmFunctionParameters, context), promise)
      )
    promise.future
  }

  def callOpa(input: String)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, String]] = {
    val promise = Promise.apply[Either[JsValue, String]]()
    WasmUtils.getInvocationQueueFor(poolId).offer(WasmAction.WasmOpaInvocation(() => callOpaSync(input), promise))
    promise.future
  }

  def close(lifetime: WasmVmLifetime, shouldRelease: Boolean = true): Unit = {
    if (lifetime == WasmVmLifetime.Invocation) {
      if (WasmUtils.logger.isDebugEnabled) {
        WasmUtils.logger.debug(s"calling close on WasmContextSlot of ${id}")
      }
      forceClose(lifetime, shouldRelease)
    }
    if(shouldRelease)
      release()
  }

  def forceClose(lifetime: WasmVmLifetime = WasmVmLifetime.Invocation, shouldRelease: Boolean = true): Unit = {
    if (WasmUtils.logger.isDebugEnabled) {
      WasmUtils.logger.debug(s"calling forceClose on WasmContextSlot of ${id}")
    }
      try {
        if (lifetime != WasmVmLifetime.Forever) {
          println("#### WARNING plugin has been closed #### ")
          plugin.close()
        }

        if(shouldRelease)
          release()
      } catch {
        case e: Throwable => e.printStackTrace()
      }
  }
}

class WasmContext(plugins: TrieMap[String, WasmContextSlot] = new TrieMap[String, WasmContextSlot]()) {
  def put(id: String, slot: WasmContextSlot): Unit = plugins.put(id, slot)
  def get(id: String): Option[WasmContextSlot]     = plugins.get(id)
  def close(): Unit = {
    if (WasmUtils.logger.isDebugEnabled)
      WasmUtils.logger.debug(s"[WasmContext] will close ${plugins.size} wasm plugin instances")

    plugins.foreach(plugin => plugin._2.forceClose(plugin._2.cfg.lifetime))
    plugins.clear()
  }
}

sealed trait WasmAction
object WasmAction {
  case class WasmOpaInvocation(call: () => Either[JsValue, String], promise: Promise[Either[JsValue, String]])
    extends WasmAction
  case class WasmInvocation(
                             call: () => Either[JsValue, (String, ResultsWrapper)],
                             promise: Promise[Either[JsValue, (String, ResultsWrapper)]]
                           )                                       extends WasmAction
}

sealed trait CacheableWasmScript
object CacheableWasmScript {
  case class CachedWasmScript(script: ByteString, createAt: Long)       extends CacheableWasmScript
  case class FetchingWasmScript(f: Future[Either[JsValue, ByteString]]) extends CacheableWasmScript
}

class WasmContextSlotPool(
                           poolId: String,
                           var capacity: Int = 200,
                           engine: OtoroshiEngine,
                           var template: OtoroshiTemplate,
                           config: WasmConfig,
                           wasm: ByteString,
                           hostFunctions: Array[OtoroshiHostFunction[_ <: OtoroshiHostUserData]],
                           linearMemories: Array[OtoroshiLinearMemory] = Array.empty,
                           wasi: Boolean = false
                         ) {
  case class WorkerSlot(busy: Boolean = false, wasmContextSlot: WasmContextSlot, atLeastOnceReleased: Boolean = false, uses: Int)

  private val instancesCounter                                    = new AtomicInteger(0)
  private val instances: TrieMap[Int, WorkerSlot] = TrieMap()

  private def padRight(s: String, n: Int): String = String.format("%-" + n + "s", s)

  def dump() = {
    println(s"--------- State of pool ${poolId.substring(0, 20)}--------------")
    println(s"   Id   |   Busy   |   OnceReleased   |   Calls   |")
    instances.foreach(instance => {
      println(s"${padRight(instance._1.toString, 8)}|${padRight(instance._2.busy.toString, 10)}|${padRight(instance._2.atLeastOnceReleased.toString, 18)}|${padRight(instance._2.uses.toString, 11)}|")
    })
    println("-----------------------")
  }

  def slotOnceReleased(slotId: WasmContextSlotId) = instances.get(slotId.value).map(_.atLeastOnceReleased)

  def updateTemplate(newTemplate: OtoroshiTemplate) = {
    this.template = newTemplate
  }

  def reduceCapacity(newCapacity: Int): Unit = {
    for (i <- 1 to instances.size) {
      val idx = instances.size - 1 - i
      instances.get(idx).foreach(_.wasmContextSlot.plugin.close())
    }
    instances.clear()
    this.capacity = newCapacity
  }

  def acquire(slotId: Option[WasmContextSlotId]): WasmContextSlot = {
    println(s"Try to acquire slot with $slotId")
    slotId
      .map(id => {
        instances.get(id.value) match {
          case Some(value) =>
            instances(id.value) = instances(id.value).copy(uses = instances(id.value).uses + 1)
            value.wasmContextSlot
          case None => _acquire()
        }
      })
      .getOrElse(_acquire())
  }

  private def _acquire() = {
    if (config.lifetime == WasmVmLifetime.Forever) {
//      println(instances)
      instances
        .find(!_._2.busy)
        .map(tuple => {
          println("Can reuse a idle slot")
          val slotIdx = tuple._1
          instances(slotIdx) = instances(slotIdx).copy(busy = true, uses = instances(slotIdx).uses + 1)
          instances(slotIdx).wasmContextSlot
        })
        .getOrElse {
          if (instances.forall(_._2.busy) && instances.size == capacity) {
            throw new RuntimeException("We reached the pool capacity: no stuff available")
          } else {
            println("Need to create a new one")
            instantiateNewSlot()
          }
        }
    } else {
      println("instantiate a new one")
      instantiateNewSlot()
    }
  }

  private def instantiateNewSlot(): WasmContextSlot = {
    val plugin = template.instantiate(engine, hostFunctions, linearMemories, wasi)

    if (plugin == null) {
      println("Something happened during the instantiate method execution")
      throw new RuntimeException("Something happened during the template initialization")
    } else {
      val instance = instancesCounter.incrementAndGet()

      val slot = new WasmContextSlot(
        id = s"$poolId-$instance",
        poolId = poolId,
        instance = WasmContextSlotId(instance),
        plugin,
        config,
        wasm,
        functions = hostFunctions,
        instanceId = IdGenerator.uuid,
        release = () => releaseSlot(instance)
      )
      instances.put(instance, WorkerSlot(wasmContextSlot = slot, uses = 0))
      slot
    }
  }

  def releaseSlot(slotId: Int) = {
    println(s"Try to release slot $slotId with lifetime ${config.lifetime}")
    if(config.lifetime == WasmVmLifetime.Forever)
      instances.put(slotId, instances(slotId).copy(busy = false, atLeastOnceReleased = true))
    else {
      println(s"remove $slotId from instances list of poolId $poolId")
      instances.remove(slotId)
    }
  }
}

case class WasmContextSlotId(value: Int)

case class WasmOtoroshiTemplate(template: OtoroshiTemplate, updating: AtomicBoolean = new AtomicBoolean(false))

object WasmUtils {

  private[wasm] val logger = Logger("otoroshi-wasm")

  val debugLog = Logger("otoroshi-wasm-debug")

  implicit val executor = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool((Runtime.getRuntime.availableProcessors * 4) + 1)
  )

  // TODO: handle env.wasmCacheSize based on creation date ?
  private[wasm] val _script_cache: TrieMap[String, CacheableWasmScript] = new TrieMap[String, CacheableWasmScript]()
  private[wasm] val poolCache                                           = new TrieMap[String, WasmContextSlotPool]()
  private[wasm] val queues                                              = new TrieMap[String, (DateTime, SourceQueueWithComplete[WasmAction])]()
  private[wasm] val engine                                              = new OtoroshiEngine()
  private[wasm] val templates                                           = new TrieMap[String, WasmOtoroshiTemplate]()

  def dumpPoolCache(): Unit = {
    poolCache.foreach(_._2.dump())
  }

  def releaseSlot(poolId: String, slotId: WasmContextSlotId) = {
    poolCache.get(poolId).map(_.releaseSlot(slotId.value))
  }

  def slotOnceReleased(poolId: String, slotId: WasmContextSlotId): Boolean = {
    poolCache.get(poolId).flatMap(_.slotOnceReleased(slotId)).getOrElse(false)
  }

  def scriptCache(implicit env: Env): TrieMap[String, CacheableWasmScript] = _script_cache

  def convertJsonCookies(wasmResponse: JsValue): Option[Seq[WSCookie]] =
    wasmResponse
      .select("cookies")
      .asOpt[Seq[JsObject]]
      .map { arr =>
        arr.map { c =>
          DefaultWSCookie(
            name = c.select("name").asString,
            value = c.select("value").asString,
            maxAge = c.select("maxAge").asOpt[Long],
            path = c.select("path").asOpt[String],
            domain = c.select("domain").asOpt[String],
            secure = c.select("secure").asOpt[Boolean].getOrElse(false),
            httpOnly = c.select("httpOnly").asOpt[Boolean].getOrElse(false)
          )
        }
      }

  def convertJsonPlayCookies(wasmResponse: JsValue): Option[Seq[Cookie]] =
    wasmResponse
      .select("cookies")
      .asOpt[Seq[JsObject]]
      .map { arr =>
        arr.map { c =>
          Cookie(
            name = c.select("name").asString,
            value = c.select("value").asString,
            maxAge = c.select("maxAge").asOpt[Int],
            path = c.select("path").asOpt[String].getOrElse("/"),
            domain = c.select("domain").asOpt[String],
            secure = c.select("secure").asOpt[Boolean].getOrElse(false),
            httpOnly = c.select("httpOnly").asOpt[Boolean].getOrElse(false),
            sameSite = c.select("domain").asOpt[String].flatMap(Cookie.SameSite.parse)
          )
        }
      }

  private[wasm] def getInvocationQueueFor(poolId: String)
                                         (implicit env: Env): SourceQueueWithComplete[WasmAction] = {
    queues.getOrUpdate(poolId) {
      val stream = Source
        .queue[WasmAction](env.wasmQueueBufferSize, OverflowStrategy.dropHead)
        .mapAsync(1) { action =>
          Future.apply {
            action match {
              case WasmAction.WasmInvocation(invoke, promise)    =>
                try {
                  val res = invoke()
                  promise.trySuccess(res)
                } catch {
                  case e: Throwable => promise.tryFailure(e)
                }
              case WasmAction.WasmOpaInvocation(invoke, promise) =>
                try {
                  val res = invoke()
                  promise.trySuccess(res)
                } catch {
                  case e: Throwable => promise.tryFailure(e)
                }
            }
          }(executor)
        }
      (DateTime.now(), stream.toMat(Sink.ignore)(Keep.both).run()(env.otoroshiMaterializer)._1)
    }
  }._2


  def createTemplate(config: WasmConfig, wasm: ByteString, env: Env): OtoroshiTemplate =
    env.metrics.withTimer("otoroshi.wasm.core.create-plugin.template", display = true) {
      val hash = getWasmHash(wasm)

      templates.get(hash) match {
        case Some(template) => template.template
        case None =>
          val resolver = new WasmSourceResolver()
          val source = resolver.resolve("wasm", wasm.toByteBuffer.array())

          val template = new OtoroshiTemplate(engine, hash, new Manifest(
            Seq[org.extism.sdk.wasm.WasmSource](source).asJava,
            new MemoryOptions(config.memoryPages),
            config.config.asJava,
            config.allowedHosts.asJava,
            config.allowedPaths.asJava
          ))
          templates.put(hash, WasmOtoroshiTemplate(template))

          template
      }
    }

def needsUpdate(incomingWasmConfig: WasmConfig, incomingWasm: ByteString, currentConfig: WasmConfig, currentWasm: ByteString): Boolean = {
  val configHasChanged = incomingWasmConfig != currentConfig
  val wasmHasChanged = incomingWasm != currentWasm
  if (WasmUtils.logger.isDebugEnabled && configHasChanged)
    WasmUtils.logger.debug(s"plugin needs update because of config change")
  if (WasmUtils.logger.isDebugEnabled && wasmHasChanged)
    WasmUtils.logger.debug(s"plugin needs update because of wasm change")
  configHasChanged || wasmHasChanged
}

private def updateTemplate(template: WasmOtoroshiTemplate, poolId: String, config: WasmConfig, wasm: ByteString)
                          (implicit env: Env, ec: ExecutionContext) = {
  if (template.updating.compareAndSet(false, true)) {
    val currentPool = poolCache(poolId)

    if (config.instances < currentPool.capacity) {
      env.otoroshiActorSystem.scheduler.scheduleOnce(20.seconds) { // TODO: config ?
        if (WasmUtils.logger.isDebugEnabled) {
          WasmUtils.logger.debug(s"trying to kill unused instances of $poolId")
        }
        currentPool.reduceCapacity(config.instances)
      }
    }

    currentPool.updateTemplate(template.template)
    if (WasmUtils.logger.isDebugEnabled) {
      WasmUtils.logger.debug(s"scheduling update $poolId")
    }
  }
}

  private[wasm] def getWasmHash(wasm: ByteString) = java.security.MessageDigest.getInstance("SHA-256")
    .digest(wasm.toArray)
    .map("%02x".format(_)).mkString

  private def callWasm(
                        wasm: ByteString,
                        config: WasmConfig,
                        wasmFunctionParameters: WasmFunctionParameters,
                        attrsOpt: Option[TypedMap],
                        ctx: Option[VmData],
                        addHostFunctions: Seq[OtoroshiHostFunction[_ <: OtoroshiHostUserData]]
                      )(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, (String, ResultsWrapper)]] =
    env.metrics.withTimerAsync("otoroshi.wasm.core.call-wasm", display = true) {
      WasmUtils.debugLog.debug("callWasm")

      val poolId = getWasmHash(wasm)

      def createPlugin(): WasmContextSlot = {
        poolCache.get(poolId) match {
          case Some(pool) =>
            val newSlot = pool.acquire(ctx.map(_.slotId))

            val templateHasChanged = needsUpdate(config, wasm, newSlot.cfg, newSlot.wasm)
            if (templateHasChanged) {
              templates.get(getWasmHash(wasm)).foreach(template => updateTemplate(template, poolId, config, wasm))
            }

            newSlot
          case None =>
            println(s"Create the pool for $poolId")
            val template: OtoroshiTemplate = createTemplate(config, wasm, env)
            val pool = new WasmContextSlotPool(poolId,
              capacity = config.instances,
              engine = engine,
              template = template,
              config = config,
              wasm = wasm,
              hostFunctions = if (config.importDefaultHostFunctions) {
                HostFunctions.getFunctions(config, poolId, attrsOpt) ++ addHostFunctions
              } else {
                addHostFunctions.toArray[OtoroshiHostFunction[_ <: OtoroshiHostUserData]]
              },
              linearMemories = LinearMemories.getMemories(config),
              wasi = config.wasi)

            poolCache.put(poolId, pool)
            pool.acquire(ctx.map(_.slotId))
        }
      }

      attrsOpt match {
        case None        => {
          val slot = createPlugin()
          if (config.opa) {
            // TODO: stringify already done on prev step
            slot.callOpa(wasmFunctionParameters.input.get).map { output =>
              slot.close(config.lifetime, ctx.map(_.slotId).isEmpty)
              output.map(str => (str, ResultsWrapper(new OtoroshiResults(0), slot.instance, slot.poolId)))
            }
          } else {
            slot.call(wasmFunctionParameters.withInput(wasmFunctionParameters.input.map(_.asInstanceOf[JsValue].stringify)), ctx).map { output =>
              slot.close(config.lifetime, ctx.map(_.slotId).isEmpty)
              output
            }
          }
        }
        case Some(attrs) => {
          val context = attrs.get(otoroshi.next.plugins.Keys.WasmContextKey) match {
            case None          => {
              val context = new WasmContext()
              attrs.put(otoroshi.next.plugins.Keys.WasmContextKey -> context)
              context
            }
            case Some(context) => context
          }
          val slot = ctx.map(_.slotId) match {
            case Some(slotId) => {
              val completeId = s"$poolId-${slotId.value}"
              context.get(completeId).getOrElse {
                val plugin = createPlugin()
                if (config.lifetime != WasmVmLifetime.Invocation) {
                  context.put(completeId, plugin)
                }
                plugin
              }
            }
            case None => {
              val plugin = createPlugin()
              val completeId = s"$poolId-${plugin.instance.value}"
              if (config.lifetime != WasmVmLifetime.Invocation) {
                context.put(completeId, plugin)
              }
              plugin
            }
          }
          if (config.opa) {
            slot.callOpa(wasmFunctionParameters.input.get).map { output =>
              slot.close(config.lifetime, ctx.map(_.slotId).isEmpty)
              output.map(str => (str, ResultsWrapper(new OtoroshiResults(0), slot.instance, slot.poolId)))
            }
          } else {
            slot.call(wasmFunctionParameters, ctx).map { output =>
              slot.close(config.lifetime, ctx.map(_.slotId).isEmpty)
              output
            }
          }
        }
      }
    }

  def execute(
               config: WasmConfig,
               defaultFunctionName: String,
               input: JsValue,
               attrs: Option[TypedMap],
               ctx: Option[VmData],
               shouldBeCallOnce: Boolean = false
             )(implicit env: Env): Future[Either[JsValue, String]] = {
    rawExecute(config, WasmFunctionParameters.ExtismFuntionCall(shouldBeCallOnce, config.functionName.getOrElse(defaultFunctionName), input.stringify), attrs, ctx, Seq.empty)
      .map(r => r.map(_._1))
  }

  def rawExecute(
                  _config: WasmConfig,
                  wasmFunctionParameters: WasmFunctionParameters,
                  attrs: Option[TypedMap],
                  ctx: Option[VmData],
                  addHostFunctions: Seq[OtoroshiHostFunction[_ <: OtoroshiHostUserData]]
                )(implicit env: Env): Future[Either[JsValue, (String, ResultsWrapper)]] =
    env.metrics.withTimerAsync("otoroshi.wasm.core.raw-execute", display = true) {
      val config = if (_config.opa) _config.copy(lifetime = WasmVmLifetime.Request) else _config
      WasmUtils.debugLog.debug("execute")
      val poolId = config.source.kind match {
        case WasmSourceKind.Local => {
          env.proxyState.wasmPlugin(config.source.path) match {
            case None         => config.source.cacheKey
            case Some(plugin) => plugin.config.source.cacheKey
          }
        }
        case _                    => config.source.cacheKey
      }
      scriptCache.get(poolId) match {
        case Some(CacheableWasmScript.FetchingWasmScript(fu))      =>
          fu.flatMap { _ =>
            rawExecute(config,
              wasmFunctionParameters,
              attrs,
              ctx,
              addHostFunctions)
          }
        case Some(CacheableWasmScript.CachedWasmScript(script, _)) => {
          env.metrics.withTimerAsync("otoroshi.wasm.core.get-config")(config.source.getConfig()).flatMap {
            case None              =>
              WasmUtils.callWasm(
                script,
                config,
                wasmFunctionParameters,
                attrs,
                ctx,
                addHostFunctions
              )
            case Some(finalConfig) =>
              val functionName = config.functionName.filter(_.nonEmpty).orElse(finalConfig.functionName)
              WasmUtils.callWasm(
                script,
                finalConfig.copy(functionName = functionName),
                wasmFunctionParameters.withFunctionName(functionName = functionName.getOrElse(wasmFunctionParameters.functionName)),
                attrs,
                ctx,
                addHostFunctions
              )
          }
        }
        case None if config.source.kind == WasmSourceKind.Unknown  => Left(Json.obj("error" -> "missing source")).future
        case _                                                     =>
          env.metrics.withTimerAsync("otoroshi.wasm.core.get-wasm")(config.source.getWasm()).flatMap {
            case Left(err)   => err.left.vfuture
            case Right(wasm) => {
              env.metrics.withTimerAsync("otoroshi.wasm.core.get-config")(config.source.getConfig()).flatMap {
                case None              =>
                  WasmUtils.callWasm(
                    wasm,
                    config,
                    wasmFunctionParameters,
                    attrs,
                    ctx,
                    addHostFunctions
                  )
                case Some(finalConfig) =>
                  val functionName = config.functionName.filter(_.nonEmpty).orElse(finalConfig.functionName)
                  WasmUtils.callWasm(
                    wasm,
                    finalConfig.copy(functionName = functionName),
                    wasmFunctionParameters.withFunctionName(functionName = functionName.getOrElse(wasmFunctionParameters.functionName)),
                    attrs,
                    ctx,
                    addHostFunctions
                  )
              }
            }
          }
      }
    }
}
