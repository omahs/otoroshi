package otoroshi.utils.http

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpEntity.{ChunkStreamPart, Limitable, SizeLimit}
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest, WebSocketUpgradeResponse}
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.{ClientTransport, ConnectionContext, Http, HttpsConnectionContext}
import akka.stream.{Attributes, FlowShape, Inlet, Materializer, Outlet, OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import com.google.common.base.Charsets
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.ssl.SSLConfigSettings
import otoroshi.env.Env
import otoroshi.models.{ClientConfig, Target}
import org.apache.commons.codec.binary.Base64
import otoroshi.gateway.{RequestTimeoutException, Timeout}
import otoroshi.netty.{NettyClientConfig, NettyHttpClient}
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws._
import play.api.mvc.MultipartFormData
import play.shaded.ahc.org.asynchttpclient.util.Assertions
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, DynamicSSLEngineProvider}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import reactor.netty.http.client.HttpClient

import java.io.{File, FileOutputStream}
import java.net.{InetAddress, InetSocketAddress, URI}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javax.net.ssl.SSLContext
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.TreeMap
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future, Promise}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, XML}

case class DNPart(raw: String) {
  private val parts = raw.split("=").map(_.trim)
  val name = {
    val head = parts.head.toLowerCase()
    if (head == "sn") {
      "surname"
    } else {
      head
    }
  }
  val value         = parts.last
}

case class DN(raw: String) {
  val parts                  = raw.split(",").toSeq.map(_.trim).map(DNPart.apply)
  def isEqualsTo(other: DN): Boolean = {
    parts.size == other.parts.size && parts.forall(p => other.parts.exists(o => o.name == p.name && o.value == p.value))
  }
  def stringifyDebug: String = s"DN(${stringify})"
  def stringify: String = {
    parts
      .sortWith((a, b) => a.name.compareTo(b.name) > 0)
      .map(p => s"${p.name.toUpperCase()}=${p.value}")
      .mkString(", ")
  }
}

case class MtlsConfig(
    certs: Seq[String] = Seq.empty,
    trustedCerts: Seq[String] = Seq.empty,
    mtls: Boolean = false,
    loose: Boolean = false,
    trustAll: Boolean = false
) {
  lazy val legit: Boolean = certs.nonEmpty || trustedCerts.nonEmpty || trustAll || loose
  lazy val actualCerts: Seq[Cert] = {
    certs.flatMap { id =>
      DynamicSSLEngineProvider.certificates.get(id) match {
        case a @ Some(_) => a
        case None        =>
          val dn = DN(id)
          DynamicSSLEngineProvider.certificates.values.toSeq.filter { cert =>
            cert.certificate.exists { c =>
              val otherDn = DN(c.getSubjectDN.getName)
              dn.isEqualsTo(otherDn)
            }
          } filter { cert =>
            cert.from.isBefore(org.joda.time.DateTime.now()) && cert.to.isAfter(org.joda.time.DateTime.now())
          } sortWith { (c1, c2) =>
            c1.to.compareTo(c2.to) > 0
          } headOption
      }
    }
  }
  lazy val actualTrustedCerts: Seq[Cert] = {
    trustedCerts.flatMap { id =>
      DynamicSSLEngineProvider.certificates.get(id) match {
        case a @ Some(_) => a
        case None        =>
          val dn = DN(id)
          DynamicSSLEngineProvider.certificates.values.toSeq.filter { cert =>
            cert.certificate.exists { c =>
              val otherDn = DN(c.getSubjectDN.getName)
              dn.isEqualsTo(otherDn)
            }
          } filter { cert =>
            cert.from.isBefore(org.joda.time.DateTime.now()) && cert.to.isAfter(org.joda.time.DateTime.now())
          } sortWith { (c1, c2) =>
            c1.to.compareTo(c2.to) > 0
          } headOption
      }
    }
  }
  def json: JsValue       = MtlsConfig.format.writes(this)
  def toJKS(implicit env: Env): (java.io.File, java.io.File, String) = {
    val password      = IdGenerator.token
    val path1         = java.nio.file.Files.createTempFile("oto-kafka-keystore-", ".jks")
    val path2         = java.nio.file.Files.createTempFile("oto-kafka-truststore-", ".jks")
    val certificates1 = certs.flatMap(DynamicSSLEngineProvider.certificates.get)
    val certificates2 = trustedCerts.flatMap(DynamicSSLEngineProvider.certificates.get)
    val keystore1     = DynamicSSLEngineProvider.createKeyStore(certificates1)
    keystore1.store(new FileOutputStream(path1.toFile), password.toCharArray)
    val keystore2     = DynamicSSLEngineProvider.createKeyStore(certificates2)
    keystore2.store(new FileOutputStream(path2.toFile), password.toCharArray)
    env.lifecycle.addStopHook { () =>
      path1.toFile.delete()
      path1.toFile.deleteOnExit()
      path2.toFile.delete()
      path2.toFile.deleteOnExit()
      Future.successful(())
    }
    if (trustedCerts.isEmpty) {
      (path1.toFile, path1.toFile, password)
    } else {
      (path1.toFile, path2.toFile, password)
    }
  }
}

object MtlsConfig {
  val default                                = MtlsConfig()
  def read(opt: Option[JsValue]): MtlsConfig = opt.flatMap(json => format.reads(json).asOpt).getOrElse(default)
  val format                                 = new Format[MtlsConfig] {
    override def reads(json: JsValue): JsResult[MtlsConfig] =
      Try {
        MtlsConfig(
          certs = (json \ "certs")
            .asOpt[Seq[String]]
            .orElse((json \ "certId").asOpt[String].map(v => Seq(v)))
            .map(_.filter(_.trim.nonEmpty))
            .getOrElse(Seq.empty),
          trustedCerts = (json \ "trustedCerts")
            .asOpt[Seq[String]]
            .map(_.filter(_.trim.nonEmpty))
            .getOrElse(Seq.empty),
          mtls = (json \ "mtls").asOpt[Boolean].orElse((json \ "tls").asOpt[Boolean]).getOrElse(false),
          loose = (json \ "loose").asOpt[Boolean].getOrElse(false),
          trustAll = (json \ "trustAll").asOpt[Boolean].getOrElse(false)
        )
      } match {
        case Failure(e) => JsError(e.getMessage())
        case Success(v) => JsSuccess(v)
      }
    override def writes(o: MtlsConfig): JsValue             =
      Json.obj(
        "certs"        -> JsArray(o.certs.map(JsString.apply)),
        "trustedCerts" -> JsArray(o.trustedCerts.map(JsString.apply)),
        "mtls"         -> o.mtls,
        "loose"        -> o.loose,
        "trustAll"     -> o.trustAll
      )
  }
}

class MtlsWs(chooser: WsClientChooser) {
  @inline
  def url(url: String, config: MtlsConfig): WSRequest =
    config match {
      case MtlsConfig(_, _, false, _, _)    => chooser.url(url)
      case m @ MtlsConfig(_, _, true, _, _) => chooser.urlWithCert(url, Some(m))
      // case MtlsConfig(seq, seq2, _, _, _) if (seq ++ seq2).isEmpty         => chooser.url(url)
      // case MtlsConfig(seq, seq2, false, _, _) if (seq ++ seq2).nonEmpty    => chooser.url(url)
      // case m @ MtlsConfig(seq, seq2, true, _, _) if (seq ++ seq2).nonEmpty => chooser.urlWithCert(url, Some(m))
    }
}

object MtlsWs {
  def apply(chooser: WsClientChooser): MtlsWs = new MtlsWs(chooser)
}

object WsClientChooser {
  def apply(
      standardClient: WSClient,
      akkaClient: AkkWsClient,
      nettyClient: NettyHttpClient,
      // ahcCreator: SSLConfigSettings => WSClient,
      fullAkka: Boolean,
      env: Env
  ): WsClientChooser = new WsClientChooser(standardClient, akkaClient, nettyClient, /*ahcCreator, */ fullAkka, env)
}

