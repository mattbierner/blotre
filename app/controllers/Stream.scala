package controllers

import Actors.{StreamSupervisor}
import akka.actor._
import akka.contrib.pattern.DistributedPubSubMediator
import be.objectify.deadbolt.java.actions.SubjectPresent
import models.User
import org.bson.types.ObjectId
import play.api.libs.iteratee.{Concurrent, Iteratee}
import play.api.mvc._
import play.api.libs.json._
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import scala.collection.immutable._
import helper._
import helper.ImageHelper

/**
 *
 */
object Stream extends Controller
{
  import models.Serializable._

  val AcceptsPng = PrefersExtOrMime("png", "image/png")

  def uriMap(uri: String): Map[String, String] = {
    (uri
      .split('/')
      .foldLeft(("", Map[String, String]())) { (p, c) =>
        (p._1 + "/" + c, p._2 + (c -> (p._1 + "/" + c)))
      })._2
  }

  /**
   * Stream root index page.
   *
   * Displays a list of streams for searching.
   */
  def index = Action { implicit request => JavaContext.withContext {
    val query = request.getQueryString("query").getOrElse("")
    val streams = if (query.isEmpty) models.Stream.findByUpdated() else models.Stream.findByQuery(query)
    request match {
      case Prefers.Json() =>
        Ok(Json.obj(
          "query" -> query,
          "streams" -> streams
        ))

      case Accepts.Html() =>
        Ok(views.html.stream.index.render())

      case _ =>
        BadRequest
    }
  }}

  /**
   * Lookup a stream.
   *
   * Supports:
   *     png - Render 1x1 image of the current status.
   *
   *     html - View of the stream.
   *
   *     json: Returns json of the stream.
   */
  def getStream(uri: String) = Action { implicit request => JavaContext.withContext {
    val path = uri.split('.')(0)
    request match {
      case AcceptsPng() =>
        renderStreamStatusPng(path, request)

      case Prefers.Json() =>
        renderStreamJson(path, request)

      case Accepts.Html() =>
        renderStream(path, request)

      case _ =>
        BadRequest
    }
  }}

  /**
   * Render a stream as html.
   *
   * Displays a try create page if the stream does not exist but the parent does.
   */
  def renderStream(uri: String, request: Request[AnyContent]) =
    models.Stream.findByUri(uri) match {
      case Some(s) =>
        val map = uriMap(s.uri)
        Ok(views.html.stream.stream.render(s, s.getChildren(), uriPath = map))

      case _ =>
        tryCreateDecendant(uri, request)
    }

  /**
   * Render a stream as Json.
   */
  def renderStreamJson(uri: String, request: Request[AnyContent]): Result =
    models.Stream.findByUri(uri)
      .map(s =>
        renderStreamJson(s, request))
      .getOrElse(NotFound)


  def renderStreamJson(stream: models.Stream, request: Request[AnyContent]): Result =
    Ok(Json.toJson(stream).as[JsObject] + ("children", Json.toJson(models.Stream.getChildrenData(stream))))

  /**
   * Render a stream's current status as a 1x1 PNG image.
   */
  def renderStreamStatusPng(uri: String, request: Request[AnyContent]) =
    models.Stream.findByUri(uri)
      .map(s => {
        val img = ImageHelper.createImage(s.status.color)
        Ok(ImageHelper.toPng(img))
          .withHeaders(
            "Cache-Control" -> "no-cache, no-store, must-revalidate",
            "Expires" -> "0")
          .as("image/png")
      })
      .getOrElse(NotFound)

  def setStreamStatus(uri: String) =  Action(parse.json) { request => {
    models.Stream.findByUri(uri) map { stream =>
      apiSetStreamStatus(stream, request)
    } getOrElse(NotFound)
  }}

  /**
   * Checks if child stream can created and displays an create page.
   *
   * A child stream can only be created if its direct parent exists and
   * is owned by the current user.
   */
  def tryCreateDecendant(uri: String, request: Request[AnyContent]) = {
    val user = Application.getLocalUser(request)
    getParentPath(uri) match {
      case Some((parent, child)) =>
        models.Stream.findByUri(parent)
          .flatMap({ stream =>
            models.Stream.asEditable(user, stream)
          })
          .map(stream =>
            Ok(views.html.stream.createChild.render(stream, child)))
          .getOrElse(
            NotFound(views.html.notFound.render("")))

      case _ =>
        NotFound(views.html.notFound.render(""))
    }
  }

