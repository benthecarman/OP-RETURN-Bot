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
import org.bitcoins.crypto.{CryptoUtil, DoubleSha256DigestBE, Sha256Digest}
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

  config.start()

  if (config.startBinaries) {
    lnd.start()
  }

  val feeProvider: MempoolSpaceProvider = MempoolSpaceProvider(HalfHourFeeTarget)

  val invoiceDAO: InvoiceDAO = InvoiceDAO()

  // The URL to the request.  You can call this directly from the template, but it
  // can be more convenient to leave the template completely stateless i.e. all
  // of the "Controller" references are inside the .scala file.
  private val postUrl = routes.Controller.createRequest()

  private val recentTransactions: ArrayBuffer[DoubleSha256DigestBE] = {
    val f = invoiceDAO.lastFiveCompleted()
    val res = Await.result(f, 15.seconds)
    mutable.ArrayBuffer[DoubleSha256DigestBE]().addAll(res)
  }

  def index: Action[AnyContent] =
    Action { implicit request: MessagesRequest[AnyContent] =>
      // Pass an unpopulated form to the template
      Ok(
        views.html
          .index(recentTransactions.toSeq, opReturnRequestForm, postUrl))
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
              throw new RuntimeException("Invoice not from OP_RETURN Bot")
            case Some(InvoiceDb(_, _, _, _, _, _, None)) =>
              Ok(views.html.showInvoice(invoice))
            case Some(InvoiceDb(_, _, _, _, _, _, Some(txId))) =>
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
            case Some(InvoiceDb(_, _, _, _, _, Some(tx), _)) =>
              Ok(views.html.success(tx))
            case Some(InvoiceDb(_, invoice, _, _, _, None, _)) =>
              throw new RuntimeException(s"This is impossible, $invoice")
          }

          Await.result(resultF, 30.seconds)
      }
    }
  }

  // This will be the action that handles our form post
  def createRequest: Action[AnyContent] =
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
          val OpReturnRequest(message, hashMessage) = data
          val usableMessage = CryptoUtil.normalize(message)
          require(
            usableMessage.length <= 80 || hashMessage,
            "OP_Return message received was too long, must be less than 80 chars, or hash the message")

          val result = feeProvider.getFeeRate
            .map(_.asInstanceOf[SatoshisPerVirtualByte])
            .flatMap { feeRate =>
              // 102 base tx fee + 100 app fee
              val baseSize = 102 + 100
              // if we are hashing the message it is a fixed 32 size
              val messageSize = if (hashMessage) 32 else usableMessage.length

              // tx fee + app fee (1337)
              val sats = (feeRate * (baseSize + messageSize)) + Satoshis(1337)
              val expiry = 60 * 5 // 5 minutes

              lnd
                .addInvoice(s"OP_RETURN Bot: $usableMessage",
                            MilliSatoshis(sats),
                            expiry)
                .flatMap { invoiceResult =>
                  val invoice = invoiceResult.invoice
                  val db: InvoiceDb =
                    InvoiceDb(Sha256Digest(invoiceResult.rHash),
                              invoice,
                              usableMessage,
                              hashMessage,
                              feeRate,
                              None,
                              None)

                  startMonitor(invoiceResult.rHash,
                               invoice,
                               usableMessage,
                               hashMessage,
                               feeRate,
                               expiry)

                  invoiceDAO.create(db).map { _ =>
                    Redirect(routes.Controller.invoice(invoice.toString()))
                  }
                }
            }
          Await.result(result, 30.seconds)
      }

      val formValidationResult = opReturnRequestForm.bindFromRequest()
      formValidationResult.fold(errorFunction, successFunction)
    }

  private def startMonitor(
      rHash: ByteVector,
      invoice: LnInvoice,
      message: String,
      hashMessage: Boolean,
      feeRate: SatoshisPerVirtualByte,
      expiry: Int): Cancellable = {
    system.scheduler.scheduleOnce(2.seconds) {
      logger.info(s"Starting monitor for invoice ${rHash.toHex}")

      lnd.monitorInvoice(rHash, 1.second, expiry).flatMap { invoiceResult =>
        if (invoiceResult.state.isSettled) {
          logger.info(s"Received ${invoiceResult.amtPaidSat} sats!")

          val output = {
            val messageToUse =
              if (hashMessage)
                CryptoUtil.sha256(ByteVector(message.getBytes)).bytes
              else ByteVector(message.getBytes)

            val asm = OP_RETURN +: BitcoinScriptUtil.calculatePushOp(
              messageToUse) :+ ScriptConstant(messageToUse)

            val scriptPubKey = ScriptPubKey(asm.toVector)

            TransactionOutput(Satoshis.zero, scriptPubKey)
          }

          val createTxF = for {
            transaction <-
              lnd.sendOutputs(Vector(output), feeRate, spendUnconfirmed = true)
            _ <- lnd.publishTransaction(transaction)
          } yield {
            val txId = transaction.txIdBE
            logger.info(s"Successfully created tx: ${txId.hex}")

            recentTransactions += txId
            if (recentTransactions.size >= 5) {
              val old = recentTransactions.takeRight(5)
              recentTransactions.clear()
              recentTransactions ++= old
            }

            val dbWithTx: InvoiceDb = InvoiceDb(Sha256Digest(rHash),
                                                invoice,
                                                message,
                                                hashMessage,
                                                feeRate,
                                                Some(transaction),
                                                Some(txId))
            invoiceDAO.upsert(dbWithTx)
          }

          createTxF.failed.foreach { err =>
            logger.error(
              s"Failed to create tx for invoice ${invoice.lnTags.paymentHash.hash.hex}, got error $err")
          }

          createTxF
        } else Future.unit
      }
    }
  }
}
