package controllers

import grizzled.slf4j.Logging
import org.bitcoins.core.crypto.ExtKeyVersion.SegWitMainNetPriv
import org.bitcoins.core.util.{FutureUtil, TimeUtil}
import org.bitcoins.crypto._
import org.bitcoins.keymanager.WalletStorage
import org.scalastr.client.NostrClient
import org.scalastr.core.{NostrEvent, NostrKind}
import play.api.libs.json.JsArray

import scala.concurrent.Future

trait NostrHandler extends Logging { self: InvoiceMonitor =>
  import system.dispatcher

  lazy val clients: Vector[NostrClient] = config.nostrClients

  private[this] lazy val privateKey: ECPrivateKey =
    WalletStorage
      .getPrivateKeyFromDisk(config.seedPath,
                             SegWitMainNetPriv,
                             config.aesPasswordOpt,
                             config.bip39PasswordOpt)
      .key

  lazy val pubKey: SchnorrPublicKey = privateKey.schnorrPublicKey

  def sendNostrMessage(message: String): Future[Option[Sha256Digest]] = {
    val event = NostrEvent.build(
      privateKey = privateKey,
      created_at = TimeUtil.currentEpochSecond,
      kind = NostrKind.TextNote,
      tags = JsArray.empty,
      content = message
    )

    val fs = clients.map { client =>
      client
        .publishEvent(event)
        .map(_ => Some(event.id))
        .recover(_ => None)
    }

    Future.sequence(fs).map(_.flatten.headOption)
  }

  protected def handleNostrMessage(
      message: String,
      txId: DoubleSha256DigestBE): Future[Option[Sha256Digest]] = {
    Future.sequence(clients.map(_.start().recover(_ => ()))).flatMap { _ =>
      // Every 15th OP_RETURN we shill
      val count = shillCounter.getAndIncrement()
      if (count % 15 == 0 && count != 0) {
        shillNostrMessage()
      }

      val tweet =
        s"""
           |🔔 🔔 NEW OP_RETURN 🔔 🔔
           |
           |$message
           |
           |https://mempool.space/tx/${txId.hex}
           |""".stripMargin

      for {
        opt <- sendNostrMessage(tweet)
        _ = opt match {
          case Some(id) =>
            logger.info(s"Sent nostr message ${id.hex} for txid ${txId.hex}")
          case None =>
            logger.error("Failed to send nostr message for txid " + txId.hex)
        }
        _ <- Future.sequence(clients.map(_.stop()))
      } yield opt
    }
  }

  private def shillNostrMessage(): Future[Option[Sha256Digest]] = {
    if (uri != uriErrorString) {
      val tweet =
        s"""
           |Like OP_RETURN Bot?
           |
           |Consider connecting and opening a lightning channel!
           |
           |$uri
           |""".stripMargin

      sendNostrMessage(tweet)
    } else FutureUtil.none
  }
}
