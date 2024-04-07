package controllers

import akka.actor.ActorSystem
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import config.OpReturnBotAppConfig
import grizzled.slf4j.Logging
import models._
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnurl.json.LnURLJsonModels._
import org.scalastr.core.NostrEvent
import play.api.data._
import play.api.libs.json._
import play.api.mvc._
import scodec.bits.ByteVector

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.{URL, URLDecoder}
import javax.imageio.ImageIO
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

  lazy val lnd: LndRpcClient = config.lndRpcClient

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
  val nip5DAO: Nip5DAO = Nip5DAO()
  val zapDAO: ZapDAO = ZapDAO()

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

  lazy val invoiceMonitor =
    new InvoiceMonitor(lnd, Some(telegramHandler), recentTransactions)

  startF.map { _ =>
    setURI()
    telegramHandler.start()
    invoiceMonitor.startSubscription()
    invoiceMonitor.setNostrMetadata()
    invoiceMonitor.listenForDMs()
  }

  def notFound(route: String): Action[AnyContent] = {
    Action { implicit request: MessagesRequest[AnyContent] =>
      NotFound(views.html.notFound())
        .withHeaders(("Onion-Location", onionAddr))
    }
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

  def createNip5: Action[AnyContent] = {
    Action { implicit request: MessagesRequest[AnyContent] =>
      // Pass an unpopulated form to the template
      Ok(views.html.createNip5(nip5RequestForm))
        .withHeaders(("Onion-Location", onionAddr))
    }
  }

  def connect: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      if (uri == uriErrorString) {
        setURI().map { _ =>
          Ok(views.html.connect(uri, invoiceMonitor.nostrPubKey))
            .withHeaders(("Onion-Location", onionAddr))
        }
      } else {
        Future.successful(
          Ok(views.html.connect(uri, invoiceMonitor.nostrPubKey))
            .withHeaders(("Onion-Location", onionAddr)))
      }
    }
  }

  private val defaultNip5: JsObject = Json.obj(
    "names" -> Json.obj(
      "_" -> invoiceMonitor.nostrPubKey.hex,
      "me" -> invoiceMonitor.nostrPubKey.hex,
      "opreturnbot" -> invoiceMonitor.nostrPubKey.hex,
      "op_return_bot" -> invoiceMonitor.nostrPubKey.hex,
      "OP_RETURN bot" -> invoiceMonitor.nostrPubKey.hex,
      "OP_RETURN Bot" -> invoiceMonitor.nostrPubKey.hex
    ))

  def nip5(name: Option[String]): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      val jsonF = name match {
        case Some(value) =>
          nip5DAO.getPublicKey(value).map {
            case Some(pubkey) =>
              Json.obj("names" -> Json.obj(value -> pubkey.hex))
            case None => defaultNip5
          }
        case None => Future.successful(defaultNip5)
      }

      jsonF.map { json =>
        Ok(json)
          .withHeaders(("Onion-Location", onionAddr))
          .withHeaders(
            "Access-Control-Allow-Origin" -> "*",
            "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
            "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
            "Access-Control-Allow-Credentials" -> "true"
          )

      }
    }
  }

  def getLnurlPay(user: String): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      val metadata =
        s"[[\"text/plain\",\"A donation to ben!\"],[\"text/identifier\",\"$user@opreturnbot.com\"]]"
      val hash = CryptoUtil.sha256(ByteVector(metadata.getBytes("UTF-8"))).hex

      val url =
        new URL(s"https://opreturnbot.com/lnurlp/$hash?user=$user")

      val response =
        LnURLPayResponse(
          callback = url,
          maxSendable = MilliSatoshis(Bitcoins.one),
          minSendable = MilliSatoshis(Satoshis.one),
          metadata = metadata,
          nostrPubkey = Some(invoiceMonitor.nostrPubKey),
          allowsNostr = Some(true)
        )

      val result = Ok(Json.toJson(response)).withHeaders(
        "Access-Control-Allow-Origin" -> "*",
        "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
        "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
        "Access-Control-Allow-Credentials" -> "true"
      )

      Future.successful(result)
    }
  }

  def lnurlPay(meta: String, user: Option[String]): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      request.getQueryString("amount") match {
        case Some(amountStr) =>
          val amount = MilliSatoshis(amountStr.toLong)

          request.getQueryString("nostr") match {
            case Some(eventStr) =>
              logger.info("Receiving zap request!")
              // nostr zap
              val (_, decoded) = Try {
                val decoded = URLDecoder.decode(eventStr, "UTF-8")
                val event = Json.parse(decoded).as[NostrEvent]
                (event, decoded)
              }.getOrElse {
                val event = Json.parse(eventStr).as[NostrEvent]
                (event, eventStr)
              }

              val hash = CryptoUtil.sha256(decoded)
              val myKey = invoiceMonitor.nostrPubKey

              for {
                invoice <- lnd.addInvoice(hash, amount, 86400)
                db = ZapDb(rHash = invoice.rHash.hash,
                           invoice = invoice.invoice,
                           myKey = myKey,
                           amount = amount,
                           request = decoded,
                           noteId = None)
                _ <- zapDAO.create(db)
              } yield {
                val response = LnURLPayInvoice(invoice.invoice, None)
                Ok(Json.toJson(response)).withHeaders(
                  "Access-Control-Allow-Origin" -> "*",
                  "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
                  "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
                  "Access-Control-Allow-Credentials" -> "true"
                )
              }
            case None =>
              // normal lnurl-pay
              val hash = Sha256Digest(meta)

              lnd.addInvoice(hash, amount, 86400).map { invoice =>
                val response = LnURLPayInvoice(invoice.invoice, None)
                Ok(Json.toJson(response)).withHeaders(
                  "Access-Control-Allow-Origin" -> "*",
                  "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
                  "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
                  "Access-Control-Allow-Credentials" -> "true"
                )
              }
          }
        case None =>
          val error =
            Json.obj("status" -> "ERROR", "reason" -> "no amount given")
          Future.successful(
            BadRequest(error).withHeaders(
              "Access-Control-Allow-Origin" -> "*",
              "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
              "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
              "Access-Control-Allow-Credentials" -> "true"
            ))
      }
    }
  }

  def qrCode(
      string: String,
      widthStr: String,
      heightStr: String): Action[AnyContent] = {
    Action.async { _ =>
      val width = widthStr.toInt
      val height = heightStr.toInt

      val qrCodeWriter = new QRCodeWriter()
      val bitMatrix =
        qrCodeWriter.encode(string, BarcodeFormat.QR_CODE, width, height)
      val qrCodeImage =
        new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
      val graphics = qrCodeImage.createGraphics()
      graphics.setColor(Color.WHITE)
      graphics.fillRect(0, 0, width, height)
      graphics.setColor(Color.BLACK)
      for (x <- 0 until width) {
        for (y <- 0 until height) {
          if (bitMatrix.get(x, y)) {
            graphics.fillRect(x, y, 1, 1)
          }
        }
      }

      val byteArrayOutputStream = new ByteArrayOutputStream()
      ImageIO.write(qrCodeImage, "png", byteArrayOutputStream)
      val qrCodeByteArray = byteArrayOutputStream.toByteArray

      Future.successful(Ok(qrCodeByteArray).as("image/png"))
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
      val hash = Sha256Digest
        .fromHexT(rHash)
        .getOrElse(LnInvoice.fromString(rHash).lnTags.paymentHash.hash)
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
                case Some(tx) =>
                  Ok(
                    views.html.success(tx,
                                       invoiceDb.message.getBytes.length > 80))
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
          invoiceDb <- invoiceMonitor.createInvoice(message = input.message,
                                                    noTwitter = input.noTwitter,
                                                    nodeIdOpt = None,
                                                    telegramId = None,
                                                    nostrKey = None,
                                                    dvmEvent = None)
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
            .createInvoice(data.message, data.noTwitter, None, None, None, None)
            .map { invoiceDb =>
              Redirect(routes.Controller.invoice(invoiceDb.invoice.toString()))
            }
      }

      val formValidationResult = opReturnRequestForm.bindFromRequest()
      formValidationResult.fold(errorFunction, successFunction)
    }
  }

  // This will be the action that handles our form post
  def createNip5Request: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      val errorFunction: Form[Nip5Request] => Future[Result] = {
        formWithErrors: Form[Nip5Request] =>
          // This is the bad case, where the form had validation errors.
          // Let's show the user the form again, with the errors highlighted.
          // Note how we pass the form with errors to the template.
          logger.warn(
            "From with errors: " + formWithErrors.errors.mkString(
              " ") + s"\n${formWithErrors.data}")

          Future.successful(BadRequest(views.html.createNip5(formWithErrors)))
      }

      // This is the good case, where the form was successfully parsed as an OpReturnRequest
      val successFunction: Nip5Request => Future[Result] = {
        data: Nip5Request =>
          invoiceMonitor
            .createNip5Invoice(data.name, data.publicKey)
            .map { invoiceDb =>
              Redirect(routes.Controller.invoice(invoiceDb.invoice.toString))
            }
      }

      val formValidationResult = nip5RequestForm.bindFromRequest()
      formValidationResult.fold(errorFunction, successFunction)
    }
  }
}
