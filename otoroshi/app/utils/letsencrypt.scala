package otoroshi.utils

import java.io.StringWriter
import java.security.cert.X509Certificate
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, KeyPair}
import java.util.Base64
import java.util.concurrent.Executors
import java.util.regex.Matcher

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import env.Env
import org.shredzone.acme4j._
import org.shredzone.acme4j.challenge._
import org.shredzone.acme4j.util._
import play.api.Logger
import play.api.libs.json._
import ssl.DynamicSSLEngineProvider.{PRIVATE_KEY_PATTERN, PUBLIC_KEY_PATTERN, base64Decode}
import ssl.{Cert, PemHeaders}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class LetsEncryptSettings(enabled: Boolean = false, server: String = "acme://letsencrypt.org/staging", publicKey: String = "", privateKey: String = "") {

  def json: JsValue = LetsEncryptSettings.format.writes(this)

  def keyPair: Option[KeyPair] = {
    for {
      privko <- Option(privateKey).filter(_.trim.nonEmpty)
        .map(_.replace(PemHeaders.BeginPrivateKey, "").replace(PemHeaders.EndPrivateKey, "").trim())
        .map { content =>
          val encodedKey: Array[Byte] = base64Decode(content)
          new PKCS8EncodedKeySpec(encodedKey)
        }
      pubko <- Option(publicKey).filter(_.trim.nonEmpty)
        .map(_.replace(PemHeaders.BeginPublicKey, "").replace(PemHeaders.EndPublicKey, "").trim)
        .map { content =>
          val encodedKey: Array[Byte] = base64Decode(content)
          new X509EncodedKeySpec(encodedKey)
        }
    } yield {
      Try(KeyFactory.getInstance("RSA"))
        .orElse(Try(KeyFactory.getInstance("DSA")))
        .map { factor =>
          val prk = factor.generatePrivate(privko)
          val pbk = factor.generatePublic(pubko)
          new KeyPair(pbk, prk)
        }
        .get
    }
  }
}

object LetsEncryptSettings {
  val format = new Format[LetsEncryptSettings] {
    override def reads(json: JsValue): JsResult[LetsEncryptSettings] = Try {
      LetsEncryptSettings(
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
        server = (json \ "server").asOpt[String].getOrElse("acme://letsencrypt.org/staging"),
        publicKey = (json \ "publicKey").asOpt[String].getOrElse(""),
        privateKey = (json \ "privateKey").asOpt[String].getOrElse("")
      )
    } match {
      case Success(s) => JsSuccess(s)
      case Failure(e) => JsError(e.getMessage)
    }

    override def writes(o: LetsEncryptSettings): JsValue = Json.obj(
      "enabled" -> o.enabled,
      "server" -> o.server,
      "publicKey" -> o.publicKey,
      "privateKey" -> o.privateKey,
    )
  }
}


object LetsEncryptHelper {

  private val logger = Logger("LetsEncryptHelper")

  private val blockingEc = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

  def createCertificate(domain: String)(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Cert] = {

    env.datastores.globalConfigDataStore.singleton().flatMap { config =>
      val letsEncryptSettings = config.letsEncryptSettings

      val session = new Session(letsEncryptSettings.server)

      (letsEncryptSettings.keyPair match {
        case None =>
          val kp = KeyPairUtils.createKeyPair(2048)
          val newSettings = letsEncryptSettings.copy(
            privateKey = s"${PemHeaders.BeginPrivateKey}\n${Base64.getEncoder.encodeToString(kp.getPrivate.getEncoded)}\n${PemHeaders.EndPrivateKey}",
            publicKey = s"${PemHeaders.BeginPublicKey}\n${Base64.getEncoder.encodeToString(kp.getPublic.getEncoded)}\n${PemHeaders.EndPublicKey}"
          )
          config.copy(letsEncryptSettings = newSettings).save().map(_ => kp)
        case Some(kp) => FastFuture.successful(kp)
      }).flatMap { userKeyPair =>

        val account = new AccountBuilder()
          .agreeToTermsOfService()
          .useKeyPair(userKeyPair)
          .create(session)

        logger.info(s"ordering lets encrypt certificate for $domain")
        orderLetsEncryptCertificate(account, domain).flatMap { order =>

          logger.info(s"waiting for challenge challenge $domain")
          doChallenges(order, domain).flatMap { _ =>

            logger.info(s"building csr for $domain")
            val keyPair = KeyPairUtils.createKeyPair(2048)
            val csrByteString = buildCsr(domain, keyPair)

            logger.info(s"ordering certificate for $domain")

            orderCertificate(order, csrByteString).flatMap { _ =>

              logger.info(s"storing certificate for $domain")

              val certificate: X509Certificate = order.getCertificate.getCertificate

              val cert = Cert.apply(certificate, keyPair, None, false).copy(letsEncrypt = true).enrich()

              cert.save().map(_ => cert)
            }
          }
        }
      }
    }
  }

