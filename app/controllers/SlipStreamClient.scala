package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import grizzled.slf4j.Logging
import org.bitcoins.core.util.StartStopAsync
import org.bitcoins.tor.Socks5ClientTransport
import play.api.libs.json.{JsValue, Json, Reads}

import java.net.URI
import scala.concurrent._
import scala.util.Try

class SlipStreamClient()(implicit val system: ActorSystem)
    extends StartStopAsync[Unit]
    with Logging {
  implicit val ec: ExecutionContext = system.dispatcher

  private val http = Http(system)

  private var (queue, source) = Source
    .queue[Message](bufferSize = 10,
                    OverflowStrategy.backpressure,
                    maxConcurrentOffers = 2)
    .toMat(BroadcastHub.sink)(Keep.both)
    .run()

  private def setNewQueueAndSource(): Unit = {
    val (newQueue, newSource) = Source
      .queue[Message](bufferSize = 10,
                      OverflowStrategy.backpressure,
                      maxConcurrentOffers = 2)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()
    queue = newQueue
    source = newSource
  }

  private var subscriptionQueue: Option[(SourceQueueWithComplete[Message],
                                         Promise[Unit])] = None

  private def createHttpConnectionPoolSettings(): ConnectionPoolSettings = {
    Socks5ClientTransport.createConnectionPoolSettings(
      new URI(SlipStreamClient.URL),
      None)
  }

  def publishTx(broadcast: String): Future[Unit] = {
    val json = s"""{"tx_hex":"$broadcast"}"""
    queue.offer(TextMessage(json)).map(_ => ())
  }

  override def start(): Future[Unit] = {
    require(subscriptionQueue.isEmpty, "Already started")
    setNewQueueAndSource()

    val sink = Sink.foreachAsync[Message](5) {
      case TextMessage.Strict(text) =>
        val json: JsValue = Try {
          Json.parse(text)
        }.getOrElse {
          throw new RuntimeException(s"Could not parse json: $text")
        }
        json.asOpt[TxResponse] match {
          case Some(resp) =>
            logger.info(s"Received tx response: $resp")
          case None =>
            logger.warn(s"Received unexpected message: $json")
        }

        Future.unit
      case streamed: TextMessage.Streamed =>
        streamed.textStream.runWith(Sink.ignore)
        Future.unit
      case bm: BinaryMessage =>
        bm.dataStream.runWith(Sink.ignore)
        logger.warn("Received unexpected message")
        Future.unit
    }

    val shutdownP = Promise[Unit]()

    val flow = Flow.fromSinkAndSourceMat(sink, source)(Keep.left)
    val wsFlow = flow.watchTermination() { (_, termination) =>
      termination.onComplete { _ =>
        shutdownP.success(())
        subscriptionQueue = None
      }
    }

    val httpConnectionPoolSettings = createHttpConnectionPoolSettings()

    val (upgradeResponse, _) =
      http.singleWebSocketRequest(
        WebSocketRequest(SlipStreamClient.URL),
        wsFlow,
        settings = httpConnectionPoolSettings.connectionSettings)
    subscriptionQueue = Some((queue, shutdownP))

    upgradeResponse.map {
      case _: ValidUpgrade => ()
      case InvalidUpgradeResponse(response, cause) =>
        throw new RuntimeException(
          s"Connection failed ${response.status}: $cause")
    }
  }

  override def stop(): Future[Unit] = {
    subscriptionQueue match {
      case Some((queue, closedP)) =>
        queue.complete()
        subscriptionQueue = None
        closedP.future
      case None => Future.unit
    }
  }
}

case class TxResponse(tx_success: Option[String], tx_error: Option[String])

object TxResponse {
  implicit val TxResponseReads: Reads[TxResponse] = Json.reads[TxResponse]
}

private object SlipStreamClient {
  final val URL = "wss://slipstream.mara.com/api"
}
