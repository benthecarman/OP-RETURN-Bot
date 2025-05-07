package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpMethods,
  HttpRequest
}
import play.api.libs.json.{Json, Reads}

import scala.concurrent._

class SlipStreamClient()(implicit val system: ActorSystem) {
  implicit val ec: ExecutionContext = system.dispatcher

  private val http = Http(system)

  def publishTx(broadcast: String): Future[Unit] = {
    val json = s"""{"tx_hex":"$broadcast"}"""

    // post request
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = SlipStreamClient.URL,
      entity = HttpEntity(ContentTypes.`application/json`, json)
    )

    // send request
    http.singleRequest(request).map(_ => ())
  }
}

case class TxResponse(tx_success: Option[String], tx_error: Option[String])

object TxResponse {
  implicit val TxResponseReads: Reads[TxResponse] = Json.reads[TxResponse]
}

private object SlipStreamClient {
  final val URL = "wss://slipstream.mara.com/submit-tx"
}
