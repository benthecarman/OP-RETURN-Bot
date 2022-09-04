package controllers

import com.danielasfregola.twitter4s.entities.Tweet
import grizzled.slf4j.Logging
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.crypto.DoubleSha256DigestBE

import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.Try

trait TwitterHandler extends Logging { self: InvoiceMonitor =>
  import system.dispatcher

  lazy val shillCounter: AtomicInteger = {
    // set shill counter based off db
    val f = invoiceDAO.numCompleted()
    val res = Try(Await.result(f, 60.seconds))
    new AtomicInteger(res.getOrElse(0))
  }

  val uriErrorString = "Error: try again"
  var uri: String = uriErrorString

  def setURI(): Future[Unit] = {
    lnd.getInfo.map { info =>
      val torAddrOpt = info.uris.find(_.contains(".onion"))

      uri = torAddrOpt.getOrElse(info.uris.head)
    }
  }

  protected def sendTweet(message: String): Future[Tweet] = {
    val client = config.twitterClient

    client.createTweet(status = message, possibly_sensitive = true)
  }

  protected def handleTweet(
      message: String,
      txId: DoubleSha256DigestBE): Future[Tweet] = FutureUtil.makeAsync { () =>
    // Every 15th OP_RETURN we shill
    val count = shillCounter.getAndIncrement()
    if (count % 15 == 0 && count != 0) {
      shillTweet()
    }

    val usedMessage = censorMessage(message)

    val tweet =
      s"""
         |🔔 🔔 NEW OP_RETURN 🔔 🔔
         |
         |$usedMessage
         |
         |https://mempool.space/tx/${txId.hex}
         |""".stripMargin

    sendTweet(tweet)
  }.flatten

  protected def shillTweet(): Future[Unit] = {
    if (uri != uriErrorString) {
      val tweet =
        s"""
           |Like OP_RETURN Bot?
           |
           |Consider connecting and opening a lightning channel!
           |
           |$uri
           |""".stripMargin

      sendTweet(tweet).map(_ => ())
    } else Future.unit
  }

  private def censorMessage(message: String): String = {
    val replacement = "$#@!&"

    config.bannedWords.foldLeft(message) { case (msg, bannedWord) =>
      val myPattern = Pattern.compile(bannedWord, Pattern.CASE_INSENSITIVE)
      myPattern.matcher(msg).replaceAll(replacement)
    }
  }
}
