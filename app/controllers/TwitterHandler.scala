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

  private def censorMessage(message: String): String = {
    val replacement = "$#@!&"
    message
      .replaceAll("(?i)nigger", replacement)
      .replaceAll("(?i)niggger", replacement)
      .replaceAll("(?i)nigggger", replacement)
      .replaceAll("(?i)niggggger", replacement)
      .replaceAll("(?i)nigggggger", replacement)
      .replaceAll("(?i)niggggggger", replacement)
      .replaceAll("(?i)niggr", replacement)
      .replaceAll("(?i)nigggr", replacement)
      .replaceAll("(?i)n i g g e r", replacement)
      .replaceAll("(?i)nifgger", replacement)
      .replaceAll("(?i)n1gger", replacement)
      .replaceAll("(?i)n1ggr", replacement)
      .replaceAll("(?i)n1ggger", replacement)
      .replaceAll("(?i)nigga", replacement)
      .replaceAll("(?i)niggga", replacement)
      .replaceAll("(?i)n1gga", replacement)
      .replaceAll("(?i)niglet", replacement)
      .replaceAll("(?i)n1glet", replacement)
      .replaceAll("(?i)nigl3t", replacement)
      .replaceAll("(?i)n1gl3t", replacement)
      .replaceAll("(?i)nig", replacement)
      .replaceAll("(?i)kike", replacement)
      .replaceAll("(?i)k1ke", replacement)
      .replaceAll("(?i)retard", replacement)
      .replaceAll("(?i)faggot", replacement)
      .replaceAll("(?i)f a g g o t", replacement)
      .replaceAll("(?i)fag", replacement)
      .replaceAll("(?i)f a g", replacement)
      .replaceAll("(?i)fagget", replacement)
      .replaceAll("(?i)fagget", replacement)
      .replaceAll("(?i)faggit", replacement)
      .replaceAll("(?i)beaner", replacement)
      .replaceAll("(?i)b e a n e r", replacement)
      .replaceAll("(?i)chink", replacement)
      .replaceAll("(?i)c h i n k", replacement)
      .replaceAll("(?i)coon", replacement)
      .replaceAll("(?i)koon", replacement)
      .replaceAll("(?i)gook", replacement)
      .replaceAll("(?i)sodomy", replacement)
  }
}
