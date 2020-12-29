package controllers

import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.CryptoUtil

object Forms {
  import play.api.data.Form
  import play.api.data.Forms._

  case class WrappedInvoice(invoice: LnInvoice)

  case class OpReturnRequest(
      message: String,
      hash: Boolean,
      feeRate: SatoshisPerVirtualByte) {

    lazy val usedMessage: String =
      if (hash)
        CryptoUtil.sha256(message).hex
      else message
  }

  val opReturnRequestForm: Form[OpReturnRequest] = Form(
    mapping(
      "Message" -> nonEmptyText,
      "Hash" -> boolean,
      "FeeRate" -> number(0).transform[SatoshisPerVirtualByte](
        int => SatoshisPerVirtualByte.fromLong(int),
        _.toLong.toInt)
    )(OpReturnRequest.apply)(OpReturnRequest.unapply)
  )

  val invoiceForm: Form[WrappedInvoice] = Form(
    mapping(
      "invoice" -> text.verifying(str => LnInvoice.fromStringT(str).isSuccess)
    )(str => WrappedInvoice(LnInvoice.fromString(str)))(wi =>
      WrappedInvoice.unapply(wi).map(_.toString()))
  )
}
