package controllers

import grizzled.slf4j.Logging
import org.bitcoins.core.crypto.ExtKeyVersion.SegWitMainNetPriv
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.crypto._
import org.bitcoins.keymanager.WalletStorage
import org.scalastr.client.NostrClient
import org.scalastr.core.{Metadata, NostrEvent, NostrFilter, NostrKind}
import play.api.libs.json._

import java.net.URL
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

trait NostrHandler extends Logging { self: InvoiceMonitor =>
  import system.dispatcher

  def setNostrMetadata(): Future[Option[Sha256Digest]] = {
    val metadata = Metadata.create(
      displayName = Some("OP_RETURN Bot"),
      name = Some("opreturnbot"),
      about = Some("Store a message forever in the bitcoin blockchain"),
      nip05 = Some("_@opreturnbot.com"),
      lud16 = Some("me@opreturnbot.com"),
      website = Some(new URL("https://opreturnbot.com")),
      picture =
        Some(new URL("https://opreturnbot.com/assets/images/op-return-bot.png"))
    )

    val event = NostrEvent.build(privateKey = privateKey,
                                 created_at = TimeUtil.currentEpochSecond,
                                 tags = JsArray.empty,
                                 metadata = metadata)

    sendNostrEvent(event)
  }

  private def getDmFilter: NostrFilter = NostrFilter(
    ids = None,
    authors = None,
    kinds = Some(Vector(NostrKind.EncryptedDM)),
    `#e` = None,
    `#p` = Some(Vector(pubKey)),
    since = Some(TimeUtil.currentEpochSecond),
    until = None,
    limit = None
  )

  private lazy val processedEvents: mutable.Set[Sha256Digest] =
    mutable.Set.empty

  private def processDMEvent(event: NostrEvent): Future[Unit] = {
    if (event.kind == NostrKind.EncryptedDM) {
      val skip = synchronized {
        if (processedEvents.contains(event.id)) {
          true
        } else {
          processedEvents.add(event.id)
          false
        }
      }

      if (skip) Future.unit
      else {
        logger.info("Processing DM event: " + event.id.hex)
        val message = NostrEvent.decryptDM(event, privateKey)

        for {
          db <- processMessage(message = message,
                               noTwitter = false,
                               nodeIdOpt = None,
                               telegramId = None,
                               nostrKey = Some(event.pubkey))
          _ <- sendNostrDM(db.invoice.toString, event.pubkey)
        } yield logger.info(s"Sent invoice to ${event.pubkey.hex} over nostr!")
      }
    } else {
      logger.warn(s"Got unexpected event: $event")
      Future.unit
    }
  }

  private lazy val dmClients: Vector[NostrClient] = config.nostrRelays.map {
    relay =>
      new NostrClient(relay, None) {

        override def processEvent(
            subscriptionId: String,
            event: NostrEvent): Future[Unit] = {
          processDMEvent(event)
        }

        override def processNotice(notice: String): Future[Unit] = Future.unit
      }
  }

  def listenForDMs(): Future[Unit] = {
    val filter = getDmFilter
    val startFs = dmClients.map { client =>
      client.start().flatMap(_ => client.subscribe(filter))
    }

    Future.sequence(startFs).map(_ => ())
  }

  private[this] lazy val privateKey: ECPrivateKey =
    WalletStorage
      .getPrivateKeyFromDisk(config.seedPath,
                             SegWitMainNetPriv,
                             config.aesPasswordOpt,
                             config.bip39PasswordOpt)
      .key

  lazy val pubKey: SchnorrPublicKey = privateKey.schnorrPublicKey

  protected def announceOnNostr(
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

    sendNostrEvent(event)
  }

  protected def sendNostrDM(
      message: String,
      pubkey: SchnorrPublicKey): Future[Option[Sha256Digest]] = {
    val event =
      NostrEvent.encryptedDM(message,
                             privateKey,
                             TimeUtil.currentEpochSecond,
                             JsArray.empty,
                             pubkey)

    sendNostrEvent(event)
  }

  def sendingClients: Vector[NostrClient] = config.nostrRelays.map { relay =>
    new NostrClient(relay, None) {

      override def processEvent(
          subscriptionId: String,
          event: NostrEvent): Future[Unit] = {
        Future.unit
      }

      override def processNotice(notice: String): Future[Unit] = Future.unit
    }
  }

  private def sendNostrEvent(
      event: NostrEvent): Future[Option[Sha256Digest]] = {
    val fs = sendingClients.map { client =>
      Try(Await.result(client.start(), 5.seconds)) match {
        case Failure(_) =>
          logger.warn(s"Failed to connect to nostr relay: ${client.url}")
          Future.successful(None)
        case Success(_) =>
          val f = for {
            idT <- client
              .publishEvent(event)
              .map(_ => Success(event.id))
              .recover(err => Failure(err))

            _ = idT match {
              case Success(id) =>
                logger.info(s"Sent nostr event ${id.hex}")
              case Failure(err) =>
                logger.error("Failed to send nostr event: ", err)
            }
            _ <- client.stop()
          } yield idT.toOption

          f.recover(_ => None)
      }
    }

    Future.sequence(fs).map(_.flatten.headOption)
  }
}
