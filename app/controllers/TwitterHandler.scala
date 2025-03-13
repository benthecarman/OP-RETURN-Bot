package controllers

import grizzled.slf4j.Logging
import io.github.redouane59.twitter.dto.tweet.Tweet
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.crypto.DoubleSha256DigestBE

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future, Promise}
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

  def sendTweet(message: String): Future[Tweet] = {
    Promise
      .fromTry(Try(config.twitterClient.postTweet(message)))
      .future
  }

  protected def handleTweet(
      message: String,
      txId: DoubleSha256DigestBE): Future[Tweet] =
    FutureUtil.makeAsync { () =>
      // Every 15th OP_RETURN we shill
      val count = shillCounter.getAndIncrement()
      if (count % 15 == 0 && count != 0) {
        shillTweet()
      }

      val usedMessage = config.censorMessage(message)

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

  private def shillTweet(): Future[Unit] = {
    if (uri != uriErrorString) {
      val tweet =
        s"""
           |Like OP_RETURN Bot?
           |
           |Consider connecting and opening a lightning channel!
           |
           |$uri
           |""".stripMargin

      sendTweet(tweet).map(_ => ()).recover { case ex =>
        logger.error(s"Failed to send shill tweet: ${ex.getMessage}")
        ()
      }
    } else Future.unit
  }
}
