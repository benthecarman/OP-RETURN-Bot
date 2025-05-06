package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.api.declarative.Commands
import com.bot4s.telegram.clients.FutureSttpClient
import com.bot4s.telegram.future.{Polling, TelegramBot}
import com.bot4s.telegram.methods.SetMyCommands
import com.bot4s.telegram.models.{BotCommand, Message}
import config.OpReturnBotAppConfig
import models._
import org.bitcoins.commons.jsonmodels.lnd.TxDetails
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.util.{StartStopAsync, TimeUtil}
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.Sha256Digest
import org.scalastr.core.{NostrNoteId, NostrPublicKey}
import sttp.capabilities.akka.AkkaStreams
import sttp.client3.SttpBackend
import sttp.client3.akkahttp.AkkaHttpBackend

import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale
import scala.concurrent.Future
import scala.util.Try

class TelegramHandler(controller: Controller)(implicit
    config: OpReturnBotAppConfig,
    system: ActorSystem)
    extends TelegramBot
    with Polling
    with Commands[Future]
    with StartStopAsync[Unit] {

  private val intFormatter: NumberFormat =
    java.text.NumberFormat.getIntegerInstance

  private val currencyFormatter: NumberFormat =
    java.text.NumberFormat.getCurrencyInstance(Locale.US)

  val invoiceDAO: InvoiceDAO = InvoiceDAO()
  val nip5DAO: Nip5DAO = Nip5DAO()
  val zapDAO: ZapDAO = ZapDAO()

  private val myTelegramId = config.telegramId
  private val telegramCreds = config.telegramCreds

  implicit private val backend: SttpBackend[Future, AkkaStreams] =
    AkkaHttpBackend.usingActorSystem(system)

  override val client: RequestHandler[Future] = new FutureSttpClient(
    telegramCreds)

  override def start(): Future[Unit] = {
    val commands = List(
      BotCommand("create", "Creating an invoice with the given message")
    )

    for {
      _ <- run()
      _ <- request(SetMyCommands(commands))
      _ <- sendTelegramMessage("Connected!", myTelegramId)
    } yield ()
  }

  override def stop(): Future[Unit] = Future.unit

  private def checkAdminMessage(msg: Message): Boolean = {
    msg.from match {
      case Some(user) => user.id.toString == myTelegramId
      case None       => false
    }
  }

  onCommand("report") { implicit msg =>
    if (checkAdminMessage(msg)) {
      val secAgo = getTimeParam(msg)
      val afterTimeOpt = secAgo.map(secondsAgo)
      createReport(afterTimeOpt).flatMap { report =>
        reply(report).map(_ => ())
      }
    } else {
      reply("You are not allowed to use this command!").map(_ => ())
    }
  }

  onCommand("processunhandled") { implicit msg =>
    if (checkAdminMessage(msg)) {
      val num = Try {
        val str = msg.text.get.trim.split(" ", 2).last
        str.trim.toInt
      }.toOption
      controller.invoiceMonitor.processUnhandledInvoices(num).flatMap { dbs =>
        reply(s"Updated ${dbs.size} invoices").map(_ => ())
      }
    } else {
      reply("You are not allowed to use this command!").map(_ => ())
    }
  }

  onCommand("fakezap") { implicit msg =>
    if (checkAdminMessage(msg)) {
      val vec = msg.text.get.trim.split(" ", 2).toVector
      val noteIdT = Try(NostrNoteId.fromStringT(vec(1))).flatten

      val f = if (vec.size != 2 || noteIdT.isFailure) {
        reply("Usage: /fakezap <noteid>").map(_ => ())
      } else {
        logger.info("received fake zap request")
        for {
          id <- controller.invoiceMonitor.createFakeZap(noteIdT.get)
          _ <- reply(NostrNoteId(id).toString)
        } yield ()
      }

      f.recoverWith(ex => reply(ex.getMessage).map(_ => ()))
    } else {
      reply("You are not allowed to use this command!").map(_ => ())
    }
  }

  onCommand("create") { implicit msg =>
    val vec = msg.text.get.trim.split(" ", 2).toVector
    val f = if (vec.size != 2) {
      reply("Usage: /create <message>").map(_ => ())
    } else {
      val str = vec(1)
      val id = msg.chat.id
      controller.invoiceMonitor
        .createInvoice(message = str,
                       noTwitter = false,
                       nodeIdOpt = None,
                       telegramId = Some(id),
                       nostrKey = None,
                       dvmEvent = None)
        .flatMap { db =>
          val replyF = reply(db.invoice.toString)

          val url =
            s"https://opreturnbot.com/qr?string=${URLEncoder.encode("lightning:" + db.invoice.toString, "UTF-8")}&width=300&height=300"
          val qrF = sendTelegramPhoto(url, id.toString)

          for {
            _ <- replyF
            _ <- qrF
          } yield ()
        }
    }

    f.failed.foreach(err =>
      logger.error(s"Error processing create command", err))

    f
  }

  private val http = Http()

  def sendTelegramMessage(
      message: String,
      telegramId: String = myTelegramId): Future[Unit] = {
    val url = s"https://api.telegram.org/bot$telegramCreds/sendMessage" +
      s"?chat_id=${URLEncoder.encode(telegramId, "UTF-8")}" +
      s"&text=${URLEncoder.encode(message.trim, "UTF-8")}"

    http.singleRequest(Get(url)).map(_ => ())
  }

  def sendTelegramPhoto(
      photoUrl: String,
      telegramId: String = myTelegramId): Future[Unit] = {
    val url = s"https://api.telegram.org/bot$telegramCreds/sendPhoto" +
      s"?chat_id=${URLEncoder.encode(telegramId, "UTF-8")}" +
      s"&photo=${URLEncoder.encode(photoUrl.trim, "UTF-8")}"

    http.singleRequest(Get(url)).map(_ => ())
  }

  def handleTelegram(
      rHash: Sha256Digest,
      invoice: LnInvoice,
      invoiceDb: InvoiceDb,
      tweetOpt: Option[TweetData],
      nostrOpt: Option[Sha256Digest],
      message: String,
      feeRate: SatoshisPerVirtualByte,
      txDetails: TxDetails,
      totalProfit: CurrencyUnit,
      totalChainFees: CurrencyUnit): Future[Unit] = {
    val amount = invoice.amount.get.toSatoshis
    val profit = amount - txDetails.totalFees

    val deliveryMethod = if (invoiceDb.nostrKey.isDefined) {
      "Nostr"
    } else if (invoiceDb.telegramIdOpt.isDefined) {
      "Telegram"
    } else if (invoiceDb.nodeIdOpt.isDefined) {
      "Lightning Onion Message"
    } else if (invoiceDb.dvmEvent.isDefined) {
      "DVM"
    } else "Web"

    val nonStd =
      if (message.getBytes.length > 80)
        s"Non-standard output! ${printSize(message.getBytes.length)}"
      else ""

    val tweetLine = tweetOpt match {
      case Some(tweet) =>
        s"https://x.com/OP_RETURN_Bot/status/${tweet.id}"
      case None => "Hidden"
    }

    val nostrLine = nostrOpt match {
      case Some(nostr) => NostrNoteId(nostr)
      case None        => "Hidden"
    }

    val telegramMsg =
      s"""
         |🔔 🔔 NEW OP_RETURN 🔔 🔔
         |Message: $message
         |Delivery: $deliveryMethod
         |rhash: ${rHash.hex}
         |tx: https://mempool.space/tx/${txDetails.txId.hex}
         |tweet: $tweetLine
         |nostr: $nostrLine
         |$nonStd
         |
         |fee rate: $feeRate
         |invoice amount: ${printAmount(amount)}
         |tx fee: ${printAmount(txDetails.totalFees)}
         |profit: ${printAmount(profit)}
         |
         |total chain fees: ${printAmount(totalChainFees)}
         |total profit: ${printAmount(totalProfit)}
         |""".stripMargin

    sendTelegramMessage(telegramMsg, myTelegramId)
  }

  def handleTelegramUserPurchase(
      telegramId: Long,
      txDetails: TxDetails): Future[Unit] = {
    val telegramMsg =
      s"""
         |OP_RETURN Created!
         |
         |https://mempool.space/tx/${txDetails.txId.hex}
         |""".stripMargin

    sendTelegramMessage(telegramMsg, telegramId.toString)
  }

  def handleZap(zapDb: ZapDb): Future[Unit] = {
    val requestEvent = zapDb.requestEvent

    val comment =
      if (requestEvent.content.nonEmpty)
        s"Comment: ${requestEvent.content}"
      else ""
    val telegramMsg =
      s"""
         |⚡ ⚡ Zapped! ⚡ ⚡
         |
         |Amount: ${printAmount(zapDb.amount.toSatoshis)}
         |From: ${NostrPublicKey(requestEvent.pubkey)}
         |Note: ${zapDb.noteIdOpt.map(_.toString).getOrElse("Failed")}
         |$comment
         |""".stripMargin.trim

    sendTelegramMessage(telegramMsg, myTelegramId)
  }

  final private val HOURLY_SECONDS: Long = 3_600
  final private val DAILY_SECONDS: Long = 86_400
  final private val WEEKLY_SECONDS: Long = 604_800
  final private val MONTHLY_SECONDS: Long = 2_629_800
  final private val YEARLY_SECONDS: Long = 31_557_600

  private def getTimeParam(msg: Message): Option[Long] = {
    Try {
      val str = msg.text.get.trim.split(" ", 2).last
      val num = str.init.trim.toLong
      if (str.endsWith("h")) {
        Some(num * HOURLY_SECONDS)
      } else if (str.endsWith("hr")) {
        val num = str.dropRight(2).toLong
        Some(num * HOURLY_SECONDS)
      } else if (str.endsWith("d")) {
        Some(num * DAILY_SECONDS)
      } else if (str.endsWith("w")) {
        Some(num * WEEKLY_SECONDS)
      } else if (str.endsWith("m")) {
        Some(num * MONTHLY_SECONDS)
      } else if (str.endsWith("y")) {
        Some(num * YEARLY_SECONDS)
      } else None
    }.toOption.flatten
  }

  protected def secondsAgo(seconds: Long): Long = {
    TimeUtil.now.minusSeconds(seconds).getEpochSecond
  }

  private def createReport(afterTimeOpt: Option[Long]): Future[String] = {
    val action = for {
      completed <- invoiceDAO.completedAction(afterTimeOpt)
      nip5s <- nip5DAO.getNumCompletedAction(afterTimeOpt)
      zapped <- zapDAO.totalZappedAction(afterTimeOpt)
      waitingAction <- invoiceDAO.numWaitingAction(afterTimeOpt)
    } yield (completed, nip5s, zapped, waitingAction)

    invoiceDAO.safeDatabase.run(action).map {
      case (completed, nip5s, zapped, waitingAction) =>
        val chainFees = completed.flatMap(_.chainFeeOpt).sum
        val profit = completed.flatMap(_.profitOpt).sum
        val vbytes = completed.flatMap(_.txOpt.map(_.vsize)).sum
        val nonStdVbytes = completed
          .filter(_.messageBytes.length > 80)
          .flatMap(_.txOpt.map(_.vsize))
          .sum

        s"""
           |Total OP_RETURNs: ${intFormatter.format(completed.size)}
           |Total Non-standard: ${intFormatter.format(
            completed.count(_.messageBytes.length > 80))}
           |Total chain size: ${printSize(vbytes)}
           |Total non-std chain size: ${printSize(nonStdVbytes)}
           |Total chain fees: ${printAmount(chainFees)}
           |Total profit: ${printAmount(profit)}
           |
           |Total NIP-05s: ${intFormatter.format(nip5s)}
           |Total Zapped: ${printAmount(zapped)}
           |
           |Total waiting action: ${intFormatter.format(waitingAction)}
           |Mempool limit: ${controller.invoiceMonitor.mempoolLimit}
           |""".stripMargin
    }
  }

  private def printSize(size: Long): String = {
    if (size < 1000) {
      s"$size vbytes"
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
