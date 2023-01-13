package controllers

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import com.danielasfregola.twitter4s.entities.Tweet
import com.translnd.rotator.InvoiceState._
import com.translnd.rotator.{InvoiceState, PubkeyRotator}
import config.OpReturnBotAppConfig
import controllers.OpReturnBotTLV.BroadcastTransactionTLV
import grizzled.slf4j.Logging
import lnrpc.Invoice
import models.{InvoiceDAO, InvoiceDb}
import org.bitcoins.core.config.MainNet
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.protocol.ln.LnTag.PaymentHashTag
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.{Transaction, TransactionOutput}
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.script.control.OP_RETURN
import org.bitcoins.core.util.BitcoinScriptUtil
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.feeprovider.MempoolSpaceTarget.FastestFeeTarget
import org.bitcoins.feeprovider._
import org.bitcoins.lnd.rpc.{LndRpcClient, LndUtils}
import scodec.bits.ByteVector
import signrpc.TxOut
import slick.dbio.DBIOAction
import walletrpc.SendOutputsRequest

import scala.collection.mutable.ArrayBuffer
import scala.concurrent._
import scala.concurrent.duration.DurationInt

class InvoiceMonitor(
    val lnd: LndRpcClient,
    pubkeyRotator: PubkeyRotator,
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

  def startSubscription(): Future[Done] = {
    val parallelism = Runtime.getRuntime.availableProcessors()

    pubkeyRotator
      .subscribeInvoices()
      .mapAsyncUnordered(parallelism) { invoice =>
        invoice.state match {
          case Unpaid | InvoiceState.Accepted => Future.unit
          case Cancelled | Expired =>
            val action = invoiceDAO
              .findByPrimaryKeyAction(invoice.hash)
              .flatMap {
                case None => DBIOAction.successful(())
                case Some(invoiceDb) =>
                  invoiceDAO.updateAction(invoiceDb.copy(closed = true))
              }

            invoiceDAO.safeDatabase.run(action)
          case Paid =>
            invoiceDAO.read(invoice.hash).flatMap {
              case None =>
                logger.warn(
                  s"Processed invoice not from OP_RETURN Bot, ${invoice.hash.hex}")
                Future.unit
              case Some(invoiceDb) =>
                invoiceDb.txIdOpt match {
                  case Some(_) =>
                    logger.warn(
                      s"Processed invoice that already has a tx associated with it, rHash: ${invoice.hash.hex}")
                    Future.unit
                  case None =>
                    val amtPaid =
                      invoice.amountPaidOpt.getOrElse(MilliSatoshis.zero)
                    require(amtPaid >= invoice.amountOpt.get,
                            "User did not pay invoice in full")
                    onInvoicePaid(invoiceDb).map(_ => ())
                }
            }
        }

      }
      .runWith(Sink.ignore)

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
            invoiceDAO.read(Sha256Digest(invoice.rHash)).flatMap {
              case None =>
                logger.warn(
                  s"Processed invoice not from OP_RETURN Bot, ${invoice.rHash.toBase16}")
                Future.unit
              case Some(invoiceDb) =>
                invoiceDb.txIdOpt match {
                  case Some(_) =>
                    logger.warn(
                      s"Processed invoice that already has a tx associated with it, rHash: ${invoice.rHash.toBase16}")
                    Future.unit
                  case None =>
                    require(invoice.amtPaidMsat >= invoice.valueMsat,
                            "User did not pay invoice in full")
                    onInvoicePaid(invoiceDb).map(_ => ())
                }
            }
        }

      }
      .runWith(Sink.ignore)
  }

  def processUnhandledInvoices(): Future[Vector[InvoiceDb]] = {
    invoiceDAO.findUnclosed().flatMap { unclosed =>
      if (unclosed.nonEmpty) {
        val time = System.currentTimeMillis()
        logger.info(s"Processing ${unclosed.size} unhandled invoices")

        val updateFs = unclosed.map { db =>
          if (db.txOpt.isDefined) Future.successful(db.copy(closed = true))
          else {
            pubkeyRotator
              .lookupInvoice(db.rHash)
              .flatMap {
                case None =>
                  lnd
                    .lookupInvoice(PaymentHashTag(db.rHash))
                    .flatMap { inv =>
                      inv.state match {
                        case Invoice.InvoiceState.OPEN |
                            Invoice.InvoiceState.ACCEPTED =>
                          Future.successful(db)
                        case Invoice.InvoiceState.SETTLED =>
                          if (inv.amtPaidMsat >= inv.valueMsat) {
                            onInvoicePaid(db)
                          } else Future.successful(db.copy(closed = true))
                        case Invoice.InvoiceState.CANCELED =>
                          Future.successful(db.copy(closed = false))
                        case Invoice.InvoiceState.Unrecognized(_) =>
                          Future.successful(db)
                      }
                    }
                    .recover { case _: Throwable => db.copy(closed = true) }
                case Some(inv) =>
                  inv.state match {
                    case Unpaid | InvoiceState.Accepted =>
                      Future.successful(db)
                    case Cancelled | Expired =>
                      Future.successful(db.copy(closed = true))
                    case Paid =>
                      val amtPaid =
                        inv.amountPaidOpt.getOrElse(MilliSatoshis.zero)
                      if (amtPaid >= inv.amountOpt.get) {
                        onInvoicePaid(db)
                      } else Future.successful(db.copy(closed = true))
                  }
              }
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

  def onInvoicePaid(invoiceDb: InvoiceDb): Future[InvoiceDb] = {
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
      errorOpt <- lnd.publishTransaction(transaction)
      txId = transaction.txIdBE

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
                  s"Sent nostr DM with id ${id.hex} to ${nostrKey.hex}")
              case None =>
                logger.error(s"Failed to send nostr DM to ${nostrKey.hex}")
            }
            // recover so we can finish accounting
            .recover { case err: Throwable =>
              logger.error(s"Error sending nostr dm back to ${nostrKey.hex}",
                           err)
            }
        case None => Future.unit
      }

      _ = errorOpt match {
        case Some(error) =>
          logger.error(
            s"Error when broadcasting transaction ${txId.hex}, $error")
        case None =>
          logger.info(s"Successfully created tx: ${txId.hex}")
      }

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
      chainFeeOpt = txDetailsOpt.map(_.totalFees)
      profitOpt = txDetailsOpt.map(d => amount - d.totalFees)

      dbWithTx: InvoiceDb = invoiceDb.copy(closed = true,
                                           txOpt = Some(transaction),
                                           txIdOpt = Some(txId),
                                           profitOpt = profitOpt,
                                           chainFeeOpt = chainFeeOpt)

      res <- invoiceDAO.update(dbWithTx)

      tweetF =
        if (noTwitter) Future.successful((None, None))
        else {
          val tweetP = Promise[Option[Tweet]]()
          val nostrP = Promise[Option[Sha256Digest]]()

          // add a 15 second delay for tweet so mempool.space has time
          // to index the transaction
          // - requested by the mempool.space team
          system.scheduler.scheduleOnce(15.seconds) {
            handleTweet(message, txId)
              .map(Some(_))
              .recover { err =>
                logger.error(
                  s"Failed to create tweet for invoice ${rHash.hash.hex}, got error $err")
                None
              }
              .onComplete(t => tweetP.tryComplete(t))

            announceOnNostr(message, txId)
              .recover { err =>
                logger.error(
                  s"Failed to create nostr note for invoice ${rHash.hash.hex}, got error $err")
                None
              }
              .onComplete(t => nostrP.tryComplete(t))
            ()
          }

          tweetP.future
            .recover(_ => None)
            .flatMap { tweetOpt =>
              nostrP.future
                .recover(_ => None)
                .map { nostrOpt =>
                  (tweetOpt, nostrOpt)
                }
            }
        }
      _ <- {
        val telegramF = txDetailsOpt match {
          case Some(details) =>
            lazy val action = for {
              profit <- invoiceDAO.totalProfitAction()
              chainFees <- invoiceDAO.totalChainFeesAction()
            } yield (profit, chainFees)

            val accountingTelegramF = telegramHandlerOpt
              .map { handler =>
                for {
                  (profit, chainFees) <- invoiceDAO.safeDatabase.run(action)
                  (tweetOpt, nostrOpt) <- tweetF
                  _ <- handler.handleTelegram(
                    rHash = rHash.hash,
                    invoice = invoice,
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
              _ <- invoiceDb.telegramIdOpt
                .flatMap(telegramId =>
                  telegramHandlerOpt.map(
                    _.handleTelegramUserPurchase(telegramId, details)))
                .getOrElse(Future.unit)

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
        updated = invoiceDb.copy(closed = false)
        newDb <- invoiceDAO.update(updated)
      } yield newDb
    }
  }

  def processMessage(
      message: String,
      noTwitter: Boolean,
      nodeIdOpt: Option[NodeId],
      telegramId: Option[Long],
      nostrKey: Option[SchnorrPublicKey]): Future[InvoiceDb] = {
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

//        val createInvoiceF = pubkeyRotator
//          .createInvoice(hash, sats, expiry)
        val createInvoiceF =
          lnd.addInvoice(hash, sats.satoshis, expiry).map(_.invoice)

        createInvoiceF
          .flatMap { invoice =>
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
