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
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnurl.json.LnURLJsonModels._
import org.scalastr.core.NostrEvent
import play.api.data._
import play.api.libs.json._
import play.api.mvc._
import scodec.bits.ByteVector
import slick.dbio.DBIOAction

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

  val opReturnDAO: OpReturnRequestDAO = OpReturnRequestDAO()
  val invoiceDAO: InvoiceDAO = InvoiceDAO()
  val onChainDAO: OnChainPaymentDAO = OnChainPaymentDAO()
  val nip5DAO: Nip5DAO = Nip5DAO()
  val zapDAO: ZapDAO = ZapDAO()

  // The URL to the request.  You can call this directly from the template, but it
  // can be more convenient to leave the template completely stateless i.e. all
  // of the "Controller" references are inside the .scala file.
  private val postUrl = routes.Controller.createRequest

  private val recentTransactions: ArrayBuffer[DoubleSha256DigestBE] = {
    val f = startF.flatMap(_ => opReturnDAO.lastFiveCompleted())
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
    val _ = invoiceMonitor.startSubscription()
    val _ = invoiceMonitor.startTxSubscription()
    val _ = invoiceMonitor.startBlockSubscription()
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

  def lnurlPay(meta: String): Action[AnyContent] = {
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
                           noteId = None,
                           time = TimeUtil.currentEpochSecond)
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
      val res = opReturnDAO.findByTxId(txId).map {
        case None =>
          BadRequest("Tx does not originate from OP_RETURN Bot")
        case Some(db) =>
          Ok(db.getMessage)
      }

      res.map(
        _.withHeaders(
          "Access-Control-Allow-Origin" -> "*",
          "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
          "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
          "Access-Control-Allow-Credentials" -> "true"
        ))
    }
  }

  def invoiceStatus(rHash: String): Action[AnyContent] = {
    Action.async { _ =>
      val hash = Sha256Digest
        .fromHexT(rHash)
        .getOrElse(LnInvoice.fromString(rHash).lnTags.paymentHash.hash)

      val action = for {
        opt <- invoiceDAO.findOpReturnRequestByRHashAction(hash)
        res <- opt match {
          case None => DBIOAction.successful(None)
          case Some((invoiceDb, requestDb)) =>
            onChainDAO
              .findByOpReturnRequestIdAction(invoiceDb.opReturnRequestId)
              .map(o => Some((o, invoiceDb, requestDb)))
        }
      } yield res

      val res = invoiceDAO.safeDatabase.run(action).map {
        case None =>
          BadRequest("Invoice not from OP_RETURN Bot")
        case Some((onChainOpt, invoiceDb, requestDb)) =>
          requestDb.txIdOpt match {
            case Some(txId) => Ok(txId.hex)
            case None =>
              if (invoiceDb.paid) {
                Ok("null")
              } else {
                onChainOpt match {
                  case Some(onChain) =>
                    if (onChain.txid.isDefined) {
                      Ok("null")
                    } else {
                      BadRequest("Invoice has not been paid")
                    }
                  case None => BadRequest("Invoice has not been paid")
                }
              }
          }
      }

      res.map(
        _.withHeaders(
          "Access-Control-Allow-Origin" -> "*",
          "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
          "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
          "Access-Control-Allow-Credentials" -> "true"
        ))
    }
  }

  def invoice(invoiceStr: String): Action[AnyContent] =
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      val hashT = Sha256Digest
        .fromHexT(invoiceStr)
        .orElse(
          LnInvoice.fromStringT(invoiceStr).map(_.lnTags.paymentHash.hash))

      hashT match {
        case Failure(exception) =>
          logger.error(exception)
          Future.successful(
            BadRequest(views.html
              .index(recentTransactions.toSeq, opReturnRequestForm, postUrl)))
        case Success(hash) =>
          val action = for {
            opt <- invoiceDAO.findOpReturnRequestByRHashAction(hash)
            res <- opt match {
              case None => DBIOAction.successful(None)
              case Some((invoiceDb, requestDb)) =>
                onChainDAO
                  .findByOpReturnRequestIdAction(invoiceDb.opReturnRequestId)
                  .map(o => Some((o, invoiceDb, requestDb)))
            }
          } yield res

          opReturnDAO.safeDatabase
            .run(action)
            .map {
              case None =>
                BadRequest("Invoice not from OP_RETURN Bot")
              case Some((onChainOpt, invoiceDb, requestDb)) =>
                requestDb.txIdOpt match {
                  case Some(txId) =>
                    Redirect(routes.Controller.success(txId.hex))
                  case None =>
                    if (invoiceDb.paid) {
                      Ok(views.html.pending())
                    } else {
                      onChainOpt match {
                        case None =>
                          Ok(
                            views.html.showInvoice(requestDb.getMessage,
                                                   invoiceDb.invoice))
                        case Some(onChain) =>
                          val unified = createUnifiedAddr(onChain, invoiceDb)
                          Ok(
                            views.html.showUnified(requestDb.getMessage,
                                                   invoiceDb.rHash.hex,
                                                   unified))
                      }
                    }
                }
            }
      }
    }

  private def createUnifiedAddr(
      onChainDb: OnChainPaymentDb,
      invoiceDb: InvoiceDb): String = {
    val btc = Bitcoins(onChainDb.expectedAmount.satoshis)
    s"bitcoin:${onChainDb.address}?amount=${btc.decimalString}&lightning=${invoiceDb.invoice}".toUpperCase
  }

  def success(rHashStr: String): Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      Try(Sha256Digest.fromHex(rHashStr)) match {
        case Failure(exception) =>
          logger.error(exception)
          Future.successful(
            BadRequest(views.html
              .index(recentTransactions.toSeq, opReturnRequestForm, postUrl)))
        case Success(rHash) =>
          invoiceDAO.findOpReturnRequestByRHash(rHash).map {
            case None =>
              BadRequest(views.html
                .index(recentTransactions.toSeq, opReturnRequestForm, postUrl))
            case Some((invoiceDb, requestDb)) =>
              requestDb.txIdOpt match {
                case Some(txId) =>
                  Ok(
                    views.html.success(txId,
                                       requestDb.messageBytes.length > 80))
                case None =>
                  if (invoiceDb.paid) {
                    Ok(views.html.pending())
                  } else {
                    BadRequest(
                      views.html
                        .index(recentTransactions.toSeq,
                               opReturnRequestForm,
                               postUrl))
                  }
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

  def mempoolLimit: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      Future.successful(
        if (invoiceMonitor.mempoolLimit) {
          Ok("true")
        } else {
          Ok("false")
        }
      )
    }
  }

  def create: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      def failure(badForm: Form[OpReturnRequest]): Future[Result] = {
        Future.successful(BadRequest(badForm.errorsAsJson))
      }

      def success(input: OpReturnRequest): Future[Result] = {
        for {
          (invoiceDb, _) <- invoiceMonitor.createInvoice(
            message = ByteVector(input.message.getBytes("UTF-8")),
            noTwitter = input.noTwitter,
            nodeIdOpt = None,
            telegramId = None,
            nostrKey = None,
            dvmEvent = None)
        } yield {
          Ok(invoiceDb.invoice.toString()).withHeaders(
            "Access-Control-Allow-Origin" -> "*",
            "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
            "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
            "Access-Control-Allow-Credentials" -> "true"
          )
        }
      }

      opReturnRequestForm.bindFromRequest().fold(failure, success)
    }
  }

  def createUnified: Action[AnyContent] = {
    Action.async { implicit request: MessagesRequest[AnyContent] =>
      def failure(badForm: Form[OpReturnRequest]): Future[Result] = {
        Future.successful(BadRequest(badForm.errorsAsJson))
      }

      def success(input: OpReturnRequest): Future[Result] = {
        for {
          (invoiceDb, onChainDb, _) <- invoiceMonitor.createUnified(
            message = ByteVector(input.message.getBytes("UTF-8")),
            noTwitter = input.noTwitter)
        } yield {
          val unified = createUnifiedAddr(onChainDb, invoiceDb)
          val json = Json.obj(
            "address" -> Json.toJson(onChainDb.address.toString()),
            "invoice" -> Json.toJson(invoiceDb.invoice.toString()),
            "amountBtc" -> Json.toJson(
              Bitcoins(onChainDb.expectedAmount.satoshis).decimalString),
            "rHash" -> Json.toJson(invoiceDb.rHash.hex),
            "paymentString" -> Json.toJson(unified)
          )
          Ok(json).withHeaders(
            "Access-Control-Allow-Origin" -> "*",
            "Access-Control-Allow-Methods" -> "OPTIONS, GET, POST, PUT, DELETE, HEAD",
            "Access-Control-Allow-Headers" -> "Accept, Content-Type, Origin, X-Json, X-Prototype-Version, X-Requested-With",
            "Access-Control-Allow-Credentials" -> "true"
          )
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
            .createUnified(message = ByteVector(data.message.getBytes("UTF-8")),
                           noTwitter = data.noTwitter)
            .map { case (invoiceDb, _, _) =>
              Redirect(routes.Controller.invoice(invoiceDb.rHash.hex))
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
            .map { case (invoiceDb, _) =>
              Redirect(routes.Controller.invoice(invoiceDb.rHash.hex))
            }
      }

      val formValidationResult = nip5RequestForm.bindFromRequest()
      formValidationResult.fold(errorFunction, successFunction)
    }
  }
}
