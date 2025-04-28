package controllers

import com.github.scribejava.core.model.{OAuthRequest, Verb}
import grizzled.slf4j.Logging
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.crypto.DoubleSha256DigestBE
import play.api.libs.json.{JsValue, Json, Reads}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future, Promise}
import scala.util.Try

case class TweetData(id: String, text: String)
case class TweetResult(data: Option[TweetData])

trait TwitterHandler extends Logging { self: InvoiceMonitor =>
  import system.dispatcher

  private lazy val shillCounter: AtomicInteger = {
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

  implicit val tweetDataReads: Reads[TweetData] = Json.reads[TweetData]
  implicit val tweetResultReads: Reads[TweetResult] = Json.reads[TweetResult]

  final val url = "https://api.twitter.com/2/tweets"

  def sendTweet(message: String): Future[TweetData] = {
    Promise
      .fromTry(Try {
        val request = new OAuthRequest(Verb.POST, url)
        request.addHeader("Content-Type", "application/json")
        request.setPayload("{\"text\":\"" + message + "\"}")

        config.twitterClient.signRequest(config.twitterAccessToken, request)

        val response = config.twitterClient.execute(request)

        System.out.println("Status code: " + response.getCode)
        System.out.println("Response body: " + response.getBody)

        val json: JsValue = Try {
          Json.parse(response.getBody)
        }.getOrElse {
          throw new RuntimeException(
            s"Could not parse json: ${response.getBody}")
        }
        json.asOpt[TweetResult] match {
          case Some(resp) =>
            resp.data match {
              case Some(data) => data
              case None       => throw new RuntimeException("No response data")
            }
          case None =>
            throw new RuntimeException(s"Received unexpected message: $json")
        }
      })
      .future
  }

  protected def handleTweet(
      message: String,
      txId: DoubleSha256DigestBE): Future[TweetData] =
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
