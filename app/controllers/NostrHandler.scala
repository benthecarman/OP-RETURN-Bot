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

  def clients: Vector[NostrClient] = config.nostrRelays.map { relay =>
    new NostrClient(relay, None) {

      override def processEvent(
          subscriptionId: String,
          event: NostrEvent): Future[Unit] = {
        Future.unit
      }

      override def processNotice(notice: String): Future[Unit] = Future.unit
    }
  }

  private[this] lazy val privateKey: ECPrivateKey =
    WalletStorage
      .getPrivateKeyFromDisk(config.seedPath,
                             SegWitMainNetPriv,
                             config.aesPasswordOpt,
                             config.bip39PasswordOpt)
      .key

  lazy val pubKey: SchnorrPublicKey = privateKey.schnorrPublicKey

  protected def handleNostrMessage(
      message: String,
      txId: DoubleSha256DigestBE): Future[Option[Sha256Digest]] = {
    val content =
      s"""
         |🔔 🔔 NEW OP_RETURN 🔔 🔔
         |
         |$message
         |
         |https://mempool.space/tx/${txId.hex}
         |""".stripMargin

    val event = NostrEvent.build(
      privateKey = privateKey,
      created_at = TimeUtil.currentEpochSecond,
      kind = NostrKind.TextNote,
      tags = JsArray.empty,
      content = content
    )

    val fs = clients.map { client =>
      client
        .start()
        .flatMap { _ =>
          for {
            opt <- client
              .publishEvent(event)
              .map(_ => Some(event.id))
              .recover(_ => None)

            _ = opt match {
              case Some(id) =>
                logger.info(
                  s"Sent nostr message ${id.hex} for txid ${txId.hex}")
              case None =>
                logger.error(
                  "Failed to send nostr message for txid " + txId.hex)
            }
            _ <- client.stop()
          } yield opt
        }
        .recover(_ => None)
    }

    Future.sequence(fs).map(_.flatten.headOption)
  }
}
