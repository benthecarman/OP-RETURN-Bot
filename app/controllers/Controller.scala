package controllers

import akka.actor.{ActorSystem, Cancellable}
import config.OpReturnBotAppConfig
import grizzled.slf4j.Logging
import models.{InvoiceDAO, InvoiceDb}
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.script.control.OP_RETURN
import org.bitcoins.core.util.BitcoinScriptUtil
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.{DoubleSha256DigestBE, Sha256Digest}
import org.bitcoins.feeprovider.MempoolSpaceProvider
import org.bitcoins.feeprovider.MempoolSpaceTarget._
import org.bitcoins.lnd.rpc.LndRpcClient
import play.api.data._
import play.api.mvc._
import scodec.bits.ByteVector

import javax.inject.Inject
import scala.collection._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class Controller @Inject() (cc: MessagesControllerComponents)
    extends MessagesAbstractController(cc)
    with TelegramHandler
    with Logging {

  import controllers.Forms._

  implicit lazy val system: ActorSystem = {
    val system = ActorSystem("op-return-bot")
    system.log.info("Akka logger started")
    system
  }
  implicit lazy val ec: ExecutionContext = system.dispatcher

  implicit lazy val config: OpReturnBotAppConfig =
    OpReturnBotAppConfig.fromDefaultDatadir()

  val lnd: LndRpcClient = config.lndRpcClient

  val startF: Future[Unit] = config.start()

  if (config.startBinaries) {
    lnd.start()
  }

  val feeProvider: MempoolSpaceProvider = MempoolSpaceProvider(
    HalfHourFeeTarget)

  var uri: String = "Error: try again"

  lnd.getInfo.map { info =>
    val torAddrOpt = info.uris.find(_.contains(".onion"))

    uri = torAddrOpt.getOrElse(info.uris.head)
  }

  val invoiceDAO: InvoiceDAO = InvoiceDAO()

  // The URL to the request.  You can call this directly from the template, but it
  // can be more convenient to leave the template completely stateless i.e. all
  // of the "Controller" references are inside the .scala file.
  private val postUrl = routes.Controller.createRequest()

  private val recentTransactions: ArrayBuffer[DoubleSha256DigestBE] = {
    val f = startF.flatMap(_ => invoiceDAO.lastFiveCompleted())
    val res = Await.result(f, 15.seconds)
    mutable.ArrayBuffer[DoubleSha256DigestBE]().addAll(res)
  }

  def index: Action[AnyContent] = {
    Action { implicit request: MessagesRequest[AnyContent] =>
      // Pass an unpopulated form to the template
      Ok(
        views.html
          .index(recentTransactions.toSeq, opReturnRequestForm, postUrl))
        .withHeaders(
          ("Onion-Location",
           "http://v2twhpggkhd5xrcxdhfjiwclfn6hegcd26og2u7apblc4wrbr62sowyd.onion"))
    }
  }

  def connect: Action[AnyContent] = {
    Action { implicit request: MessagesRequest[AnyContent] =>
      Ok(views.html.connect(uri)).withHeaders(
        ("Onion-Location",
         "http://v2twhpggkhd5xrcxdhfjiwclfn6hegcd26og2u7apblc4wrbr62sowyd.onion"))
    }
  }

  def invoiceStatus(rHash: String): Action[AnyContent] = {
    Action.async { _ =>
      val hash = Sha256Digest.fromHex(rHash)
      invoiceDAO.read(hash).map {
        case None =>
          BadRequest("Invoice not from OP_RETURN Bot")
        case Some(InvoiceDb(_, _, _, _, _, _, None, _)) =>
          BadRequest("Invoice has not been paid")
        case Some(InvoiceDb(_, _, _, _, _, _, Some(txId), _)) =>
          Ok(txId.hex)
      }
    }
  }

  def invoice(invoiceStr: String): Action[AnyContent] =
    Action { implicit request: MessagesRequest[AnyContent] =>
      LnInvoice.fromStringT(invoiceStr) match {
        case Failure(exception) =>
          logger.error(exception)
          BadRequest(
            views.html
              .index(recentTransactions.toSeq, opReturnRequestForm, postUrl))
        case Success(invoice) =>
          val resultF = invoiceDAO.read(invoice.lnTags.paymentHash.hash).map {
            case None =>
              BadRequest("Invoice not from OP_RETURN Bot")
            case Some(InvoiceDb(_, _, _, _, _, _, None, _)) =>
              Ok(views.html.showInvoice(invoice))
            case Some(InvoiceDb(_, _, _, _, _, _, Some(txId), _)) =>
              Redirect(routes.Controller.success(txId.hex))
          }

          Await.result(resultF, 30.seconds)
      }
    }

  def success(txIdStr: String): Action[AnyContent] = {
    Action { implicit request: MessagesRequest[AnyContent] =>
      Try(DoubleSha256DigestBE.fromHex(txIdStr)) match {
        case Failure(exception) =>
          logger.error(exception)
          BadRequest(
            views.html
              .index(recentTransactions.toSeq, opReturnRequestForm, postUrl))
        case Success(txId) =>
          val resultF = invoiceDAO.findByTxId(txId).map {
            case None =>
              BadRequest(views.html
                .index(recentTransactions.toSeq, opReturnRequestForm, postUrl))
            case Some(InvoiceDb(_, _, _, _, _, Some(tx), _, _)) =>
              Ok(views.html.success(tx))
            case Some(InvoiceDb(_, invoice, _, _, _, None, _, _)) =>
              throw new RuntimeException(s"This is impossible, $invoice")
          }

          Await.result(resultF, 30.seconds)
      }
    }
  }

  def create: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      def failure(badForm: Form[OpReturnRequest]): Future[Result] = {
        Future.successful(BadRequest(badForm.errorsAsJson))
      }

      def success(input: OpReturnRequest): Future[Result] = {
        for {
          invoiceDb <- processMessage(input.message)
        } yield {
          Ok(invoiceDb.invoice.toString())
        }
      }

      opReturnRequestForm.bindFromRequest().fold(failure, success)
    }
  }

  // This will be the action that handles our form post
  def createRequest: Action[AnyContent] = {
    Action { implicit request: MessagesRequest[AnyContent] =>
      val errorFunction: Form[OpReturnRequest] => Result = {
        formWithErrors: Form[OpReturnRequest] =>
          // This is the bad case, where the form had validation errors.
          // Let's show the user the form again, with the errors highlighted.
          // Note how we pass the form with errors to the template.
          BadRequest(
            views.html
              .index(recentTransactions.toSeq, formWithErrors, postUrl))
      }

      // This is the good case, where the form was successfully parsed as an OpReturnRequest
      val successFunction: OpReturnRequest => Result = {
        data: OpReturnRequest =>
          val message = data.message
          val resultF = processMessage(message).map { invoiceDb =>
            Redirect(routes.Controller.invoice(invoiceDb.invoice.toString()))
          }
          Await.result(resultF, 30.seconds)
      }

      val formValidationResult = opReturnRequestForm.bindFromRequest()
      formValidationResult.fold(errorFunction, successFunction)
    }
  }

  private def processMessage(message: String): Future[InvoiceDb] = {
    require(
      message.getBytes.length <= 80,
      "OP_Return message received was too long, must be less than 80 chars")

    feeProvider.getFeeRate
      .flatMap { feeRate =>
        // 124 base tx fee + 100 app fee
        val baseSize = 124 + 100
        val messageSize = message.getBytes.length

        // tx fee + app fee (1337)
        val sats = (feeRate * (baseSize + messageSize)) + Satoshis(1337)
        val expiry = 60 * 5 // 5 minutes

        lnd
          .addInvoice(s"OP_RETURN Bot: $message", MilliSatoshis(sats), expiry)
          .flatMap { invoiceResult =>
            val invoice = invoiceResult.invoice
            val db: InvoiceDb =
              InvoiceDb(rHash = Sha256Digest(invoiceResult.rHash),
                        invoice = invoice,
                        message = message,
                        hash = false,
                        feeRate = feeRate,
                        txOpt = None,
                        txIdOpt = None,
                        profitOpt = None)

            startMonitor(rHash = invoiceResult.rHash,
                         invoice = invoice,
                         message = message,
                         feeRate = feeRate,
                         expiry = expiry)

            invoiceDAO.create(db)
          }
      }
  }

  private def startMonitor(
      rHash: ByteVector,
      invoice: LnInvoice,
      message: String,
      feeRate: SatoshisPerVirtualByte,
      expiry: Int): Cancellable = {
    system.scheduler.scheduleOnce(2.seconds) {
      logger.info(s"Starting monitor for invoice ${rHash.toHex}")

      lnd.monitorInvoice(rHash, 1.second, expiry + 60).flatMap {
        invoiceResult =>
          if (invoiceResult.state.isSettled) {
            onInvoicePaid(rHash, invoice, message, feeRate)
          } else Future.unit
      }
    }
  }

  private def onInvoicePaid(
      rHash: ByteVector,
      invoice: LnInvoice,
      message: String,
      feeRate: SatoshisPerVirtualByte): Future[InvoiceDb] = {
    logger.info(s"Received ${invoice.amount.get.toSatoshis} sats!")

    val output = {
      val messageBytes = ByteVector(message.getBytes)

      val asm = OP_RETURN +: BitcoinScriptUtil.calculatePushOp(
        messageBytes) :+ ScriptConstant(messageBytes)

      val scriptPubKey = ScriptPubKey(asm.toVector)

      TransactionOutput(Satoshis.zero, scriptPubKey)
    }

    val createTxF = for {
      transaction <-
        lnd.sendOutputs(Vector(output), feeRate, spendUnconfirmed = true)
      _ <- lnd.publishTransaction(transaction)

      txId = transaction.txIdBE
      _ = logger.info(s"Successfully created tx: ${txId.hex}")

      txDetailsOpt <- lnd.getTransaction(txId)

      _ = {
        recentTransactions += txId
        if (recentTransactions.size >= 5) {
          val old = recentTransactions.takeRight(5)
          recentTransactions.clear()
          recentTransactions ++= old
        }
      }

      amount = invoice.amount.get.toSatoshis
      // Need to make sure we upsert the tx and txid even if this fails, so we can't call .get
      profitOpt = txDetailsOpt.map(d => amount - d.totalFees)

      dbWithTx: InvoiceDb = InvoiceDb(Sha256Digest(rHash),
                                      invoice,
                                      message,
                                      hash = false,
                                      feeRate,
                                      Some(transaction),
                                      Some(txId),
                                      profitOpt)

      res <- invoiceDAO.upsert(dbWithTx)

      _ <- txDetailsOpt match {
        case Some(details) =>
          for {
            profit <- invoiceDAO.totalProfit()
            _ <-
              handleTelegram(rHash, invoice, message, feeRate, details, profit)
          } yield ()
        case None =>
          val msg = s"Failed to get transaction details for ${rHash.toHex}"
          logger.warn(msg)
          sendTelegramMessage(msg)
      }
    } yield res

    createTxF.failed.foreach { err =>
      logger.error(
        s"Failed to create tx for invoice ${invoice.lnTags.paymentHash.hash.hex}, got error $err")
    }

    createTxF
  }
}
