package otoroshi.plugins.geoloc

import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import akka.http.scaladsl.util.FastFuture
import akka.stream.{IOResult, Materializer}
import akka.stream.scaladsl.FileIO
import com.maxmind.geoip2.DatabaseReader
import otoroshi.env.Env
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginVisibility, NgStep}
import otoroshi.plugins.Keys
import otoroshi.script._
import otoroshi.utils.cache.Caches
import otoroshi.utils.cache.types.UnboundedTrieMap
import play.api.Logger
import play.api.libs.json.{JsNumber, JsObject, JsValue, Json}
import play.api.mvc.{Result, Results}
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.future.Implicits._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// MIGRATED
class MaxMindGeolocationInfoExtractor extends PreRouting {

  private val logger = Logger("otoroshi-plugins-maxmind-geolocation-info")

  override def name: String = "Geolocation details extractor (using Maxmind db)"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "GeolocationInfo" -> Json.obj(
          "path" -> "global",
          "log"  -> false
        )
      )
    )

  override def description: Option[String] =
    Some(
      """This plugin extract geolocation informations from ip address using the [Maxmind dbs](https://www.maxmind.com/en/geoip2-databases).
      |The informations are store in plugins attrs for other plugins to use
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "GeolocationInfo": {
      |    "path": "/foo/bar/cities.mmdb", // file path, can be "global"
      |    "log": false // will log geolocation details
      |  }
      |}
      |```
    """.stripMargin
    )

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute)

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val config  = ctx.configFor("GeolocationInfo")
    val pathOpt = (config \ "path").asOpt[String].orElse(Some("global"))
    val log     = (config \ "log").asOpt[Boolean].getOrElse(false)
    val from    = ctx.request.theIpAddress
    pathOpt match {
      case None           => FastFuture.successful(())
      case Some("global") =>
        env.datastores.globalConfigDataStore.latestSafe match {
          case None                                      => FastFuture.successful(())
          case Some(c) if !c.geolocationSettings.enabled => FastFuture.successful(())
          case Some(c)                                   =>
            c.geolocationSettings.find(from).map {
              case None           => FastFuture.successful(())
              case Some(location) => {
                if (log) logger.info(s"Ip-Address: $from, ${Json.prettyPrint(location)}")
                ctx.attrs.putIfAbsent(Keys.GeolocationInfoKey -> location)
                FastFuture.successful(())
              }
            }
        }
      case Some(path)     =>
        MaxMindGeolocationHelper.find(from, path).map {
          case None           => FastFuture.successful(())
          case Some(location) => {
            if (log) logger.info(s"Ip-Address: $from, ${Json.prettyPrint(location)}")
            ctx.attrs.putIfAbsent(Keys.GeolocationInfoKey -> location)
            FastFuture.successful(())
          }
        }
    }
  }
}

// MIGRATED
class IpStackGeolocationInfoExtractor extends PreRouting {

  private val logger = Logger("otoroshi-plugins-ipstack-geolocation-info")

  override def name: String = "Geolocation details extractor (using IpStack api)"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "GeolocationInfo" -> Json.obj(
          "apikey"  -> "xxxxxxx",
          "timeout" -> 2000,
          "log"     -> false
        )
      )
    )

  override def description: Option[String] =
    Some("""This plugin extract geolocation informations from ip address using the [IpStack dbs](https://ipstack.com/).
      |The informations are store in plugins attrs for other plugins to use
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "GeolocationInfo": {
      |    "apikey": "xxxxxxx",
      |    "timeout": 2000, // timeout in ms
      |    "log": false // will log geolocation details
      |  }
      |}
      |```
    """.stripMargin)

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                = Seq(NgStep.PreRoute)

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val config        = ctx.configFor("GeolocationInfo")
    val timeout: Long = (config \ "timeout").asOpt[Long].getOrElse(2000)
    val apiKeyOpt     = (config \ "apikey").asOpt[String]
    val log           = (config \ "log").asOpt[Boolean].getOrElse(false)
    val from          = ctx.request.theIpAddress
    apiKeyOpt match {
      case None         => FastFuture.successful(())
      case Some(apiKey) =>
        IpStackGeolocationHelper.find(from, apiKey, timeout).map {
          case None           => FastFuture.successful(())
          case Some(location) => {
            if (log) logger.info(s"Ip-Address: $from, ${Json.prettyPrint(location)}")
            ctx.attrs.putIfAbsent(Keys.GeolocationInfoKey -> location)
            FastFuture.successful(())
          }
        }
    }
  }
}