class WsClientChooser(
    standardClient: WSClient,
    akkaClient: AkkWsClient,
    val nettyClient: NettyHttpClient,
    // ahcCreator: SSLConfigSettings => WSClient,
    fullAkka: Boolean,
    env: Env
) extends WSClient {

  private[utils] val logger                  = Logger("otoroshi-ws-client-chooser")
  private[utils] val lastSslConfig           = new AtomicReference[SSLConfigSettings](null)
  private[utils] val connectionContextHolder = new AtomicReference[WSClient](null)

  private val nettyClientConfig  = NettyClientConfig.parseFrom(env)
  private val enforceNettyOnAkka = nettyClientConfig.enforceAkkaClient
  private val enforceAll         = nettyClientConfig.enforceAll

  // private def getAhcInstance(): WSClient = {
  //   val currentSslContext = DynamicSSLEngineProvider.sslConfigSettings
  //   if (currentSslContext != null && !currentSslContext.equals(lastSslConfig.get())) {
  //     lastSslConfig.set(currentSslContext)
  //     logger.debug("Building new client instance")
  //     val client = ahcCreator(currentSslContext)
  //     connectionContextHolder.set(client)
  //   }
  //   connectionContextHolder.get()
  // }

  def ws[T](
      request: WebSocketRequest,
      targetOpt: Option[Target],
      mtlsConfigOpt: Option[MtlsConfig],
      clientFlow: Flow[Message, Message, T],
      customizer: ClientConnectionSettings => ClientConnectionSettings
  ): (Future[WebSocketUpgradeResponse], T) = {
    val tlsConfigOpt = targetOpt.map(_.mtlsConfig).orElse(mtlsConfigOpt)
    val certs: Seq[Cert]        = tlsConfigOpt
      .filter(_.mtls)
      .toSeq
      .flatMap(_.actualCerts)
    //.flatMap(DynamicSSLEngineProvider.certificates.get)
    val trustedCerts: Seq[Cert] = tlsConfigOpt
      .filter(_.mtls)
      .toSeq
      .flatMap(_.actualTrustedCerts)
    // .flatMap(DynamicSSLEngineProvider.certificates.get)
    akkaClient.executeWsRequest(
      request,
      tlsConfigOpt.exists(_.loose),
      tlsConfigOpt
        .filter(_.mtls)
        .exists(_.trustAll),
      certs,
      trustedCerts,
      clientFlow,
      customizer
    )
  }

  def url(url: String): WSRequest = {
    val protocol = scala.util.Try(url.split(":\\/\\/").apply(0)).getOrElse("http")
    urlWithProtocol(protocol, url)
  }

  def urlMulti(url: String, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    val protocol = scala.util.Try(url.split(":\\/\\/").apply(0)).getOrElse("http")
    urlWithProtocol(protocol, url, clientConfig)
  }

  def akkaUrl(url: String, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    if (enforceNettyOnAkka || enforceAll) {
      nettyClient.url(url).withClientConfig(clientConfig)
    } else {
      new AkkaWsClientRequest(akkaClient, url, None, HttpProtocols.`HTTP/1.1`, clientConfig = clientConfig, env = env)(
        akkaClient.mat
      )
    }
  }

  // def _urlWithCert(url: String, certs: Seq[String], mtls: Boolean = false, loose: Boolean = false): WSRequest = {
  //   new AkkaWsClientRequest(akkaClient, url, certId.map(c => Target(
  //     host = "####",
  //     mtlsConfig = MtlsConfig(certs, mtls, loose)
  //     // loose = loose,
  //     // mtls = mtls,
  //     // certId = Some(c)
  //   )), HttpProtocols.`HTTP/1.1`, clientConfig = ClientConfig(), env = env)(
  //     akkaClient.mat
  //   )
  // }

  def urlWithCert(url: String, mtlsConfig: Option[MtlsConfig]): WSRequest = {
    if (enforceNettyOnAkka || enforceAll) {
      mtlsConfig match {
        case None    => nettyClient.url(url)
        case Some(c) => nettyClient.url(url).withTlsConfig(c)
      }
    } else {
      new AkkaWsClientRequest(
        akkaClient,
        url,
        mtlsConfig.map(c =>
          Target(
            host = "####",
            mtlsConfig = c
          )
        ),
        HttpProtocols.`HTTP/1.1`,
        clientConfig = ClientConfig(),
        env = env
      )(
        akkaClient.mat
      )
    }
  }
  def akkaUrlWithTarget(url: String, target: Target, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    if (enforceNettyOnAkka || enforceAll || target.protocol.isHttp2OrHttp3) {
      nettyClient.url(url).withTarget(target).withClientConfig(clientConfig).withProtocol(target.protocol.value)
    } else {
      new AkkaWsClientRequest(
        akkaClient,
        url,
        Some(target),
        HttpProtocols.`HTTP/1.1`,
        clientConfig = clientConfig,
        env = env
      )(
        akkaClient.mat
      )
    }
  }
  def akkaHttp2Url(url: String, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    if (enforceNettyOnAkka || enforceAll) {
      nettyClient.url(url).withClientConfig(clientConfig).withProtocol("HTTP/2.0")
    } else {
      new AkkaWsClientRequest(akkaClient, url, None, HttpProtocols.`HTTP/2.0`, clientConfig = clientConfig, env = env)(
        akkaClient.mat
      )
    }
  }

  // def ahcUrl(url: String): WSRequest = getAhcInstance().url(url)

  def classicUrl(url: String): WSRequest = {
    if (enforceAll) {
      nettyClient.url(url)
    } else {
      standardClient.url(url)
    }
  }

  def urlWithTarget(url: String, target: Target, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    val useAkkaHttpClient = env.datastores.globalConfigDataStore.latestSafe.map(_.useAkkaHttpClient).getOrElse(false)
    if (useAkkaHttpClient || fullAkka) {
      if (enforceNettyOnAkka || enforceAll || target.protocol.isHttp2OrHttp3) {
        nettyClient.url(url).withTarget(target).withClientConfig(clientConfig).withProtocol(target.protocol.value)
      } else {
        new AkkaWsClientRequest(
          akkaClient,
          url,
          Some(target),
          HttpProtocols.`HTTP/1.1`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )
      }
    } else {
      if (enforceAll || target.protocol.isHttp2OrHttp3) {
        nettyClient.url(url).withTarget(target).withClientConfig(clientConfig)
      } else {
        urlWithProtocol(target.scheme, url, clientConfig)
      }
    }
  }

  def urlWithProtocol(protocol: String, url: String, clientConfig: ClientConfig = ClientConfig()): WSRequest = {
    val useAkkaHttpClient = env.datastores.globalConfigDataStore.latestSafe.map(_.useAkkaHttpClient).getOrElse(false)
    protocol.toLowerCase() match {
      case _ if enforceAll                                            =>
        nettyClient.url(url).withClientConfig(clientConfig).withProtocol(protocol)
      case _ if enforceNettyOnAkka && (useAkkaHttpClient || fullAkka) =>
        nettyClient.url(url).withClientConfig(clientConfig).withProtocol(protocol)
      case "http" if useAkkaHttpClient || fullAkka                    =>
        new AkkaWsClientRequest(
          akkaClient,
          url,
          None,
          HttpProtocols.`HTTP/1.1`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )
      case "https" if useAkkaHttpClient || fullAkka                   =>
        new AkkaWsClientRequest(
          akkaClient,
          url,
          None,
          HttpProtocols.`HTTP/1.1`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )

      case "http" if !useAkkaHttpClient  => standardClient.url(url)
      case "https" if !useAkkaHttpClient => standardClient.url(url)

      case "standard:http"  => standardClient.url(url.replace("standard:http://", "http://"))
      case "standard:https" => standardClient.url(url.replace("standard:https://", "https://"))

      // case "ahc:http"  => getAhcInstance().url(url.replace("ahc:http://", "http://"))
      // case "ahc:https" => getAhcInstance().url(url.replace("ahc:https://", "https://"))

      case "ahc:http"  =>
        new AkkaWsClientRequest(
          akkaClient,
          url.replace("ahc:http://", "http://"),
          None,
          HttpProtocols.`HTTP/1.1`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )
      case "ahc:https" =>
        new AkkaWsClientRequest(
          akkaClient,
          url.replace("ahc:https://", "http://"),
          None,
          HttpProtocols.`HTTP/1.1`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )

      case "ahttp"                    =>
        new AkkaWsClientRequest(
          akkaClient,
          url.replace("ahttp://", "http://"),
          None,
          HttpProtocols.`HTTP/1.1`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )
      case "ahttps"                   =>
        new AkkaWsClientRequest(
          akkaClient,
          url.replace("ahttps://", "https://"),
          None,
          HttpProtocols.`HTTP/1.1`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )
      case "mtls:https"               =>
        new AkkaWsClientRequest(
          akkaClient,
          url.replace("mtls:https://", "https://"),
          None,
          HttpProtocols.`HTTP/1.1`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )
      case "mtls"                     =>
        new AkkaWsClientRequest(
          akkaClient,
          url.replace("mtls://", "https://"),
          None,
          HttpProtocols.`HTTP/1.1`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )
      case p if p.startsWith("mtls#") => {
        val parts           = url.split("://")
        val mtlsConfigRaw   = parts.apply(0).replace("mtls#", "")
        val urlEnds         = parts.apply(1)
        val (loose, certId) = if (mtlsConfigRaw.contains("loose#")) {
          (true, mtlsConfigRaw.replace("loose#", ""))
        } else {
          (false, mtlsConfigRaw)
        }
        new AkkaWsClientRequest(
          akkaClient,
          "https://" + urlEnds,
          Some(
            Target(
              host = urlEnds,
              mtlsConfig = MtlsConfig(Seq(certId), Seq.empty, true, loose)
              // loose = loose,
              // mtls = true,
              // certId = Some(certId)
            )
          ),
          HttpProtocols.`HTTP/1.1`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )
      }
      case "http2"                    =>
        new AkkaWsClientRequest(
          akkaClient,
          url.replace("http2://", "http://"),
          None,
          HttpProtocols.`HTTP/2.0`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )
      case "http2s"                   =>
        new AkkaWsClientRequest(
          akkaClient,
          url.replace("http2s://", "https://"),
          None,
          HttpProtocols.`HTTP/2.0`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )

      case _ if useAkkaHttpClient || fullAkka    =>
        new AkkaWsClientRequest(
          akkaClient,
          url,
          None,
          HttpProtocols.`HTTP/1.1`,
          clientConfig = clientConfig,
          env = env
        )(
          akkaClient.mat
        )
      case _ if !(useAkkaHttpClient || fullAkka) => standardClient.url(url)
    }
  }

  override def underlying[T]: T = standardClient.underlying[T]

  override def close(): Unit = ()
}

