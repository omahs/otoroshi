package otoroshi.next.models

import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

case class RuleId(value: String)
case class SectionId(value: String)

case class RulesSection(id: SectionId, rules: Seq[Rule]) {
  def json(): JsValue = Json.obj(
    "id" -> id.value,
    "rules" -> rules.map(_.json())
  )
}

object RulesSection {
  def reads(json: JsValue): JsResult[Seq[RulesSection]] = {
    Try {
      JsSuccess(json.as[JsArray].value.map(item => RulesSection(
          id = SectionId(item.select("id").as[String]),
          rules = item.select("rules").as[Seq[Rule]](Rule.reads)
        )))
    } recover { case e =>
      JsError(e.getMessage)
    } get
  }
}

case class Rule(id: RuleId,
                description: Option[String] = None,
                globalWeight: Double,
                sectionWeight: Double,
                enabled: Boolean = true) {
  def json(): JsValue = Json.obj(
    "id" -> id.value,
    "description" -> description,
    "global_weight" -> globalWeight,
    "section_weight" -> sectionWeight,
    "enabled" -> enabled
  )
}

object Rule {
  def reads(json: JsValue): JsResult[Seq[Rule]] = {
    Try {
      JsSuccess(json.as[JsArray].value.map(item => Rule(
        id = RuleId(item.select("id").as[String]),
        description = item.select("description").asOpt[String],
        globalWeight = item.select("global_weight").as[Double],
        sectionWeight = item.select("section_weight").as[Double],
        enabled = item.select("enabled").as[Boolean],
      )))
    } recover { case e =>
      JsError(e.getMessage)
    } get
  }
}

object RulesManager {
  val sections = Seq(
    RulesSection(SectionId("architecture"), Seq(
      Rule(RuleId("AR01"), globalWeight = 6.25, sectionWeight = 25, description = "Use Event Driven Architecture to avoid polling madness and inform subscribers of an update. Use Event Driven Architecture to avoid polling madness."),
      Rule(RuleId("AR02"), globalWeight = 6.25, sectionWeight = 25),
      Rule(RuleId("AR03"), globalWeight = 6.25, sectionWeight = 25),
      Rule(RuleId("AR04"), globalWeight = 6.25, sectionWeight = 25),
    )),
    RulesSection(SectionId("design"), Seq(
      Rule(RuleId("DE01"), globalWeight = 10, sectionWeight = 25),
      Rule(RuleId("DE02"), globalWeight = 6, sectionWeight = 15),
      Rule(RuleId("DE03"), globalWeight = 8, sectionWeight = 20),
      Rule(RuleId("DE04"), globalWeight = 0.8, sectionWeight = 2),
      Rule(RuleId("DE05"), globalWeight = 1.6, sectionWeight = 4),
      Rule(RuleId("DE06"), globalWeight = 1.6, sectionWeight = 4),
      Rule(RuleId("DE07"), globalWeight = 4, sectionWeight = 10),
      Rule(RuleId("DE08"), globalWeight = 1, sectionWeight = 2.5),
      Rule(RuleId("DE09"), globalWeight = 4, sectionWeight = 10),
      Rule(RuleId("DE10"), globalWeight = 2, sectionWeight = 5),
      Rule(RuleId("DE11"), globalWeight = 1, sectionWeight = 2.5)
    )),
    RulesSection(SectionId("usage"), Seq(
      Rule(RuleId("US01"), globalWeight = 1.25, sectionWeight = 5),
      Rule(RuleId("US02"), globalWeight = 2.5, sectionWeight = 10),
      Rule(RuleId("US03"), globalWeight = 2.5, sectionWeight = 10),
      Rule(RuleId("US04"), globalWeight = 2.5, sectionWeight = 10),
      Rule(RuleId("US05"), globalWeight = 5, sectionWeight = 20),
      Rule(RuleId("US06"), globalWeight = 6.25, sectionWeight = 25),
      Rule(RuleId("US07"), globalWeight = 5, sectionWeight = 20)
    )),
    RulesSection(SectionId("log"), Seq(
      Rule(RuleId("LO01"), globalWeight = 10, sectionWeight = 10)
    ))
  )
}

case class GreenScoreConfig(sections: Seq[RulesSection]) extends NgPluginConfig {
  def json: JsValue = Json.obj(
    "sections" -> sections.map(section => {
      Json.obj(
        "id" -> section.id.value,
        "rules" -> section.rules.map(_.json())
      )
    })
  )
}

object GreenScoreConfig {
  def readFrom(lookup: JsLookupResult): GreenScoreConfig = {
    lookup.select("sections").asOpt[JsArray] match {
      case None => GreenScoreConfig(RulesManager.sections)
      case Some(config) =>
        format.reads(config).getOrElse(GreenScoreConfig(sections = RulesManager.sections))
    }
  }

  val format = new Format[GreenScoreConfig] {
    override def reads(json: JsValue): JsResult[GreenScoreConfig] = Try {
      GreenScoreConfig(
        sections = json.select("sections").as[Seq[RulesSection]](RulesSection.reads)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
    override def writes(o: GreenScoreConfig): JsValue             = o.json
  }
}

