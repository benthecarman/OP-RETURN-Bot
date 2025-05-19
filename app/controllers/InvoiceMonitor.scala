package controllers

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import chainrpc.BlockEpoch
import config.OpReturnBotAppConfig
import grizzled.slf4j.Logging
import lnrpc.Invoice
import models._
import org.bitcoins.core.config.MainNet
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.ln.LnTag._
import org.bitcoins.core.protocol.ln._
import org.bitcoins.core.protocol.ln.currency._
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutPoint
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.script.control.OP_RETURN
import org.bitcoins.core.util.{BitcoinScriptUtil, FutureUtil, TimeUtil}
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

  var mempoolLimit = false

  val slipStreamClient = new SlipStreamClient()

  val feeProvider: MempoolSpaceProvider =
    MempoolSpaceProvider(FastestFeeTarget, MainNet, None)

  val feeProviderBackup: BitcoinerLiveFeeRateProvider =
    BitcoinerLiveFeeRateProvider(30, None)

  val opReturnDAO: OpReturnRequestDAO = OpReturnRequestDAO()
  val invoiceDAO: InvoiceDAO = InvoiceDAO()
  val onChainDAO: OnChainPaymentDAO = OnChainPaymentDAO()
  val zapDAO: ZapDAO = ZapDAO()
  val nip5DAO: Nip5DAO = Nip5DAO()

  val esplora = new EsploraClient(MempoolSpaceEsploraSite(MainNet), None)

  def startBlockSubscription(): Future[Done] = {
    lnd.chainClient
      .registerBlockEpochNtfn(BlockEpoch())
      .mapAsync(1) { _ =>
        if (mempoolLimit) {
          // process some unhandled invoices, lifting the limit
          processUnhandledRequests(Some(500), liftMempoolLimit = true)

          logger.info("Mempool limit lifted, resuming invoices")
          telegramHandlerOpt
            .map(
              _.sendTelegramMessage("Mempool limit lifted, resuming invoices"))
            .getOrElse(Future.unit)
        } else {
          Future.unit
        }
      }
      .runWith(Sink.ignore)
      .flatMap(_ => startBlockSubscription())
      .recoverWith(_ => startBlockSubscription())
  }

  def startTxSubscription(): Future[Done] = {
    lnd
      .subscribeTransactions()
      .mapAsync(1) { txDetails =>
        if (txDetails.numConfirmations > 0) {
          FutureUtil.sequentially(
            txDetails.outputDetails.filter(_.isOurAddress)) { outputDetails =>
            // todo get npub for nip05
            onChainDAO
              .findOpReturnRequestByAddress(outputDetails.addressOpt.get)
              .flatMap {
                case None => Future.unit
                case Some((onchainDb, requestDb)) =>
                  if (
                    onchainDb.expectedAmount <= outputDetails.amount && onchainDb.txid.isEmpty && requestDb.txIdOpt.isEmpty
                  ) {
                    onAddressPaid(onchainDb = onchainDb,
                                  requestDb = requestDb,
                                  amount = outputDetails.amount.satoshis,
                                  txid = txDetails.txId,
                                  npubOpt = None).map(_ => ())
                  } else {
                    logger.warn(
                      s"Received ${outputDetails.amount} for address ${outputDetails.addressOpt.get}, expected ${onchainDb.expectedAmount}")
                    Future.unit
                  }
              }
          }
        } else {
          Future.unit
        }
      }
      .runWith(Sink.ignore)
      .flatMap(_ => startBlockSubscription())
      .recoverWith(_ => startBlockSubscription())
  }

  def startSubscription(): Future[Done] = {
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
              .findOpReturnRequestByRHashAction(Sha256Digest(invoice.rHash))
              .flatMap {
                case None => DBIOAction.successful(())
                case Some((_, request_db)) =>
                  opReturnDAO.updateAction(request_db.copy(closed = true))
              }

            invoiceDAO.safeDatabase.run(action)
          case lnrpc.Invoice.InvoiceState.SETTLED =>
            val rHash = Sha256Digest(invoice.rHash)
            val readAction = for {
              invOpt <- invoiceDAO.findOpReturnRequestByRHashAction(rHash)
              npubOpt <- invOpt.map(_._1.opReturnRequestId) match {
                case Some(id) =>
                  nip5DAO
                    .findByPrimaryKeyAction(id)
                    .map(_.map(_.publicKey))
                case None => DBIOAction.successful(None)
              }
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
                  case (Some((invoiceDb, requestDb)), None) =>
                    requestDb.txIdOpt match {
                      case Some(_) =>
                        logger.warn(
                          s"Processed invoice that already has a tx associated with it, rHash: ${invoice.rHash.toBase16}")
                        Future.unit
                      case None =>
                        require(invoice.amtPaidMsat >= invoice.valueMsat,
                                "User did not pay invoice in full")
                        onInvoicePaid(invoiceDb, requestDb, npubOpt).map(_ =>
                          ())
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
      .flatMap(_ => startSubscription())
      .recoverWith(_ => startSubscription())
  }

  def processUnhandledRequests(
      limit: Option[Int],
      liftMempoolLimit: Boolean): Future[Int] = {
    opReturnDAO.findUnclosed(limit).flatMap { unclosed =>
      if (unclosed.nonEmpty) {
        if (liftMempoolLimit) {
          mempoolLimit = false
        }

        val txsF = for {
          height <- lnd.getInfo.map(_.blockHeight.toInt)
          txs <- lnd.getTransactions((height - 2016) min 0, height)
        } yield txs

        val time = System.currentTimeMillis()
        logger.info(s"Processing ${unclosed.size} unhandled requests")

        def processInvoice(
            invoiceDb: InvoiceDb,
            requestDb: OpReturnRequestDb): Future[(InvoiceDb,
                                                   OpReturnRequestDb)] = {
          if (requestDb.txOpt.isDefined)
            Future.successful((invoiceDb, requestDb.copy(closed = true)))
          else {
            lnd
              .lookupInvoice(PaymentHashTag(invoiceDb.rHash))
              .flatMap { inv =>
                inv.state match {
                  case Invoice.InvoiceState.OPEN |
                      Invoice.InvoiceState.ACCEPTED =>
                    Future.successful((invoiceDb, requestDb))
                  case Invoice.InvoiceState.SETTLED =>
                    if (inv.amtPaidMsat >= inv.valueMsat) {
                      nip5DAO.read(invoiceDb.opReturnRequestId).flatMap {
                        nip5Opt =>
                          onInvoicePaid(invoiceDb,
                                        requestDb,
                                        nip5Opt.map(_.publicKey))
                      }
                    } else
                      Future.successful(
                        (invoiceDb, requestDb.copy(closed = true)))
                  case Invoice.InvoiceState.CANCELED =>
                    Future.successful(
                      (invoiceDb, requestDb.copy(closed = true)))
                  case Invoice.InvoiceState.Unrecognized(_) =>
                    Future.successful((invoiceDb, requestDb))
                }
              }
              .recover { case _: Throwable => (invoiceDb, requestDb) }
          }
        }

        def processTx(
            onChainDb: OnChainPaymentDb,
            requestDb: OpReturnRequestDb): Future[(OnChainPaymentDb,
                                                   OpReturnRequestDb)] = {
          if (requestDb.txOpt.isDefined)
            Future.successful((onChainDb, requestDb.copy(closed = true)))
          else {
            txsF
              .map(_.find(_.outputDetails.exists(
                _.addressOpt.contains(onChainDb.address))))
              .flatMap {
                case None =>
                  // if has been 2 weeks, mark as closed
                  if (
                    requestDb.time + (86400 * 7) < TimeUtil.currentEpochSecond
                  ) {
                    logger.info(
                      s"Closing request ${requestDb.id.get} after 2 weeks")
                    Future.successful(
                      (onChainDb, requestDb.copy(closed = true)))
                  } else {
                    Future.successful((onChainDb, requestDb))
                  }
                case Some(txDetails) =>
                  val amtPaid = txDetails.outputDetails
                    .find(_.addressOpt.contains(onChainDb.address))
                    .map(_.amount)
                    .getOrElse(Satoshis.zero)
                  if (
                    txDetails.numConfirmations > 0 && amtPaid >= onChainDb.expectedAmount
                  ) {
                    nip5DAO.read(onChainDb.opReturnRequestId).flatMap {
                      nip5Opt =>
                        onAddressPaid(onChainDb,
                                      requestDb,
                                      amtPaid.satoshis,
                                      txDetails.txId,
                                      nip5Opt.map(_.publicKey))
                    }
                  } else {
                    logger.warn(
                      s"Received $amtPaid for address ${onChainDb.address}, expected ${onChainDb.expectedAmount}")
                    Future.successful((onChainDb, requestDb))
                  }
              }
              .recover { case _: Throwable => (onChainDb, requestDb) }
          }
        }

        val updateF =
          FutureUtil
            .sequentially(unclosed) { case (req, inv, onChain) =>
              (inv, onChain) match {
                case (None, None) => Future.successful(None)
                case (Some(invDb), None) =>
                  processInvoice(invDb, req).map(t =>
                    Some((t._2, Some(t._1), None)))
                case (None, Some(tx)) =>
                  processTx(tx, req).map(t => Some((t._2, None, Some(t._1))))
                case (Some(invDb), Some(tx)) =>
                  processInvoice(invDb, req).flatMap { case (invDb, req) =>
                    // if its was paid, continue on
                    if (req.txIdOpt.isDefined) {
                      Future.successful(Some((req, Some(invDb), None)))
                    } else { // otherwise try to handle on-chain
                      processTx(tx, req).map(t =>
                        Some((t._2, None, Some(t._1))))
                    }
                  }
              }
            }
            .map(_.flatten)

        val f = for {
          updates <- updateF
          requests = updates.map(_._1)
          invoices = updates.flatMap(_._2)
          onChain = updates.flatMap(_._3)
          action = for {
            _ <- opReturnDAO.updateAllAction(requests)
            _ <- invoiceDAO.updateAllAction(invoices)
            _ <- onChainDAO.updateAllAction(onChain)
          } yield ()
          _ <- invoiceDAO.safeDatabase.run(action)
          took = System.currentTimeMillis() - time
          _ = logger.info(
            s"Processed ${updates.size} unhandled requests, took $took ms")
        } yield updates.size

        f.failed.map(logger.error("Error processing unhandled requests", _))

        f
      } else Future.successful(0)
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
      requestDb: OpReturnRequestDb,
      npubOpt: Option[SchnorrPublicKey]): Future[(InvoiceDb,
                                                  OpReturnRequestDb)] = {
    for {
      db <- invoiceDAO.update(invoiceDb.copy(paid = true))
      res <-
        onRequestPaid(requestDb,
                      db.invoice.amount.get.toSatoshis,
                      isOnChain = false,
                      npubOpt)
    } yield (db, res)
  }

  def onAddressPaid(
      onchainDb: OnChainPaymentDb,
      requestDb: OpReturnRequestDb,
      amount: Satoshis,
      txid: DoubleSha256DigestBE,
      npubOpt: Option[SchnorrPublicKey]): Future[(OnChainPaymentDb,
                                                  OpReturnRequestDb)] = {
    val action = for {
      db <- onChainDAO.updateAction(
        onchainDb.copy(txid = Some(txid), amountPaid = Some(amount)))
      invoiceDbOpt <- invoiceDAO
        .findByOpReturnRequestIdAction(onchainDb.opReturnRequestId)
    } yield (db, invoiceDbOpt)

    for {
      (db, invoiceDbOpt) <- onChainDAO.safeDatabase.run(action)

      // cancel the invoice if it exists
      cancelF = invoiceDbOpt
        .map(d => lnd.cancelInvoice(d.rHash).recover(_ => ()))
        .getOrElse(Future.unit)

      res <-
        onRequestPaid(requestDb, amount, isOnChain = true, npubOpt)
      _ <- cancelF
    } yield (db, res)
  }

  def onRequestPaid(
      requestDb: OpReturnRequestDb,
      amount: Satoshis,
      isOnChain: Boolean,
      npubOpt: Option[SchnorrPublicKey]): Future[OpReturnRequestDb] = {
    // just mark paid and skip for now if we have a mempool limit
    if (mempoolLimit) {
      logger.warn("Mempool limit in action, skipping for now")
      return Future.successful(requestDb)
    }

    val message = requestDb.getMessage()
    val feeRate = requestDb.feeRate
    val noTwitter = requestDb.noTwitter

    logger.info(s"Received $amount!")

    val spk = {
      val messageBytes = requestDb.messageBytes

      val asm = OP_RETURN +: BitcoinScriptUtil.calculatePushOp(
        messageBytes) :+ ScriptConstant.fromBytes(messageBytes)

      ScriptPubKey(asm.toVector)
    }

    logger.info(s"SPK: $spk")

    val txOut = TxOut(0, spk.asmBytes)

    val request: SendOutputsRequest = SendOutputsRequest(
      satPerKw = feeRate.toSatoshisPerKW.toLong,
      outputs = Vector(txOut),
      label = s"OP_RETURN Bot",
      spendUnconfirmed = false)

    val heightF = lnd.getInfo.map(_.blockHeight)

    val createTxF = for {
      transaction <- lnd
        .sendOutputs(request)
        .recoverWith { e =>
          // if it fails with spending only confirmed, try again with unconfirmed
          logger.warn(s"Error sending outputs: $e, trying unconfirmed")
          lnd.sendOutputs(request.copy(spendUnconfirmed = true))
        }
        .recover(e => {
          throw new RuntimeException(s"SendOutputs error: $e")
        })
      _ = logger.info(s"Created tx: ${transaction.hex}")
      txId = transaction.txIdBE

      errorOpt <- lnd.publishTransaction(transaction)

      _ = errorOpt match {
        case Some(error) =>
          logger.error(
            s"Error when broadcasting transaction ${txId.hex}, $error")
          throw new RuntimeException(
            s"Error when broadcasting transaction ${txId.hex}, $error")
        case None =>
          logger.info(s"Successfully created tx: ${txId.hex}")
      }
      // broadcast to esplora in bg, only if standard
      _ = if (requestDb.messageBytes.length <= 80) {
        esplora.broadcastTransaction(transaction).recover(_ => txId)
      }
      // try to broadcast to slipstream
      _ = {
        val broadcast = transaction.hex
        slipStreamClient.publishTx(broadcast)
      }

      height <- heightF
      getTxStart = System.currentTimeMillis()
      txDetailsOpt <- lnd
        .getTransactions(height.toInt)
        .map(_.find(_.txId == txId))
        .recover(_ => None)
      getTxEnd = System.currentTimeMillis()
      _ = logger.info(
        s"Get tx took ${getTxEnd - getTxStart} ms, tx: ${txId.hex}")

      _ = Try {
        recentTransactions += txId
        if (recentTransactions.size >= 5) {
          val old = recentTransactions.takeRight(5)
          recentTransactions.clear()
          recentTransactions ++= old
        }
        logger.info(s"Updated saved tx: ${txId.hex} to in recent txs list")
      }

      // Need to make sure we upsert the tx and txid even if this fails, so we can't call .get
      chainFeeOpt = txDetailsOpt.map(_.totalFees)
      profitOpt = txDetailsOpt.map(d => amount - d.totalFees)

      dbWithTx = requestDb.copy(closed = true,
                                txOpt = Some(transaction),
                                txIdOpt = Some(txId),
                                vsize = Some(transaction.vsize),
                                profitOpt = profitOpt,
                                chainFeeOpt = chainFeeOpt)

      res <- opReturnDAO.update(dbWithTx)
      _ = logger.info(s"Successfully saved tx: ${txId.hex} to database")

      // send if nostr
      _ <- res.nostrKey match {
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

      // send if DVM
      _ <- res.dvmEvent match {
        case Some(event) =>
          sendDvmJobResult(txId, event)
            .map {
              case Some(id) =>
                logger.info(s"Sent nostr DVM result with id ${NostrNoteId(
                    id)} to ${NostrPublicKey(event.pubkey)}")
              case None =>
                logger.error(
                  s"Failed to send nostr DVM result to ${NostrPublicKey(event.pubkey)}")
            }
            // recover so we can finish accounting
            .recover { case err: Throwable =>
              logger.error(
                s"Error sending nostr DVM result back to ${NostrPublicKey(event.pubkey)}",
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
            .map(x => Option(x).flatten)
            .recover { err =>
              logger.error(
                s"Failed to create tweet for tx ${txId.hex}, got error $err")
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
                s"Failed to create nostr note for tx ${txId.hex}, got error $err")
              None
            }
        }

      _ <- {
        val telegramF = txDetailsOpt match {
          case Some(details) =>
            val userTelegramF = res.telegramIdOpt
              .flatMap(telegramId =>
                telegramHandlerOpt.map(
                  _.handleTelegramUserPurchase(telegramId, details)))
              .getOrElse(Future.unit)

            lazy val action = for {
              profit <- opReturnDAO.totalProfitAction(None)
              chainFees <- opReturnDAO.totalChainFeesAction(None)
              inQueue <- opReturnDAO.numWaitingAction()
            } yield (profit, chainFees, inQueue)

            val accountingTelegramF = telegramHandlerOpt
              .map { handler =>
                for {
                  (profit, chainFees, inQueue) <- opReturnDAO.safeDatabase.run(
                    action)
                  tweetOpt <- tweetF
                  nostrOpt <- nostrF
                  _ <- handler.handleTelegram(
                    requestId = requestDb.id.get,
                    amount = amount,
                    requestDb = res,
                    isOnChain = isOnChain,
                    tweetOpt = tweetOpt,
                    nostrOpt = nostrOpt,
                    message = message,
                    feeRate = feeRate,
                    txDetails = details,
                    totalProfit = profit,
                    totalChainFees = chainFees,
                    remainingInQueue = inQueue
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
              s"Failed to get transaction details for Transaction (${txId.hex})"
            logger.warn(msg)

            telegramHandlerOpt
              .map(_.sendTelegramMessage(msg))
              .getOrElse(Future.unit)
        }

        telegramF.recover { err =>
          logger.error(
            s"Failed to send telegram message for request ${requestDb.id.get}, got error $err")
        }
      }
    } yield res

    createTxF.recoverWith { case err: Throwable =>
      logger.error(
        s"Failed to create tx for request ${requestDb.id.get}, got error: ",
        err)

      val wasSet = mempoolLimit

      val limitF =
        if (err.toString.contains("too many unconfirmed ancestors")) {
          mempoolLimit = true
          logger.warn(
            "We've hit the mempool limit, disallowing invoices until next block!")
          if (!wasSet) {
            // only send the message if we just hit the limit
            telegramHandlerOpt
              .map(_.sendTelegramMessage(
                s"We've hit the mempool limit, disallowing invoices until next block!"))
              .getOrElse(Future.unit)
          } else {
            Future.unit
          }
        } else {
          Future.unit
        }

      for {
        _ <- telegramHandlerOpt
          .map(_.sendTelegramMessage(
            s"Failed to create tx for request ${requestDb.id.get}, got error: ${err.getMessage}"))
          .getOrElse(Future.unit)
        _ <- limitF
      } yield requestDb
    }
  }

  private def getPaymentParams(
      message: ByteVector,
      noTwitter: Boolean): Future[(CurrencyUnit, SatoshisPerVirtualByte)] = {
    require(
      message.length <= 90_000,
      "OP_Return message received was too long, must be less than 90,000 bytes")

    val rateF = fetchFeeRate()
    val heightF = lnd.getInfo.map(_.blockHeight.toLong)

    val data = for {
      feeRate <- rateF
      height <- heightF
    } yield (feeRate, height)

    data
      .map { params =>
        val (rate, height) = params
        val baseSize = 125 // 125 base tx size
        val messageSize = message.length

        // if this is non-standard, double the fee rate and make sure it's at least 5 sats/vbyte
        // we double the fee rate to make sure it gets in since there is only a few pools that will accept it
        // otherwise, add 4 sats/vbyte, just to make sure it gets in
        val startRate = if (messageSize > 80) {
          val value = rate.toLong * 2 max 5
          SatoshisPerVirtualByte.fromLong(value)
        } else {
          rate.copy(rate.currencyUnit + Satoshis(4))
        }

        // multiply by 10 if pre-halving, just to make sure it gets in
        val feeRate = if (height == 1_050_000 - 1) {
          val value = startRate.toLong * 10
          SatoshisPerVirtualByte.fromLong(value)
        } else {
          startRate
        }

        // Add fee if no tweet
        val noTwitterFee = if (noTwitter) Satoshis(1000) else Satoshis.zero
        // add fee if non standard transaction
        val noStdFee = if (messageSize > 80) Satoshis(10000) else Satoshis.zero

        // tx fee + app fee (1337) + twitter fee + non standard fee
        val sats =
          // multiply by 2 just in case
          (feeRate * 2 * (baseSize + messageSize)) +
            Satoshis(1337) + noTwitterFee + noStdFee

        (sats, feeRate)
      }
  }

  private def createInvoice(
      message: ByteVector,
      noTwitter: Boolean): Future[(LnInvoice, SatoshisPerVirtualByte)] = {
    getPaymentParams(message, noTwitter).flatMap { case (sats, feeRate) =>
      val expiry = 60 * 5 // 5 minutes
      val hash = CryptoUtil.sha256(message)

      lnd
        .addInvoice(hash, sats.satoshis, expiry)
        .map(t => (t.invoice, feeRate))
    }
  }

  def createInvoice(
      message: ByteVector,
      noTwitter: Boolean,
      nodeIdOpt: Option[NodeId],
      telegramId: Option[Long],
      nostrKey: Option[SchnorrPublicKey],
      dvmEvent: Option[NostrEvent]): Future[(InvoiceDb, OpReturnRequestDb)] = {
    createInvoice(message, noTwitter)
      .flatMap { case (invoice, feeRate) =>
        val requestDb =
          OpReturnRequestDb(
            id = None,
            noTwitter = noTwitter,
            feeRate = feeRate,
            closed = false,
            nodeIdOpt = nodeIdOpt,
            telegramIdOpt = telegramId,
            nostrKey = nostrKey,
            dvmEvent = dvmEvent,
            txOpt = None,
            txIdOpt = None,
            profitOpt = None,
            chainFeeOpt = None,
            time = TimeUtil.currentEpochSecond,
            messageBytes = message,
            vsize = None
          )

        val action = for {
          createdReq <- opReturnDAO.createAction(requestDb)
          invoiceDb = InvoiceDb(
            rHash = invoice.lnTags.paymentHash.hash,
            opReturnRequestId = createdReq.id.get,
            invoice = invoice,
            paid = false
          )
          createdInv <- invoiceDAO.createAction(invoiceDb)
        } yield (createdInv, createdReq)

        invoiceDAO.safeDatabase.run(action)
      }
  }

  def createAddress(
      message: ByteVector,
      noTwitter: Boolean): Future[(OnChainPaymentDb, OpReturnRequestDb)] = {
    val paramsF = for {
      (amt, feeRate) <- getPaymentParams(message, noTwitter)
      address <- lnd.getNewAddress
    } yield (amt, feeRate, address)

    paramsF
      .flatMap { case (amt, feeRate, address) =>
        val requestDb =
          OpReturnRequestDb(
            id = None,
            noTwitter = noTwitter,
            feeRate = feeRate,
            closed = false,
            nodeIdOpt = None,
            telegramIdOpt = None,
            nostrKey = None,
            dvmEvent = None,
            txOpt = None,
            txIdOpt = None,
            profitOpt = None,
            chainFeeOpt = None,
            time = TimeUtil.currentEpochSecond,
            messageBytes = message,
            vsize = None
          )

        val action = for {
          createdReq <- opReturnDAO.createAction(requestDb)
          invoiceDb = OnChainPaymentDb(
            address = address,
            opReturnRequestId = createdReq.id.get,
            expectedAmount = amt,
            amountPaid = None,
            txid = None
          )
          createdOnChain <- onChainDAO.createAction(invoiceDb)
        } yield (createdOnChain, createdReq)

        opReturnDAO.safeDatabase.run(action)
      }
  }

  def createUnified(message: ByteVector, noTwitter: Boolean): Future[
    (InvoiceDb, OnChainPaymentDb, OpReturnRequestDb)] = {
    val addrF = lnd.getNewAddress
    val paramsF = for {
      (invoice, feeRate) <- createInvoice(message, noTwitter)
      address <- addrF
      amt = invoice.amount.get.toSatoshis
    } yield (amt, feeRate, address, invoice)

    paramsF
      .flatMap { case (amt, feeRate, address, invoice) =>
        val requestDb =
          OpReturnRequestDb(
            id = None,
            noTwitter = noTwitter,
            feeRate = feeRate,
            closed = false,
            nodeIdOpt = None,
            telegramIdOpt = None,
            nostrKey = None,
            dvmEvent = None,
            txOpt = None,
            txIdOpt = None,
            profitOpt = None,
            chainFeeOpt = None,
            time = TimeUtil.currentEpochSecond,
            messageBytes = message,
            vsize = None
          )

        val action = for {
          createdReq <- opReturnDAO.createAction(requestDb)
          onchainDb = OnChainPaymentDb(
            address = address,
            opReturnRequestId = createdReq.id.get,
            expectedAmount = amt,
            amountPaid = None,
            txid = None
          )
          createdOnChain <- onChainDAO.createAction(onchainDb)
          invoiceDb = InvoiceDb(
            rHash = invoice.lnTags.paymentHash.hash,
            opReturnRequestId = createdReq.id.get,
            invoice = invoice,
            paid = false
          )
          createdInv <- invoiceDAO.createAction(invoiceDb)
        } yield (createdInv, createdOnChain, createdReq)

        opReturnDAO.safeDatabase.run(action)
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
      publicKey: NostrPublicKey): Future[(InvoiceDb, OpReturnRequestDb)] = {
    if (takenNames.contains(name)) {
      Future.failed(
        new IllegalArgumentException(s"Cannot create invoice for NIP-05 $name"))
    } else {
      val message = s"nip5:$name:${publicKey.hex}"

      createInvoice(ByteVector(message.getBytes("UTF-8")), noTwitter = false)
        .flatMap { case (invoice, feeRate) =>
          val requestDb =
            OpReturnRequestDb(
              id = None,
              noTwitter = false,
              feeRate = feeRate,
              closed = false,
              nodeIdOpt = None,
              telegramIdOpt = None,
              nostrKey = None,
              dvmEvent = None,
              txOpt = None,
              txIdOpt = None,
              profitOpt = None,
              chainFeeOpt = None,
              time = TimeUtil.currentEpochSecond,
              messageBytes = ByteVector(message.getBytes),
              vsize = None
            )

          val action = nip5DAO.getPublicKeyAction(name).flatMap {
            case Some(key) =>
              val nostrKey = NostrPublicKey(key)
              DBIO.failed(new IllegalArgumentException(
                s"Cannot create invoice for NIP-05 $name, already exists for key $nostrKey"))
            case None =>
              for {
                createdReq <- opReturnDAO.createAction(requestDb)
                invoiceDb = InvoiceDb(
                  rHash = invoice.lnTags.paymentHash.hash,
                  opReturnRequestId = createdReq.id.get,
                  invoice = invoice,
                  paid = false
                )
                createdInv <- invoiceDAO.createAction(invoiceDb)
                _ <- nip5DAO.createAction(
                  Nip5Db(invoiceDb.opReturnRequestId, name, publicKey.key))
              } yield (createdInv, createdReq)
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

  def listUtxoAncestorTxIds(): Future[
    Map[TransactionOutPoint, Vector[DoubleSha256DigestBE]]] = {
    for {
      utxos <- lnd.listUnspent
      ancestors <- Future
        .sequence(utxos.filter(t => t.outPointOpt.isDefined).map { utxo =>
          if (utxo.confirmations > 0) {
            Future.successful((utxo.outPointOpt.get, Vector.empty))
          } else {
            config.bitcoindClient
              .getMemPoolAncestors(utxo.outPointOpt.get.txId)
              .map(t => (utxo.outPointOpt.get, t))
          }
        })
        .map(_.toMap)
    } yield ancestors
  }

  def rebroadcastAncestors(): Future[Unit] = {
    for {
      map <- listUtxoAncestorTxIds()
      _ <- Future.sequence(map.map { case (utxo, txIds) =>
        logger.info(s"Rebroadcasting ${txIds.size} ancestors for utxo $utxo")
        txIds
          .foldLeft(Future.successful(())) { case (accF, txId) =>
            for {
              _ <- accF
              tx <- config.bitcoindClient.getRawTransactionRaw(txId)
              _ <- slipStreamClient.publishTx(tx.hex)
              _ <- config.bitcoindClient.broadcastTransaction(tx)
            } yield ()
          }
      })
    } yield ()
  }

  def checkTxIds(): Future[Int] = {
    for {
      completed <- opReturnDAO.completed()
      items <- FutureUtil.foldLeftAsync(Vector.empty[OpReturnRequestDb],
                                        completed) { (acc, db) =>
        config.bitcoindClient
          .getRawTransactionRaw(db.txIdOpt.get)
          .map(_ => acc)
          .recover(_ => {
            logger.warn(
              s"Transaction ${db.txIdOpt.get.hex} not found, marking invoice as unconfirmed")
            acc :+ db.copy(closed = false)
          })
      }
      _ <- opReturnDAO.updateAll(items)
    } yield items.size
  }

  def createReport(afterTimeOpt: Option[Long]): Future[Report] = {
    val action = for {
      num <- opReturnDAO.numCompletedAction(afterTimeOpt)
      nonStd <- opReturnDAO.numNonStdCompletedAction(afterTimeOpt)
      numOnChain <- opReturnDAO.numOnChainCompletedAction(afterTimeOpt)
      chainFees <- opReturnDAO.totalChainFeesAction(afterTimeOpt)
      profit <- opReturnDAO.totalProfitAction(afterTimeOpt)
      vbytes <- opReturnDAO.totalChainSizeAction(afterTimeOpt)
      nonStdVbytes <- opReturnDAO.totalNonStdChainSizeAction(afterTimeOpt)
      nip5s <- nip5DAO.getNumCompletedAction(afterTimeOpt)
      zapped <- zapDAO.totalZappedAction(afterTimeOpt)
      waitingAction <- opReturnDAO.numWaitingAction()
    } yield Report(num,
                   nonStd,
                   numOnChain,
                   chainFees,
                   profit,
                   vbytes,
                   nonStdVbytes,
                   nip5s,
                   zapped,
                   waitingAction)

    opReturnDAO.safeDatabase.run(action)
  }
}

case class Report(
    num: Int,
    nonStd: Int,
    numOnChain: Int,
    chainFees: CurrencyUnit,
    profit: CurrencyUnit,
    vbytes: Long,
    nonStdVbytes: Long,
    nip5s: Int,
    zapped: CurrencyUnit,
    waitingAction: Int
)
