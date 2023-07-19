package functional

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import otoroshi.models.{ApiKey, ServiceDescriptor, ServiceGroupIdentifier, Target}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

class ApiKeysSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec {

  lazy val serviceHost = "auth.oto.tools"
  implicit val system  = ActorSystem("otoroshi-test")

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  s"[$name] Otoroshi ApiKeys" should {

    val callCounter1              = new AtomicInteger(0)
    val basicTestExpectedBody     = """{"message":"hello world"}"""
    val basicTestServer1          = TargetService(
      Some(serviceHost),
      "/api",
      "application/json",
      { _ =>
        callCounter1.incrementAndGet()
        basicTestExpectedBody
      }
    ).await()
    val privateByDefaultService   = ServiceDescriptor(
      id = "auth-test",
      name = "auth-test",
      env = "prod",
      subdomain = "auth",
      domain = "oto.tools",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${basicTestServer1.port}",
          scheme = "http"
        )
      ),
      forceHttps = false,
      enforceSecureCommunication = false
    )
    val privateByPatternService   = ServiceDescriptor(
      id = "auth-test",
      name = "auth-test",
      env = "prod",
      subdomain = "auth",
      domain = "oto.tools",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${basicTestServer1.port}",
          scheme = "http"
        )
      ),
      forceHttps = false,
      enforceSecureCommunication = false,
      privatePatterns = Seq("/api"),
      publicPatterns = Seq("/.*")
    )
    val notPublicByPatternService = ServiceDescriptor(
      id = "auth-test",
      name = "auth-test",
      env = "prod",
      subdomain = "auth",
      domain = "oto.tools",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${basicTestServer1.port}",
          scheme = "http"
        )
      ),
      forceHttps = false,
      enforceSecureCommunication = false,
      publicPatterns = Seq("/foo/.*")
    )
    val service                   = ServiceDescriptor(
      id = "auth-test",
      name = "auth-test",
      env = "prod",
      subdomain = "auth",
      domain = "oto.tools",
      targets = Seq(
        Target(
          host = s"127.0.0.1:${basicTestServer1.port}",
          scheme = "http"
        )
      ),
      forceHttps = false,
      enforceSecureCommunication = false
    )
    val apiKey                    = ApiKey(
      clientId = "apikey-test",
      clientSecret = "1234",
      clientName = "apikey-test",
      authorizedEntities = Seq(ServiceGroupIdentifier("default"))
    )
    val apiKey2                   = ApiKey(
      clientId = "apikey-test-2",
      clientSecret = "1234",
      clientName = "apikey-test-2",
      authorizedEntities = Seq(ServiceGroupIdentifier("default")),
      allowClientIdOnly = true
    )
    val basicAuth                 = Base64.getUrlEncoder.encodeToString(s"apikey-test:1234".getBytes)
    val algorithm                 = Algorithm.HMAC256("1234")
    val bearerAuth                = JWT
      .create()
      .withIssuer("apikey-test")
      .withClaim("name", "John Doe")
      .withClaim("admin", true)
      .sign(algorithm)
    val bearerAuthXsrf            = JWT
      .create()
      .withIssuer("apikey-test")
      .withClaim("name", "John Doe")
      .withClaim("admin", true)
      .withClaim("xsrfToken", "123456")
      .sign(algorithm)

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().futureValue // WARM UP
      createOtoroshiApiKey(apiKey).futureValue
      createOtoroshiApiKey(apiKey2).futureValue
    }

    "Prevent to access private by default service" in {
      createOtoroshiService(privateByDefaultService).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      resp.status mustBe 400
      resp.body.contains("no apikey") mustBe true

      deleteOtoroshiService(privateByDefaultService).futureValue
    }

    "Prevent to access private by patterns service" in {
      createOtoroshiService(privateByPatternService).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      resp.status mustBe 400
      resp.body.contains("no apikey") mustBe true

      deleteOtoroshiService(privateByPatternService).futureValue
    }

    "Prevent to access not public by patterns service" in {
      createOtoroshiService(notPublicByPatternService).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      resp.status mustBe 400
      resp.body.contains("no apikey") mustBe true

      deleteOtoroshiService(notPublicByPatternService).futureValue
    }

    "Allow access with otoroshi headers (Otoroshi-Client-Id, Otoroshi-Client-Secret)" in {
      createOtoroshiService(service).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"                                              -> serviceHost,
          otoroshiComponents.env.Headers.OtoroshiClientId     -> "apikey-test",
          otoroshiComponents.env.Headers.OtoroshiClientSecret -> "1234"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body == basicTestExpectedBody mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Allow access with basic otoroshi header (Otoroshi-Authorization)" in {
      createOtoroshiService(service).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"                                               -> serviceHost,
          otoroshiComponents.env.Headers.OtoroshiAuthorization -> s"Basic $basicAuth"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body == basicTestExpectedBody mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Allow access with basic otoroshi query param (basic_auth)" in {
      createOtoroshiService(service).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withQueryStringParameters("basic_auth" -> basicAuth)
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body == basicTestExpectedBody mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Allow access with basic auth header (Authorization)" in {
      createOtoroshiService(service).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"          -> serviceHost,
          "Authorization" -> s"Basic $basicAuth"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body == basicTestExpectedBody mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Allow access with bearer auth header (Authorization)" in {
      createOtoroshiService(service).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"          -> serviceHost,
          "Authorization" -> s"Bearer $bearerAuth"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body == basicTestExpectedBody mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Allow access with access_token in cookie (access_token)" in {
      createOtoroshiService(service).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"   -> serviceHost,
          "Cookie" -> s"access_token=$bearerAuth;secure"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body == basicTestExpectedBody mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Allow access with access_token in cookie and xsrf token (access_token)" in {
      createOtoroshiService(service).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"         -> serviceHost,
          "Cookie"       -> s"access_token=$bearerAuthXsrf;secure",
          "X-XSRF-TOKEN" -> "123456"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body == basicTestExpectedBody mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Not allow access with access_token in cookie and without xsrf token (access_token)" in {
      createOtoroshiService(service).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"   -> serviceHost,
          "Cookie" -> s"access_token=$bearerAuthXsrf;secure"
        )
        .get()
        .futureValue

      resp.status mustBe 401
      resp.body.contains("bad apikey") mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Allow access with bearer otoroshi header (Otoroshi-Bearer)" in {
      createOtoroshiService(service).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"                                        -> serviceHost,
          otoroshiComponents.env.Headers.OtoroshiBearer -> s"Bearer $bearerAuth"
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body == basicTestExpectedBody mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Allow access with bearer otoroshi query param (bearer_auth)" in {
      createOtoroshiService(service).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withQueryStringParameters("bearer_auth" -> bearerAuth)
        .withHttpHeaders(
          "Host" -> serviceHost
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body == basicTestExpectedBody mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Allow access with simple client id (x-api-key)" in {
      createOtoroshiService(service).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"      -> serviceHost,
          "x-api-key" -> apiKey2.clientId
        )
        .get()
        .futureValue

      resp.status mustBe 200
      resp.body == basicTestExpectedBody mustBe true

      deleteOtoroshiService(service).futureValue
    }

    "Not allow access with simple client id (x-api-key) for standard apikey" in {
      createOtoroshiService(service).futureValue

      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders(
          "Host"      -> serviceHost,
          "x-api-key" -> apiKey.clientId
        )
        .get()
        .futureValue

      resp.status mustBe 401

      deleteOtoroshiService(service).futureValue
    }

    "stop servers" in {
      basicTestServer1.stop()
      system.terminate()
    }

    "shutdown" in {
      stopAll()
    }
  }
}