  /**
   */
  @SubjectPresent
  def createChildStream(uri: String) = Action { implicit request =>
    val user = Application.getLocalUser(request)
    request match {
      case Prefers.Json() =>
        createDescendant(uri, user)
          .map(s =>
            renderStreamJson(s, request))
          .getOrElse(BadRequest)

      case Accepts.Html() =>
        createDescendant(uri, user)
          .map(s =>
            Redirect(routes.Stream.getStream(s.uri)))
          .getOrElse(BadRequest)

      case _ =>
        BadRequest
    }
  }

  private def createDescendant(uri: String, user: User) =
    getParentPath(uri) flatMap { s =>
      models.Stream.createDescendant(s._1, s._2, user)
    }

  private def getParentPath(uri: String) = {
    val index = uri.lastIndexOf('/')
    if (index == -1 || index >= uri.length - 1)
      None
    else
      Some((uri.slice(0, index), uri.slice(index + 1, uri.length)))
  }

  /**
   *
   */
  def apiGetStream(id: String) = Action { implicit request => {
    models.Stream.findById(id) map { stream =>
      Ok(Json.toJson(stream))
    } getOrElse {
      NotFound
    }
  }}

  /**
   *
   */
  def apiGetStreamStatus(id: String) = Action { implicit request => {
    models.Stream.findById(id) map { stream =>
      Ok(Json.toJson(stream.status))
    } getOrElse {
      NotFound
    }
  }}

  /**
   *
   */
  @SubjectPresent
  def apiSetStreamStatus(id: String): Action[JsValue] = Action(parse.json) { request => {
    models.Stream.findById(id) map { stream =>
      apiSetStreamStatus(stream, request)
    } getOrElse(NotFound)
  }}

  def apiSetStreamStatus(stream: models.Stream, request: Request[JsValue]): Result = {
    val user = Application.getLocalUser(request)
    models.Stream.asEditable(user, stream) map { stream =>
      ((__ \ "color").read[String]).reads(request.body) map { status =>
        updateStreamStatus(stream, status, user) map { _ =>
          Ok("")
        } getOrElse(BadRequest)
      } recoverTotal {
        e => BadRequest
      }
    } getOrElse(Unauthorized)
  }

  /**
   *
   */
  def apiGetChildren(id: String) = Action { implicit request => {
    models.Stream.findById(id) map { stream =>
      renderStreamJson(stream, request)
    } getOrElse(NotFound)
  }}

  /**
   *
   */
  def apiGetChild(id: String, childId: String) = Action { implicit request => {
    models.Stream.findById(id) flatMap { stream =>
      models.Stream.getChildById(stream, new ObjectId(childId))
    } map { childData =>
      Ok(Json.toJson(childData))
    } getOrElse(NotFound)
  }}

  /**
   *
   */
  @SubjectPresent
  def apiCreateChild(id: String) = Action(parse.json) { implicit request =>
    val user = Application.getLocalUser(request)
    models.Stream.findById(id) map { parent =>
      ((__ \ "childId").read[ObjectId]).reads(request.body) map { childId =>
        models.Stream.addChild(parent, childId, user) map { childData =>
          Ok(Json.toJson(childData))
        } getOrElse(BadRequest)
      } recoverTotal { _ =>
        BadRequest
      }

    } getOrElse(NotFound)
  }

  /**
   * Can a user edit a given stream?
   */
  def canUpdateStreamStatus(stream: models.Stream, poster: User): Option[models.Stream] = {
    if (poster != null && stream != null)
      if (stream.ownerId == poster.id)
        return Some(stream);
    return None;
  }

  def canUpdateStreamStatus(uri: String, poster: User): Option[models.Stream] =
    models.Stream.findByUri(uri)
      .flatMap(x => canUpdateStreamStatus(x, poster))

  /**
   *
   */
  private def updateStreamStatus(stream: models.Stream, color: String, poster: User) =
    canUpdateStreamStatus(stream, poster) flatMap { _ =>
      models.Stream.updateStreamStatus(stream, color, poster)
    } map { s =>
      StreamSupervisor.updateStatus(stream.uri, s.status)
    }
}

