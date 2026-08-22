package controllers

import org.bitcoins.crypto.DoubleSha256DigestBE

sealed private[controllers] trait PaymentStatus

private[controllers] object PaymentStatus {
  case object Unpaid extends PaymentStatus
  case object Pending extends PaymentStatus
  case class Complete(txId: DoubleSha256DigestBE) extends PaymentStatus

  def apply(
      invoicePaid: Boolean,
      txIdOpt: Option[DoubleSha256DigestBE]): PaymentStatus = {
    txIdOpt match {
      case Some(txId)          => Complete(txId)
      case None if invoicePaid => Pending
      case None                => Unpaid
    }
  }
}
