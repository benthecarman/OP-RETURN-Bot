package startup

import org.apache.pekko.actor.ActorSystem
import config.OpReturnBotAppConfig
import grizzled.slf4j.Logging
import play.api.inject.ApplicationLifecycle

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

@Singleton
class OpReturnBotStartup @Inject() (
    config: OpReturnBotAppConfig,
    lifecycle: ApplicationLifecycle,
    actorSystem: ActorSystem
) extends Logging {

  implicit private val ec: ExecutionContext = actorSystem.dispatcher

  private val startupTimeout = 60.seconds

  private val startF: Future[Unit] = config.start()

  startF.failed.foreach(err => logger.error("Failed to start app config", err))

  Await.result(startF, startupTimeout)

  lifecycle.addStopHook(() => config.stop())
}
