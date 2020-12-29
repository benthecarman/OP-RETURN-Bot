package unit

import controllers.Forms
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.scalatestplus.play.PlaySpec
import play.api.data.FormError
import play.api.mvc._
import play.api.test._

/**
  * Unit tests that do not require a running Play application.
  *
  * This is useful for testing forms and constraints.
  */
class UnitSpec extends PlaySpec {
  import play.api.data.FormBinding.Implicits._

  "WidgetForm" must {

    "apply successfully from request" in {
      // The easiest way to test a form is by passing it a fake request.
      val call = controllers.routes.Controller.createRequest()
      implicit val request: Request[_] =
        FakeRequest(call).withFormUrlEncodedBody("Message" -> "foo",
                                                 "Hash" -> "true",
                                                 "FeeRate" -> "10")
      // A successful binding using an implicit request will give you a form with a value.
      val boundForm = Forms.opReturnRequestForm.bindFromRequest()
      // You can then get the widget data out and test it.
      val data = boundForm.value.get

      data.message must equal("foo")
      data.hash must equal(true)
      data.feeRate must equal(SatoshisPerVirtualByte.fromLong(10))
    }

    "apply successfully from map" in {
      // You can also bind directly from a map, if you don't have a request handy.
      val data = Map("Message" -> "foo", "Hash" -> "true", "FeeRate" -> "10")
      // A successful binding will give you a form with a value.
      val boundForm = Forms.opReturnRequestForm.bind(data)
      // You can then get the widget data out and test it.
      val request = boundForm.value.get

      request.message must equal("foo")
      request.hash must equal(true)
      request.feeRate must equal(SatoshisPerVirtualByte.fromLong(10))
    }

    "show errors when applied unsuccessfully" in {
      // Pass in a negative price that fails the constraints...
      val data = Map("Message" -> "foo", "Hash" -> "-100")

      // ...and binding the form will show errors.
      val errorForm = Forms.opReturnRequestForm.bind(data)
      // You can then get the widget data out and test it.
      val listOfErrors = errorForm.errors

      // Note that the FormError's key is the field it was bound to.
      // If there is no key, then it is a "global error".
      val formError: FormError = listOfErrors.head
      formError.key must equal("Hash")

      // In this case, we don't have any global errors -- they're caused
      // when a constraint on the form itself fails.
      errorForm.hasGlobalErrors mustBe false
    }

  }

}
