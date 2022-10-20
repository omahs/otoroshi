package otoroshi.next.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.models.RefJwtVerifier
import otoroshi.next.plugins.Keys.JwtInjectionKey
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue, BetterSyntax}
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

case class NgJwtVerificationConfig(verifiers: Seq[String] = Seq.empty) extends NgPluginConfig {
  def json: JsValue = NgJwtVerificationConfig.format.writes(this)
}

object NgJwtVerificationConfig {
  val format = new Format[NgJwtVerificationConfig] {
    override def reads(json: JsValue): JsResult[NgJwtVerificationConfig] = Try {
      NgJwtVerificationConfig(
        verifiers = json.select("verifiers").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgJwtVerificationConfig): JsValue             = Json.obj("verifiers" -> o.verifiers)
  }
}

class JwtVerification extends NgAccessValidator with NgRequestTransformer {

  private val configReads: Reads[NgJwtVerificationConfig] = NgJwtVerificationConfig.format

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess, NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def usesCallbacks: Boolean      = false
  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false

  override def isAccessAsync: Boolean                      = true
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Jwt verifiers"
  override def description: Option[String]                 =
    "This plugin verifies the current request with one or more jwt verifier".some
  override def defaultConfigObject: Option[NgPluginConfig] = NgJwtVerificationConfig().some

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    // val verifiers = ctx.config.select("verifiers").asOpt[Seq[String]].getOrElse(Seq.empty)
    val NgJwtVerificationConfig(verifiers) =
      ctx.cachedConfig(internalName)(configReads).getOrElse(NgJwtVerificationConfig())
    if (verifiers.nonEmpty) {
      val verifier = RefJwtVerifier(verifiers, true, Seq.empty)
      if (verifier.isAsync) {
        val promise = Promise[NgAccess]()
        verifier
          .verifyFromCache(
            request = ctx.request,
            desc = ctx.route.serviceDescriptor.some,
            apikey = ctx.apikey,
            user = ctx.user,
            elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
            attrs = ctx.attrs
          )
          .map {
            case Left(result)     => promise.trySuccess(NgAccess.NgDenied(result))
            case Right(injection) =>
              ctx.attrs.put(JwtInjectionKey -> injection)
              promise.trySuccess(NgAccess.NgAllowed)
          }
        promise.future
      } else {
        verifier.verifyFromCacheSync(
          request = ctx.request,
          desc = ctx.route.serviceDescriptor.some,
          apikey = ctx.apikey,
          user = ctx.user,
          elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          attrs = ctx.attrs
        ) match {
          case Left(result)     => NgAccess.NgDenied(result).vfuture
          case Right(injection) =>
            ctx.attrs.put(JwtInjectionKey -> injection)
            NgAccess.NgAllowed.vfuture
        }
      }
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }

  override def transformRequestSync(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    ctx.attrs.get(JwtInjectionKey) match {
      case None            => ctx.otoroshiRequest.right
      case Some(injection) => {
        ctx.otoroshiRequest
          .applyOnIf(injection.removeCookies.nonEmpty) { req =>
            req.copy(cookies = req.cookies.filterNot(c => injection.removeCookies.contains(c.name)))
          }
          .applyOnIf(injection.removeHeaders.nonEmpty) { req =>
            req.copy(headers =
              req.headers.filterNot(tuple => injection.removeHeaders.map(_.toLowerCase).contains(tuple._1.toLowerCase))
            )
          }
          .applyOnIf(injection.additionalHeaders.nonEmpty) { req =>
            req.copy(headers = req.headers ++ injection.additionalHeaders)
          }
          .applyOnIf(injection.additionalCookies.nonEmpty) { req =>
            req.copy(cookies = req.cookies ++ injection.additionalCookies.map(t => DefaultWSCookie(t._1, t._2)))
          }
          .right
      }
    }
  }
}


case class NgJwtVerificationOnlyConfig(verifier: Option[String] = None, failIfAbsent: Boolean = true) extends NgPluginConfig {
  def json: JsValue = NgJwtVerificationOnlyConfig.format.writes(this)
}

object NgJwtVerificationOnlyConfig {
  val format = new Format[NgJwtVerificationOnlyConfig] {
    override def reads(json: JsValue): JsResult[NgJwtVerificationOnlyConfig] = Try {
      NgJwtVerificationOnlyConfig(
        verifier = json.select("verifier").asOpt[String],
        failIfAbsent = json.select("fail_if_absent").asOpt[Boolean].getOrElse(true)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgJwtVerificationOnlyConfig): JsValue             = Json.obj(
      "verifier" -> o.verifier,
      "fail_if_absent" -> o.failIfAbsent
    )
  }
}

// TODO - features to implement
class JwtVerificationOnly extends NgAccessValidator with NgRequestTransformer {