object AkkWsClient {
  def cookies(httpResponse: HttpResponse): Seq[WSCookie] = {
    httpResponse.headers
      .collect { case c: `Set-Cookie` =>
        c.cookie
      }
      .map { c =>
        WSCookieWithSameSite(
          name = c.name,
          value = c.value,
          domain = c.domain,
          path = c.path,
          maxAge = c.maxAge,
          secure = c.secure,
          httpOnly = c.httpOnly,
          sameSite = c.`extension`
            .filter(_.startsWith("SameSite="))
            .map(_.replace("SameSite=", ""))
            .flatMap(play.api.mvc.Cookie.SameSite.parse)
        )
      }
  }
}

case class WSCookieWithSameSite(
    name: String,
    value: String,
    domain: Option[String] = None,
    path: Option[String] = None,
    maxAge: Option[Long] = None,
    secure: Boolean = false,
    httpOnly: Boolean = false,
    sameSite: Option[play.api.mvc.Cookie.SameSite] = None
) extends WSCookie

/*
// huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false
class CustomLooseHostnameVerifier(mkLogger: LoggerFactory) extends HostnameVerifier {

  private val logger = mkLogger(getClass)

  private val defaultHostnameVerifier = new NoopHostnameVerifier // new DefaultHostnameVerifier(mkLogger)

  override def verify(hostname: String, sslSession: SSLSession): Boolean = {
    if (hostname.contains("&")) {
      val parts           = hostname.split("&")
      val actualHost      = parts(1)
      val hostNameMatches = defaultHostnameVerifier.verify(actualHost, sslSession)
      if (!hostNameMatches) {
        logger.warn(
          s"Hostname verification failed on hostname $actualHost, but the connection was accepted because 'loose' is enabled on service target."
        )
      }
      true
    } else {
      val hostNameMatches = defaultHostnameVerifier.verify(hostname, sslSession)
      if (!hostNameMatches) {
        logger.warn(
          s"Hostname verification failed on hostname $hostname, but the connection was accepted because 'loose' is enabled on service target."
        )
      }
      true
    }
  }
}

// huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false
class CustomHostnameVerifier(mkLogger: LoggerFactory) extends HostnameVerifier {

  private val logger = mkLogger(getClass)

  private val defaultHostnameVerifier = new NoopHostnameVerifier //new DefaultHostnameVerifier(mkLogger)

  override def verify(hostname: String, sslSession: SSLSession): Boolean = {
    if (hostname.contains("&")) {
      val parts      = hostname.split("&")
      val actualHost = parts(1)
      // println(s"verifying ${actualHost} over ${sslSession.getPeerCertificateChain.head.getSubjectDN.getName}")
      defaultHostnameVerifier.verify(actualHost, sslSession)
    } else {
      // println(s"verifying ${hostname} over ${sslSession.getPeerCertificateChain.head.getSubjectDN.getName}")
      defaultHostnameVerifier.verify(hostname, sslSession)
    }
  }
}
 */

object SSLConfigSettingsCustomizer {
  implicit class BetterSSLConfigSettings(val sslc: SSLConfigSettings) extends AnyVal {
    def callIf(pred: => Boolean, f: SSLConfigSettings => SSLConfigSettings): SSLConfigSettings = {
      if (pred) {
        f(sslc)
      } else {
        sslc
      }
    }
  }
}

