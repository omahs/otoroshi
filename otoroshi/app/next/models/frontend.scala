package otoroshi.next.models

import otoroshi.models.ApiKeyRouteMatcher
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

case class NgDomainAndPath(raw: String) {
  private lazy val parts = raw.split("\\/")
  lazy val domain = parts.head
  lazy val path = if (parts.size == 1) "/" else parts.tail.mkString("/", "/", "")
  def json: JsValue = JsString(raw)
}

case class NgFrontend(domains: Seq[NgDomainAndPath], headers: Map[String, String], methods: Seq[String], stripPath: Boolean, exact: Boolean) {
  def json: JsValue = Json.obj(
    "domains" -> JsArray(domains.map(_.json)),
    "strip_path" -> stripPath,
    "exact" -> exact,
    "headers" -> headers,
    "methods" -> methods,
  )
}

object NgFrontend {
  def empty: NgFrontend = NgFrontend(Seq.empty, Map.empty, Seq.empty, stripPath = true, exact = false)
  def readFrom(lookup: JsLookupResult): NgFrontend = {
    lookup.asOpt[JsObject] match {
      case None => empty
      case Some(obj) => NgFrontend(
        domains = obj.select("domains").asOpt[Seq[String]].map(_.map(NgDomainAndPath.apply)).getOrElse(Seq.empty),
        stripPath = obj.select("strip_path").asOpt[Boolean].getOrElse(true),
        exact = obj.select("exact").asOpt[Boolean].getOrElse(false),
        headers = obj.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        methods = obj.select("methods").asOpt[Seq[String]].getOrElse(Seq.empty),
      )
    }
  }
}