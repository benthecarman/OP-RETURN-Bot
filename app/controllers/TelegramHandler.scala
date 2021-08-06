package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.api.declarative.Commands
import com.bot4s.telegram.clients.FutureSttpClient
import com.bot4s.telegram.future.{Polling, TelegramBot}
import com.danielasfregola.twitter4s.entities.Tweet
import config.OpReturnBotAppConfig
import models.InvoiceDAO
import org.bitcoins.commons.jsonmodels.lnd.TxDetails
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.Sha256Digest
import sttp.capabilities._
import sttp.client3.SttpBackend
import sttp.client3.okhttp.OkHttpFutureBackend

import java.net.URLEncoder
import java.text.NumberFormat
import scala.concurrent.Future
import scala.util.matching.Regex

class TelegramHandler(implicit
    config: OpReturnBotAppConfig,
    system: ActorSystem)
    extends TelegramBot
    with Polling
    with Commands[Future] {

  val numericRegex: Regex = "-?([1-9,][0-9,]*)?".r
  val numberFormatter: NumberFormat = java.text.NumberFormat.getIntegerInstance

  val invoiceDAO: InvoiceDAO = InvoiceDAO()

  private val myTelegramId = config.telegramId
  private val telegramCreds = config.telegramCreds

  implicit val backend: SttpBackend[Future, WebSockets] =
    OkHttpFutureBackend()

  override val client: RequestHandler[Future] = new FutureSttpClient(
    telegramCreds)

  onCommand("report") { implicit msg =>
    createReport().map { report =>
      reply(report)
    }
  }

  def sendTelegramMessage(message: String): Future[Unit] = {
    val url = s"https://api.telegram.org/bot$telegramCreds/sendMessage" +
      s"?chat_id=${URLEncoder.encode(myTelegramId, "UTF-8")}" +
      s"&text=${URLEncoder.encode(message, "UTF-8")}"

    Http().singleRequest(Get(url)).map(_ => ())
  }

  def handleTelegram(
      rHash: Sha256Digest,
      invoice: LnInvoice,
      tweetOpt: Option[Tweet],
      message: String,
      feeRate: SatoshisPerVirtualByte,
      txDetails: TxDetails,
      totalProfit: CurrencyUnit,
      totalChainFees: CurrencyUnit): Future[Unit] = {
    val amount = invoice.amount.get.toSatoshis
    val profit = amount - txDetails.totalFees

    val tweetLine = tweetOpt match {
      case Some(tweet) =>
        s"https://twitter.com/OP_RETURN_Bot/status/${tweet.id}"
      case None => "failed"
    }

    val telegramMsg =
      s"""
         |🔔 🔔 NEW OP_RETURN 🔔 🔔
         |Message: $message
         |rhash: ${rHash.hex}
         |tx: https://mempool.space/tx/${txDetails.txId.hex}
         |tweet: $tweetLine
         |
         |fee rate: $feeRate
         |invoice amount: ${printAmount(amount)}
         |tx fee: ${printAmount(txDetails.totalFees)}
         |profit: ${printAmount(profit)}
         |
         |total chain fees: ${printAmount(totalChainFees)}
         |total profit: ${printAmount(totalProfit)}
         |""".stripMargin

    sendTelegramMessage(telegramMsg)
  }

  protected def createReport(): Future[String] = {
    invoiceDAO.completed().map { completed =>
      val chainFees = completed.flatMap(_.chainFeeOpt).sum
      val profit = completed.flatMap(_.profitOpt).sum

      s"""
         |Total OP_RETURNs: ${numberFormatter.format(completed.size)}
         |Total chain fees: ${printAmount(chainFees)}
         |Total profit: ${printAmount(profit)}
         |""".stripMargin
    }
  }

  private def printAmount(amount: CurrencyUnit): String = {
    numberFormatter.format(amount.satoshis.toLong) + " sats"
  }
}
