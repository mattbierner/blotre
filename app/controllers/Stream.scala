package controllers

import Actors.{StreamSupervisor, StreamActor}
import akka.actor._
import akka.contrib.pattern.DistributedPubSubMediator
import be.objectify.deadbolt.java.actions.SubjectPresent
import com.feth.play.module.pa.user.AuthUser
import models.User
import play.api.libs.iteratee.{Concurrent, Enumerator, Iteratee}
import play.api.mvc._
import play.api.libs.json._
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import scala.collection.immutable._
import helper.ImageHelper


/**
 *
 */
object ActorListener {
  def props(channel: Concurrent.Channel[JsValue]): Props = Props(new ActorListener(channel))
}

class ActorListener(channel: Concurrent.Channel[JsValue]) extends Actor {
  import DistributedPubSubMediator.{ Subscribe, SubscribeAck }

  def receive = {
    case StreamActor.StatusUpdate(uri, status) =>
      channel.push(Json.obj(
        "type" -> "StatusUpdate",
        "stream"-> Json.obj(
          "uri" -> uri,
          "updated" -> status.created,
          "status" -> status)))

    case SubscribeAck(Subscribe(uri, None, `self`)) =>
  }
}

case class AcceptingExtOrMime(val ext: String, val mimeType: String) {
  /**
   * Don't use `request.accepts` to avoid accepting `* / *`.
   */
  def unapply(request: RequestHeader): Boolean =
    request.headers.get("accepts") == Some(mimeType) || request.path.endsWith("." + ext)
}

/**
 *
 */
object Stream extends Controller {
  case class StatusUpdate(val color: String) { }

  /**
   * Form to update the status of a stream.
   */
  val statusForm = Form(
    mapping(
      "color" -> nonEmptyText.verifying(pattern("""#[0-9a-fA-f]{6}""".r))
    )(StatusUpdate.apply)(StatusUpdate.unapply))

  val AcceptsPng = AcceptingExtOrMime("png", "image/png")


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
    Ok(views.html.stream.index.render())
  }}

  /**
   *
   */
  def stream = WebSocket.using[JsValue] { implicit request =>
    val (out, channel) = Concurrent.broadcast[JsValue]

    val ws = Akka.system.actorOf(ActorListener.props(channel))

    val in = Iteratee.foreach[JsValue] { msg =>
      (msg \ "type").as[String] match {
        case "Subscribe" =>
          val target: String = (msg \ "value").as[String]
          val current = models.Stream.findByUri(target)
          if (current != null) {
            StreamSupervisor.subscribe(target, ws)
            // notify of current status
            ws ! StreamActor.StatusUpdate(target, current.status)
          }
        case "Unsubscribe" =>
          val target = (msg \ "value").as[String]
          StreamSupervisor.subscribe(target, ws)

        case _ =>
      }
    } map { _ =>
    }

    (in, out)
  }

  /**
   * Create a new stream from a JSON request.
   */
  def create = Action(parse.json) { request =>
    request.body.validate[models.Stream] match {
      case s: JsSuccess[models.Stream] => {
        val stream: models.Stream = s.get
        Ok("xx")
      }
      case e: JsError => {
        Ok(e.toString())
      }
    }
  }

  /**
   *
   */
  def getStream(uri: String) = Action { implicit request => JavaContext.withContext {
    val path = uri.split('.')(0)
    val s: models.Stream = models.Stream.findByUri(path)
    if (s == null) {
      // TODO: replace with try create page?
      NotFound(views.html.notFound.render(""));
    } else {
      request match {
        case AcceptsPng() => {
          val img = ImageHelper.createImage(s.status.color);
          Ok(ImageHelper.toPng(img))
            .withHeaders(
              "Cache-Control" -> "no-cache, no-store, must-revalidate",
              "Expires" -> "0")
            .as("image/png")
        }
        case Accepts.Html() => {
          val map = uriMap(s.uri)
          Ok(views.html.stream.stream.render(s, children = List(), uriPath = map))
        }
      }
    }
  }}

  /**
   * Update an existing stream.
   */
  @SubjectPresent
  def postStreamUpdate (uri: String) = Action { implicit request => {
    val localUser: User = Application.getLocalUser(request)
    statusForm.bindFromRequest().fold(
      formWithErrors => BadRequest(""),
      userData => {
        updateStreamStatus(uri, userData.color, localUser)
        Ok("")
      })
  }}

  /**
   *
   */
  private def canUpdateStreamStatus(uri: String, poster: User): Option[models.Stream] = {
    val stream = models.Stream.findByUri(uri)
    if (poster != null && stream != null)
      if (stream.ownerId == poster.id)
        return Some(stream);
    return None;
  }

  /**
   *
   */
  private def updateStreamStatus(uri: String, color: String, poster: User) = {
    canUpdateStreamStatus(uri: String, poster)
      .map { _ =>
        models.Stream.updateStreamStatus(uri, color, poster) match {
          case Some(s) =>
            StreamSupervisor.updateStatus(uri, s.status)
          case None =>
        }
      }
  }
}
