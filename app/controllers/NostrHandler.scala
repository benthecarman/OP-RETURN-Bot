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

  def sendNostrMessage(message: String): Future[Sha256Digest] = {
    val fs = clients.map { client =>
      val event =
        NostrEvent.build(
          privateKey = privateKey,
          created_at = TimeUtil.currentEpochSecond,
          kind = NostrKind.TextNote,
          tags = JsArray.empty,
          content = message
        )

      client
        .publishEvent(event)
        .recover(_ => ())
        .map(_ => event.id)
    }

    Future.sequence(fs).map(_.head)
  }

  protected def handleNostrMessage(
      message: String,
      txId: DoubleSha256DigestBE): Future[Sha256Digest] = FutureUtil.makeAsync {
    () =>
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

      sendNostrMessage(tweet)
  }.flatten

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

      sendNostrMessage(tweet).map(Some(_))
    } else FutureUtil.none
  }
}
