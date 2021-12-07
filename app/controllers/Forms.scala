package controllers

import org.bitcoins.core.protocol.ln.LnInvoice

object Forms {
  import play.api.data.Form
  import play.api.data.Forms._

  case class WrappedInvoice(invoice: LnInvoice)

  case class OpReturnRequest(message: String, noTwitter: Boolean)

  val opReturnRequestForm: Form[OpReturnRequest] = Form(
    mapping(
      "message" -> nonEmptyText.verifying("Message was too long",
                                          _.getBytes.length <= 80),
      "noTwitter" -> boolean
    )(OpReturnRequest.apply)(OpReturnRequest.unapply)
  )

  val invoiceForm: Form[WrappedInvoice] = Form(
    mapping(
      "invoice" -> text.verifying(str => LnInvoice.fromStringT(str).isSuccess)
    )(str => WrappedInvoice(LnInvoice.fromString(str)))(wi =>
      WrappedInvoice.unapply(wi).map(_.toString()))
  )
}
