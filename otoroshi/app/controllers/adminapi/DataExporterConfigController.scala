package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.events.UpdateExporters
import otoroshi.models.DataExporterConfig
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
import play.api.libs.json.{JsArray, JsError, JsObject, JsValue, Json}
import play.api.mvc.{AbstractController, ControllerComponents, RequestHeader}

import scala.concurrent.{ExecutionContext, Future}

class DataExporterConfigController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with BulkControllerHelper[DataExporterConfig, JsValue]
    with CrudControllerHelper[DataExporterConfig, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-data-exporter-api")

  override def singularName: String = "data-exporter"

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: DataExporterConfig): String = entity.id

  override def readEntity(json: JsValue): Either[JsValue, DataExporterConfig] =
    DataExporterConfig.format.reads(json).asEither match {
      case Left(e)  => Left(JsError.toJson(e))
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: DataExporterConfig): JsValue = DataExporterConfig.format.writes(entity)

  override def findByIdOps(id: String, req: RequestHeader)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], OptionalEntityAndContext[DataExporterConfig]]] = {
    env.datastores.dataExporterConfigDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_DATA_EXPORTER_CONFIG",
          message = "User accessed a data exporter config",
          metadata = Json.obj("dataExporterConfigId" -> id),
          alert = "DataExporterConfigAccessed"
        )
      )
    }
  }

  override def findAllOps(req: RequestHeader)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], SeqEntityAndContext[DataExporterConfig]]] = {
    env.datastores.dataExporterConfigDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_DATA_EXPORTER_CONFIG",
          message = "User accessed all data exporter config",
          metadata = Json.obj(),
          alert = "DataExporterConfigAccessed"
        )
      )
    }
  }

  override def createEntityOps(entity: DataExporterConfig, req: RequestHeader)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], EntityAndContext[DataExporterConfig]]] = {
    env.datastores.dataExporterConfigDataStore.set(entity).map {
      case true  => {
        env.otoroshiEventsActor ! UpdateExporters
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_DATA_EXPORTER_CONFIG",
            message = "User created a data exporter config",
            metadata = entity.json.as[JsObject],
            alert = "DataExporterConfigCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "data exporter config not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(entity: DataExporterConfig, req: RequestHeader)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], EntityAndContext[DataExporterConfig]]] = {
    env.datastores.dataExporterConfigDataStore.set(entity).map {
      case true  => {
        env.otoroshiEventsActor ! UpdateExporters
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_DATA_EXPORTER_CONFIG",
            message = "User updated a data exporter config",
            metadata = entity.json.as[JsObject],
            alert = "DataExporterConfigUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "data exporter config not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(id: String, req: RequestHeader)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], NoEntityAndContext[DataExporterConfig]]] = {
    env.datastores.dataExporterConfigDataStore.delete(id).map {
      case true  => {
        env.otoroshiEventsActor ! UpdateExporters
        Right(
          NoEntityAndContext(
            action = "DELETE_DATA_EXPORTER_CONFIG",
            message = "User deleted a data exporter config",
            metadata = Json.obj("dataExporterConfigId" -> id),
            alert = "DataExporterConfigDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "data exporter config not deleted ...")
          )
        )
      }
    }
  }
}
