package controllers

import org.bitcoins.core.protocol.ln.LnInvoice
import org.scalastr.core.NostrPublicKey

object Forms {

  import play.api.data.Form
  import play.api.data.Forms._

  case class WrappedInvoice(invoice: LnInvoice)

  case class OpReturnRequest(message: String, noTwitter: Boolean = false)

  case class Nip5Request(name: String, private val pubkey: String) {
    def publicKey: NostrPublicKey = NostrPublicKey.fromString(pubkey)
  }

  val opReturnRequestForm: Form[OpReturnRequest] = Form(
    mapping(
      "message" -> nonEmptyText.verifying("Message was too long",
                                          _.getBytes.length <= 9000),
      "noTwitter" -> boolean
    )(OpReturnRequest.apply)(OpReturnRequest.unapply)
  )

  val nip5RequestForm: Form[Nip5Request] = Form(
    mapping(
      "name" -> nonEmptyText.verifying(
        "Name was too long, max length is 10 characters",
        _.getBytes.length <= 10),
      "pubkey" -> text.verifying(str =>
        NostrPublicKey.fromStringT(str).isSuccess)
    )(Nip5Request.apply)(Nip5Request.unapply)
  )

  val invoiceForm: Form[WrappedInvoice] = Form(
    mapping(
      "invoice" -> text.verifying(str => LnInvoice.fromStringT(str).isSuccess)
    )(str => WrappedInvoice(LnInvoice.fromString(str)))(wi =>
      WrappedInvoice.unapply(wi).map(_.toString()))
  )
}