class AkkWsClient(config: WSClientConfig, env: Env)(implicit system: ActorSystem, materializer: Materializer)
    extends WSClient {

  val ec     = system.dispatcher
  val mat    = materializer
  val client = Http(system)

  override def underlying[T]: T = client.asInstanceOf[T]

  def url(url: String): WSRequest = {
    new AkkaWsClientRequest(this, url, clientConfig = ClientConfig(), targetOpt = None, env = env)
  }

  override def close(): Unit = Await.ready(client.shutdownAllConnectionPools(), 10.seconds) // AWAIT: valid

  private[utils] val logger                         = Logger("otoroshi-akka-ws-client")
  private[utils] val wsClientConfig: WSClientConfig = config
  private[utils] val akkaSSLConfig: AkkaSSLConfig   = AkkaSSLConfig(system).withSettings(
    config.ssl
      // huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false
      // .callIf(env.manualDnsResolve, _.withHostnameVerifierClass(classOf[CustomHostnameVerifier]))
      .withSslParametersConfig(
        config.ssl.sslParametersConfig
          .withClientAuth(com.typesafe.sslconfig.ssl.ClientAuth.need) // TODO: do we really need that ?
      )
      .withDefault(false)
  )
  private[utils] val akkaSSLLooseConfig: AkkaSSLConfig = AkkaSSLConfig(system).withSettings(
    config.ssl
      // huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false
      //.callIf(env.manualDnsResolve, _.withHostnameVerifierClass(classOf[CustomLooseHostnameVerifier]))
      .withLoose(config.ssl.loose.withAcceptAnyCertificate(true).withDisableHostnameVerification(true))
      .withSslParametersConfig(
        config.ssl.sslParametersConfig
          .withClientAuth(com.typesafe.sslconfig.ssl.ClientAuth.need) // TODO: do we really need that ?
      )
      .withDefault(false)
  )

  private[utils] val lastSslContext               = new AtomicReference[SSLContext](null)
  private[utils] val connectionContextHolder      =
    new AtomicReference[HttpsConnectionContext](client.createClientHttpsContext(akkaSSLConfig))
  private[utils] val connectionContextLooseHolder =
    new AtomicReference[HttpsConnectionContext](connectionContextHolder.get())

  // client.validateAndWarnAboutLooseSettings()

  private[utils] val clientConnectionSettings: ClientConnectionSettings = ClientConnectionSettings(system)
    .withConnectingTimeout(FiniteDuration(config.connectionTimeout._1, config.connectionTimeout._2))
    .withIdleTimeout(config.idleTimeout)
  //.withUserAgentHeader(Some(`User-Agent`("Otoroshi-akka"))) // config.userAgent.map(_ => `User-Agent`(_)))

  private[utils] val connectionPoolSettings: ConnectionPoolSettings = ConnectionPoolSettings(system)
    .withConnectionSettings(clientConnectionSettings)
    .withMaxRetries(0)
    .withIdleTimeout(config.idleTimeout)

  private[utils] val singleSslContextCache: Cache[String, SSLContext] = Scaffeine()
    .recordStats()
    .expireAfterWrite(1.hour)
    .maximumSize(1000)
    .build()

  private[utils] def executeRequest[T](
      request: HttpRequest,
      loose: Boolean,
      trustAll: Boolean,
      clientCerts: Seq[Cert],
      trustedCerts: Seq[Cert],
      clientConfig: ClientConfig,
      customizer: ConnectionPoolSettings => ConnectionPoolSettings
  ): Future[HttpResponse] = {
    // TODO: fix warning with
    // https://github.com/akka/akka/blob/master/akka-stream/src/main/scala/com/typesafe/sslconfig/akka/AkkaSSLConfig.scala#L83-L109
    // https://github.com/lightbend/ssl-config/blob/master/ssl-config-core/src/main/scala/com/typesafe/sslconfig/ssl/SSLContextBuilder.scala#L99-L127
    clientCerts match {
      case certs if (clientCerts ++ trustedCerts).isEmpty  => {
        val currentSslContext = DynamicSSLEngineProvider.currentClient
        if (currentSslContext != null && !currentSslContext.equals(lastSslContext.get())) {
          lastSslContext.set(currentSslContext)
          val connectionContext: HttpsConnectionContext      =
            ConnectionContext.https(currentSslContext, sslConfig = Some(akkaSSLConfig))
          val connectionContextLoose: HttpsConnectionContext =
            ConnectionContext.https(currentSslContext, sslConfig = Some(akkaSSLLooseConfig))
          connectionContextHolder.set(connectionContext)
          connectionContextLooseHolder.set(connectionContextLoose)
        }
        val pool              = customizer(connectionPoolSettings).withMaxConnections(512)
        val cctx              = if (loose) connectionContextLooseHolder.get() else connectionContextHolder.get()
        if (clientConfig.cacheConnectionSettings.enabled) {
          queueClientRequest(request, pool, cctx, clientConfig.cacheConnectionSettings)
        } else {
          client.singleRequest(
            request,
            cctx,
            pool
          )
        }
      }
      case certs if (clientCerts ++ trustedCerts).nonEmpty => {
        if (logger.isDebugEnabled)
          logger.debug(
            s"Calling ${request.uri} with mTLS context of ${clientCerts.size} client certificates and ${trustedCerts.size} trusted certificates"
          )
        // logger.info(s"Calling ${request.uri} with mTLS context of ${clientCerts.size} client certificates and ${trustedCerts.size} trusted certificates: ${Json.prettyPrint(Json.obj(
        //   "clientCerts" -> JsArray(clientCerts.map(c => JsString(c.name + " - " + c.enrich().certificates.head.getSubjectDN.getName))),
        //   "trustedCerts" -> JsArray(trustedCerts.map(c => JsString(c.name + " - " + c.enrich().certificates.head.getSubjectDN.getName))),
        // ))}")
        val sslContext = env.metrics.withTimer("otoroshi.core.tls.http-client.single-context-fetch") {
          val cacheKey = certs.sortWith((c1, c2) => c1.id.compareTo(c2.id) > 0).map(_.cacheKey).mkString("-")
          singleSslContextCache.getOrElse(
            cacheKey,
            DynamicSSLEngineProvider.setupSslContextFor(certs, trustedCerts, trustAll, client = true, env)
          )
        }
        // val sslContext = DynamicSSLEngineProvider.setupSslContextFor(clientCerts, trustedCerts, trustAll, env)
        env.metrics.withTimer("otoroshi.core.tls.http-client.single-context-call") {
          val pool = customizer(connectionPoolSettings).withMaxConnections(512)
          val cctx = if (loose) {
            ConnectionContext.https(sslContext, sslConfig = Some(akkaSSLLooseConfig))
          } else {
            ConnectionContext.https(sslContext, sslConfig = Some(akkaSSLConfig))
          }
          if (clientConfig.cacheConnectionSettings.enabled) {
            queueClientRequest(request, pool, cctx, clientConfig.cacheConnectionSettings)
          } else {
            client.singleRequest(request, cctx, pool)
          }
        }
      }
    }
  }

  private val queueCache = new UnboundedTrieMap[String, SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])]]()

  private def getQueue(
      request: HttpRequest,
      settings: ConnectionPoolSettings,
      connectionContext: HttpsConnectionContext,
      queueSettings: CacheConnectionSettings
  ): SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])] = {
    val host    = request.uri.authority.host.toString()
    val port    = request.uri.authority.port
    val isHttps = request.uri.scheme.equalsIgnoreCase("https")
    val key     = s"$host-$port-$isHttps-${queueSettings.queueSize}"
    queueCache.getOrElseUpdate(
      key, {
        if (logger.isDebugEnabled) logger.debug(s"create host connection cache queue for '$key'")
        val pool = if (isHttps) {
          client.cachedHostConnectionPoolHttps[Promise[HttpResponse]](
            host = host,
            port = port,
            connectionContext = connectionContext,
            settings = settings
          )
        } else {
          client.cachedHostConnectionPool[Promise[HttpResponse]](host = host, port = port, settings = settings)
        }
        Source
          .queue[(HttpRequest, Promise[HttpResponse])](queueSettings.queueSize, queueSettings.strategy)
          .via(pool)
          .to(Sink.foreach {
            case (Success(resp), p) => p.success(resp)
            case (Failure(e), p)    => p.failure(e)
          })
          .run()
      }
    )
  }

  private def queueClientRequest(
      request: HttpRequest,
      settings: ConnectionPoolSettings,
      connectionContext: HttpsConnectionContext,
      queueSettings: CacheConnectionSettings
  ): Future[HttpResponse] = {
    val queue           = getQueue(request, settings, connectionContext, queueSettings)
    val responsePromise = Promise[HttpResponse]()
    queue
      .offer((request, responsePromise))
      .flatMap {
        case QueueOfferResult.Enqueued    => responsePromise.future
        case QueueOfferResult.Dropped     =>
          FastFuture.failed(ClientQueueError("Client queue overflowed. Try again later."))
        case QueueOfferResult.Failure(ex) => FastFuture.failed(ClientQueueError(ex.getMessage))
        case QueueOfferResult.QueueClosed =>
          FastFuture.failed(
            ClientQueueError("Client queue was closed (pool shut down) while running the request. Try again later.")
          )
      }(ec)
  }

  private[utils] def executeWsRequest[T](
      __request: WebSocketRequest,
      loose: Boolean,
      trustAll: Boolean,
      clientCerts: Seq[Cert],
      trustedCerts: Seq[Cert],
      clientFlow: Flow[Message, Message, T],
      customizer: ClientConnectionSettings => ClientConnectionSettings
  ): (Future[WebSocketUpgradeResponse], T) = {
    val request = __request
      .applyOnWithPredicate(_.uri.scheme == "http")(r => r.copy(uri = r.uri.copy(scheme = "ws")))
      .applyOnWithPredicate(_.uri.scheme == "https")(r => r.copy(uri = r.uri.copy(scheme = "wss")))
      .copy(extraHeaders =
        __request.extraHeaders
          .filterNot(h => h.lowercaseName() == "content-length")
          .filterNot(h => h.lowercaseName() == "content-type")
      )
    clientCerts match {
      case certs if (clientCerts ++ trustedCerts).isEmpty  => {
        val currentSslContext = DynamicSSLEngineProvider.currentClient
        if (currentSslContext != null && !currentSslContext.equals(lastSslContext.get())) {
          lastSslContext.set(currentSslContext)
          val connectionContext: HttpsConnectionContext      =
            ConnectionContext.https(currentSslContext, sslConfig = Some(akkaSSLConfig))
          val connectionContextLoose: HttpsConnectionContext =
            ConnectionContext.https(currentSslContext, sslConfig = Some(akkaSSLLooseConfig))
          connectionContextHolder.set(connectionContext)
          connectionContextLooseHolder.set(connectionContextLoose)
        }
        client.singleWebSocketRequest(
          request = request,
          clientFlow = clientFlow,
          connectionContext = if (loose) connectionContextLooseHolder.get() else connectionContextHolder.get(),
          settings = customizer(ClientConnectionSettings(system))
        )(mat)
      }
      case certs if (clientCerts ++ trustedCerts).nonEmpty => {
        if (logger.isDebugEnabled)
          logger.debug(s"Calling ws ${request.uri} with mTLS context of ${certs.size} certificates")
        val sslContext = env.metrics.withTimer("otoroshi.core.tls.http-client.single-context-fetch") {
          val cacheKey = certs.sortWith((c1, c2) => c1.id.compareTo(c2.id) > 0).map(_.cacheKey).mkString("-")
          singleSslContextCache.getOrElse(
            cacheKey,
            DynamicSSLEngineProvider.setupSslContextFor(certs, trustedCerts, trustAll, client = true, env)
          )
        }
        // val sslContext = DynamicSSLEngineProvider.setupSslContextFor(clientCerts, trustedCerts, trustAll, env)
        env.metrics.withTimer("otoroshi.core.tls.http-client.single-context-call") {
          val cctx = if (loose) {
            ConnectionContext.https(sslContext, sslConfig = Some(akkaSSLLooseConfig))
          } else {
            ConnectionContext.https(sslContext, sslConfig = Some(akkaSSLConfig))
          }
          client.singleWebSocketRequest(
            request = request,
            clientFlow = clientFlow,
            connectionContext = cctx,
            settings = customizer(ClientConnectionSettings(system))
          )(mat)
        }
      }
    }
  }
}

case class ClientQueueError(message: String) extends RuntimeException(message) with NoStackTrace

case class CacheConnectionSettings(
    enabled: Boolean = false,
    queueSize: Int = 2048,
    strategy: OverflowStrategy = OverflowStrategy.dropNew
) {
  def json: JsValue = {
    Json.obj(
      "enabled"   -> enabled,
      "queueSize" -> queueSize
    )
  }
}

