package controllers

import config.OpReturnBotAppConfig
import org.bitcoins.core.currency.{CurrencyUnits, Satoshis}
import org.bitcoins.core.script.ScriptType
import org.bitcoins.testkit.BitcoinSTestAppConfig.tmpDir
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.DualLndFixture
import scodec.bits.ByteVector

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class InvoiceMonitorTest extends DualLndFixture {

  implicit val config: OpReturnBotAppConfig =
    OpReturnBotAppConfig(tmpDir(), Vector.empty)

  before {
    val startF = config.start()
    startF.failed.foreach(_.printStackTrace())
    Await.result(startF, 10.seconds)
  }

  after {
    val f = config.dropAll().map(_ => config.clean())
    Await.result(f, 10.seconds)
  }

  it must "process an invoice" in { param =>
    val (_, lndA, _) = param
    val monitor =
      new InvoiceMonitor(lndA, None, ArrayBuffer.empty)

    monitor.startSubscription()

    for {
      (invDb, reqDb) <- monitor.createInvoice(
        message = ByteVector("hello world".getBytes("UTF-8")),
        noTwitter = true,
        nodeIdOpt = None,
        telegramId = None,
        nostrKey = None,
        dvmEvent = None)
      (invoiceDb, requestDb) <- monitor.onInvoicePaid(invDb, reqDb, None)
    } yield {
      assert(invoiceDb.invoice == invDb.invoice)
      assert(invoiceDb.opReturnRequestId == requestDb.id.get)
      assert(invoiceDb.paid)
      assert(requestDb.noTwitter)
      assert(requestDb.closed)
      assert(requestDb.txIdOpt.isDefined)
      assert(requestDb.txOpt.isDefined)
      assert(requestDb.chainFeeOpt.isDefined)
    }
  }

  it must "process a paid invoice" in { param =>
    val (bitcoind, lndA, lndB) = param
    val monitor =
      new InvoiceMonitor(lndA, None, ArrayBuffer.empty)

    monitor.startSubscription()

    for {
      mempool <- bitcoind.getRawMemPool
      _ = assert(mempool.isEmpty)

      (invDb, reqDb) <- monitor.createInvoice(
        message = ByteVector("hello world".getBytes("UTF-8")),
        noTwitter = true,
        nodeIdOpt = None,
        telegramId = None,
        nostrKey = None,
        dvmEvent = None)
      invoice = invDb.invoice

      _ <- lndB.sendPayment(invoice, 60.seconds)
      _ <- TestAsyncUtil.awaitConditionF(() =>
        bitcoind.getRawMemPool.map(_.size == 1))

      hash <- bitcoind.getRawMemPool.map(_.head)
      tx <- bitcoind.getRawTransactionRaw(hash)

      _ <- TestAsyncUtil.awaitConditionF(() =>
        monitor.opReturnDAO.read(reqDb.id.get).map(_.exists(_.closed)))

      findOpt <- monitor.opReturnDAO.read(reqDb.id.get)
      report <- monitor.createReport(None)
    } yield {
      assert(tx.outputs.exists(_.value == Satoshis.zero))
      assert(
        tx.outputs.exists(_.scriptPubKey.scriptType == ScriptType.NONSTANDARD))

      findOpt match {
        case Some(reqDb) =>
          assert(reqDb.id.get == invDb.opReturnRequestId)
          assert(reqDb.noTwitter)
          assert(reqDb.closed)
          assert(reqDb.txIdOpt.contains(hash))
          assert(reqDb.txOpt.contains(tx))
          assert(reqDb.chainFeeOpt.isDefined)
        case None => fail("invoice not found")
      }

      assert(report.num == 1)
      assert(report.profit > CurrencyUnits.zero)
      assert(report.chainFees > CurrencyUnits.zero)
      assert(report.vbytes > 0)

      assert(report.nonStd == 0)
      assert(report.nonStdVbytes == 0)
    }
  }
}