  private val configReads: Reads[NgJwtVerificationOnlyConfig] = NgJwtVerificationOnlyConfig.format
  override def defaultConfigObject: Option[NgPluginConfig] = NgJwtVerificationOnlyConfig().some

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess, NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def usesCallbacks: Boolean      = false
  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false

  override def isAccessAsync: Boolean                      = true
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Jwt verification only"
  override def description: Option[String]                 =
    "This plugin verifies the current request with one jwt verifier".some


  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val NgJwtVerificationOnlyConfig(verifier, failIfAbsent) =
      ctx.cachedConfig(internalName)(configReads).getOrElse(NgJwtVerificationOnlyConfig())
    if (verifier.nonEmpty) {
      val refVerifier = RefJwtVerifier(Seq(verifier.get), true, Seq.empty)
      if (refVerifier.isAsync) {
        val promise = Promise[NgAccess]()
        refVerifier
          .verifyFromCache(
            request = ctx.request,
            desc = ctx.route.serviceDescriptor.some,
            apikey = ctx.apikey,
            user = ctx.user,
            elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
            attrs = ctx.attrs
          )
          .map {
            case Left(result)     => promise.trySuccess(NgAccess.NgDenied(result))
            case Right(injection) =>
              ctx.attrs.put(JwtInjectionKey -> injection)
              promise.trySuccess(NgAccess.NgAllowed)
          }
        promise.future
      } else {
        refVerifier.verifyFromCacheSync(
          request = ctx.request,
          desc = ctx.route.serviceDescriptor.some,
          apikey = ctx.apikey,
          user = ctx.user,
          elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          attrs = ctx.attrs
        ) match {
          case Left(result)     => NgAccess.NgDenied(result).vfuture
          case Right(injection) =>
            ctx.attrs.put(JwtInjectionKey -> injection)
            NgAccess.NgAllowed.vfuture
        }
      }
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }

  override def transformRequestSync(
                                     ctx: NgTransformerRequestContext
                                   )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    ctx.attrs.get(JwtInjectionKey) match {
      case None            => ctx.otoroshiRequest.right
      case Some(injection) => {
        ctx.otoroshiRequest
          .applyOnIf(injection.removeCookies.nonEmpty) { req =>
            req.copy(cookies = req.cookies.filterNot(c => injection.removeCookies.contains(c.name)))
          }
          .applyOnIf(injection.removeHeaders.nonEmpty) { req =>
            req.copy(headers =
              req.headers.filterNot(tuple => injection.removeHeaders.map(_.toLowerCase).contains(tuple._1.toLowerCase))
            )
          }
          .applyOnIf(injection.additionalHeaders.nonEmpty) { req =>
            req.copy(headers = req.headers ++ injection.additionalHeaders)
          }
          .applyOnIf(injection.additionalCookies.nonEmpty) { req =>
            req.copy(cookies = req.cookies ++ injection.additionalCookies.map(t => DefaultWSCookie(t._1, t._2)))
          }
          .right
      }
    }
  }
}

case class NgJwtSignerConfig(verifier: Option[String] = None, replaceIfPresent: Boolean = true, failIfPresent: Boolean = false) extends NgPluginConfig {
  def json: JsValue = NgJwtSignerConfig.format.writes(this)
}

object NgJwtSignerConfig {
  val format = new Format[NgJwtSignerConfig] {
    override def reads(json: JsValue): JsResult[NgJwtSignerConfig] = Try {
      NgJwtSignerConfig(
        verifier = json.select("verifier").asOpt[String],
        replaceIfPresent = json.select("replace_if_present").asOpt[Boolean].getOrElse(true),
        failIfPresent = json.select("fail_if_present").asOpt[Boolean].getOrElse(true),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: NgJwtSignerConfig): JsValue             = Json.obj(
      "verifier" -> o.verifier,
      "replace_if_present" -> o.replaceIfPresent,
      "fail_if_present" -> o.failIfPresent
    )
  }
}

// TODO - features to implement
class JwtSigner extends NgAccessValidator with NgRequestTransformer {

