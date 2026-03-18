package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import grizzled.slf4j.Logging
import play.api.libs.json.Json

import scala.concurrent._

class BtcPriceFetcher()(implicit
    system: ActorSystem,
    ec: ExecutionContext)
    extends Logging {

  private val http = Http(system)

  /** Fetches BTC/USD price in cents from Coinbase */
  def fetchPrice(): Future[Long] = {
    val request = HttpRequest(
      uri = "https://api.coinbase.com/v2/prices/BTC-USD/spot"
    )

    http
      .singleRequest(request)
      .flatMap(res => Unmarshal(res.entity).to[String])
      .map { body =>
        val json = Json.parse(body)
        val priceStr = (json \ "data" \ "amount").as[String]
        val dollars = BigDecimal(priceStr)
        (dollars * 100).toLongExact
      }
      .recover { case ex =>
        logger.error(s"Failed to fetch BTC price: ${ex.getMessage}")
        0L
      }
  }
}
