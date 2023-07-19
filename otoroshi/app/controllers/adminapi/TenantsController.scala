package otoroshi.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.models.Tenant
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

class TenantsController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with BulkControllerHelper[Tenant, JsValue]
    with CrudControllerHelper[Tenant, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-tenants-api")

  override def singularName: String = "organization"

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: Tenant): String = entity.id.value

  override def readEntity(json: JsValue): Either[JsValue, Tenant] =
    Tenant.format.reads(json).asEither match {
      case Left(e)  => Left(JsError.toJson(e))
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: Tenant): JsValue = Tenant.format.writes(entity)

  override def findByIdOps(
      id: String,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], OptionalEntityAndContext[Tenant]]] = {
    env.datastores.tenantDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_TENANT",
          message = "User accessed a Tenant",
          metadata = Json.obj("TenantId" -> id),
          alert = "TenantAccessed"
        )
      )
    }
  }

  override def findAllOps(
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], SeqEntityAndContext[Tenant]]] = {
    env.datastores.tenantDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_TENANTS",
          message = "User accessed all tenants",
          metadata = Json.obj(),
          alert = "TenanttsAccessed"
        )
      )
    }
  }

  override def createEntityOps(
      entity: Tenant,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Tenant]]] = {
    env.datastores.tenantDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_TENANT",
            message = "User created a tenant",
            metadata = entity.json.as[JsObject],
            alert = "TenantCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "tenant not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
      entity: Tenant,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[Tenant]]] = {
    env.datastores.tenantDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_TENANT",
            message = "User updated a tenant",
            metadata = entity.json.as[JsObject],
            alert = "TenantUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "tenant not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
      id: String,
      req: RequestHeader
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[Tenant]]] = {
    env.datastores.tenantDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_TENANT",
            message = "User deleted a tenant",
            metadata = Json.obj("TenantId" -> id),
            alert = "TenantDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "tenant not deleted ...")
          )
        )
      }
    }
  }
}