  private val configReads: Reads[NgJwtSignerConfig] = NgJwtSignerConfig.format
  override def defaultConfigObject: Option[NgPluginConfig] = NgJwtSignerConfig().some

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess, NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.AccessControl, NgPluginCategory.Classic)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand

  override def multiInstance: Boolean      = true
  override def core: Boolean               = true
  override def usesCallbacks: Boolean      = false
  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = false
  override def transformsError: Boolean    = false

  override def isAccessAsync: Boolean                      = true
  override def isTransformRequestAsync: Boolean            = false
  override def isTransformResponseAsync: Boolean           = true
  override def name: String                                = "Jwt signer"
  override def description: Option[String]                 = "This plugin can only generate token".some


  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val NgJwtSignerConfig(verifier, replaceIfPresent, failIfPresent) = ctx.cachedConfig(internalName)(configReads).getOrElse(NgJwtSignerConfig())
    if (verifier.nonEmpty) {
      val refVerifier = RefJwtVerifier(Seq(verifier.get), true, Seq.empty)
      if (refVerifier.isAsync) {
        val promise = Promise[NgAccess]()
        refVerifier
          .verifyFromCache(
            request = ctx.request,
            desc = ctx.route.serviceDescriptor.some,
            apikey = ctx.apikey,
            user = ctx.user,
            elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
            attrs = ctx.attrs
          )
          .map {
            case Left(result)     => promise.trySuccess(NgAccess.NgDenied(result))
            case Right(injection) =>
              ctx.attrs.put(JwtInjectionKey -> injection)
              promise.trySuccess(NgAccess.NgAllowed)
          }
        promise.future
      } else {
        refVerifier.verifyFromCacheSync(
          request = ctx.request,
          desc = ctx.route.serviceDescriptor.some,
          apikey = ctx.apikey,
          user = ctx.user,
          elContext = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          attrs = ctx.attrs
        ) match {
          case Left(result)     => NgAccess.NgDenied(result).vfuture
          case Right(injection) =>
            ctx.attrs.put(JwtInjectionKey -> injection)
            NgAccess.NgAllowed.vfuture
        }
      }
    } else {
      NgAccess.NgAllowed.vfuture
    }
  }

  override def transformRequestSync(
                                     ctx: NgTransformerRequestContext
                                   )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Either[Result, NgPluginHttpRequest] = {
    ctx.attrs.get(JwtInjectionKey) match {
      case None            => ctx.otoroshiRequest.right
      case Some(injection) => {
        ctx.otoroshiRequest
          .applyOnIf(injection.removeCookies.nonEmpty) { req =>
            req.copy(cookies = req.cookies.filterNot(c => injection.removeCookies.contains(c.name)))
          }
          .applyOnIf(injection.removeHeaders.nonEmpty) { req =>
            req.copy(headers =
              req.headers.filterNot(tuple => injection.removeHeaders.map(_.toLowerCase).contains(tuple._1.toLowerCase))
            )
          }
          .applyOnIf(injection.additionalHeaders.nonEmpty) { req =>
            req.copy(headers = req.headers ++ injection.additionalHeaders)
          }
          .applyOnIf(injection.additionalCookies.nonEmpty) { req =>
            req.copy(cookies = req.cookies ++ injection.additionalCookies.map(t => DefaultWSCookie(t._1, t._2)))
          }
          .right
      }
    }
  }
}