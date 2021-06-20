package controllers

import com.danielasfregola.twitter4s.TwitterRestClient
import com.danielasfregola.twitter4s.entities.Tweet
import grizzled.slf4j.Logging
import org.bitcoins.crypto.DoubleSha256DigestBE

import scala.concurrent.Future

trait TwitterHandler extends Logging {

  protected def sendTweet(message: String): Future[Tweet] = {
    val client = TwitterRestClient()

    client.createTweet(status = message, possibly_sensitive = true)
  }

  protected def handleTweet(
      message: String,
      txId: DoubleSha256DigestBE): Future[Tweet] = {
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
}