case class AkkWsClientStreamedResponse(
    httpResponse: HttpResponse,
    underlyingUrl: String,
    mat: Materializer,
    requestTimeout: FiniteDuration,
    env: Env
) extends WSResponse {

  lazy val allHeaders: Map[String, Seq[String]] = {
    val headers                        = httpResponse.headers.groupBy(_.name()).mapValues(_.map(_.value())).toSeq ++ Seq(
      ("Content-Type" -> Seq(contentType))
    )
    val headz                          = TreeMap(headers: _*)(CaseInsensitiveOrdered)
    val noContentLengthHeader: Boolean =
      httpResponse.entity.contentLengthOption.isEmpty /*headz.getIgnoreCase("Content-Length").isEmpty*/
    val isContentLengthZero: Boolean   = httpResponse.entity.contentLengthOption.contains(
      0L
    ) /*headz.getIgnoreCase("Content-Length").exists(_.contains("0")) || */
    val hasChunkedHeader: Boolean      = headz
      .getIgnoreCase("Transfer-Encoding")
      .isDefined && headz.getIgnoreCase("Transfer-Encoding").exists(_.contains("chunked"))
    val isChunked: Boolean             =
      Option(httpResponse.entity.isChunked()).filter(identity) match { // don't know if actually legit ...
        case _ if isContentLengthZero                                                              => false
        case Some(chunked)                                                                         => chunked
        case None if !env.emptyContentLengthIsChunked                                              => hasChunkedHeader // false
        case None if env.emptyContentLengthIsChunked && hasChunkedHeader                           => true
        case None if env.emptyContentLengthIsChunked && !hasChunkedHeader && noContentLengthHeader => true
        case _                                                                                     => false
      }
    val addContentLengthHeaderZero     = isContentLengthZero && !noContentLengthHeader
    headz
      .applyOnIf(isChunked)(_ + ("Transfer-Encoding" -> Seq("chunked")))
      .applyOnIf(addContentLengthHeaderZero)(_ + ("Content-Length" -> Seq("0")))
      .applyOnIf(!addContentLengthHeaderZero && httpResponse.entity.contentLengthOption.isDefined)(
        _ + ("Content-Length" -> httpResponse.entity.contentLengthOption.toSeq.map(_.toString))
      )
  }

  private lazy val _charset: Option[HttpCharset] = httpResponse.entity.contentType.charsetOption
  private lazy val _contentType: String          = httpResponse.entity.contentType.mediaType
    .toString()
    .applyOnWithPredicate(_ == "none/none")(_ => "application/octet-stream") + _charset
    .map(v => ";charset=" + v.value)
    .getOrElse("")
  private lazy val _bodyAsBytes: ByteString      =
    Await.result(
      bodyAsSource.runFold(ByteString.empty)(_ ++ _)(mat),
      FiniteDuration(10, TimeUnit.MINUTES)
    ) // AWAIT: valid
  private lazy val _bodyAsString: String   = _bodyAsBytes.utf8String
  private lazy val _bodyAsXml: Elem        = XML.loadString(_bodyAsString)
  private lazy val _bodyAsJson: JsValue    = Json.parse(_bodyAsString)
  private lazy val _cookies: Seq[WSCookie] = AkkWsClient.cookies(httpResponse)

  def status: Int                                      = httpResponse.status.intValue()
  def statusText: String                               = httpResponse.status.defaultMessage()
  def headers: Map[String, Seq[String]]                = allHeaders
  def underlying[T]: T                                 = httpResponse.asInstanceOf[T]
  def bodyAsSource: Source[ByteString, _] = {
    if (ClientConfig.logger.isDebugEnabled)
      ClientConfig.logger.debug(s"[httpclient] consuming body in ${requestTimeout}")
    httpResponse.entity.dataBytes.takeWithin(requestTimeout)
  }
  override def header(name: String): Option[String]    = headerValues(name).headOption
  override def headerValues(name: String): Seq[String] = headers.getOrElse(name, Seq.empty)
  override def contentType: String                     = _contentType

  override def body[T: BodyReadable]: T      =
    throw new RuntimeException("Not supported on this WSClient !!! (StreamedResponse.body)")
  def body: String                           = _bodyAsString
  def bodyAsBytes: ByteString                = _bodyAsBytes
  def cookies: Seq[WSCookie]                 = _cookies
  def cookie(name: String): Option[WSCookie] = _cookies.find(_.name == name)
  override def xml: Elem                     = _bodyAsXml
  override def json: JsValue                 = _bodyAsJson
  override def uri: URI                      = new URI(underlyingUrl)
}

case class AkkWsClientRawResponse(httpResponse: HttpResponse, underlyingUrl: String, rawbody: ByteString)
    extends WSResponse {

  lazy val allHeaders: Map[String, Seq[String]] = {
    val headers = httpResponse.headers.groupBy(_.name()).mapValues(_.map(_.value())).toSeq ++ Seq(
      ("Content-Type" -> Seq(contentType))
    ) /*++ (if (httpResponse.entity.isChunked()) {
      Seq(("Transfer-Encoding" -> Seq("chunked")))
    } else {
      Seq.empty
    })*/
    TreeMap(headers: _*)(CaseInsensitiveOrdered)
  }

  private lazy val _charset: Option[HttpCharset] = httpResponse.entity.contentType.charsetOption
  private lazy val _contentType: String          = httpResponse.entity.contentType.mediaType
    .toString()
    .applyOnWithPredicate(_ == "none/none")(_ => "application/octet-stream") + _charset
    .map(v => ";charset=" + v.value)
    .getOrElse("")
  private lazy val _bodyAsBytes: ByteString      = rawbody
  private lazy val _bodyAsString: String         = rawbody.utf8String
  private lazy val _bodyAsXml: Elem              = XML.loadString(_bodyAsString)
  private lazy val _bodyAsJson: JsValue          = Json.parse(_bodyAsString)
  private lazy val _cookies: Seq[WSCookie]       = AkkWsClient.cookies(httpResponse)

  def status: Int                                      = httpResponse.status.intValue()
  def statusText: String                               = httpResponse.status.defaultMessage()
  def headers: Map[String, Seq[String]]                = allHeaders
  def underlying[T]: T                                 = httpResponse.asInstanceOf[T]
  def bodyAsSource: Source[ByteString, _]              = Source.single(rawbody)
  override def header(name: String): Option[String]    = headerValues(name).headOption
  override def headerValues(name: String): Seq[String] = headers.getOrElse(name, Seq.empty)
  def body: String                                     = _bodyAsString
  def bodyAsBytes: ByteString                          = _bodyAsBytes
  override def xml: Elem                               = _bodyAsXml
  override def json: JsValue                           = _bodyAsJson
  override def contentType: String                     = _contentType
  def cookies: Seq[WSCookie]                           = _cookies
  override def body[T: BodyReadable]: T                =
    throw new RuntimeException("Not supported on this WSClient !!! (RawResponse.body)")
  def cookie(name: String): Option[WSCookie]           = _cookies.find(_.name == name)
  override def uri: URI                                = new URI(underlyingUrl)
}

object CaseInsensitiveOrdered extends Ordering[String] {
  def compare(x: String, y: String): Int = {
    val xl = x.length
    val yl = y.length
    if (xl < yl) -1 else if (xl > yl) 1 else x.compareToIgnoreCase(y)
  }
}

object WSProxyServerUtils {

  def isIgnoredForHost(hostname: String, nonProxyHosts: Seq[String]): Boolean = {
    Assertions.assertNotNull(hostname, "hostname")
    if (nonProxyHosts.nonEmpty) {
      val var2: Iterator[_] = nonProxyHosts.iterator
      while ({
        var2.hasNext
      }) {
        val nonProxyHost: String = var2.next.asInstanceOf[String]
        if (this.matchNonProxyHost(hostname, nonProxyHost)) return true
      }
    }
    false
  }

  private def matchNonProxyHost(targetHost: String, nonProxyHost: String): Boolean = {
    if (nonProxyHost.length > 1) {
      if (nonProxyHost.charAt(0) == '*')
        return targetHost.regionMatches(
          true,
          targetHost.length - nonProxyHost.length + 1,
          nonProxyHost,
          1,
          nonProxyHost.length - 1
        )
      if (nonProxyHost.charAt(nonProxyHost.length - 1) == '*')
        return targetHost.regionMatches(true, 0, nonProxyHost, 0, nonProxyHost.length - 1)
    }
    nonProxyHost.equalsIgnoreCase(targetHost)
  }
}

object AkkaWsClientRequest {
  val atomicFalse = new AtomicBoolean(false)
}

