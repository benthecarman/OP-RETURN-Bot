package controllers

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import config.OpReturnBotAppConfig
import lnrpc.Invoice.InvoiceState.CANCELED
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.transaction.{
  TransactionConstants,
  TransactionOutput
}
import org.bitcoins.core.script.ScriptType
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.feeprovider.ConstantFeeRateProvider
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.rpc.config.{BitcoindConfig, BitcoindInstanceLocal}
import org.bitcoins.rpc.util.RpcUtil
import org.bitcoins.testkit.BitcoinSTestAppConfig.tmpDir
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.{LndRpcTestClient, LndRpcTestUtil}
import org.bitcoins.testkit.rpc.BitcoindRpcTestUtil.writtenConfig
import org.bitcoins.testkit.util.{BitcoindRpcTestClient, FileUtil}
import org.scalatest.FutureOutcome
import scodec.bits.ByteVector

import java.io.File
import java.nio.file.{Files, StandardOpenOption}
import java.net.URI
import scala.collection.mutable.ArrayBuffer
import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util.Properties
import scala.jdk.CollectionConverters.IteratorHasAsScala

class InvoiceMonitorTest extends BitcoinSFixture {

  override type FixtureParam =
    (OpReturnBitcoindClient, LndRpcClient, LndRpcClient)

  final val sendingWalletName = "sending"
  final val receivingWalletName = "receiving"
  final val miningWalletName = "mining"

  def createNodePair(
      bitcoind: OpReturnBitcoindClient,
      channelSize: CurrencyUnit = DEFAULT_CHANNEL_AMT,
      channelPushAmt: CurrencyUnit = DEFAULT_CHANNEL_AMT / Satoshis(2)): Future[
    (LndRpcClient, LndRpcClient)] = {
    val actorSystemA =
      ActorSystem.create("bitcoin-s-lnd-test-" + FileUtil.randomDirName)
    val clientA = LndRpcTestClient
      .fromSbtDownload(Some(bitcoind), Some("v0.19.0-beta"))(actorSystemA)

    val actorSystemB =
      ActorSystem.create("bitcoin-s-lnd-test-" + FileUtil.randomDirName)
    val clientB = LndRpcTestClient
      .fromSbtDownload(Some(bitcoind), Some("v0.19.0-beta"))(actorSystemB)

    val clientsF = for {
      a <- clientA.start()
      b <- clientB.start()
    } yield (a, b)

    def isSynced: Future[Boolean] = for {
      (client, otherClient) <- clientsF

      infoA <- client.getInfo
      infoB <- otherClient.getInfo
    } yield infoA.syncedToChain && infoB.syncedToChain

    def isFunded: Future[Boolean] = for {
      (client, otherClient) <- clientsF

      balA <- client.walletBalance()
      balB <- otherClient.walletBalance()
    } yield balA.confirmedBalance > Satoshis.zero && balB.confirmedBalance > Satoshis.zero

    for {
      (client, otherClient) <- clientsF

      _ <- LndRpcTestUtil.connectLNNodes(client, otherClient)
      _ <- LndRpcTestUtil.fundLNNodes(bitcoind, client, otherClient)

      _ <- AsyncUtil.awaitConditionF(() => isSynced)
      _ <- AsyncUtil.awaitConditionF(() => isFunded)

      _ <- LndRpcTestUtil.openChannel(bitcoind = bitcoind,
                                      n1 = client,
                                      n2 = otherClient,
                                      amt = channelSize,
                                      pushAmt = channelPushAmt)

      _ <- TestAsyncUtil.nonBlockingSleep(2.seconds)
    } yield (client, otherClient)
  }

  private val DEFAULT_CHANNEL_AMT = Satoshis(500000L)

  def binary(): File = {
    val binaryDirectory = BitcoindRpcTestClient.sbtBinaryDirectory
    val fileList = Files
      .list(binaryDirectory)
      .iterator()
      .asScala
      .toList
      .filter(f => Files.isDirectory(f))
    val filtered = fileList.filter(f => f.toString.contains("29.0"))

    if (filtered.isEmpty)
      throw new RuntimeException(
        s"bitcoind v29.0 is not installed in $binaryDirectory. Run `sbt downloadBitcoind`")

    // might be multiple versions downloaded for
    // each major version, i.e. 0.16.2 and 0.16.3
    val versionFolder = filtered.max

    versionFolder
      .resolve("bin")
      .resolve(if (Properties.isWin) "bitcoind.exe" else "bitcoind")
      .toFile
  }

