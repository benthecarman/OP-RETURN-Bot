package functional

import controllers.{routes, Controller}
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Play.materializer
import play.api.http.Status
import play.api.test.Helpers._
import play.api.test._

/** Functional specification that has a running Play application.
  *
  * This is good for testing filter functionality, such as CSRF token and
  * template checks.
  *
  * See
  * https://www.playframework.com/documentation/2.8.x/ScalaFunctionalTestingWithScalaTest
  * for more details.
  */
class FunctionalSpec
    extends PlaySpec
    with GuiceOneAppPerSuite
    with Injecting
    with ScalaFutures {

  // CSRF token helper adds "withCSRFToken" to FakeRequest:
  // https://www.playframework.com/documentation/2.8.x/ScalaCsrf#Testing-CSRF
  import CSRFTokenHelper._

  "Controller" must {

    "reject a POST request when given empty Message value" in {
      val controller = inject[Controller]

      // Call the controller with negative price...
      val request = FakeRequest(routes.Controller.createRequest)
        .withFormUrlEncodedBody("message" -> "")
        .withCSRFToken
      val futureResult =
        controller.createRequest().apply(request)

      status(futureResult) must be(Status.BAD_REQUEST)
    }

    "reject a POST request when given bad Message value" in {
      val controller = inject[Controller]

      // Call the controller with negative price...
      val request = FakeRequest(routes.Controller.createRequest)
        .withFormUrlEncodedBody(
          "message" -> "this is over 80 characters _____________________________________________________________________")
        .withCSRFToken
      val futureResult =
        controller.createRequest().apply(request)

      status(futureResult) must be(Status.BAD_REQUEST)
    }
  }

}