case class AkkaWsClientRequest(
    client: AkkWsClient,
    rawUrl: String,
    targetOpt: Option[Target],
    protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`,
    _method: HttpMethod = HttpMethods.GET,
    body: WSBody = EmptyBody,
    headers: Map[String, Seq[String]] = Map.empty[String, Seq[String]],
    requestTimeout: Option[Duration] = None,
    proxy: Option[WSProxyServer] = None,
    clientConfig: ClientConfig = ClientConfig(),
    alreadyFailed: AtomicBoolean = AkkaWsClientRequest.atomicFalse,
    env: Env
)(implicit materializer: Materializer)
    extends WSRequest {

  implicit val ec = client.ec

  override type Self = WSRequest

  private val _uri = {
    val u = Uri(rawUrl)
    targetOpt match {
      case None         => u
      case Some(target) => {
        target.ipAddress match {
          case None                                                                       => u // TODO: fix it
          // huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false
          case Some(ipAddress) if env.manualDnsResolve && u.authority.host.isNamedHost()  => {
            u.copy(
              authority = u.authority.copy(
                port = target.thePort
                // host = akka.http.scaladsl.model.Uri.Host(s"${ipAddress}&${u.authority.host.address()}")
              )
            )
          }
          case Some(ipAddress) if !env.manualDnsResolve && u.authority.host.isNamedHost() => {
            val addr = InetAddress.getByAddress(u.authority.host.address(), InetAddress.getByName(ipAddress).getAddress)
            u.copy(
              authority = u.authority.copy(
                port = target.thePort,
                host = akka.http.scaladsl.model.Uri.Host(addr)
              )
            )
          }
          case Some(ipAddress)                                                            => {
            u.copy(
              authority = u.authority.copy(
                port = target.thePort,
                host = akka.http.scaladsl.model.Uri.Host(InetAddress.getByName(ipAddress))
              )
            )
          }
        }
      }
    }
  }

  private def customizer: ConnectionPoolSettings => ConnectionPoolSettings = {
    val relUri            = _uri.toRelative.toString()
    val idleTimeout       = clientConfig.extractTimeout(relUri, _.idleTimeout, _.idleTimeout)
    val connectionTimeout = clientConfig.extractTimeout(relUri, _.connectionTimeout, _.connectionTimeout)
    proxy
      .filter(p =>
        WSProxyServerUtils.isIgnoredForHost(Uri(rawUrl).authority.host.toString(), p.nonProxyHosts.getOrElse(Seq.empty))
      )
      .map { proxySettings =>
        val proxyAddress        = InetSocketAddress.createUnresolved(proxySettings.host, proxySettings.port)
        val httpsProxyTransport = (proxySettings.principal, proxySettings.password) match {
          case (Some(principal), Some(password)) => {
            val auth = akka.http.scaladsl.model.headers.BasicHttpCredentials(principal, password)
            //val realmBuilder = new Realm.Builder(proxySettings.principal.orNull, proxySettings.password.orNull)
            //val scheme: Realm.AuthScheme = proxySettings.protocol.getOrElse("http").toLowerCase(java.util.Locale.ENGLISH) match {
            //  case "http" | "https" => Realm.AuthScheme.BASIC
            //  case "kerberos" => Realm.AuthScheme.KERBEROS
            //  case "ntlm" => Realm.AuthScheme.NTLM
            //  case "spnego" => Realm.AuthScheme.SPNEGO
            //  case _ => scala.sys.error("Unrecognized protocol!")
            //}
            //realmBuilder.setScheme(scheme)
            ClientTransport.httpsProxy(proxyAddress, auth)
          }
          case _                                 => ClientTransport.httpsProxy(proxyAddress)
        }
        a: ConnectionPoolSettings => {
          if (ClientConfig.logger.isDebugEnabled)
            ClientConfig.logger.debug(
              s"[httpclient] using idleTimeout: $idleTimeout, connectionTimeout: $connectionTimeout"
            )
          a.withTransport(httpsProxyTransport)
            .withIdleTimeout(idleTimeout)
            .withConnectionSettings(
              a.connectionSettings
                .withTransport(httpsProxyTransport)
                .withConnectingTimeout(connectionTimeout)
                .withIdleTimeout(idleTimeout)
            )
        }
      } getOrElse { a: ConnectionPoolSettings =>
      val maybeIpAddress = targetOpt match {
        case None         => None
        case Some(target) =>
          target.ipAddress.map(addr => InetSocketAddress.createUnresolved(addr, target.thePort))
      }
      if (env.manualDnsResolve && maybeIpAddress.isDefined) {
        a.withTransport(ManualResolveTransport.resolveTo(maybeIpAddress.get))
          .withIdleTimeout(idleTimeout)
          .withConnectionSettings(
            a.connectionSettings
              .withTransport(ManualResolveTransport.resolveTo(maybeIpAddress.get))
              .withConnectingTimeout(connectionTimeout)
              .withIdleTimeout(idleTimeout)
          )
      } else {
        a.withIdleTimeout(idleTimeout)
          .withConnectionSettings(
            a.connectionSettings
              .withConnectingTimeout(connectionTimeout)
              .withIdleTimeout(idleTimeout)
          )
      }
    }
  }

  def withMethod(method: String): WSRequest = {
    copy(_method = HttpMethods.getForKeyCaseInsensitive(method).getOrElse(HttpMethod.custom(method)))
  }

  def withHttpHeaders(headers: (String, String)*): WSRequest = {
    copy(
      headers = headers.foldLeft(this.headers)((m, hdr) =>
        if (m.contains(hdr._1)) m.updated(hdr._1, m(hdr._1) :+ hdr._2)
        else m + (hdr._1 -> Seq(hdr._2))
      )
    )
  }

  def withRequestTimeout(timeout: Duration): Self = {
    if (ClientConfig.logger.isDebugEnabled)
      ClientConfig.logger.debug(s"[httpclient] setting requestTimeout to ${timeout}")
    copy(requestTimeout = Some(timeout))
  }

  def withFailureIndicator(af: AtomicBoolean): Self = {
    copy(alreadyFailed = af)
  }

  override def withBody[T](body: T)(implicit evidence$1: BodyWritable[T]): WSRequest =
    copy(body = evidence$1.transform(body))

  override def withHeaders(headers: (String, String)*): WSRequest = withHttpHeaders(headers: _*)

  def stream(): Future[WSResponse] = {
    val certs: Seq[Cert]        = targetOpt
      .filter(_.mtlsConfig.mtls)
      .toSeq
      .flatMap(_.mtlsConfig.actualCerts)
    //.flatMap(DynamicSSLEngineProvider.certificates.get)
    val trustedCerts: Seq[Cert] = targetOpt
      .filter(_.mtlsConfig.mtls)
      .toSeq
      .flatMap(_.mtlsConfig.actualTrustedCerts)
    //.flatMap(DynamicSSLEngineProvider.certificates.get)
    val trustAll: Boolean       = targetOpt
      .filter(_.mtlsConfig.mtls)
      .exists(_.mtlsConfig.trustAll)
    val req                     = buildRequest()
    val zeTimeout               = requestTimeout
      .map(v => FiniteDuration(v.toMillis, TimeUnit.MILLISECONDS))
      .getOrElse(env.longRequestTimeout) // (FiniteDuration(30, TimeUnit.DAYS)) // yeah that's infinity ...
    if (ClientConfig.logger.isDebugEnabled)
      ClientConfig.logger.debug(s"[httpclient] stream request with timeout to ${zeTimeout}")
    if (ClientConfig.logger.isDebugEnabled) ClientConfig.logger.debug(s"[httpclient] start req")
    val failure = Timeout
      .timeout(Done, zeTimeout)(client.ec, env.otoroshiScheduler)
      .flatMap(_ => FastFuture.failed(RequestTimeoutException))
    val start   = System.currentTimeMillis()
    val reqExec = client
      .executeRequest(
        req,
        targetOpt.exists(_.mtlsConfig.loose),
        trustAll,
        certs,
        trustedCerts,
        clientConfig,
        customizer
      )
      .flatMap { resp =>
        val remaining = zeTimeout.toMillis - (System.currentTimeMillis() - start)
        if (alreadyFailed.get()) {
          if (ClientConfig.logger.isDebugEnabled) ClientConfig.logger.debug(s"[httpclient] stream already failed")
          resp.entity.discardBytes()
          FastFuture.failed(otoroshi.gateway.RequestTimeoutException)
        } else if (remaining <= 0) {
          if (ClientConfig.logger.isDebugEnabled) ClientConfig.logger.debug(s"[httpclient] got stream resp too late")
          resp.entity.discardBytes()
          FastFuture.failed(otoroshi.gateway.RequestTimeoutException)
        } else {
          val remainingTimeout = remaining.millis
          if (ClientConfig.logger.isDebugEnabled)
            ClientConfig.logger.debug(s"[httpclient] got stream resp - ${remainingTimeout}")
          AkkWsClientStreamedResponse(
            resp,
            rawUrl,
            client.mat,
            remainingTimeout,
            env
          ).future
        }
      }
    Future.firstCompletedOf(Seq(reqExec, failure))
  }

  override def execute(method: String): Future[WSResponse] = {
    withMethod(method).execute()
  }

  override def execute(): Future[WSResponse] = {
    val certs: Seq[Cert]        = targetOpt
      .filter(_.mtlsConfig.mtls)
      .toSeq
      .flatMap(_.mtlsConfig.actualCerts)
    //.flatMap(DynamicSSLEngineProvider.certificates.get)
    val trustedCerts: Seq[Cert] = targetOpt
      .filter(_.mtlsConfig.mtls)
      .toSeq
      .flatMap(_.mtlsConfig.actualTrustedCerts)
    //.flatMap(DynamicSSLEngineProvider.certificates.get)
    val trustAll: Boolean       = targetOpt
      .filter(_.mtlsConfig.mtls)
      .exists(_.mtlsConfig.trustAll)
    val zeTimeout               = requestTimeout
      .map(v => FiniteDuration(v.toMillis, TimeUnit.MILLISECONDS))
      .getOrElse(env.longRequestTimeout) // (FiniteDuration(30, TimeUnit.DAYS)) // yeah that's infinity ...
    val failure = Timeout
      .timeout(Done, zeTimeout)(client.ec, env.otoroshiScheduler)
      .flatMap(_ => FastFuture.failed(RequestTimeoutException))
    val start   = System.currentTimeMillis()
    val reqExec = client
      .executeRequest(
        buildRequest(),
        targetOpt.exists(_.mtlsConfig.loose),
        trustAll,
        certs,
        trustedCerts,
        clientConfig,
        customizer
      )
      .flatMap { response: HttpResponse =>
        // FiniteDuration(client.wsClientConfig.requestTimeout._1, client.wsClientConfig.requestTimeout._2)
        val remaining = zeTimeout.toMillis - (System.currentTimeMillis() - start)
        if (alreadyFailed.get()) {
          if (ClientConfig.logger.isDebugEnabled) ClientConfig.logger.debug(s"[httpclient] execute already failed")
          response.entity.discardBytes()
          FastFuture.failed(otoroshi.gateway.RequestTimeoutException)
        } else if (remaining <= 0) {
          if (ClientConfig.logger.isDebugEnabled) ClientConfig.logger.debug(s"[httpclient] got resp too late")
          response.entity.discardBytes()
          FastFuture.failed(otoroshi.gateway.RequestTimeoutException)
        } else {
          val remainingTimeout = remaining.millis
          if (ClientConfig.logger.isDebugEnabled)
            ClientConfig.logger.debug(s"[httpclient] got resp - ${remainingTimeout}")
          response.entity
            .toStrict(remainingTimeout)
            .map(a => (response, a))
        }
      }
      .map { case (response: HttpResponse, body: HttpEntity.Strict) =>
        AkkWsClientRawResponse(response, rawUrl, body.data)
      }
    Future.firstCompletedOf(Seq(reqExec, failure))
  }

  private def realContentType: Option[ContentType] = {
    headers
      .get(`Content-Type`.name)
      .orElse(
        headers
          .get(`Content-Type`.lowercaseName)
      )
      .flatMap(_.headOption)
      .map { value =>
        HttpHeader.parse("Content-Type", value)
      }
      .flatMap {
        // case ParsingResult.Ok(header, _) => Option(header.asInstanceOf[`Content-Type`].contentType)
        case ParsingResult.Ok(header, _) =>
          header match {
            case `Content-Type`(contentType)                => contentType.some
            case RawHeader(_, value) if value.contains(",") =>
              value.split(",").headOption.map(_.trim).map(v => `Content-Type`.parseFromValueString(v)) match {
                case Some(Left(errs))                         => {
                  ClientConfig.logger.error(s"Error while parsing request content-type: ${errs}")
                  None
                }
                case Some(Right(`Content-Type`(contentType))) => contentType.some
                case None                                     => None
              }
            case RawHeader(_, value)                        =>
              `Content-Type`.parseFromValueString(value) match {
                case Left(errs)                         => {
                  ClientConfig.logger.error(s"Error while parsing request content-type: ${errs}")
                  None
                }
                case Right(`Content-Type`(contentType)) => contentType.some
              }
            case _                                          => None
          }
        case _                           => None
      }
  }

  private def realContentLength: Option[Long] = {
    headers
      .get(`Content-Length`.name)
      .orElse(headers.get(`Content-Length`.lowercaseName))
      .flatMap(_.headOption)
      .map(_.toLong)
  }

  private def realUserAgent: Option[String] = {
    headers
      .get(`User-Agent`.name)
      .orElse(headers.get(`User-Agent`.lowercaseName))
      .flatMap(_.headOption)
  }

  lazy val (akkaHttpEntity, updatedHeaders) = {
    val ct = realContentType.getOrElse(ContentTypes.`application/octet-stream`)
    val cl = realContentLength
    body match {
      case EmptyBody                                     => (HttpEntity.Empty, headers)
      case InMemoryBody(bytes)                           => (HttpEntity(ct, bytes), headers)
      // case SourceBody(_)     if cl.isDefined && cl.get == 0L => (HttpEntity.Default(ct, 0L, Source.single(ByteString.empty)), headers) // does not work as Default should have length > 0
      case SourceBody(_) if cl.isDefined && cl.get == 0L => (HttpEntity.Strict(ct, ByteString.empty), headers)
      case SourceBody(bytes) if cl.isDefined             => (HttpEntity(ct, cl.get, bytes), headers)
      case SourceBody(bytes)                             => (HttpEntity(ct, bytes), headers)
    }
  }

  def buildRequest(): HttpRequest = {
    // val internalUri = Uri(rawUrl)
    // val ua = realUserAgent.flatMap(s => Try(`User-Agent`(s)).toOption)
    val akkaHeaders: List[HttpHeader] = updatedHeaders
      .flatMap { case (key, values) =>
        values.distinct.map(value => HttpHeader.parse(key, value))
      }
      .flatMap {
        case ParsingResult.Ok(header, _) => Option(header)
        case _                           => None
      }
      .filter { h =>
        h.isNot(`Content-Type`.lowercaseName) &&
        h.isNot(`Content-Length`.lowercaseName) &&
        h.isNot(`Transfer-Encoding`.lowercaseName) &&
        //h.isNot(`User-Agent`.lowercaseName) &&
        !(h.is(Cookie.lowercaseName) && h.value().trim.isEmpty)
      }
      .toList // ++ ua

    val onInternalApi = updatedHeaders.getIgnoreCase("Host").map(_.last).contains(env.adminApiHost)
    val proto         = targetOpt.map(_.protocol.asAkka).getOrElse(protocol)
    val finalProtocol =
      if (proto == HttpProtocols.`HTTP/1.0` && onInternalApi) HttpProtocols.`HTTP/1.1`
      else (if (akkaHttpEntity.isChunked() && proto == HttpProtocols.`HTTP/1.0`) HttpProtocols.`HTTP/1.1`
            else proto)

    HttpRequest(
      method = _method,
      uri = _uri,
      headers = akkaHeaders,
      entity = akkaHttpEntity,
      protocol = finalProtocol
    )
  }

  ///////////

  override def withCookies(cookies: WSCookie*): WSRequest = {
    val oldCookies = headers.get("Cookie").getOrElse(Seq.empty[String])
    val newCookies = oldCookies :+ cookies.toList
      .map { c =>
        s"${c.name}=${c.value}"
      }
      .mkString(";")
    copy(
      headers = headers + ("Cookie" -> newCookies)
    )
  }

  override lazy val followRedirects: Option[Boolean]                                                     = Some(false)
  override def withFollowRedirects(follow: Boolean): Self                                                = this
  override def method: String                                                                            = _method.value
  override def queryString: Map[String, Seq[String]]                                                     = _uri.query().toMultiMap
  override def get(): Future[WSResponse]                                                                 = withMethod("GET").execute()
  override def post[T](body: T)(implicit evidence$2: BodyWritable[T]): Future[WSResponse]                =
    withMethod("POST")
      .withBody(evidence$2.transform(body))
      .addHttpHeaders("Content-Type" -> evidence$2.contentType)
      .execute()
  override def post(body: File): Future[WSResponse]                                                      =
    withMethod("POST")
      .withBody(InMemoryBody(ByteString(scala.io.Source.fromFile(body).mkString)))
      .addHttpHeaders("Content-Type" -> "application/octet-stream")
      .execute()
  override def patch[T](body: T)(implicit evidence$3: BodyWritable[T]): Future[WSResponse]               =
    withMethod("PATCH")
      .withBody(evidence$3.transform(body))
      .addHttpHeaders("Content-Type" -> evidence$3.contentType)
      .execute()
  override def patch(body: File): Future[WSResponse]                                                     =
    withMethod("PATCH")
      .withBody(InMemoryBody(ByteString(scala.io.Source.fromFile(body).mkString)))
      .addHttpHeaders("Content-Type" -> "application/octet-stream")
      .execute()
  override def put[T](body: T)(implicit evidence$4: BodyWritable[T]): Future[WSResponse]                 =
    withMethod("PUT")
      .withBody(evidence$4.transform(body))
      .addHttpHeaders("Content-Type" -> evidence$4.contentType)
      .execute()
  override def put(body: File): Future[WSResponse]                                                       =
    withMethod("PUT")
      .withBody(InMemoryBody(ByteString(scala.io.Source.fromFile(body).mkString)))
      .addHttpHeaders("Content-Type" -> "application/octet-stream")
      .execute()
  override def delete(): Future[WSResponse]                                                              = withMethod("DELETE").execute()
  override def head(): Future[WSResponse]                                                                = withMethod("HEAD").execute()
  override def options(): Future[WSResponse]                                                             = withMethod("OPTIONS").execute()
  override lazy val url: String                                                                          = _uri.toString()
  override lazy val uri: URI                                                                             = new URI(_uri.toRelative.toString())
  override lazy val contentType: Option[String]                                                          = realContentType.map(_.value)
  override lazy val cookies: Seq[WSCookie] = {
    headers.get("Cookie").map { headers =>
      headers.flatMap { header =>
        header.split(";").map { value =>
          val parts = value.split("=")
          DefaultWSCookie(
            name = parts(0),
            value = parts(1)
          )
        }
      }
    } getOrElse Seq.empty
  }
  override def withQueryString(parameters: (String, String)*): WSRequest                                 = addQueryStringParameters(parameters: _*)
  override def withQueryStringParameters(parameters: (String, String)*): WSRequest                       =
    copy(rawUrl = _uri.withQuery(Uri.Query.apply(parameters: _*)).toString())
  override def addQueryStringParameters(parameters: (String, String)*): WSRequest = {
    val params: Seq[(String, String)] =
      _uri.query().toMultiMap.toSeq.flatMap(t => t._2.map(t2 => (t._1, t2))) ++ parameters
    copy(rawUrl = _uri.withQuery(Uri.Query.apply(params: _*)).toString())
  }
  override def withProxyServer(proxyServer: WSProxyServer): WSRequest                                    = copy(proxy = Option(proxyServer))
  override def proxyServer: Option[WSProxyServer]                                                        = proxy
  override def post(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse]  =
    post[Source[MultipartFormData.Part[Source[ByteString, _]], _]](body)
  override def patch(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse] =
    patch[Source[MultipartFormData.Part[Source[ByteString, _]], _]](body)
  override def put(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[WSResponse]   =
    put[Source[MultipartFormData.Part[Source[ByteString, _]], _]](body)

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  override def auth: Option[(String, String, WSAuthScheme)]          =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.auth)")
  override def calc: Option[WSSignatureCalculator]                   =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.calc)")
  override def virtualHost: Option[String]                           =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.virtualHost)")
  override def sign(calc: WSSignatureCalculator): WSRequest          =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.sign)")
  override def withAuth(username: String, password: String, scheme: WSAuthScheme): WSRequest = {
    scheme match {
      case WSAuthScheme.BASIC =>
        addHttpHeaders(
          "Authorization" -> s"Basic ${Base64.encodeBase64String(s"${username}:${password}".getBytes(Charsets.UTF_8))}"
        )
      case _                  => throw new RuntimeException("Not supported on this WSClient !!! (Request.withAuth)")
    }
  }
  override def withRequestFilter(filter: WSRequestFilter): WSRequest =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.withRequestFilter)")
  override def withVirtualHost(vh: String): WSRequest                =
    throw new RuntimeException("Not supported on this WSClient !!! (Request.withVirtualHost)")

  override def withUrl(url: String): WSRequest = copy(rawUrl = url)
}

object Implicits {

  private val logger = Logger("otoroshi-http-implicits")

  implicit class BetterStandaloneWSRequest[T <: StandaloneWSRequest](val req: T)    extends AnyVal {
    def withMaybeProxyServer(opt: Option[WSProxyServer]): req.Self = {
      opt match {
        case Some(proxy) => req.withProxyServer(proxy)
        case None        => req.asInstanceOf[req.Self]
      }
    }
    def withFailureIndicator(alreadyFailed: AtomicBoolean): req.Self = {
      req match {
        case areq: AkkaWsClientRequest => areq.withFailureIndicator(alreadyFailed).asInstanceOf[req.Self]
        case _                         => req.asInstanceOf[req.Self]
      }
    }
    def ignore()(implicit mat: Materializer): req.Self = {
      req match {
        case httpRequest: AkkaWsClientRequest =>
          Try(httpRequest.akkaHttpEntity.dataBytes.runWith(Sink.ignore)(mat)) match {
            case Failure(e) => logger.error("Error while discarding request entity bytes ...", e)
            case _          => ()
          }
          req.asInstanceOf[req.Self]
        case _                                => req.asInstanceOf[req.Self]
      }
    }
  }
  implicit class BetterStandaloneWSResponse[T <: StandaloneWSResponse](val resp: T) extends AnyVal {
    def contentLength: Option[Long] = {
      resp.underlying[Any] match {
        case httpResponse: HttpResponse => httpResponse.entity.contentLengthOption
        case _                          => resp.header("Content-Length").map(_.toLong)
      }
    }
    def contentLengthStr: Option[String] = {
      resp.underlying[Any] match {
        case httpResponse: HttpResponse => httpResponse.entity.contentLengthOption.map(_.toString)
        case _                          => resp.header("Content-Length")
      }
    }
    def isChunked(): Option[Boolean] = {
      resp.underlying[Any] match {
        case httpResponse: HttpResponse => Some(httpResponse.entity.isChunked())
        //case responsePublisher: play.shaded.ahc.org.asynchttpclient.netty.handler.StreamedResponsePublisher => {
        //  val ahc = req.asInstanceOf[play.api.libs.ws.ahc.AhcWSResponse]
        //  val field = ahc.getClass.getDeclaredField("underlying")
        //  field.setAccessible(true)
        //  val sawsr = field.get(ahc).asInstanceOf[play.api.libs.ws.ahc.StreamedResponse]//.asInstanceOf[play.api.libs.ws.ahc.StandaloneAhcWSResponse]
        //  println(sawsr.headers)
        //  // val field2 = sawsr.getClass.getField("ahcResponse")
        //  // field2.setAccessible(true)
        //  // val response = field2.get(sawsr).asInstanceOf[play.shaded.ahc.org.asynchttpclient.Response]
        //  // println(response.getHeaders.names())
        //  //play.shaded.ahc.org.asynchttpclient.netty.handler.
        //  //HttpHeaders.isTransferEncodingChunked(responsePublisher)
        //  None
        //}
        case _                          => None
      }
    }
    def ignore()(implicit mat: Materializer): StandaloneWSResponse = {
      resp.underlying[Any] match {
        case httpResponse: HttpResponse =>
          Try(httpResponse.discardEntityBytes()) match {
            case Failure(e) => logger.error("Error while discarding entity bytes ...", e)
            case _          => ()
          }
          resp
        case _                          => resp
      }
    }
    def ignoreIf(predicate: => Boolean)(implicit mat: Materializer): StandaloneWSResponse = {
      if (predicate) {
        resp.underlying[Any] match {
          case httpResponse: HttpResponse =>
            Try(httpResponse.discardEntityBytes()) match {
              case Failure(e) => logger.error("Error while discarding entity bytes ...", e)
              case _          => ()
            }
            resp
          case _                          => resp
        }
      } else {
        resp
      }
    }
  }
}

object ManualResolveTransport {

  def resolveTo(address: InetSocketAddress): ClientTransport = {
    new ClientTransport {
      override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit
          system: ActorSystem
      ): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] =
        ClientTransport.TCP.connectTo(address.getHostString, address.getPort, settings)
    }
  }

  // huge workaround for https://github.com/akka/akka-http/issues/92,  can be disabled by setting otoroshi.options.manualDnsResolve to false
  // lazy val http: ClientTransport = ManualResolveTransport()
  /*
  private case class ManualResolveTransport() extends ClientTransport {
    def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(
        implicit system: ActorSystem
    ): Flow[ByteString, ByteString, Future[OutgoingConnection]] = {
      val inetSocketAddress = if (host.contains("&")) {
        val parts      = host.split("&")
        val ipAddress  = parts(0)
        val actualHost = parts(1)
        new InetSocketAddress(InetAddress.getByAddress(actualHost, InetAddress.getByName(ipAddress).getAddress), port)
      } else {
        InetSocketAddress.createUnresolved(host, port)
      }
      Tcp()
        .outgoingConnection(
          inetSocketAddress,
          settings.localAddress,
          settings.socketOptions,
          halfClose = true,
          settings.connectingTimeout,
          settings.idleTimeout
        )
        .mapMaterializedValue(
          _.map(tcpConn => OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher)
        )
    }
  }*/

  //private case class ManualResolveTransport(ipAddress: String) extends ClientTransport {
  //  def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
  //    Tcp().outgoingConnection(
  //      new InetSocketAddress(InetAddress.getByAddress(host, InetAddress.getByName(ipAddress).getAddress), port),
  //      settings.localAddress,
  //      settings.socketOptions,
  //      halfClose = true,
  //      settings.connectingTimeout,
  //      settings.idleTimeout
  //    ).mapMaterializedValue(_.map(tcpConn => OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher))
  //}
  //
  //private case class ManualResolveTransportTLS(ipAddress: String, sslContext: SSLContext, neg: NegotiateNewSession) extends ClientTransport {
  //  def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
  //    Tcp().outgoingTlsConnection(
  //      new InetSocketAddress(InetAddress.getByAddress(host, InetAddress.getByName(ipAddress).getAddress), port),
  //      sslContext = sslContext,
  //      negotiateNewSession = neg,
  //      localAddress = settings.localAddress,
  //      options = settings.socketOptions,
  //      connectTimeout =  settings.connectingTimeout,
  //      idleTimeout = settings.idleTimeout
  //    ).mapMaterializedValue(_.map(tcpConn => OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher))
  //}
}
