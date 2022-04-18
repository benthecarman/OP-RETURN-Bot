package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.api.declarative.Commands
import com.bot4s.telegram.clients.FutureSttpClient
import com.bot4s.telegram.future.{Polling, TelegramBot}
import com.bot4s.telegram.methods.SetMyCommands
import com.bot4s.telegram.models.BotCommand
import com.danielasfregola.twitter4s.entities.Tweet
import config.OpReturnBotAppConfig
import models.InvoiceDAO
import org.bitcoins.commons.jsonmodels.lnd.TxDetails
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.util.StartStopAsync
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.Sha256Digest
import sttp.capabilities._
import sttp.client3.SttpBackend
import sttp.client3.okhttp.OkHttpFutureBackend

import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale
import scala.concurrent.Future
import scala.util.matching.Regex

class TelegramHandler(controller: Controller)(implicit
    config: OpReturnBotAppConfig,
    system: ActorSystem)
    extends TelegramBot
    with Polling
    with Commands[Future]
    with StartStopAsync[Unit] {

  val numericRegex: Regex = "^[0-9]*\\.[0-9]{2}$ or ^[0-9]*\\.[0-9][0-9]$".r
  val intFormatter: NumberFormat = java.text.NumberFormat.getIntegerInstance

  val currencyFormatter: NumberFormat =
    java.text.NumberFormat.getCurrencyInstance(Locale.US)

  val invoiceDAO: InvoiceDAO = InvoiceDAO()

  private val myTelegramId = config.telegramId
  private val telegramCreds = config.telegramCreds

  implicit val backend: SttpBackend[Future, WebSockets] =
    OkHttpFutureBackend()

  override val client: RequestHandler[Future] = new FutureSttpClient(
    telegramCreds)

  override def start(): Future[Unit] = {
    val commands = List(
      BotCommand("report", "Generate report of profit and total on chain fees"),
      BotCommand("processunhandled", "Forces processing of invoices")
    )

    for {
      _ <- run()
      _ <- request(SetMyCommands(commands))
      _ <- sendTelegramMessage("Connected!")
    } yield ()
  }

  override def stop(): Future[Unit] = Future.unit

  onCommand("report") { implicit msg =>
    createReport().flatMap { report =>
      reply(report).map(_ => ())
    }
  }

  onCommand("processunhandled") { implicit msg =>
    controller.processUnhandledInvoices().flatMap { dbs =>
      reply(s"Updated ${dbs.size} invoices").map(_ => ())
    }
  }

  def sendTelegramMessage(message: String): Future[Unit] = {
    val url = s"https://api.telegram.org/bot$telegramCreds/sendMessage" +
      s"?chat_id=${URLEncoder.encode(myTelegramId, "UTF-8")}" +
      s"&text=${URLEncoder.encode(message.trim, "UTF-8")}"

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
      case None => "Hidden"
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
      val buggedTxs = completed.filter(_.profitOpt.exists(_ <= Satoshis.zero))
      val chainFees = completed.flatMap(_.chainFeeOpt).sum
      val buggedChainFees = buggedTxs.flatMap(_.chainFeeOpt).sum
      val profit = completed.flatMap(_.profitOpt).sum
      val bugged = buggedTxs.flatMap(_.profitOpt).sum
      val vbytes = completed.flatMap(_.txOpt.map(_.vsize)).sum

      s"""
         |Total OP_RETURNs: ${intFormatter.format(completed.size)}
         |Total chain size: ${printSize(vbytes)}
         |Total chain fees: ${printAmount(chainFees)}
         |Chain fees w/o bug: ${printAmount(chainFees - buggedChainFees)}
         |Total profit: ${printAmount(profit)}
         |Total bugged: ${printAmount(bugged)}
         |Profit w/o bug: ${printAmount(profit - bugged)}
         |""".stripMargin
    }
  }

  private def printSize(size: Long): String = {
    if (size < 1000) {
      s"${currencyFormatter.format(size).tail} vbytes"
    } else if (size < 1000000) {
      s"${currencyFormatter.format(size / 1000.0).tail} vKB"
    } else if (size < 1000000000) {
      s"${currencyFormatter.format(size / 1000000.0).tail} vMB"
    } else {
      s"${currencyFormatter.format(size / 1000000000.0).tail} vGB"
    }
  }

  private def printAmount(amount: CurrencyUnit): String = {
    intFormatter.format(amount.satoshis.toLong) + " sats"
  }
}
