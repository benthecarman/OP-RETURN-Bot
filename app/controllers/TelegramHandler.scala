package controllers

import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import com.danielasfregola.twitter4s.entities.Tweet
import grizzled.slf4j.Logging
import org.bitcoins.commons.jsonmodels.lnd.TxDetails
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.Sha256Digest

import java.net.URLEncoder
import scala.concurrent.Future

trait TelegramHandler extends Logging { self: Controller =>
  private val myTelegramId = self.config.telegramId
  private val telegramCreds = self.config.telegramCreds

  protected def sendTelegramMessage(message: String): Future[Unit] = {
    val url = s"https://api.telegram.org/bot$telegramCreds/sendMessage" +
      s"?chat_id=${URLEncoder.encode(myTelegramId, "UTF-8")}" +
      s"&text=${URLEncoder.encode(message, "UTF-8")}"

    Http().singleRequest(Get(url)).map(_ => ())
  }

  protected def handleTelegram(
      rHash: Sha256Digest,
      invoice: LnInvoice,
      tweet: Tweet,
      message: String,
      feeRate: SatoshisPerVirtualByte,
      txDetails: TxDetails,
      totalProfit: CurrencyUnit): Future[Unit] = {
    val amount = invoice.amount.get.toSatoshis
    val profit = amount - txDetails.totalFees

    val telegramMsg =
      s"""
         |🔔 🔔 NEW OP_RETURN 🔔 🔔
         |Message: $message
         |rhash: ${rHash.hex}
         |tx: https://mempool.space/tx/${txDetails.txId.hex}
         |tweet: https://twitter.com/OP_RETURN_Bot/status/${tweet.id}
         |
         |fee rate: $feeRate
         |invoice amount: ${amount.satoshis}
         |tx fee: ${txDetails.totalFees.satoshis}
         |profit: ${profit.satoshis}
         |
         |total profit: ${totalProfit.satoshis}
         |""".stripMargin

    sendTelegramMessage(telegramMsg)
  }
}
