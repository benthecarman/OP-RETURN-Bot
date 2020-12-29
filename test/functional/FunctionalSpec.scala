package functional

import controllers.{routes, Controller}
import org.bitcoins.core.protocol.ln.LnInvoice
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.http.Status
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.Future

/**
  * Functional specification that has a running Play application.
  *
  * This is good for testing filter functionality, such as CSRF token and template checks.
  *
  * See https://www.playframework.com/documentation/2.8.x/ScalaFunctionalTestingWithScalaTest for more details.
  */
class FunctionalSpec
    extends PlaySpec
    with GuiceOneAppPerSuite
    with Injecting
    with ScalaFutures {

  // CSRF token helper adds "withCSRFToken" to FakeRequest:
  // https://www.playframework.com/documentation/2.8.x/ScalaCsrf#Testing-CSRF
  import CSRFTokenHelper._

  val dummyInvoice: LnInvoice = LnInvoice.fromString(
    "lntb20500n1p074mmppp5kscnwh85l98ecyj78qsnsjw2f84kj49csm2ftudz9sv0gu6d7fpqdqafag975j92324yn3qgfhhgw3qvehk7xqzfv9qy9qsqsp5untk52hexrl86ep7qhexaexaswtutzrgehymfz3amvgcesegdxcq4zy422g4mrr4f965nql54qnsnxelhvs2xmyg2cyyp3z8cq2tjznpx8tqzqsrlm2alayxqcm0wdjwp206x0fqnevxp9m0f7904cfdjfqq9xy6uq")

  "Controller" must {

    "process a POST request successfully" in {
      // Pull the controller from the already running Play application, using Injecting
      val controller = inject[Controller]

      // Call using the FakeRequest and the correct body information and CSRF token
      val request = FakeRequest(routes.Controller.createRequest())
        .withFormUrlEncodedBody("Message" -> "foo",
                                "Hash" -> "false",
                                "FeeRate" -> "10")
        .withCSRFToken
      val futureResult: Future[Result] =
        controller.createRequest().apply(request)

      // And we can get the results out using Scalatest's "Futures" trait, which gives us whenReady
      whenReady(futureResult) { result =>
        result.header.headers(LOCATION) must startWith(
          routes.Controller
            .invoice(dummyInvoice.toString())
            .url
            .replaceAll(dummyInvoice.toString(), ""))
      }
    }

    "reject a POST request when given bad Message value" in {
      val controller = inject[Controller]

      // Call the controller with negative price...
      val request = FakeRequest(routes.Controller.createRequest())
        .withFormUrlEncodedBody("Message" -> "",
                                "Hash" -> "false",
                                "FeeRate" -> "10")
        .withCSRFToken
      val futureResult: Future[Result] =
        controller.createRequest().apply(request)

      status(futureResult) must be(Status.BAD_REQUEST)
    }

    "reject a POST request when given bad Hash value" in {
      val controller = inject[Controller]

      // Call the controller with negative price...
      val request = FakeRequest(routes.Controller.createRequest())
        .withFormUrlEncodedBody("Message" -> "foo",
                                "Hash" -> "-100",
                                "FeeRate" -> "10")
        .withCSRFToken
      val futureResult: Future[Result] =
        controller.createRequest().apply(request)

      status(futureResult) must be(Status.BAD_REQUEST)
    }

    "reject a POST request when given bad FeeRate value" in {
      val controller = inject[Controller]

      // Call the controller with negative price...
      val request = FakeRequest(routes.Controller.createRequest())
        .withFormUrlEncodedBody("Message" -> "foo",
                                "Hash" -> "false",
                                "FeeRate" -> "-10")
        .withCSRFToken
      val futureResult: Future[Result] =
        controller.createRequest().apply(request)

      status(futureResult) must be(Status.BAD_REQUEST)
    }
  }

}
