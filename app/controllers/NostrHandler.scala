package controllers

import grizzled.slf4j.Logging
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.core.config.MainNet
import org.bitcoins.core.crypto.ExtKeyVersion.SegWitMainNetPriv
import org.bitcoins.core.currency.Bitcoins
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.ln.LnTag._
import org.bitcoins.core.protocol.ln._
import org.bitcoins.core.protocol.ln.currency.{LnCurrencyUnits, MilliSatoshis}
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.crypto._
import org.bitcoins.keymanager.WalletStorage
import org.scalastr.client.NostrClient
import org.scalastr.core._
import play.api.libs.json.{JsArray, JsString, Json}
import scodec.bits.HexStringSyntax

import java.net.URL
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

trait NostrHandler extends Logging { self: InvoiceMonitor =>
  import system.dispatcher

  def setNostrMetadata(): Future[Vector[Sha256Digest]] = {
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

    sendNostrEvents(Vector(event, contacts), config.allRelays)
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
      logger.debug(s"Started DM listener for ${client.url}")
      client.shutdownPOpt match {
        case Some(shutdownP) =>
          for {
            _ <- shutdownP.future
            _ = logger.debug(
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
      npubOpt: Option[SchnorrPublicKey],
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

    val tags = npubOpt.orElse(NostrPublicKey.fromStringOpt(message)) match {
      case Some(npub) => Vector(Json.arr("p", npub.hex))
      case None       => Vector.empty
    }

    val event = NostrEvent.build(
      privateKey = nostrPrivateKey,
      created_at = TimeUtil.currentEpochSecond,
      kind = NostrKind.TextNote,
      tags = tags,
      content = content
    )

    sendNostrEvents(Vector(event), config.allRelays).map(_.headOption).flatMap {
      res =>
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

          sendNostrEvents(Vector(event), config.badBoyNostrRelays).map(
            _.headOption.orElse(res))
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

    sendNostrEvents(Vector(event), config.allRelays).map(_.headOption)
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

  def sendNostrEvents(
      event: Vector[NostrEvent],
      relays: Vector[String]): Future[Vector[Sha256Digest]] = {
    val fs = sendingClients(relays).map { client =>
      Try(Await.result(client.start(), 5.seconds)) match {
        case Failure(_) =>
          logger.warn(s"Failed to connect to nostr relay: ${client.url}")
          Future.successful(None)
        case Success(_) =>
          val sendFs = event.map { event =>
            client
              .publishEvent(event)
              .map(_ => Some(event.id))
              .recover(_ => None)
          }

          val f = for {
            ids <- Future.sequence(sendFs)
            _ <- client.stop()
          } yield ids.flatten

          f.recover(_ => None)
      }
    }

    Future.sequence(fs).map(_.flatten)
  }

  def getNote(noteId: NostrNoteId): Future[Option[NostrEvent]] = {
    val events: mutable.Buffer[NostrEvent] = mutable.Buffer.empty[NostrEvent]

    val clients: Vector[NostrClient] = {
      config.nostrRelays.map { relay =>
        new NostrClient(relay, None) {

          override def unsubOnEOSE: Boolean = true

          override def processEvent(
              subscriptionId: String,
              event: NostrEvent): Future[Unit] = {
            if (event.id == noteId.id) {
              events += event
            }
            Future.unit
          }

          override def processNotice(notice: String): Future[Unit] =
            Future.unit
        }
      }
    }

    val filter: NostrFilter = NostrFilter(
      ids = Some(Vector(noteId.id)),
      authors = None,
      kinds = None,
      `#e` = None,
      `#p` = None,
      since = None,
      until = None,
      limit = Some(1)
    )

    clients.map { client =>
      Try(Await.result(client.start(), 5.seconds)) match {
        case Failure(_) =>
          logger.warn(s"Failed to connect to nostr relay: ${client.url}")
          Future.unit
        case Success(_) => client.subscribe(filter)
      }
    }

    AsyncUtil
      .awaitCondition(() => events.nonEmpty, maxTries = 100)
      .map(_ => events.headOption)
  }

  def createFakeZapEvents(noteId: NostrNoteId): Future[Vector[NostrEvent]] = {
    getNote(noteId).map {
      case None => throw new RuntimeException(s"Failed to get note $noteId")
      case Some(note) =>
        require(note.id == noteId.id)

        val requestTags = Vector(
          Json.arr("p", note.pubkey.hex),
          Json.arr("e", noteId.hex)
        )

        val relays = JsArray(("relays" +: config.allRelays).map(JsString))
        val msats = MilliSatoshis(Bitcoins(1_000))

        val request = NostrEvent.build(
          privateKey = ECPrivateKey.freshPrivateKey,
          created_at = TimeUtil.currentEpochSecond,
          kind = NostrKind.ZapRequest,
          tags = requestTags :+ relays,
          content = ""
        )

        require(NostrEvent.isValidZapRequest(event = request, msats, None),
                s"Zap Request invalid: ${Json.toJson(request)}")

        val requestJson = Json.toJson(request).toString

        val preImage = CryptoUtil.randomBytes(32)
        val hash = CryptoUtil.sha256(preImage)
        val paymentSecret = CryptoUtil.randomBytes(32)

        val hashTag = PaymentHashTag(hash)
        val memoTag = DescriptionHashTag(CryptoUtil.sha256(requestJson))
        val expiryTimeTag = ExpiryTimeTag(UInt32(360))
        val paymentSecretTag = SecretTag(PaymentSecret(paymentSecret))
        val featuresTag = FeaturesTag(hex"2420") // copied from a LND invoice

        val lnTags = LnTaggedFields(
          Vector(hashTag,
                 memoTag,
                 expiryTimeTag,
                 paymentSecretTag,
                 featuresTag))

        val invoice = LnInvoice.build(
          LnHumanReadablePart(MainNet, LnCurrencyUnits.fromMSat(msats)),
          lnTags,
          ECPrivateKey.freshPrivateKey)

        val tags = Vector(
          Json.arr("bolt11", invoice.toString),
          Json.arr("preimage", preImage.toBase16),
          Json.arr("description", requestJson)
        ) ++ requestTags

        val zapEvent =
          NostrEvent.build(
            config.extraNostrPrivKey.map(_.key).getOrElse(nostrPrivateKey),
            TimeUtil.currentEpochSecond,
            NostrKind.Zap,
            tags,
            "")

        Vector(note, zapEvent)
    }
  }

  def createFakeZap(noteId: NostrNoteId): Future[Sha256Digest] = {
    createFakeZapEvents(noteId).flatMap(events =>
      sendNostrEvents(events, config.allRelays).map(_.last))
  }
}