// MIGRATED
class GeolocationInfoHeader extends RequestTransformer {

  override def name: String = "Geolocation header"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "GeolocationInfoHeader" -> Json.obj(
          "headerName" -> "X-Geolocation-Info"
        )
      )
    )

  override def description: Option[String] =
    Some(
      """This plugin will send informations extracted by the Geolocation details extractor to the target service in a header.
      |
      |This plugin can accept the following configuration
      |
      |```json
      |{
      |  "GeolocationInfoHeader": {
      |    "headerName": "X-Geolocation-Info" // header in which info will be sent
      |  }
      |}
      |```
    """.stripMargin
    )

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config     = ctx.configFor("GeolocationInfoHeader")
    val headerName = (config \ "headerName").asOpt[String].getOrElse("X-Geolocation-Info")
    ctx.attrs.get(otoroshi.plugins.Keys.GeolocationInfoKey) match {
      case None           => Right(ctx.otoroshiRequest).future
      case Some(location) => {
        Right(
          ctx.otoroshiRequest.copy(
            headers = ctx.otoroshiRequest.headers ++ Map(
              headerName -> Json.stringify(location)
            )
          )
        ).future
      }
    }
  }
}

// MIGRATED
class GeolocationInfoEndpoint extends RequestTransformer {

  override def name: String = "Geolocation endpoint"

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] =
    Some(
      """This plugin will expose current geolocation informations on the following endpoint.
        |
        |`/.well-known/otoroshi/plugins/geolocation`
      """.stripMargin
    )

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Other)
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)

  override def transformRequestWithCtx(
      ctx: TransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    (ctx.rawRequest.method.toLowerCase(), ctx.rawRequest.path) match {
      case ("get", "/.well-known/otoroshi/plugins/geolocation") =>
        ctx.attrs.get(otoroshi.plugins.Keys.GeolocationInfoKey) match {
          case None           =>
            Left(
              Results.NotFound(Json.obj("error" -> "geolocation not found"))
            ).future // Right(ctx.otoroshiRequest).future
          case Some(location) => Left(Results.Ok(location)).future
        }
      case _                                                    => Right(ctx.otoroshiRequest).future
    }
  }
}

object IpStackGeolocationHelper {

  import scala.concurrent.duration._

  private val cache = Caches.bounded[String, Option[JsValue]](10000)

  def find(ip: String, apikey: String, timeout: Long)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Option[JsValue]] = {
    env.metrics.withTimerAsync("otoroshi.plugins.geolocation.ipstack.details") {
      cache.getIfPresent(ip) match {
        case Some(details) => FastFuture.successful(details)
        case None          => {
          env.Ws // no need for mtls here
            .url(s"https://api.ipstack.com/$ip?access_key=$apikey&format=1")
            .withFollowRedirects(false)
            .withRequestTimeout(timeout.millis)
            .get()
            .map {
              case resp if resp.status == 200 && resp.header("Content-Type").exists(_.contains("application/json")) =>
                val res = Some(resp.json)
                cache.put(ip, res)
                res
              case _                                                                                                => None
            }
        }
      }
    }
  }
}

object MaxMindGeolocationHelper {

  import scala.concurrent.duration._

