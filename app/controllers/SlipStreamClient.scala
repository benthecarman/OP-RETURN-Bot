package controllers

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpMethods,
  HttpRequest
}

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

private object SlipStreamClient {
  final val URL = "wss://slipstream.mara.com/submit-tx"
}