  def getChallengeForToken(domain: String, token: String)(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Option[ByteString]] = {
    env.datastores.rawDataStore.get(s"${env.storageRoot}:letsencrypt:$domain:challenges:$token").map {
      case None =>
        logger.info(s"Trying to access token ${token} for domain ${domain} but none found")
        None
      case s@Some(_) =>
        logger.info(s"Trying to access token ${token} for domain ${domain}: found !")
        env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:letsencrypt:$domain:challenges:$token"))
        s
    }
  }

  private def orderLetsEncryptCertificate(account: Account, domain: String)(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Order] = {
    Future {
      account.newOrder().domains(domain).create()
    }(blockingEc)
  }

  private def doChallenges(order: Order, domain: String)(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Seq[Status]]] = {
    Source(order.getAuthorizations.asScala.toList).mapAsync(1) { authorization =>
      val challenge = authorization.findChallenge(classOf[Http01Challenge])
      logger.info("setting challenge content in datastore")
      env.datastores.rawDataStore.set(s"${env.storageRoot}:letsencrypt:challenges:$domain:${challenge.getToken}", ByteString(challenge.getAuthorization), Some(10.minutes.toMillis))
      authorizeOrder(domain, authorization.getStatus, challenge)
    }.toMat(Sink.seq)(Keep.right).run().map { seq =>
      seq.find(_.isLeft).map(v => Left(v.left.get)).getOrElse(Right(seq.map(_.right.get)))
    }
  }

  private def authorizeOrder(domain: String, status: Status, challenge: Http01Challenge)(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Status]] = {
    logger.info(s"authorizing order $domain")
    if (status == Status.VALID) {
      FastFuture.successful(Right(Status.VALID))
    } else {
      if (challenge.getStatus ==  Status.VALID) {
        FastFuture.successful(Right(Status.VALID))
      } else {
        challenge.trigger()
        Source.tick(1.seconds, 3.seconds, ())
          .mapAsync(1) { _ =>
            Future {
              challenge.update()
              challenge.getStatus
            }(blockingEc)
          }
          .take(10)
          .filter(_ == Status.VALID)
          .take(1)
          .map(o => Right(o))
          .recover { case e => Left(s"Failed to authorize certificate for domain, ${e.getMessage}") }
          .toMat(Sink.headOption)(Keep.right).run().map {
          case None => Left(s"Failed to authorize certificate for domain, empty")
          case Some(e) => e
        }
      }
    }
  }

  private def orderCertificate(order: Order, csr: ByteString)(implicit ec: ExecutionContext, env: Env, mat: Materializer): Future[Either[String, Order]] = {
    Source.tick(1.seconds, 3.seconds, ())
      .mapAsync(1) { _ =>
        Future {
          order.update()
          order
        }(blockingEc)
      }
      .take(10)
      .filter(_.getStatus == Status.VALID)
      .take(1)
      .map(o => Right(o))
      .recover { case e => Left(s"Failed to order certificate for domain, ${e.getMessage}") }
      .toMat(Sink.headOption)(Keep.right).run().map {
        case None => Left(s"Failed to order certificate for domain, empty")
        case Some(e) => e
      }
  }

  private def buildCsr(domain: String, keyPair: KeyPair): ByteString = {
    val csrb = new CSRBuilder()
    csrb.addDomains(domain)
    csrb.sign(keyPair)
    val stringWriter = new StringWriter()
    csrb.write(stringWriter)
    ByteString(csrb.getEncoded)
  }
}
