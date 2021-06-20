package controllers

import com.danielasfregola.twitter4s.TwitterRestClient
import com.danielasfregola.twitter4s.entities.Tweet
import grizzled.slf4j.Logging
import org.bitcoins.crypto.DoubleSha256DigestBE

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

trait TwitterHandler extends Logging { self: Controller =>

  val shillCounter: AtomicInteger = new AtomicInteger(0)

  protected def sendTweet(message: String): Future[Tweet] = {
    val client = TwitterRestClient()

    client.createTweet(status = message, possibly_sensitive = true)
  }

  protected def handleTweet(
      message: String,
      txId: DoubleSha256DigestBE): Future[Tweet] = {
    // Every 15th OP_RETURN we shill
    val count = shillCounter.getAndIncrement()
    if (count % 15 == 0 && count != 0) {
      shillTweet()
    }

    val tweet =
      s"""
         |🔔 🔔 NEW OP_RETURN 🔔 🔔
         |
         |$message
         |
         |https://mempool.space/tx/${txId.hex}
         |""".stripMargin

    sendTweet(tweet)
  }

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
}
