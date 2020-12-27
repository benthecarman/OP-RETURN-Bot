package controllers

import config.OpReturnBotAppConfig
import grizzled.slf4j.Logging
import org.bitcoins.core.util.StartStopAsync
import org.bitcoins.eclair.rpc.client.EclairRpcClient
import org.bitcoins.eclair.rpc.config.EclairInstance
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.rpc.config.BitcoindInstance

import java.net.InetAddress
import scala.concurrent.{ExecutionContext, Future}

case class EclairBitcoindPair(
    eclair: EclairRpcClient,
    bitcoind: BitcoindRpcClient)(implicit ec: ExecutionContext)
    extends StartStopAsync[EclairBitcoindPair]
    with Logging {

  require(eclair.instance.zmqConfig.isDefined, "Eclair must have a zmq config")
  require(
    eclair.instance.bitcoindAuthCredentials.get == bitcoind.instance.authCredentials,
    "Eclair's bitcoind authCredentials must match bitcoind's")
  require(
    InetAddress.getByName(
      eclair.instance.bitcoindRpcUri.get.getHost) == InetAddress.getByName(
      bitcoind.instance.rpcUri.getHost),
    s"Eclair's bitcoind rpc host must match bitcoind's, ${eclair.instance.bitcoindRpcUri.get.getHost} != ${bitcoind.instance.rpcUri.getHost}"
  )
//  require(
//    eclair.instance.bitcoindRpcUri.get.getPort == bitcoind.instance.rpcUri.getPort,
//    s"Eclair's bitcoind rpc port must match bitcoind's, ${eclair.instance.bitcoindRpcUri.get.getPort} != ${bitcoind.instance.rpcUri.getPort}"
//  )
  require(
    eclair.instance.zmqConfig.get.rawBlock == bitcoind.instance.zmqConfig.rawBlock,
    s"Eclair's bitcoind zmq raw block must match bitcoind's, ${eclair.instance.zmqConfig.get.rawBlock} != ${bitcoind.instance.zmqConfig.rawBlock}"
  )
  require(
    eclair.instance.zmqConfig.get.rawTx == bitcoind.instance.zmqConfig.rawTx,
    s"Eclair's bitcoind zmq raw tx must match bitcoind's, ${eclair.instance.zmqConfig.get.rawTx} != ${bitcoind.instance.zmqConfig.rawTx}"
  )

  override def start(): Future[EclairBitcoindPair] = {
    logger.info("Starting bitcoind")
    val startBitcoindF = bitcoind.start()
    for {
      startedBitcoind <- startBitcoindF

      _ = logger.info("Starting eclair")
      _ <- eclair.start()
    } yield EclairBitcoindPair(eclair, startedBitcoind)
  }

  override def stop(): Future[EclairBitcoindPair] = {
    for {
      stoppedEclair <- eclair.stop()
      stoppedBitcoind <- bitcoind.stop()
    } yield EclairBitcoindPair(stoppedEclair, stoppedBitcoind)
  }
}

object EclairBitcoindPair {

  def fromConfig(eclairConf: OpReturnBotAppConfig)(implicit
      ec: ExecutionContext): EclairBitcoindPair = {
    val bitcoindLocation = eclairConf.bitcoindBinary
    val bitcoindInstance = BitcoindInstance.fromDatadir(
      eclairConf.bitcoindDataDir.toFile,
      bitcoindLocation)
    val bitcoind = BitcoindRpcClient(bitcoindInstance)

    val eclairInstance =
      EclairInstance.fromDatadir(eclairConf.eclairDataDir.toFile, None)

    val eclair = EclairRpcClient(eclairInstance, Some(eclairConf.eclairBinary))

    EclairBitcoindPair(eclair, bitcoind)
  }
}