  private val logger  = Logger("otoroshi-plugins-maxmind-geolocation-helper")
  private val ipCache = Caches.bounded[String, InetAddress](10000)
  private val cache   = Caches.bounded[String, Option[JsValue]](10000)
  private val dbs     = new UnboundedTrieMap[String, (AtomicReference[DatabaseReader], AtomicBoolean, AtomicBoolean)]()
  private val exc     =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors() + 1))

  def dbRefInit(path: String)(implicit env: Env, ec: ExecutionContext): Unit = {

    def init(initializing: AtomicBoolean): Future[Unit] = {
      if (initializing.compareAndSet(false, true)) {
        if (path.startsWith("http:zip://") || path.startsWith("https:zip://") || path.toLowerCase().contains(".zip")) {
          logger.info(s"Initializing Geolocation db from zip file URL: ${path.replace("zip:", "")} ...")
          initDbFromURLWithUnzip(path)
        } else if (
          path.startsWith("http:tgz://") || path.startsWith("https:tgz://") || path
            .toLowerCase()
            .contains(".tar.gz")
        ) {
          logger.info(s"Initializing Geolocation db from tar.gz file URL: ${path.replace("tgz:", "")} ...")
          initDbFromURLWithUntar(path)
        } else if (path.startsWith("http://") || path.startsWith("https://")) {
          logger.info(s"Initializing Geolocation db from URL: $path ...")
          initDbFromURL(path)
        } else {
          logger.info(s"Initializing Geolocation db from file path: $path ...")
          initDbFromFilePath(path)
        }
      } else {
        FastFuture.successful(())
      }
    }

    def tryInit(): Future[Unit] = {
      dbs.get(path) match {
        case None                                             =>
          dbs.putIfAbsent(
            path,
            (new AtomicReference[DatabaseReader](), new AtomicBoolean(false), new AtomicBoolean(false))
          )
          tryInit()
        case Some((_, _, initialized)) if initialized.get()   =>
          FastFuture.successful(())
        case Some((_, initializing, _)) if initializing.get() =>
          FastFuture.successful(())
        case Some((_, initializing, _))                       =>
          env.metrics.withTimerAsync("otoroshi.plugins.geolocation.maxmind.init") {
            init(initializing)
          }
      }
    }

    tryInit()
    ()
  }

  def dbRefSet(path: String, reader: DatabaseReader): Unit = dbs.get(path).foreach(_._1.set(reader))
  def dbRefGet(path: String): Option[DatabaseReader]       = dbs.get(path).flatMap(t => Option(t._1.get()))
  def dbInitializationDoneSet(path: String): Unit = {
    dbs.get(path).foreach { tuple =>
      tuple._2.set(true)
      tuple._3.set(true)
    }
  }
  def dbInitializationDoneGet(path: String): Boolean       = dbs.get(path).exists(_._3.get())

  private def wasSuccessful(io: IOResult): Boolean = {
    io.wasSuccessful
  }

  private def initDbFromFilePath(file: String)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    Future {
      val cityDbFile = new File(file)
      val cityDb     = new DatabaseReader.Builder(cityDbFile).build()
      dbRefSet(file, cityDb)
      dbInitializationDoneSet(file)
    }(exc).andThen {
      case Success(_) =>
        logger.info("Geolocation db from file path initialized")
        dbInitializationDoneSet(file)
      case Failure(e) =>
        logger.error("Geolocation db from file path initialization failed", e)
        dbInitializationDoneSet(file)
    }(exc)
  }

  private def initDbFromURL(url: String)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val dir  = java.nio.file.Files.createTempDirectory("oto-geolite-")
    val file = dir.resolve("geolite.mmdb")
    env.Ws
      .url(url) // no need for mtls here
      .withRequestTimeout(30.seconds)
      .withFollowRedirects(false)
      .withMethod("GET")
      .stream()
      .map {
        case resp if resp.status != 200 =>
          logger.error("Geolocation db initialization from URL failed, could not write file on disk")
          dbInitializationDoneSet(url)
        case resp                       => {
          resp.bodyAsSource.runWith(FileIO.toPath(file))(env.otoroshiMaterializer).map {
            case res if !wasSuccessful(res) =>
              logger.error("Geolocation db initialization from URL failed, status was not 200")
              dbInitializationDoneSet(url)
            case res if wasSuccessful(res)  =>
              val cityDbFile = file.toFile
              val cityDb     = new DatabaseReader.Builder(cityDbFile).build()
              dbRefSet(url, cityDb)
              dbInitializationDoneSet(url)
              logger.info("Geolocation db from URL initialized")
          }
        }
      }
  }

  private def initDbFromURLWithUnzip(rawUrl: String)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val dir  = java.nio.file.Files.createTempDirectory("oto-geolite-")
    val file = dir.resolve("geolite.zip")
    val url  = rawUrl.replace("zip:", "")
    env.Ws
      .url(url) // no need for mtls here
      .withRequestTimeout(30.seconds)
      .withFollowRedirects(false)
      .withMethod("GET")
      .stream()
      .map {
        case resp if resp.status != 200 =>
          logger.error("Geolocation db initialization from zip file URL failed, status was not 200")
          dbInitializationDoneSet(rawUrl)
        case resp                       => {
          resp.bodyAsSource.runWith(FileIO.toPath(file))(env.otoroshiMaterializer).map {
            case res if !wasSuccessful(res) =>
              logger.error("Geolocation db initialization from zip file URL failed, could not write file on disk")
              dbInitializationDoneSet(rawUrl)
            case res if wasSuccessful(res)  =>
              Try {
                val builder  = new ProcessBuilder
                builder.command(
                  "/bin/sh",
                  "-c",
                  s"""cd $dir
                     |unzip geolite.zip
                     |rm -rf geolite.zip
                     |mv Geo* geolite
                     |mv geolite/GeoLite2-City.mmdb geolite.mmdb
s                     |mv *.mmdb geolite.mmdb
                     |rm -rf ./geolite
                  """.stripMargin
                )
                builder.directory(dir.toFile)
                val process  = builder.start
                val exitCode = process.waitFor
                exitCode match {
                  case 0 =>
                    val cityDbFile = dir.resolve("geolite.mmdb").toFile
                    val cityDb     = new DatabaseReader.Builder(cityDbFile).build()
                    dbRefSet(rawUrl, cityDb)
                    dbInitializationDoneSet(rawUrl)
                    logger.info("Geolocation db initialized from zip file URL")
                  case _ =>
                    logger.error("Geolocation db initialization from zip file URL failed, extraction failed")
                    dbInitializationDoneSet(rawUrl)
                }
              } match {
                case Success(_) =>
                case Failure(e) =>
                  logger.error(s"Geolocation db initialization from zip file URL failed", e)
              }
          }
        }
      }
  }

  private def initDbFromURLWithUntar(rawUrl: String)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val dir  = java.nio.file.Files.createTempDirectory("oto-geolite-")
    val file = dir.resolve("geolite.tar.gz")
    val url  = rawUrl.replace("tgz:", "")
    env.Ws
      .url(url) // no need for mtls here
      .withRequestTimeout(30.seconds)
      .withFollowRedirects(false)
      .withMethod("GET")
      .stream()
      .map {
        case resp if resp.status != 200 =>
          logger.error("Geolocation db initialization from tar.gz file URL failed, status was not 200")
          dbInitializationDoneSet(rawUrl)
        case resp                       => {
          resp.bodyAsSource.runWith(FileIO.toPath(file))(env.otoroshiMaterializer).map {
            case res if !wasSuccessful(res) =>
              logger.error("Geolocation db initialization from tar.gz file URL failed, could not write file on disk")
              dbInitializationDoneSet(rawUrl)
            case res if wasSuccessful(res)  =>
              Try {
                val builder  = new ProcessBuilder
                builder.command(
                  "/bin/sh",
                  "-c",
                  s"""cd $dir
                    |tar -xvf geolite.tar.gz
                    |rm -rf geolite.tar.gz
                    |mv Geo* geolite
                    |mv geolite/GeoLite2-City.mmdb geolite.mmdb
                    |mv *.mmdb geolite.mmdb
                    |rm -rf ./geolite
                  """.stripMargin
                )
                builder.directory(dir.toFile)
                val process  = builder.start
                val exitCode = process.waitFor
                exitCode match {
                  case 0    =>
                    val cityDbFile = dir.resolve("geolite.mmdb").toFile
                    val cityDb     = new DatabaseReader.Builder(cityDbFile).build()
                    dbRefSet(rawUrl, cityDb)
                    dbInitializationDoneSet(rawUrl)
                    logger.info(s"Geolocation db from tar.gz file URL initialized")
                  case code =>
                    dbInitializationDoneSet(rawUrl)
                    logger.error(
                      s"Geolocation db initialization from tar.gz file URL failed, tar.gz extraction failed: $code"
                    )
                }
              } match {
                case Success(_) =>
                case Failure(e) =>
                  logger.error(s"Geolocation db from tar.gz file URL initialization failed", e)
              }

          }
        }
      }
  }

  def find(ip: String, file: String)(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] = {
    env.metrics.withTimerAsync("otoroshi.plugins.geolocation.maxmind.details") {
      dbRefInit(file)
      cache.getIfPresent(ip) match {
        case loc @ Some(_)                         => FastFuture.successful(loc.flatten)
        case None if dbInitializationDoneGet(file) => {
          val inet = ipCache.get(ip, _ => InetAddress.getByName(ip))
          //dbs.get(file) match {
          //  case None =>
          //    logger.error(s"Did not found db for $file")
          //  case Some((ref, _, _)) => {
          dbRefGet(file) match {
            case None     =>
              logger.error(s"Did not found dbref for $file")
              FastFuture.successful(None)
            case Some(db) => {
              Try(db.city(inet)) match { // TODO: blocking ???
                case Failure(e)    =>
                  cache.put(ip, None)
                case Success(city) => {
                  Option(city)
                    .map { c =>
                      // val asn = asnDb.asn(inet)
                      // val org = asn.getAutonomousSystemOrganization // TODO: blocking ??? non free version ?
                      // val asnNumber = asn.getAutonomousSystemNumber // TODO: blocking ??? non free version ?
                      val ipType   = if (ip.contains(":")) "ipv6" else "ipv4"
                      val location = Json.obj(
                        "ip"             -> ip,
                        "type"           -> ipType,
                        "continent_code" -> c.getContinent.getCode,
                        "continent_name" -> c.getContinent.getName,
                        "country_code"   -> c.getCountry.getIsoCode,
                        "country_name"   -> c.getCountry.getName,
                        "region_code"    -> c.getPostal.getCode,
                        "region_name"    -> c.getMostSpecificSubdivision.getName,
                        "city"           -> c.getCity.getName,
                        "latitude"       -> JsNumber(c.getLocation.getLatitude.toDouble),
                        "longitude"      -> JsNumber(c.getLocation.getLongitude.toDouble),
                        "location"       -> Json.obj(
                          "geoname_id" -> JsNumber(c.getCountry.getGeoNameId.toInt),
                          "name"       -> c.getCountry.getName,
                          "languages"  -> Json.arr(),
                          "is_eu"      -> c.getCountry.isInEuropeanUnion
                        )
                      )
                      cache.put(ip, Some(location))
                    }
                    .getOrElse {
                      cache.put(ip, None)
                    }
                }
              }
            }
          }
          //}
          //}
          FastFuture.successful(cache.getIfPresent(ip).flatten)
        }
        case _                                     =>
          FastFuture.successful(None) // initialization in progress
      }
    }
  }
}
