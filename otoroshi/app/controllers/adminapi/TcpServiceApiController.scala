package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.tcp.TcpService
import otoroshi.utils.controllers.{
  ApiError,
  BulkControllerHelper,
  CrudControllerHelper,
  EntityAndContext,
  JsonApiError,
  NoEntityAndContext,
  OptionalEntityAndContext,
  SeqEntityAndContext
}
import play.api.Logger
import play.api.libs.json.{JsError, JsObject, JsValue, Json}
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}

import scala.concurrent.{ExecutionContext, Future}

class TcpServiceApiController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with BulkControllerHelper[TcpService, JsValue]
    with CrudControllerHelper[TcpService, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  val logger = Logger("otoroshi-tcp-service-api")

  override def singularName: String = "tcp-service"

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: TcpService): String = entity.id

  override def readEntity(json: JsValue): Either[JsValue, TcpService] =
    TcpService.fmt.reads(json).asEither match {
      case Left(e)  => Left(JsError.toJson(e))
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: TcpService): JsValue = TcpService.fmt.writes(entity)

  override def findByIdOps(id: String, req: RequestHeader)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], OptionalEntityAndContext[TcpService]]] = {
    env.datastores.tcpServiceDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_TCP_SERVICE",
          message = "User accessed a tcp service",
          metadata = Json.obj("TcpServiceId" -> id),
          alert = "TcpServiceAccessed"
        )
      )
    }
  }

  override def findAllOps(
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[TcpService]]] = {
    env.datastores.tcpServiceDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_TCP_SERVICES",
          message = "User accessed all tcp services",
          metadata = Json.obj(),
          alert = "TcpServicesAccessed"
        )
      )
    }
  }

  override def createEntityOps(
      entity: TcpService,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[TcpService]]] = {
    env.datastores.tcpServiceDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_TCP_SERVICE",
            message = "User created a tcp service",
            metadata = entity.json.as[JsObject],
            alert = "TcpServiceCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "Tcp service not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
      entity: TcpService,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[TcpService]]] = {
    env.datastores.tcpServiceDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_TCP_SERVICE",
            message = "User updated a tcp service",
            metadata = entity.json.as[JsObject],
            alert = "TcpServiceUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "Tcp service not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
      id: String,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[TcpService]]] = {
    env.datastores.tcpServiceDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_TCP_SERVICE",
            message = "User deleted a tcp service",
            metadata = Json.obj("TcpServiceId" -> id),
            alert = "TcpServiceDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "Tcp service not deleted ...")
          )
        )
      }
    }
  }
}
