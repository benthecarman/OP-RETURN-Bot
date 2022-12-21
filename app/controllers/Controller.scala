package controllers

import akka.actor.ActorSystem
import com.translnd.rotator.PubkeyRotator
import config.OpReturnBotAppConfig
import grizzled.slf4j.Logging
import models.{InvoiceDAO, InvoiceDb}
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnurl.json.LnURLJsonModels.{
  LnURLPayInvoice,
  LnURLPayResponse
}
import play.api.data._
import play.api.libs.json._
import play.api.mvc._
import scodec.bits.ByteVector

import java.net.URL
import javax.inject.Inject
import scala.collection._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class Controller @Inject() (cc: MessagesControllerComponents)
    extends MessagesAbstractController(cc)
    with OnionMessageHandler
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

  import config.transLndConfig

  val lnd: LndRpcClient = config.lndRpcClient

  lazy val pubkeyRotator: PubkeyRotator = PubkeyRotator(lnd)

  val startF: Future[Unit] = config.start()

  val uriErrorString = "Error: try again"
  var uri: String = uriErrorString

  def setURI(): Future[Unit] = {
    lnd.getInfo.map { info =>
      val torAddrOpt = info.uris.find(_.contains(".onion"))

      uri = torAddrOpt.getOrElse(info.uris.head)
    }
  }

  val invoiceDAO: InvoiceDAO = InvoiceDAO()

  // The URL to the request.  You can call this directly from the template, but it
  // can be more convenient to leave the template completely stateless i.e. all
  // of the "Controller" references are inside the .scala file.
  private val postUrl = routes.Controller.createRequest

  private val recentTransactions: ArrayBuffer[DoubleSha256DigestBE] = {
    val f = startF.flatMap(_ => invoiceDAO.lastFiveCompleted())
    val res = Await.result(f, 30.seconds)
    mutable.ArrayBuffer[DoubleSha256DigestBE]().addAll(res)
  }

  final val onionAddr =
    "http://opreturnqfd4qdv745xy6ncwvogbtxddttqkqkp5gipby6uytzpxwzqd.onion"

  private val telegramHandler = new TelegramHandler(this)

  val invoiceMonitor =
    new InvoiceMonitor(lnd,
                       pubkeyRotator,
                       Some(telegramHandler),
                       recentTransactions)

  startF.map { _ =>
    setURI()
    telegramHandler.start()
    invoiceMonitor.startSubscription()
    startOnionMessageSubscription()
  }

  def index: Action[AnyContent] = {
    Action { implicit request: MessagesRequest[AnyContent] =>
      // Pass an unpopulated form to the template
      Ok(
        views.html
          .index(recentTransactions.toSeq, opReturnRequestForm, postUrl))
        .withHeaders(("Onion-Location", onionAddr))
    }
  }

  def connect: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      if (uri == uriErrorString) {
        setURI().map { _ =>
          Ok(views.html.connect(uri, invoiceMonitor.pubKey))
            .withHeaders(("Onion-Location", onionAddr))
        }
      } else {
        Future.successful(
          Ok(views.html.connect(uri, invoiceMonitor.pubKey))
            .withHeaders(("Onion-Location", onionAddr)))
      }
    }
  }

  def hello: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      invoiceMonitor.sendNostrMessage("Just setting up my nostr").map { id =>
        Ok(JsString(id.hex))
      }
    }
  }

  def nip5: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      val json = Json.obj(
        "name" -> JsArray(Seq(Json.obj(
          "me" -> invoiceMonitor.pubKey.hex,
          "opreturnbot" -> invoiceMonitor.pubKey.hex,
          "op_return_bot" -> invoiceMonitor.pubKey.hex,
          "OP_RETURN bot" -> invoiceMonitor.pubKey.hex,
          "OP_RETURN Bot" -> invoiceMonitor.pubKey.hex
        ))))

      Future.successful(Ok(json))
    }
  }

  def getLnurlPay(user: String): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      val proto = if (request.secure) "https" else "http"

      val metadata =
        s"[[\"text/plain\",\"A donation to ben!\"],[\"text/identifier\",\"$user@${request.host}\"]]"
      val hash = CryptoUtil.sha256(ByteVector(metadata.getBytes("UTF-8"))).hex

      val response =
        LnURLPayResponse(
          callback = new URL(s"$proto://${request.host}/lnurlp/$hash"),
          maxSendable = MilliSatoshis(Bitcoins.one),
          minSendable = MilliSatoshis(Satoshis.one),
          metadata = metadata
        )

      Future.successful(Ok(Json.toJson(response)))
    }
  }

  def lnurlPay(meta: String): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      request.getQueryString("amount") match {
        case Some(amountStr) =>
          val amount = MilliSatoshis(amountStr.toLong)
          val hash = Sha256Digest(meta)

          lnd.addInvoice(hash, amount, 360).map { invoice =>
            val response = LnURLPayInvoice(invoice.invoice, None)
            Ok(Json.toJson(response))
          }
        case None =>
          val error =
            Json.obj("status" -> "ERROR", "reason" -> "no amount given")
          Future.successful(BadRequest(error))
      }
    }
  }

  def viewMessage(txIdStr: String): Action[AnyContent] = {
    Action.async { _ =>
      val txId = DoubleSha256DigestBE(txIdStr)
      invoiceDAO.findByTxId(txId).map {
        case None =>
          BadRequest("Tx does not originate from OP_RETURN Bot")
        case Some(invoiceDb: InvoiceDb) =>
          Ok(invoiceDb.message)
      }
    }
  }

  def invoiceStatus(rHash: String): Action[AnyContent] = {
    Action.async { _ =>
      val hash = Sha256Digest.fromHex(rHash)
      invoiceDAO.read(hash).map {
        case None =>
          BadRequest("Invoice not from OP_RETURN Bot")
        case Some(invoiceDb) =>
          invoiceDb.txIdOpt match {
            case Some(txId) => Ok(txId.hex)
            case None       => BadRequest("Invoice has not been paid")
          }
      }
    }
  }

  def invoice(invoiceStr: String): Action[AnyContent] =
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      LnInvoice.fromStringT(invoiceStr) match {
        case Failure(exception) =>
          logger.error(exception)
          Future.successful(
            BadRequest(views.html
              .index(recentTransactions.toSeq, opReturnRequestForm, postUrl)))
        case Success(invoice) =>
          invoiceDAO.read(invoice.lnTags.paymentHash.hash).map {
            case None =>
              BadRequest("Invoice not from OP_RETURN Bot")
            case Some(invoiceDb) =>
              invoiceDb.txIdOpt match {
                case Some(txId) => Redirect(routes.Controller.success(txId.hex))
                case None =>
                  Ok(views.html.showInvoice(invoiceDb.message, invoice))
              }
          }
      }
    }

  def success(txIdStr: String): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      Try(DoubleSha256DigestBE.fromHex(txIdStr)) match {
        case Failure(exception) =>
          logger.error(exception)
          Future.successful(
            BadRequest(views.html
              .index(recentTransactions.toSeq, opReturnRequestForm, postUrl)))
        case Success(txId) =>
          invoiceDAO.findByTxId(txId).map {
            case None =>
              BadRequest(views.html
                .index(recentTransactions.toSeq, opReturnRequestForm, postUrl))
            case Some(invoiceDb) =>
              invoiceDb.txOpt match {
                case Some(tx) => Ok(views.html.success(tx))
                case None =>
                  throw new RuntimeException(
                    s"This is impossible, ${invoiceDb.invoice}")
              }
          }
      }
    }
  }

  def publishTransaction(txHex: String): Action[AnyContent] = {
    Try(Transaction.fromHex(txHex)) match {
      case Failure(exception) =>
        Action { implicit request: MessagesRequest[AnyContent] =>
          BadRequest(exception.getMessage)
        }
      case Success(tx) => publishTransaction(tx)
    }
  }

  def publishTransaction(tx: Transaction): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      lnd.publishTransaction(tx).map {
        case Some(error) => BadRequest(error)
        case None        => Ok(tx.txIdBE.hex)
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
          invoiceDb <- invoiceMonitor.processMessage(input.message,
                                                     input.noTwitter,
                                                     None,
                                                     None)
        } yield {
          Ok(invoiceDb.invoice.toString())
        }
      }

      opReturnRequestForm.bindFromRequest().fold(failure, success)
    }
  }

  // This will be the action that handles our form post
  def createRequest: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      val errorFunction: Form[OpReturnRequest] => Future[Result] = {
        formWithErrors: Form[OpReturnRequest] =>
          // This is the bad case, where the form had validation errors.
          // Let's show the user the form again, with the errors highlighted.
          // Note how we pass the form with errors to the template.
          logger.warn(
            "From with errors: " + formWithErrors.errors.mkString(
              " ") + s"\n${formWithErrors.data}")

          Future.successful(
            BadRequest(views.html
              .index(recentTransactions.toSeq, formWithErrors, postUrl)))
      }

      // This is the good case, where the form was successfully parsed as an OpReturnRequest
      val successFunction: OpReturnRequest => Future[Result] = {
        data: OpReturnRequest =>
          invoiceMonitor
            .processMessage(data.message, data.noTwitter, None, None)
            .map { invoiceDb =>
              Redirect(routes.Controller.invoice(invoiceDb.invoice.toString()))
            }
      }

      val formValidationResult = opReturnRequestForm.bindFromRequest()
      formValidationResult.fold(errorFunction, successFunction)
    }
  }
}
