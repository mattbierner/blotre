package controllers

import java.text.SimpleDateFormat
import java.util.Date
import be.objectify.deadbolt.java.actions.SubjectPresent
import be.objectify.deadbolt.java.actions.Group
import play.Routes
import play.data.Form
import play.api.mvc._
import play.core.j.JavaHelpers

import providers.MyUsernamePasswordAuthProvider
import com.feth.play.module.pa.PlayAuthenticate
import com.feth.play.module.pa.providers.password.UsernamePasswordAuthProvider
import com.feth.play.module.pa.user.AuthUser
import scala.concurrent._

/**
 * Main application controller.
 */
object Application extends Controller {

  /**
   * Get the current logged in user for a session.
   */
  def getLocalUser(user: AuthUser): models.User =
    models.User.findByAuthUserIdentity(user)

  def getLocalUser(session: play.mvc.Http.Session): models.User =
    getLocalUser(PlayAuthenticate.getUser(session))

  def getLocalUser(request: Request[_]): models.User =
    getLocalUser(JavaHelpers.createJavaContext(request).session())

  def getLocalUser(request: RequestHeader): models.User =
    getLocalUser(JavaHelpers.createJavaContext(request).session())

  /**
   * Index page.
   *
   * Renders hero page for non logged in users or the users's root stream for logged in users.
   *
   * Redirects to the select user name page if a user has not selected a user name.
   */
  def index = Action.async { implicit request => JavaContext.withContext {
    val localUser = getLocalUser(request)
    if (localUser == null) {
      Future.successful(Ok(views.html.index.render()))
    } else if (localUser.userName == null || localUser.userName.isEmpty()) {
      Future.successful(Redirect(routes.Account.selectUserName()))
    } else {
      controllers.Stream.getStream(localUser.userName).apply(request)
    }
  }}

  /**
   * Login page.
   */
  def login = Action { implicit request => JavaContext.withContext {
    Ok(views.html.login.render(MyUsernamePasswordAuthProvider.LOGIN_FORM))
  }}

  def doLogin = Action { implicit request => JavaContext.withContext {
    val filledForm = MyUsernamePasswordAuthProvider.LOGIN_FORM.bindFromRequest()
    if (filledForm.hasErrors()) {
      BadRequest(views.html.login.render(filledForm))
    } else {
      val ctx = JavaHelpers.createJavaContext(request)
      JavaHelpers.createResult(ctx, UsernamePasswordAuthProvider.handleLogin(ctx))
    }
  }}

  /**
   * Post login handler.
   */
  @SubjectPresent
  def onLogin = Action { implicit request => JavaContext.withContext {
    Redirect(routes.Application.index())
  }}

  def formatTimestamp(t: Long): String = {
    new SimpleDateFormat("yyyy-dd-MM HH:mm:ss").format(new Date(t))
  }
}
