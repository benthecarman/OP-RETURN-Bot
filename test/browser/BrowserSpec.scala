package browser

import org.scalatestplus.play._
import org.scalatestplus.play.guice._

/**
  * To get the full round trip experience, you can use ScalaTest with Selenium.
  *
  * For browser testing, you need both a server (here "GuiceOneServerPerSuite") and a
  * browser driver (here "HtmlUnitFactory") to get running.
  *
  * The syntax comes from Scalatest's WebBrowser class, and for more details you can see:
  *
  * http://www.scalatest.org/user_guide/using_selenium
  */
class BrowserSpec
    extends PlaySpec
    with GuiceOneServerPerSuite
    with OneBrowserPerSuite
    with HtmlUnitFactory {

  "The browser should" must {
    "successfully process a form" in {
      val listWidgetsURL = controllers.routes.Controller
        .index()
        .absoluteURL(false, s"localhost:$port")

      go to listWidgetsURL

      // Enter in the form fields...
      textField("Message").value = "Foo"
      textField("Hash").value = "true"

      // Press enter button...
      submit()

      eventually {
        pageSource contains "Invoice"
      }
    }
  }
}
