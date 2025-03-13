package controllers

import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpHeader}
import grizzled.slf4j.Logging
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.crypto.DoubleSha256DigestBE
import play.api.libs.json.{Json, Reads}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.Try

case class TweetData(id: String, text: String)
case class TweetResult(data: Option[TweetData])

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

  private val http = Http()
  implicit val tweetDataReads: Reads[TweetData] = Json.reads[TweetData]
  implicit val tweetResultReads: Reads[TweetResult] = Json.reads[TweetResult]

  def sendTweet(message: String): Future[TweetData] = {
    val url = "https://api.twitter.com/2/tweets"

    val json = Json.obj(
      "text" -> message
    )
    val entity = HttpEntity(ContentTypes.`application/json`, json.toString)
    val header = HttpHeader.parse("Authorization",
                                  s"Bearer ${config.twitterBearer}") match {
      case HttpHeader.ParsingResult.Ok(h, _) => h
      case _ =>
        return Future.failed(new RuntimeException("Failed to parse header"))
    }
    val req = Post(url, entity).withHeaders(Vector(header))
    http.singleRequest(req).flatMap { response =>
      response.entity.dataBytes.runFold("")(_ + _.utf8String).map { body =>
        val json = Json.parse(body)
        val res = json.validate[TweetResult].get
        res.data match {
          case Some(data) =>
            logger.info(s"Tweet sent: ${data.text}")
            data
          case None =>
            logger.error(s"Failed to send tweet: $body")
            throw new RuntimeException("Failed to send tweet")
        }
      }
    }
  }

  protected def handleTweet(
      message: String,
      txId: DoubleSha256DigestBE): Future[TweetData] = FutureUtil.makeAsync {
    () =>
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
