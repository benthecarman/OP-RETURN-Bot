package controllers

import org.bitcoins.crypto.DoubleSha256DigestBE
import org.scalatestplus.play.PlaySpec

class PaymentStatusTest extends PlaySpec {

  private val txId = DoubleSha256DigestBE("00" * 32)

  "PaymentStatus" must {

    "keep an unpaid request on the invoice page" in {
      PaymentStatus(invoicePaid = false, txIdOpt = None) mustBe
        PaymentStatus.Unpaid
    }

    "send a paid request with a transaction ID to success" in {
      PaymentStatus(invoicePaid = true, txIdOpt = Some(txId)) mustBe
        PaymentStatus.Complete(txId)
    }

    "advance a pending request after its transaction ID exists" in {
      PaymentStatus(invoicePaid = true, txIdOpt = None) mustBe
        PaymentStatus.Pending
      PaymentStatus(invoicePaid = true, txIdOpt = Some(txId)) mustBe
        PaymentStatus.Complete(txId)
    }
  }

  "The pending page" must {

    "refresh automatically without showing a failure" in {
      val html = views.html.pending().body

      html must include("http-equiv=\"refresh\"")
      html must include("content=\"2\"")
      html must include("Your payment succeeded")
      html must not include "overwhelmed"
      html must not include "unable to process"
    }
  }
}
