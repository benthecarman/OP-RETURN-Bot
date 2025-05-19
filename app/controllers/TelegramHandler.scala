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
import scodec.bits.ByteVector
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

  val opReturnDAO: OpReturnRequestDAO = OpReturnRequestDAO()
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

  onCommand("rebroadcast") { implicit msg =>
    if (checkAdminMessage(msg)) {
      controller.invoiceMonitor
        .rebroadcastAncestors()
        .flatMap { _ =>
          reply("rebroadcasted ancestors!").map(_ => ())
        }
        .recoverWith { e =>
          reply(s"Error rebroadcasting ancestors: ${e.getMessage}").map(_ => ())
        }
    } else {
      reply("You are not allowed to use this command!").map(_ => ())
    }
  }

  onCommand("checktxids") { implicit msg =>
    if (checkAdminMessage(msg)) {
      controller.invoiceMonitor
        .checkTxIds()
        .flatMap { num =>
          if (num == 0) reply("No txids missing!").map(_ => ())
          else reply(s"$num txids missing!").map(_ => ())
        }
        .recoverWith { e =>
          reply(s"Error checking txids: $e").map(_ => ())
        }
    } else {
      reply("You are not allowed to use this command!").map(_ => ())
    }
  }

  onCommand("utxos") { implicit msg =>
    if (checkAdminMessage(msg)) {
      controller.invoiceMonitor
        .listUtxoAncestorTxIds()
        .flatMap { map =>
          val utxos = map
            .map { case (outpoint, ancestors) =>
              s"${outpoint.toHumanReadableString}: ${ancestors.size} ancestors"
            }
            .mkString("\n")

          reply(utxos).map(_ => ())
        }
    } else {
      reply("You are not allowed to use this command!").map(_ => ())
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

  onCommand("publicreport") { implicit msg =>
    if (checkAdminMessage(msg)) {
      val secAgo = getTimeParam(msg)
      val afterTimeOpt = secAgo.map(secondsAgo)
      createPublicReport(afterTimeOpt).flatMap { report =>
        reply(report).map(_ => ())
      }
    } else {
      reply("You are not allowed to use this command!").map(_ => ())
    }
  }

  onCommand("processunhandled") { implicit msg =>
    if (checkAdminMessage(msg)) {
      val (num, liftMempoolLimit) = Try {
        val pieces = msg.text.get.trim.split(" ")
        if (pieces.length == 1) {
          (None, false)
        } else if (pieces.length == 2) {
          (Some(pieces(1).trim.toInt), false)
        } else if (pieces.length == 3) {
          (Some(pieces(1).trim.toInt), pieces(2).trim.toBoolean)
        } else {
          throw new IllegalArgumentException(
            "Usage: /processunhandled <num> <liftMempoolLimit>")
        }
      }
        .getOrElse((None, false))

      controller.invoiceMonitor
        .processUnhandledRequests(num, liftMempoolLimit)
        .flatMap { num =>
          reply(s"Updated $num requests").map(_ => ())
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
        .createInvoice(message = ByteVector(str.getBytes("UTF-8")),
                       noTwitter = false,
                       nodeIdOpt = None,
                       telegramId = Some(id),
                       nostrKey = None,
                       dvmEvent = None)
        .flatMap { case (db, _) =>
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
      requestId: Long,
      amount: Satoshis,
      isOnChain: Boolean,
      requestDb: OpReturnRequestDb,
      tweetOpt: Option[TweetData],
      nostrOpt: Option[Sha256Digest],
      message: String,
      feeRate: SatoshisPerVirtualByte,
      txDetails: TxDetails,
      totalProfit: CurrencyUnit,
      totalChainFees: CurrencyUnit,
      remainingInQueue: Int): Future[Unit] = {
    val profit = amount - txDetails.totalFees

    val deliveryMethod = if (requestDb.nostrKey.isDefined) {
      "Nostr"
    } else if (requestDb.telegramIdOpt.isDefined) {
      "Telegram"
    } else if (requestDb.nodeIdOpt.isDefined) {
      "Lightning Onion Message"
    } else if (requestDb.dvmEvent.isDefined) {
      "DVM"
    } else if (isOnChain) {
      "Web (On-chain)"
    } else {
      "Web (Lightning)"
    }

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
         |id: $requestId
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
         |remaining in queue: ${intFormatter.format(remainingInQueue)}
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
    controller.invoiceMonitor.createReport(afterTimeOpt).map {
      case Report(num,
                  nonStd,
                  numOnChain,
                  chainFees,
                  profit,
                  vbytes,
                  nonStdVbytes,
                  nip5s,
                  zapped,
                  waitingAction) =>
        val percentNonStd =
          if (num == 0) 0.0
          else nonStd.toDouble / num.toDouble * 100.0

        val percentOnChain =
          if (num == 0) 0.0
          else numOnChain.toDouble / num.toDouble * 100.0
        s"""
           |Total OP_RETURNs: ${intFormatter.format(num)}
           |Total Non-standard: ${intFormatter.format(
            nonStd)} (${currencyFormatter.format(percentNonStd).tail}%)
           |Paid On-Chain: ${intFormatter.format(
            numOnChain)} (${currencyFormatter.format(percentOnChain).tail}%)
           |
           |Total chain size: ${printSize(vbytes)}
           |Total non-std chain size: ${printSize(nonStdVbytes)}
           |Total chain fees: ${printAmount(chainFees)}
           |Total profit: ${printAmount(profit)}
           |
           |Total NIP-05s: ${intFormatter.format(nip5s)}
           |Total Zapped: ${printAmount(zapped)}
           |
           |Remaining in Queue: ${intFormatter.format(waitingAction)}
           |Mempool limit: ${controller.invoiceMonitor.mempoolLimit}
           |""".stripMargin
    }
  }

  private def createPublicReport(afterTimeOpt: Option[Long]): Future[String] = {
    val action = for {
      num <- opReturnDAO.numCompletedAction(afterTimeOpt)
      nonStd <- opReturnDAO.numNonStdCompletedAction(afterTimeOpt)
      chainFees <- opReturnDAO.totalChainFeesAction(afterTimeOpt)
      vbytes <- opReturnDAO.totalChainSizeAction(afterTimeOpt)
      nonStdVbytes <- opReturnDAO.totalNonStdChainSizeAction(afterTimeOpt)
      waitingAction <- opReturnDAO.numWaitingAction()
    } yield (num, nonStd, chainFees, vbytes, nonStdVbytes, waitingAction)

    invoiceDAO.safeDatabase.run(action).map {
      case (num, nonStd, chainFees, vbytes, nonStdVbytes, waitingAction) =>
        val percentNonStd =
          if (num == 0) 0.0
          else nonStd.toDouble / num.toDouble * 100.0

        s"""
           |Total OP_RETURNs: ${intFormatter.format(num)}
           |Total Non-standard: ${intFormatter.format(
            nonStd)} (${currencyFormatter.format(percentNonStd).tail}%)
           |Total chain size: ${printSize(vbytes)}
           |Total non-std chain size: ${printSize(nonStdVbytes)}
           |Total chain fees: ${printAmount(chainFees)}
           |
           |Remaining in Queue: ${intFormatter.format(waitingAction)}
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
