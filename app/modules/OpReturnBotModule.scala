package modules

import org.apache.pekko.actor.ActorSystem
import com.google.inject.AbstractModule
import com.google.inject.Provides
import config.OpReturnBotAppConfig
import play.api.inject.ApplicationLifecycle
import startup.OpReturnBotStartup

import javax.inject.Singleton
import scala.concurrent.Future

class OpReturnBotModule extends AbstractModule {

  override def configure(): Unit = {
    bind(classOf[OpReturnBotStartup]).asEagerSingleton()
  }

  @Provides
  @Singleton
  def pekkoActorSystem(lifecycle: ApplicationLifecycle): ActorSystem = {
    implicit val system: ActorSystem = ActorSystem("op-return-bot")

    lifecycle.addStopHook { () =>
      system.terminate().map(_ => ())(system.dispatcher)
    }

    system
  }

  @Provides
  @Singleton
  def opReturnBotAppConfig(actorSystem: ActorSystem): OpReturnBotAppConfig = {
    implicit val system: ActorSystem = actorSystem
    OpReturnBotAppConfig.fromDefaultDatadir()
  }
}
