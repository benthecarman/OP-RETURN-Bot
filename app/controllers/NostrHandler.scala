package controllers

import grizzled.slf4j.Logging
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.core.crypto.ExtKeyVersion.SegWitMainNetPriv
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.crypto._
import org.bitcoins.keymanager.WalletStorage
import org.scalastr.client.NostrClient
import org.scalastr.core._
import play.api.libs.json.Json

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

    val event = NostrEvent.build(
      privateKey = nostrPrivateKey,
      created_at = 1673719403L, // change me when updates are made
      tags = Vector.empty,
      metadata = metadata)

    val extraTag = config.extraNostrPubKey.map(pk => Json.arr("p", pk.hex))
    val contacts = NostrEvent.build(
      privateKey = nostrPrivateKey,
      created_at = 1676332751L, // change me when updates are made
      kind = NostrKind.Contacts,
      tags = Vector(Json.arr("p", nostrPubKey.hex)) ++ extraTag,
      content = ""
    )

    sendNostrEvent(event, config.allRelays).flatMap(_ =>
      sendNostrEvent(contacts, config.allRelays))
  }

  private def getDmFilter: NostrFilter = NostrFilter(
    ids = None,
    authors = None,
    kinds = Some(Vector(NostrKind.EncryptedDM)),
    `#e` = None,
    `#p` = Some(Vector(nostrPubKey)),
    since = Some(TimeUtil.currentEpochSecond - 3),
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
        logger.info("Processing DM event: " + NostrNoteId(event.id))
        val message = NostrEvent.decryptDM(event, nostrPrivateKey)

        for {
          db <- createInvoice(message = message,
                              noTwitter = false,
                              nodeIdOpt = None,
                              telegramId = None,
                              nostrKey = Some(event.pubkey))
          _ <- sendNostrDM(db.invoice.toString, event.pubkey)
        } yield logger.info(
          s"Sent invoice to ${NostrPublicKey(event.pubkey)} over nostr!")
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

  private def startDmListener(client: NostrClient): Future[Unit] = {
    val f = for {
      _ <- client.start()
      _ <- client.subscribe(getDmFilter)
    } yield {
      logger.info(s"Started DM listener for ${client.url}")
      client.shutdownPOpt match {
        case Some(shutdownP) =>
          for {
            _ <- shutdownP.future
            _ = logger.info(
              s"Disconnected from nostr relay: ${client.url}, reconnecting...")
            _ = client.stop()
            _ <- startDmListener(client)
          } yield ()
          ()
        case None => logger.error("No shutdown promise for nostr client!")
      }
    }

    f.recoverWith { case err =>
      logger.error(s"Error starting DM listener for ${client.url}: $err")
      for {
        _ <- AsyncUtil.nonBlockingSleep(1.minute)
        _ <- startDmListener(client)
      } yield ()
    }
  }

  def listenForDMs(): Future[Unit] = {
    val startFs = dmClients.map(startDmListener)

    Future.sequence(startFs).map(_ => ())
  }

  lazy val nostrPrivateKey: ECPrivateKey =
    WalletStorage
      .getPrivateKeyFromDisk(config.seedPath,
                             SegWitMainNetPriv,
                             config.aesPasswordOpt,
                             config.bip39PasswordOpt)
      .key

  lazy val nostrPubKey: SchnorrPublicKey = nostrPrivateKey.schnorrPublicKey

  protected def announceOnNostr(
      message: String,
      txId: DoubleSha256DigestBE): Future[Option[Sha256Digest]] = {

    val censored = config.censorMessage(message)

    val content =
      s"""
         |🔔 🔔 NEW OP_RETURN 🔔 🔔
         |
         |$censored
         |
         |https://mempool.space/tx/${txId.hex}
         |""".stripMargin

    val event = NostrEvent.build(
      privateKey = nostrPrivateKey,
      created_at = TimeUtil.currentEpochSecond,
      kind = NostrKind.TextNote,
      tags = Vector.empty,
      content = content
    )

    sendNostrEvent(event, config.allRelays).flatMap { res =>
      if (censored != message) {
        val content =
          s"""
             |🔔 🔔 NEW OP_RETURN 🔔 🔔
             |
             |$message
             |
             |https://mempool.space/tx/${txId.hex}
             |""".stripMargin

        val event = NostrEvent.build(
          privateKey = nostrPrivateKey,
          created_at = TimeUtil.currentEpochSecond,
          kind = NostrKind.TextNote,
          tags = Vector.empty,
          content = content
        )

        sendNostrEvent(event, config.badBoyNostrRelays).map(_.orElse(res))
      } else Future.successful(res)
    }
  }

  protected def sendNostrDM(
      message: String,
      pubkey: SchnorrPublicKey): Future[Option[Sha256Digest]] = {
    val event =
      NostrEvent.encryptedDM(message,
                             nostrPrivateKey,
                             TimeUtil.currentEpochSecond,
                             Vector.empty,
                             pubkey)

    sendNostrEvent(event, config.allRelays)
  }

  private def sendingClients(relays: Vector[String]): Vector[NostrClient] = {
    relays.map { relay =>
      new NostrClient(relay, None) {

        override def processEvent(
            subscriptionId: String,
            event: NostrEvent): Future[Unit] = {
          Future.unit
        }

        override def processNotice(notice: String): Future[Unit] = Future.unit
      }
    }
  }

  def sendNostrEvent(
      event: NostrEvent,
      relays: Vector[String]): Future[Option[Sha256Digest]] = {
    val fs = sendingClients(relays).map { client =>
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
                logger.info(s"Sent nostr event ${NostrNoteId(id)}")
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