  val fundAmt: Bitcoins = Bitcoins(10)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[FixtureParam](
      () => {
        val uri = new URI("http://localhost:" + RpcUtil.randomPort)
        val rpcUri = new URI("http://localhost:" + RpcUtil.randomPort)
        val configFile =
          writtenConfig(uri,
                        rpcUri,
                        RpcUtil.zmqConfig,
                        pruneMode = false,
                        blockFilterIndex = true)

        val entry =
          s"\ndatacarriersize=1000000\naddresstype=bech32m\nchangetype=bech32m\n"

        Files.write(
          configFile.toAbsolutePath,
          entry.getBytes,
          StandardOpenOption.APPEND
        )

        val conf = BitcoindConfig(configFile)
        val instance = BitcoindInstanceLocal.fromConfig(conf, binary())
        val bitcoind = new OpReturnBitcoindClient(instance)

        for {
          _ <- bitcoind.start()
          _ <- bitcoind
            .createWallet(miningWalletName,
                          avoidReuse = true,
                          descriptors = true)
            .map(_ => ())
            .recover(_ => ())
          address <- bitcoind.getNewAddress
          _ <- bitcoind.generateToAddress(blocks = 101, address)
          (lndA, lndB) <- createNodePair(bitcoind)

          _ <- bitcoind
            .createWallet(sendingWalletName,
                          avoidReuse = true,
                          descriptors = true)
            .map(_ => ())
            .recover(_ => ())

          addr <- bitcoind.getNewAddress(Some(sendingWalletName))
          _ <- bitcoind.sendToAddress(addr,
                                      fundAmt,
                                      walletNameOpt = Some(miningWalletName))

          _ <- bitcoind
            .createWallet(receivingWalletName,
                          avoidReuse = true,
                          descriptors = true)
            .map(_ => ())
            .recover(_ => ())

          addr <- bitcoind.getNewAddress(Some(receivingWalletName))
          _ <- bitcoind.sendToAddress(addr,
                                      fundAmt,
                                      walletNameOpt = Some(miningWalletName))

          _ <- bitcoind.generateToAddress(blocks = 1, address)
        } yield (bitcoind, lndA, lndB)
      },
      { param =>
        val (b, lndA, lndB) = param
        for {
          _ <- lndA.stop()
          _ <- lndB.stop()
          _ <- b.stop()
        } yield ()
      }
    )(test)
  }

  val walletNameConfig: Config = {
    ConfigFactory.parseString(
      s"""
         |bitcoin-s.bitcoind.receivingWalletName=$receivingWalletName
         |bitcoin-s.bitcoind.sendingWalletName=$sendingWalletName
         |""".stripMargin)
  }

  implicit val config: OpReturnBotAppConfig =
    OpReturnBotAppConfig(tmpDir(), Vector(walletNameConfig))

  before {
    val startF = config.start()
    startF.failed.foreach(_.printStackTrace())
    Await.result(startF, 10.seconds)
  }

  after {
    val f = config.dropAll().map(_ => config.clean())
    Await.result(f, 10.seconds)
  }

  val feeProvider: ConstantFeeRateProvider = ConstantFeeRateProvider(
    SatoshisPerVirtualByte.fromLong(7))

  it must "process an invoice" in { param =>
    val (bitcoind, lndA, _) = param

    val monitor =
      new InvoiceMonitor(lndA, bitcoind, feeProvider, None, ArrayBuffer.empty)

    monitor.startSubscription()

    val messageBytes = ByteVector("hello world".getBytes("UTF-8"))
    val spk = bitcoind.createSpk(messageBytes)

    for {
      (invDb, reqDb) <- monitor.createInvoice(message = messageBytes,
                                              noTwitter = true,
                                              nodeIdOpt = None,
                                              telegramId = None,
                                              nostrKey = None,
                                              dvmEvent = None)

      startBal <- bitcoind.getBalance(sendingWalletName)

      (invoiceDb, requestDb) <- monitor.onInvoicePaid(invDb, reqDb, None)

      bal <- bitcoind.getBalance(sendingWalletName)

      hash <- bitcoind.getRawMemPool.map(_.head)
      tx <- bitcoind.getRawTransactionRaw(hash)
      txDetails <- bitcoind.getTransaction(hash,
                                           walletNameOpt =
                                             Some(sendingWalletName))
    } yield {
      assert(invoiceDb.invoice == invDb.invoice)
      assert(invoiceDb.opReturnRequestId == requestDb.id.get)
      assert(invoiceDb.paid)
      assert(requestDb.noTwitter)
      assert(requestDb.closed)
      assert(requestDb.txIdOpt.contains(hash))
      assert(requestDb.txOpt.contains(tx))
      // need to handle the negative "fee" from the tx
      assert(requestDb.chainFeeOpt.contains(txDetails.fee.get * Satoshis(-1)))
      assert(bal.satoshis == startBal - requestDb.chainFeeOpt.get)

      assert(tx.vsize == bitcoind.calcTxSize(spk))

      val feeRate = SatoshisPerVirtualByte.calc(fundAmt, tx)
      assert(feeRate == requestDb.feeRate)
      assert(feeRate.toLong >= 5)
    }
  }

  it must "process a non-standard invoice" in { param =>
    val (bitcoind, lndA, _) = param

    val monitor =
      new InvoiceMonitor(lndA, bitcoind, feeProvider, None, ArrayBuffer.empty)

    monitor.startSubscription()

    val message = "hello world" + "_" * 80
    assert(message.length > 80)

    for {
      mempool <- bitcoind.getRawMemPool
      _ = assert(mempool.isEmpty)

      (invDb, reqDb) <- monitor.createInvoice(
        message = ByteVector(message.getBytes("UTF-8")),
        noTwitter = true,
        nodeIdOpt = None,
        telegramId = None,
        nostrKey = None,
        dvmEvent = None)

      startBal <- bitcoind.getBalance(sendingWalletName)

      (invoiceDb, requestDb) <- monitor.onInvoicePaid(invDb, reqDb, None)

      bal <- bitcoind.getBalance(sendingWalletName)
      report <- monitor.createReport(None)

      hash <- bitcoind.getRawMemPool.map(_.head)
      tx <- bitcoind.getRawTransactionRaw(hash)
      txDetails <- bitcoind.getTransaction(hash,
                                           walletNameOpt =
                                             Some(sendingWalletName))
    } yield {
      assert(invoiceDb.invoice == invDb.invoice)
      assert(invoiceDb.opReturnRequestId == requestDb.id.get)
      assert(invoiceDb.paid)
      assert(requestDb.noTwitter)
      assert(requestDb.closed)
      assert(requestDb.txIdOpt.contains(hash))
      assert(requestDb.txOpt.contains(tx))
      assert(requestDb.chainFeeOpt.contains(txDetails.fee.get * Satoshis(-1)))
      assert(bal.satoshis == startBal - requestDb.chainFeeOpt.get)

      assert(report.num == 1)
      assert(report.nonStd == 1)
      assert(report.nonStdVbytes == report.vbytes)
      assert(report.nonStdVbytes == requestDb.txOpt.get.vsize)
    }
  }

  it must "process a paid invoice" in { param =>
    val (bitcoind, lndA, lndB) = param

    val monitor =
      new InvoiceMonitor(lndA, bitcoind, feeProvider, None, ArrayBuffer.empty)

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
      assert(tx.inputs.length == 1)
      assert(tx.inputs.head.sequence == TransactionConstants.disableRBFSequence)

      findOpt match {
        case Some(reqDb) =>
          assert(reqDb.id.get == invDb.opReturnRequestId)
          assert(reqDb.noTwitter)
          assert(reqDb.closed)
          assert(reqDb.txIdOpt.contains(hash))
          assert(reqDb.txOpt.contains(tx))
          assert(reqDb.chainFeeOpt.isDefined)
        case None => fail("request not found")
      }

      assert(report.num == 1)
      assert(report.numOnChain == 0)
      assert(report.waitingAction == 0)
      assert(report.profit > CurrencyUnits.zero)
      assert(report.chainFees > CurrencyUnits.zero)
      assert(report.vbytes > 0)

      assert(report.nonStd == 0)
      assert(report.nonStdVbytes == 0)
    }
  }

  it must "process a single paid address" in { param =>
    val (bitcoind, lndA, lndB) = param

    val monitor =
      new InvoiceMonitor(lndA, bitcoind, feeProvider, None, ArrayBuffer.empty)

    monitor.startTxSubscription()

    val messageBytes = ByteVector("hello world".getBytes("UTF-8"))
    val spk = bitcoind.createSpk(messageBytes)

    for {
      mempool <- bitcoind.getRawMemPool
      _ = assert(mempool.isEmpty)

      startBal <- bitcoind.getBalance(sendingWalletName)
      startRecvBal <- bitcoind.getBalance(receivingWalletName)

      (onchainDb, reqDb) <- monitor.createAddress(message = messageBytes,
                                                  noTwitter = true)

      _ <- lndB.sendOutputs(
        Vector(
          TransactionOutput(onchainDb.expectedAmount,
                            onchainDb.address.scriptPubKey)),
        SatoshisPerVirtualByte.fromLong(1),
        spendUnconfirmed = false)
      _ <- TestAsyncUtil.awaitConditionF(() =>
        bitcoind.getRawMemPool.map(_.size == 1))

      paymentTxId <- bitcoind.getRawMemPool.map(_.head)

      // simulate walletnotify call
      paymentTx <- bitcoind.getRawTransactionRaw(paymentTxId)
      _ <- monitor.processTransaction(paymentTxId, paymentTx.outputs)

      _ <- TestAsyncUtil.awaitConditionF(() =>
        monitor.opReturnDAO.read(reqDb.id.get).map(_.exists(_.closed)))

      findOpt <- monitor.opReturnDAO.read(reqDb.id.get)
      onchainOpt <- monitor.onChainDAO.read(onchainDb.address)
      report <- monitor.createReport(None)

      hash <- bitcoind.getRawMemPool.map(_.last)
      tx <- bitcoind.getRawTransactionRaw(hash)

      mineAddr <- bitcoind.getNewAddress(Some(miningWalletName))
      _ <- bitcoind.generateToAddress(1, mineAddr)

      bal <- bitcoind.getBalance(sendingWalletName)
      recvBal <- bitcoind.getBalance(receivingWalletName)

      txDetails <- bitcoind.getTransaction(hash,
                                           walletNameOpt =
                                             Some(receivingWalletName))
    } yield {
      assert(tx.outputs.exists(o =>
        o.value == Satoshis.zero && o.scriptPubKey == spk))
      assert(tx.inputs.length == 1)
      assert(tx.inputs.head.sequence == TransactionConstants.disableRBFSequence)
      assert(tx.inputs.head.previousOutput.txIdBE == paymentTxId)

      findOpt match {
        case Some(reqDb) =>
          assert(reqDb.id.get == onchainDb.opReturnRequestId)
          assert(reqDb.noTwitter)
          assert(reqDb.closed)
          assert(reqDb.txIdOpt.contains(hash))
          assert(reqDb.txOpt.contains(tx))
          // need to handle the negative "fee" from the tx
          assert(reqDb.chainFeeOpt.contains(txDetails.fee.get * Satoshis(-1)))
          // for on-chain we don't spend from sending wallet
          assert(bal.satoshis == startBal)
          // for on-chain we do spend from receiving wallet
          assert(
            recvBal.satoshis == startRecvBal + onchainDb.expectedAmount - reqDb.chainFeeOpt.get)

          assert(tx.vsize == bitcoind.calcTxSize(spk))

          val feeRate =
            SatoshisPerVirtualByte.calc(onchainDb.expectedAmount, tx)
          assert(feeRate == reqDb.feeRate)
          assert(feeRate.toLong >= 7)
        case None => fail("request not found")
      }

      onchainOpt match {
        case Some(db) =>
          assert(db.address == onchainDb.address)
          assert(db.expectedAmount == onchainDb.expectedAmount)
          assert(db.txid.contains(paymentTxId))
          assert(db.amountPaid.contains(onchainDb.expectedAmount))
        case None => fail("onchain address not found")
      }

      assert(report.num == 1)
      assert(report.numOnChain == 1)
      assert(report.waitingAction == 0)
      assert(report.profit > CurrencyUnits.zero)
      assert(report.chainFees > CurrencyUnits.zero)
      assert(report.vbytes > 0)

      assert(report.nonStd == 0)
      assert(report.nonStdVbytes == 0)
    }
  }

  it must "process a paid address from unified" in { param =>
    val (bitcoind, lndA, lndB) = param

    val monitor =
      new InvoiceMonitor(lndA, bitcoind, feeProvider, None, ArrayBuffer.empty)

    monitor.startSubscription()
    monitor.startTxSubscription()

    for {
      mempool <- bitcoind.getRawMemPool
      _ = assert(mempool.isEmpty)

      (invDb, onchainDb, reqDb) <- monitor.createUnified(
        message = ByteVector("hello world".getBytes("UTF-8")),
        noTwitter = true)

      _ <- lndB.sendOutputs(
        Vector(
          TransactionOutput(onchainDb.expectedAmount,
                            onchainDb.address.scriptPubKey)),
        SatoshisPerVirtualByte.fromLong(1),
        spendUnconfirmed = false)
      _ <- TestAsyncUtil.awaitConditionF(() =>
        bitcoind.getRawMemPool.map(_.size == 1))

      paymentTxId <- bitcoind.getRawMemPool.map(_.head)

      // mine a block to trigger the op_return
      mineAddr <- bitcoind.getNewAddress(Some(miningWalletName))
      _ <- bitcoind.generateToAddress(1, mineAddr)
      // simulate walletnotify call
      paymentTx <- bitcoind.getRawTransactionRaw(paymentTxId)
      _ <- monitor.processTransaction(paymentTxId, paymentTx.outputs)

      _ <- TestAsyncUtil.awaitConditionF(() =>
        monitor.opReturnDAO.read(reqDb.id.get).map(_.exists(_.closed)))

      findOpt <- monitor.opReturnDAO.read(reqDb.id.get)
      onchainOpt <- monitor.onChainDAO.read(onchainDb.address)
      report <- monitor.createReport(None)

      hash <- bitcoind.getRawMemPool.map(_.head)
      tx <- bitcoind.getRawTransactionRaw(hash)

      inv <- lndA.lookupInvoice(invDb.invoice.lnTags.paymentHash)
    } yield {
      assert(tx.outputs.exists(_.value == Satoshis.zero))
      assert(
        tx.outputs.exists(_.scriptPubKey.scriptType == ScriptType.NONSTANDARD))

      findOpt match {
        case Some(reqDb) =>
          assert(reqDb.id.get == onchainDb.opReturnRequestId)
          assert(reqDb.noTwitter)
          assert(reqDb.closed)
          assert(reqDb.txIdOpt.contains(hash))
          assert(reqDb.txOpt.contains(tx))
          assert(reqDb.chainFeeOpt.isDefined)
        case None => fail("request not found")
      }

      onchainOpt match {
        case Some(onchainDb) =>
          assert(onchainDb.address == onchainDb.address)
          assert(onchainDb.expectedAmount == onchainDb.expectedAmount)
          assert(onchainDb.txid.contains(paymentTxId))
          assert(onchainDb.amountPaid.contains(onchainDb.expectedAmount))
        case None => fail("onchain address not found")
      }

      assert(report.num == 1)
      assert(report.numOnChain == 1)
      assert(report.waitingAction == 0)
      assert(report.profit > CurrencyUnits.zero)
      assert(report.chainFees > CurrencyUnits.zero)
      assert(report.vbytes > 0)

      assert(report.nonStd == 0)
      assert(report.nonStdVbytes == 0)

      assert(inv.state == CANCELED)
    }
  }

  it must "process an unhandled address" in { param =>
    val (bitcoind, lndA, lndB) = param

    val monitor =
      new InvoiceMonitor(lndA, bitcoind, feeProvider, None, ArrayBuffer.empty)

    for {
      mempool <- bitcoind.getRawMemPool
      _ = assert(mempool.isEmpty)

      (invDb, onchainDb, reqDb) <- monitor.createUnified(
        message = ByteVector("hello world".getBytes("UTF-8")),
        noTwitter = true)

      _ <- lndB.sendOutputs(
        Vector(
          TransactionOutput(onchainDb.expectedAmount,
                            onchainDb.address.scriptPubKey)),
        SatoshisPerVirtualByte.fromLong(1),
        spendUnconfirmed = false)
      _ <- TestAsyncUtil.awaitConditionF(() =>
        bitcoind.getRawMemPool.map(_.size == 1))

      paymentTxId <- bitcoind.getRawMemPool.map(_.head)

      // mine a block to trigger the op_return
      mineAddr <- bitcoind.getNewAddress(Some(miningWalletName))
      _ <- bitcoind.generateToAddress(1, mineAddr)

      num <- monitor.processUnhandledRequests(None, liftMempoolLimit = false)
      _ = assert(num == 1)

      findOpt <- monitor.opReturnDAO.read(reqDb.id.get)
      onchainOpt <- monitor.onChainDAO.read(onchainDb.address)
      _ = assert(findOpt.exists(_.closed))
      _ = assert(
        onchainOpt.exists(_.amountPaid.contains(onchainDb.expectedAmount)))

      _ <- TestAsyncUtil.awaitConditionF(() =>
        bitcoind.getRawMemPool.map(_.nonEmpty))

      findOpt <- monitor.opReturnDAO.read(reqDb.id.get)
      report <- monitor.createReport(None)

      hash <- bitcoind.getRawMemPool.map(_.head)
      tx <- bitcoind.getRawTransactionRaw(hash)

      inv <- lndA.lookupInvoice(invDb.invoice.lnTags.paymentHash)
    } yield {
      assert(tx.outputs.exists(_.value == Satoshis.zero))
      assert(
        tx.outputs.exists(_.scriptPubKey.scriptType == ScriptType.NONSTANDARD))

      findOpt match {
        case Some(reqDb) =>
          assert(reqDb.id.get == onchainDb.opReturnRequestId)
          assert(reqDb.noTwitter)
          assert(reqDb.closed)
          assert(reqDb.txIdOpt.contains(hash))
          assert(reqDb.txOpt.contains(tx))
          assert(reqDb.chainFeeOpt.isDefined)
        case None => fail("request not found")
      }

      onchainOpt match {
        case Some(onchainDb) =>
          assert(onchainDb.address == onchainDb.address)
          assert(onchainDb.expectedAmount == onchainDb.expectedAmount)
          assert(onchainDb.txid.contains(paymentTxId))
          assert(onchainDb.amountPaid.contains(onchainDb.expectedAmount))
        case None => fail("onchain address not found")
      }

      assert(report.num == 1)
      assert(report.numOnChain == 1)
      assert(report.waitingAction == 0)
      assert(report.profit > CurrencyUnits.zero)
      assert(report.chainFees > CurrencyUnits.zero)
      assert(report.vbytes > 0)

      assert(report.nonStd == 0)
      assert(report.nonStdVbytes == 0)

      assert(inv.state == CANCELED)
    }
  }

  it must "process a unhandled invoice" in { param =>
    val (bitcoind, lndA, lndB) = param

    val monitor =
      new InvoiceMonitor(lndA, bitcoind, feeProvider, None, ArrayBuffer.empty)

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

      num <- monitor.processUnhandledRequests(None, liftMempoolLimit = false)
      _ = assert(num == 1)

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
        case None => fail("request not found")
      }

      assert(report.num == 1)
      assert(report.numOnChain == 0)
      assert(report.waitingAction == 0)
      assert(report.profit > CurrencyUnits.zero)
      assert(report.chainFees > CurrencyUnits.zero)
      assert(report.vbytes == tx.vsize)

      assert(report.nonStd == 0)
      assert(report.nonStdVbytes == 0)
    }
  }
}
