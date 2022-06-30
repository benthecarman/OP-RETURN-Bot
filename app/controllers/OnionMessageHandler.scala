package controllers

import akka.Done
import akka.stream.scaladsl.Sink
import controllers.OpReturnBotTLV._
import grizzled.slf4j.Logging
import org.bitcoins.core.protocol.BigSizeUInt
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.protocol.tlv._
import org.bitcoins.core.protocol.transaction.Transaction
import scodec.bits.ByteVector

import scala.concurrent.Future
import scala.util._

trait OnionMessageHandler extends Logging { self: Controller =>

  def startOnionMessageSubscription(): Future[Done] = {
    lnd
      .subscribeCustomMessages()
      .map { case (nodeId, tlv) =>
        tlv match {
          case unknown: UnknownTLV =>
            val knownT = Try(OpReturnBotTLV.fromUnknownTLV(unknown))

            knownT match {
              case Failure(_)              => () // ignore
              case Success(opReturnBotTLV) =>
                // handle
                opReturnBotTLV match {
                  case RequestInvoiceTLV(message) =>
                    processMessage(message, noTwitter = false, Some(nodeId))
                      .flatMap { db =>
                        val invoiceTLV = InvoiceTLV(db.invoice)
                        lnd.sendCustomMessage(nodeId, invoiceTLV.toUnknownTLV)
                      }
                  case InvoiceTLV(invoice) =>
                    logger.warn(s"Received invoice TLV $invoice")
                  case BroadcastTransactionTLV(tx) =>
                    logger.warn(s"Received broadcast transaction TLV ${tx.hex}")
                }
            }
          case _: InitTLV | _: ErrorTLV | _: PingTLV | _: PongTLV |
              _: DLCOracleTLV | _: DLCSetupPieceTLV | _: DLCSetupTLV |
              _: AmtToForwardTLV | _: OutgoingCLTVValueTLV | _: PaymentDataTLV |
              _: ShortChannelIdTLV =>
            () // ignore
        }
      }
      .runWith(Sink.ignore)
  }
}

sealed trait OpReturnBotTLV {
  def toUnknownTLV: UnknownTLV
}

sealed trait OpReturnBotTLVFactory[T <: OpReturnBotTLV] {
  def tpe: BigSizeUInt
  def fromUnknownTLV(tlv: UnknownTLV): T
}

object OpReturnBotTLV {

  case class RequestInvoiceTLV(message: String) extends OpReturnBotTLV {

    override val toUnknownTLV: UnknownTLV = {
      val bytes = ByteVector(message.getBytes)
      UnknownTLV(RequestInvoiceTLV.tpe, bytes)
    }
  }

  object RequestInvoiceTLV extends OpReturnBotTLVFactory[RequestInvoiceTLV] {
    override val tpe: BigSizeUInt = BigSizeUInt(49343)

    override def fromUnknownTLV(tlv: UnknownTLV): RequestInvoiceTLV = {
      if (tlv.tpe == tpe) {
        val message = new String(tlv.value.toArray)

        RequestInvoiceTLV(message)
      } else
        throw new IllegalArgumentException(
          s"TLV must have type ${tpe.toLong}, got ${tlv.tpe.toLong}")
    }
  }

  case class InvoiceTLV(invoice: LnInvoice) extends OpReturnBotTLV {

    override val toUnknownTLV: UnknownTLV = {
      val bytes = ByteVector(invoice.toString.getBytes)

      UnknownTLV(InvoiceTLV.tpe, bytes)
    }
  }

  object InvoiceTLV extends OpReturnBotTLVFactory[InvoiceTLV] {
    override val tpe: BigSizeUInt = BigSizeUInt(49345)

    override def fromUnknownTLV(tlv: UnknownTLV): InvoiceTLV = {
      if (tlv.tpe == tpe) {
        val str = new String(tlv.value.toArray)
        val invoice = LnInvoice.fromString(str)

        InvoiceTLV(invoice)
      } else
        throw new IllegalArgumentException(
          s"TLV must have type ${tpe.toLong}, got ${tlv.tpe.toLong}")
    }
  }

  case class BroadcastTransactionTLV(tx: Transaction) extends OpReturnBotTLV {

    override val toUnknownTLV: UnknownTLV = {
      UnknownTLV(BroadcastTransactionTLV.tpe, tx.bytes)
    }
  }

  object BroadcastTransactionTLV
      extends OpReturnBotTLVFactory[BroadcastTransactionTLV] {
    override val tpe: BigSizeUInt = BigSizeUInt(49347)

    override def fromUnknownTLV(tlv: UnknownTLV): BroadcastTransactionTLV = {
      if (tlv.tpe == tpe) {
        val tx = Transaction(tlv.value)
        BroadcastTransactionTLV(tx)
      } else
        throw new IllegalArgumentException(
          s"TLV must have type ${tpe.toLong}, got ${tlv.tpe.toLong}")
    }
  }

  def fromUnknownTLV(tlv: UnknownTLV): OpReturnBotTLV = {
    Try(RequestInvoiceTLV.fromUnknownTLV(tlv)).getOrElse(
      Try(InvoiceTLV.fromUnknownTLV(tlv))
        .getOrElse(BroadcastTransactionTLV.fromUnknownTLV(tlv)))
  }
}
