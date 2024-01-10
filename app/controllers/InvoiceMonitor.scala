package controllers

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import config.OpReturnBotAppConfig
import controllers.OpReturnBotTLV.BroadcastTransactionTLV
import grizzled.slf4j.Logging
import lnrpc.Invoice
import models._
import org.bitcoins.core.config.MainNet
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.ln.LnTag._
import org.bitcoins.core.protocol.ln._
import org.bitcoins.core.protocol.ln.currency._
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.{Transaction, TransactionOutput}
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.script.control.OP_RETURN
import org.bitcoins.core.util.{BitcoinScriptUtil, TimeUtil}
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.esplora.{EsploraClient, MempoolSpaceEsploraSite}
import org.bitcoins.feeprovider.MempoolSpaceTarget.FastestFeeTarget
import org.bitcoins.feeprovider._
import org.bitcoins.lnd.rpc.{LndRpcClient, LndUtils}
import org.scalastr.core._
import play.api.libs.json._
import scodec.bits._
import signrpc.TxOut
import slick.dbio.{DBIO, DBIOAction}
import walletrpc.SendOutputsRequest

import scala.collection.mutable.ArrayBuffer
import scala.concurrent._
import scala.util.Try

class InvoiceMonitor(
    val lnd: LndRpcClient,
    telegramHandlerOpt: Option[TelegramHandler],
    recentTransactions: ArrayBuffer[DoubleSha256DigestBE])(implicit
    val system: ActorSystem,
    val config: OpReturnBotAppConfig)
    extends Logging
    with LndUtils
    with NostrHandler
    with TwitterHandler {
  import system.dispatcher

  val feeProvider: MempoolSpaceProvider =
    MempoolSpaceProvider(FastestFeeTarget, MainNet, None)

  val feeProviderBackup: BitcoinerLiveFeeRateProvider =
    BitcoinerLiveFeeRateProvider(30, None)

  val invoiceDAO: InvoiceDAO = InvoiceDAO()
  val zapDAO: ZapDAO = ZapDAO()
  val nip5DAO: Nip5DAO = Nip5DAO()

  val esplora = new EsploraClient(MempoolSpaceEsploraSite(MainNet), None)

  def startSubscription(): Unit = {
    val parallelism = Runtime.getRuntime.availableProcessors()

    lnd
      .subscribeInvoices()
      .mapAsyncUnordered(parallelism) { invoice =>
        invoice.state match {
          case lnrpc.Invoice.InvoiceState.OPEN |
              lnrpc.Invoice.InvoiceState.ACCEPTED |
              _: lnrpc.Invoice.InvoiceState.Unrecognized =>
            Future.unit
          case lnrpc.Invoice.InvoiceState.CANCELED =>
            val action = invoiceDAO
              .findByPrimaryKeyAction(Sha256Digest(invoice.rHash))
              .flatMap {
                case None => DBIOAction.successful(())
                case Some(invoiceDb) =>
                  invoiceDAO.updateAction(invoiceDb.copy(closed = true))
              }

            invoiceDAO.safeDatabase.run(action)
          case lnrpc.Invoice.InvoiceState.SETTLED =>
            val rHash = Sha256Digest(invoice.rHash)
            val readAction = for {
              invOpt <- invoiceDAO.findByPrimaryKeyAction(rHash)
              npubOpt <- nip5DAO
                .findByPrimaryKeyAction(rHash)
                .map(_.map(_.publicKey))
              zapOpt <- zapDAO.findByPrimaryKeyAction(rHash)
            } yield (invOpt, npubOpt, zapOpt)

            invoiceDAO.safeDatabase.run(readAction).flatMap {
              case (invOpt, npubOpt, zapOpt) =>
                (invOpt, zapOpt) match {
                  case (None, None) =>
                    logger.warn(
                      s"Processed invoice not from OP_RETURN Bot, ${invoice.rHash.toBase16}")
                    Future.unit
                  case (Some(_), Some(_)) =>
                    throw new RuntimeException(
                      "Invoice found in op_return and zap tables??")
                  case (Some(invoiceDb), None) =>
                    invoiceDb.txIdOpt match {
                      case Some(_) =>
                        logger.warn(
                          s"Processed invoice that already has a tx associated with it, rHash: ${invoice.rHash.toBase16}")
                        Future.unit
                      case None =>
                        require(invoice.amtPaidMsat >= invoice.valueMsat,
                                "User did not pay invoice in full")
                        onInvoicePaid(invoiceDb, npubOpt).map(_ => ())
                    }
                  case (None, Some(zapDb)) =>
                    zapDb.noteId match {
                      case Some(_) =>
                        logger.warn(
                          s"Processed zap that already has a note associated with it, rHash: ${invoice.rHash.toBase16}")
                        Future.unit
                      case None =>
                        require(invoice.amtPaidMsat >= invoice.valueMsat,
                                "User did not pay invoice in full")
                        onZapPaid(zapDb, invoice.rPreimage).map(_ => ())
                    }
                }
            }
        }
      }
      .runWith(Sink.ignore)
    ()
  }

  def processUnhandledInvoices(): Future[Vector[InvoiceDb]] = {
    invoiceDAO.findUnclosed().flatMap { unclosed =>
      if (unclosed.nonEmpty) {
        val time = System.currentTimeMillis()
        logger.info(s"Processing ${unclosed.size} unhandled invoices")

        val updateFs = unclosed.map { db =>
          if (db.txOpt.isDefined) Future.successful(db.copy(closed = true))
          else {
            lnd
              .lookupInvoice(PaymentHashTag(db.rHash))
              .flatMap { inv =>
                inv.state match {
                  case Invoice.InvoiceState.OPEN |
                      Invoice.InvoiceState.ACCEPTED =>
                    Future.successful(db)
                  case Invoice.InvoiceState.SETTLED =>
                    if (inv.amtPaidMsat >= inv.valueMsat) {
                      nip5DAO.read(db.rHash).flatMap { nip5Opt =>
                        onInvoicePaid(db, nip5Opt.map(_.publicKey))
                      }
                    } else Future.successful(db.copy(closed = true))
                  case Invoice.InvoiceState.CANCELED =>
                    Future.successful(db.copy(closed = false))
                  case Invoice.InvoiceState.Unrecognized(_) =>
                    Future.successful(db)
                }
              }
              .recover { case _: Throwable => db.copy(closed = true) }
          }
        }

        val f = for {
          updates <- Future.sequence(updateFs)
          dbs <- invoiceDAO.updateAll(updates)
          took = System.currentTimeMillis() - time
          _ = logger.info(
            s"Processed ${dbs.size} unhandled invoices, took $took ms")
        } yield dbs

        f.failed.map(logger.error("Error processing unhandled invoices", _))

        f
      } else Future.successful(Vector.empty)
    }
  }

  protected def createFakeInvoice(
      msats: MilliSatoshis,
      preimage: ByteVector,
      descHash: Sha256Digest): LnInvoice = {
    val hash = CryptoUtil.sha256(preimage)
    val paymentSecret = CryptoUtil.randomBytes(32)

    val hashTag = PaymentHashTag(hash)
    val memoTag = DescriptionHashTag(descHash)
    val expiryTimeTag = ExpiryTimeTag(UInt32(360))
    val paymentSecretTag = SecretTag(PaymentSecret(paymentSecret))
    val featuresTag = FeaturesTag(hex"2420") // copied from a LND invoice

    val lnTags = LnTaggedFields(
      Vector(hashTag, memoTag, expiryTimeTag, paymentSecretTag, featuresTag))

    LnInvoice.build(
      LnHumanReadablePart(MainNet, LnCurrencyUnits.fromMSat(msats)),
      lnTags,
      ECPrivateKey.freshPrivateKey)
  }

  def onZapPaid(zapDb: ZapDb, preimage: ByteVector): Future[ZapDb] = {
    val privateKey = if (zapDb.myKey == nostrPubKey) {
      nostrPrivateKey
    } else if (config.extraNostrPubKey.contains(zapDb.myKey)) {
      config.extraNostrPrivKey.get.key
    } else throw new RuntimeException("Do not have the private key")

    val requestEvent = zapDb.requestEvent

    val eTag =
      requestEvent.tags.filter(_.value.head.asOpt[String].contains("e"))

    val pTag =
      requestEvent.tags.filter(_.value.head.asOpt[String].contains("p"))

    val invoice = createFakeInvoice(
      zapDb.amount,
      preimage,
      zapDb.invoice.lnTags.descriptionHash.get.hash)

    val tags = Vector(
      Json.arr("bolt11", invoice.toString),
      Json.arr("preimage", preimage.toHex),
      Json.arr("description", zapDb.request),
      Json.arr("P", zapDb.requestEvent.pubkey.hex)
    ) ++ eTag ++ pTag

    val zapEvent =
      NostrEvent.build(privateKey,
                       TimeUtil.currentEpochSecond,
                       NostrKind.Zap,
                       tags,
                       "")

    logger.info(s"Zap event created: ${Json.toJson(zapEvent).toString}")

    val relays = (requestEvent.taggedRelays ++ config.allRelays).distinct

    val sendF = sendNostrEvents(Vector(requestEvent, zapEvent), relays)
    val updatedDb = zapDb.copy(noteId = Some(zapEvent.id))
    val dbF = zapDAO.update(updatedDb)
    val telegramF =
      telegramHandlerOpt.map(_.handleZap(updatedDb)).getOrElse(Future.unit)

    for {
      _ <- sendF
      _ <- telegramF
      res <- dbF
    } yield res
  }

  def onInvoicePaid(
      invoiceDb: InvoiceDb,
      npubOpt: Option[SchnorrPublicKey]): Future[InvoiceDb] = {
    val message = invoiceDb.message
    val invoice = invoiceDb.invoice
    val feeRate = invoiceDb.feeRate
    val noTwitter = invoiceDb.noTwitter
    val rHash = PaymentHashTag(invoiceDb.rHash)

    logger.info(s"Received ${invoice.amount.get.toSatoshis}!")

    val output = {
      val messageBytes = ByteVector(message.getBytes)

      val asm = OP_RETURN +: BitcoinScriptUtil.calculatePushOp(
        messageBytes) :+ ScriptConstant(messageBytes)

      val scriptPubKey = ScriptPubKey(asm.toVector)

      TransactionOutput(Satoshis.zero, scriptPubKey)
    }

    val txOut =
      TxOut(output.value.satoshis.toLong, output.scriptPubKey.asmBytes)

    val request: SendOutputsRequest = SendOutputsRequest(
      satPerKw = feeRate.toSatoshisPerKW.toLong,
      outputs = Vector(txOut),
      label = s"OP_RETURN Bot: $message",
      spendUnconfirmed = true)

    val createTxF = for {
      transaction <- lnd.sendOutputs(request)
      txId = transaction.txIdBE

      esploraF = esplora.broadcastTransaction(transaction).recover(_ => txId)
      errorOpt <- lnd.publishTransaction(transaction)
      _ <- esploraF

      _ = errorOpt match {
        case Some(error) =>
          logger.error(
            s"Error when broadcasting transaction ${txId.hex}, $error")
        case None =>
          logger.info(s"Successfully created tx: ${txId.hex}")
      }

      txDetailsOpt <- lnd.getTransaction(txId)

      _ = Try {
        recentTransactions += txId
        if (recentTransactions.size >= 5) {
          val old = recentTransactions.takeRight(5)
          recentTransactions.clear()
          recentTransactions ++= old
        }
        logger.info(s"Updated saved tx: ${txId.hex} to in recent txs list")
      }

      amount = invoice.amount.get.toSatoshis
      // Need to make sure we upsert the tx and txid even if this fails, so we can't call .get
      chainFeeOpt = txDetailsOpt.map(_.totalFees)
      profitOpt = txDetailsOpt.map(d => amount - d.totalFees)

      dbWithTx: InvoiceDb = invoiceDb.copy(closed = true,
                                           txOpt = Some(transaction),
                                           txIdOpt = Some(txId),
                                           profitOpt = profitOpt,
                                           chainFeeOpt = chainFeeOpt)

      res <- invoiceDAO.update(dbWithTx)
      _ = logger.info(s"Successfully saved tx: ${txId.hex} to database")

      // send if onion message
      _ <- invoiceDb.nodeIdOpt match {
        case Some(nodeId) =>
          // recover so we can finish accounting
          sendBroadcastTransactionTLV(nodeId, transaction).recover {
            case err: Throwable =>
              logger.error(
                s"Error sending onion message back to nodeId $nodeId",
                err)
          }
        case None => Future.unit
      }

      // send if nostr
      _ <- invoiceDb.nostrKey match {
        case Some(nostrKey) =>
          val message =
            s"""
               |OP_RETURN Created!
               |
               |https://mempool.space/tx/${txId.hex}
               |""".stripMargin

          sendNostrDM(message, nostrKey)
            .map {
              case Some(id) =>
                logger.info(
                  s"Sent nostr DM with id ${NostrNoteId(id)} to ${NostrPublicKey(nostrKey)}")
              case None =>
                logger.error(
                  s"Failed to send nostr DM to ${NostrPublicKey(nostrKey)}")
            }
            // recover so we can finish accounting
            .recover { case err: Throwable =>
              logger.error(
                s"Error sending nostr dm back to ${NostrPublicKey(nostrKey)}",
                err)
            }
        case None => Future.unit
      }

      tweetF =
        if (noTwitter) {
          logger.info("Skipping tweet")
          Future.successful(None)
        } else {
          logger.info("Tweeting...")
          handleTweet(message, txId)
            .map(Some(_))
            .recover { err =>
              logger.error(
                s"Failed to create tweet for invoice ${rHash.hash.hex}, got error $err")
              None
            }
        }

      nostrF =
        if (noTwitter) {
          logger.info("Skipping nostr")
          Future.successful(None)
        } else {
          logger.info("Sending to nostr...")
          announceOnNostr(message, npubOpt, txId)
            .recover { err =>
              logger.error(
                s"Failed to create nostr note for invoice ${rHash.hash.hex}, got error $err")
              None
            }
        }

      _ <- {
        val telegramF = txDetailsOpt match {
          case Some(details) =>
            val userTelegramF = invoiceDb.telegramIdOpt
              .flatMap(telegramId =>
                telegramHandlerOpt.map(
                  _.handleTelegramUserPurchase(telegramId, details)))
              .getOrElse(Future.unit)

            lazy val action = for {
              profit <- invoiceDAO.totalProfitAction()
              chainFees <- invoiceDAO.totalChainFeesAction()
            } yield (profit, chainFees)

            val accountingTelegramF = telegramHandlerOpt
              .map { handler =>
                for {
                  (profit, chainFees) <- invoiceDAO.safeDatabase.run(action)
                  tweetOpt <- tweetF
                  nostrOpt <- nostrF
                  _ <- handler.handleTelegram(
                    rHash = rHash.hash,
                    invoice = invoice,
                    invoiceDb = res,
                    tweetOpt = tweetOpt,
                    nostrOpt = nostrOpt,
                    message = message,
                    feeRate = feeRate,
                    txDetails = details,
                    totalProfit = profit,
                    totalChainFees = chainFees
                  )
                } yield ()
              }
              .getOrElse(Future.unit)

            for {
              _ <- userTelegramF
              _ <- accountingTelegramF
            } yield ()
          case None =>
            val msg =
              s"Failed to get transaction details for ${rHash.hash.hex}\n" +
                s"Transaction (${txId.hex}): ${transaction.hex}"
            logger.warn(msg)

            telegramHandlerOpt
              .map(_.sendTelegramMessage(msg))
              .getOrElse(Future.unit)
        }

        telegramF.recover { err =>
          logger.error(
            s"Failed to send telegram message for invoice ${rHash.hash.hex}, got error $err")
        }
      }
    } yield res

    createTxF.recoverWith { case err: Throwable =>
      logger.error(
        s"Failed to create tx for invoice ${rHash.hash.hex}, got error: ",
        err)
      for {
        _ <- telegramHandlerOpt
          .map(_.sendTelegramMessage(
            s"Failed to create tx for invoice ${rHash.hash.hex}, got error: ${err.getMessage}"))
          .getOrElse(Future.unit)
      } yield invoiceDb
    }
  }

  private def createInvoice(
      message: String,
      noTwitter: Boolean): Future[(LnInvoice, SatoshisPerVirtualByte)] = {
    require(
      message.getBytes.length <= 80,
      "OP_Return message received was too long, must be less than 80 chars")

    fetchFeeRate()
      .flatMap { rate: SatoshisPerVirtualByte =>
        // add 4 so we get better odds of getting in next block
        val feeRate = rate.copy(rate.currencyUnit + Satoshis(4))

        // 125 base tx fee * 2 just in case
        val baseSize = 250
        val messageSize = message.getBytes.length

        // Add fee if no tweet
        val noTwitterFee = if (noTwitter) Satoshis(1000) else Satoshis.zero

        // tx fee + app fee (1337) + twitter fee
        val sats =
          (feeRate * (baseSize + messageSize)) + Satoshis(1337) + noTwitterFee
        val expiry = 60 * 5 // 5 minutes

        val hash = CryptoUtil.sha256(message)

        lnd.addInvoice(hash, sats.satoshis, expiry).map(t => (t.invoice, rate))
      }
  }

  def createInvoice(
      message: String,
      noTwitter: Boolean,
      nodeIdOpt: Option[NodeId],
      telegramId: Option[Long],
      nostrKey: Option[SchnorrPublicKey]): Future[InvoiceDb] = {
    createInvoice(message, noTwitter)
      .flatMap { case (invoice, feeRate) =>
        val db: InvoiceDb =
          InvoiceDb(
            rHash = invoice.lnTags.paymentHash.hash,
            invoice = invoice,
            message = message,
            noTwitter = noTwitter,
            feeRate = feeRate,
            closed = false,
            nodeIdOpt = nodeIdOpt,
            telegramIdOpt = telegramId,
            nostrKey = nostrKey,
            txOpt = None,
            txIdOpt = None,
            profitOpt = None,
            chainFeeOpt = None
          )
        invoiceDAO.create(db)
      }
  }

  private val takenNames = Vector("_",
                                  "me",
                                  "opreturnbot",
                                  "op_return_bot",
                                  "OP_RETURN bot",
                                  "OP_RETURN Bot") ++ config.bannedWords

  def createNip5Invoice(
      name: String,
      publicKey: NostrPublicKey): Future[InvoiceDb] = {
    if (takenNames.contains(name)) {
      Future.failed(
        new IllegalArgumentException(s"Cannot create invoice for NIP-05 $name"))
    } else {
      val message = s"nip5:$name:${publicKey.hex}"

      createInvoice(message, noTwitter = false)
        .flatMap { case (invoice, feeRate) =>
          val db: InvoiceDb =
            InvoiceDb(
              rHash = invoice.lnTags.paymentHash.hash,
              invoice = invoice,
              message = message,
              noTwitter = false,
              feeRate = feeRate,
              closed = false,
              nodeIdOpt = None,
              telegramIdOpt = None,
              nostrKey = None,
              txOpt = None,
              txIdOpt = None,
              profitOpt = None,
              chainFeeOpt = None
            )

          val action = nip5DAO.getPublicKeyAction(name).flatMap {
            case Some(key) =>
              val nostrKey = NostrPublicKey(key)
              DBIO.failed(new IllegalArgumentException(
                s"Cannot create invoice for NIP-05 $name, already exists for key $nostrKey"))
            case None =>
              for {
                db <- invoiceDAO.createAction(db)
                _ <- nip5DAO.createAction(Nip5Db(db.rHash, name, publicKey.key))
              } yield db
          }

          invoiceDAO.safeDatabase.run(action)
        }
    }
  }

  private def fetchFeeRate(): Future[SatoshisPerVirtualByte] = {
    feeProvider.getFeeRate().recoverWith { case _: Throwable =>
      feeProviderBackup.getFeeRate()
    }
  }

  def sendBroadcastTransactionTLV(
      nodeId: NodeId,
      tx: Transaction): Future[Unit] = {
    val tlv = BroadcastTransactionTLV(tx)
    lnd.sendCustomMessage(nodeId, tlv.toUnknownTLV)
  }
}
