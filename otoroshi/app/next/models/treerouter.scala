package otoroshi.next.models

import com.github.blemale.scaffeine.Scaffeine
import otoroshi.utils.RegexPool
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

object DomainPathTree {
  def empty = DomainPathTree(new TrieMap[String, PathTree](), scala.collection.mutable.MutableList.empty)
  def build(routes: Seq[NgRoute]): DomainPathTree = {
    val root = DomainPathTree.empty
    routes.foreach { route =>
      route.frontend.domains.foreach { dpath =>
        if (dpath.domain.contains("*")) {
          root.wildcards.+=(route)
        } else {
          val ptree = root.tree.getOrElseUpdate(dpath.domain, PathTree.empty)
          ptree.addSubRoutes(dpath.path.split("/").toSeq.filterNot(_.trim.isEmpty), route)
        }
      }
    }
    // TODO: using head is not acceptable ...
    root.wildcards.sortWith((r1, r2) => r1.frontend.domains.head.domain.length.compareTo(r2.frontend.domains.head.domain.length) > 0)
    root
  }
}

case class DomainPathTree(tree: TrieMap[String, PathTree], wildcards: scala.collection.mutable.MutableList[NgRoute]) {
  def json: JsValue = Json.obj(
    "tree" -> JsObject(tree.toMap.mapValues(_.json)),
    "wildcards" -> JsArray(wildcards.map(r => JsString(r.name)))
  )
  def find(domain: String, path: String): Option[Seq[NgRoute]] = {
    tree.get(domain) match {
      case Some(ptree) => ptree.find(path.split("/").filterNot(_.trim.isEmpty), path.endsWith("/"))
      case None => wildcards.filter { route =>
        route.frontend.domains.exists(d => RegexPool(d.domain).matches(domain))
      }.applyOn {
        case seq if seq.isEmpty => None
        case seq => seq.some
      }
    }
  }
}

object PathTree {

  def addSubRoutes(current: PathTree, segments: Seq[String], route: NgRoute): Unit = {
    if (segments.isEmpty) {
      current.addRoute(route)
    } else {
      val sub = current.tree.getOrElseUpdate(segments.head, PathTree.empty)
      if (segments.size == 1) {
        sub.addRoute(route)
      } else {
        addSubRoutes(sub, segments.tail, route)
      }
    }
  }

  def empty: PathTree = PathTree(scala.collection.mutable.MutableList.empty, new TrieMap[String, PathTree])
}

case class PathTree(routes: scala.collection.mutable.MutableList[NgRoute], tree: TrieMap[String, PathTree]) {
  lazy val wildcardCache = Scaffeine().maximumSize(100).expireAfterWrite(10.seconds).build[String, Option[PathTree]]()
  lazy val segmentStartsWithCache = Scaffeine().maximumSize(100).expireAfterWrite(10.seconds).build[String, Option[Seq[NgRoute]]]()
  lazy val isLeaf: Boolean = tree.isEmpty
  lazy val wildcardEntry: Option[PathTree] = tree.get("*") // lazy should be good as once built the mutable map is never mutated again
  lazy val hasWildcardKeys: Boolean = wildcardKeys.nonEmpty
  lazy val wildcardKeys: scala.collection.Set[String] = tree.keySet.filter(_.contains("*"))
  lazy val isEmpty = routes.isEmpty && tree.isEmpty
  lazy val treeIsEmpty = tree.isEmpty
  def wildcardEntriesMatching(segment: String): Option[PathTree] = wildcardCache.get(segment, _ => wildcardKeys.find(str => RegexPool(str).matches(segment)).flatMap(key => tree.get(key)))
  def addRoute(route: NgRoute): PathTree = {
    routes.+=(route)
    this
  }
  def addSubRoutes(segments: Seq[String], route: NgRoute): Unit = {
    PathTree.addSubRoutes(this, segments, route)
  }
  def json: JsValue = Json.obj(
    "routes" -> routes.toSeq.map(r => JsString(r.name)),
    "leaf" -> isLeaf,
    "tree" -> JsObject(tree.toMap.mapValues(_.json))
  )
  def find(segments: Seq[String], endsWithSlash: Boolean): Option[Seq[NgRoute]] = {
    segments.headOption match {
      case None if routes.isEmpty => None
      case None => routes.some
      case Some(head) => tree.get(head).applyOnIf(hasWildcardKeys)(opt => opt.orElse(wildcardEntriesMatching(head)).orElse(wildcardEntry)) match {
        case None if endsWithSlash && routes.isEmpty => None
        case None if endsWithSlash && routes.nonEmpty => routes.some
        case None if !endsWithSlash => {
          // here is one of the worst case where the user wants to use '/api/999' to match calls on '/api/999-foo'
          segmentStartsWithCache.get(head, _ => {
            // println("worst case", head, tree.isEmpty, routes.isEmpty)
            tree.keySet.toSeq
              .sortWith((r1, r2) => r1.length.compareTo(r2.length) > 0)
              .find {
                case key if key.contains("*") => RegexPool(key).matches(head)
                case key => head.startsWith(key)
              }
              .flatMap(key => tree.get(key)) match {
              case None if routes.isEmpty => None
              case None => routes.some
              case Some(ptree) => ptree.find(segments.tail, endsWithSlash) match { // is that right ?
                case None if routes.isEmpty => None
                case None => routes.some
                case s => s
              }
            }
          })
        }
        case Some(ptree) if ptree.isEmpty && routes.isEmpty => None
        case Some(ptree) if ptree.isEmpty && routes.nonEmpty => routes.some
        case Some(ptree) if ptree.treeIsEmpty && ptree.routes.isEmpty => None
        case Some(ptree) if ptree.treeIsEmpty && ptree.routes.nonEmpty => ptree.routes.some
        case Some(ptree) => ptree.find(segments.tail, endsWithSlash) match { // is that right ?
          case None if routes.isEmpty => None
          case None => routes.some
          case s => s
        }
      }
    }
  }
}
