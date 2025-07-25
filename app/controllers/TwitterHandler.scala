package controllers

import com.github.scribejava.core.model.{OAuthRequest, Verb}
import grizzled.slf4j.Logging
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.crypto.DoubleSha256DigestBE
import play.api.libs.json.{JsObject, JsValue, Json, Reads}

import scala.concurrent.{Future, Promise}
import scala.util.Try

case class TweetData(id: String, text: String)
case class TweetResult(data: Option[TweetData])

trait TwitterHandler extends Logging { self: InvoiceMonitor =>
  import system.dispatcher

  implicit val tweetDataReads: Reads[TweetData] = Json.reads[TweetData]
  implicit val tweetResultReads: Reads[TweetResult] = Json.reads[TweetResult]

  final val url = "https://api.twitter.com/2/tweets"

  def sendTweet(message: String): Future[TweetData] = {
    Promise
      .fromTry(Try {
        val request = new OAuthRequest(Verb.POST, url)
        request.addHeader("Content-Type", "application/json")
        request.setPayload(
          Json
            .obj(
              "text" -> Json.toJson(message)
            )
            .toString())

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
      txId: DoubleSha256DigestBE): Future[Option[TweetData]] = {
    if (Try(Json.parse(message).asOpt[JsObject].isDefined).getOrElse(false)) {
      logger.warn(s"Message is a JSON object, not sending to twitter")
      return Future.successful(None)
    }

    FutureUtil.makeAsync { () =>
      val usedMessage = config.censorMessage(message)

      val link =
        if (message.getBytes.length > 80)
          s"https://benpool.space/tx/${txId.hex}"
        else s"https://mempool.space/tx/${txId.hex}"

      val tweet =
        s"""
           |🔔 🔔 NEW OP_RETURN 🔔 🔔
           |
           |$usedMessage
           |
           |$link
           |""".stripMargin

      sendTweet(tweet).map(Some(_))
    }.flatten
  }
}
