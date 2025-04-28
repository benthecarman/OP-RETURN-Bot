package controllers

import com.typesafe.config.{Config, ConfigFactory}
import config.OpReturnBotAppConfig
import org.bitcoins.core.util.EnvUtil
import org.bitcoins.testkit.BitcoinSTestAppConfig.tmpDir
import org.bitcoins.testkit.fixtures.LndFixture

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

class TwitterTest extends LndFixture {

  val twitterConfig: Config = {
    ConfigFactory.parseString("""
                                |twitter {
                                |  clientid = ""
                                |  clientsecret = ""
                                |  access.token = ""
                                |  access.secret = ""
                                |}
                                |""".stripMargin)
  }

  implicit val config: OpReturnBotAppConfig =
    OpReturnBotAppConfig(tmpDir(), Vector(twitterConfig))

  it must "send a tweet" in { lnd =>
    if (EnvUtil.isCI) {
      Future.successful(succeed)
    } else {
      val monitor =
        new InvoiceMonitor(lnd, None, ArrayBuffer.empty)

      for {
        tweet <- monitor.sendTweet("test")
      } yield {
        assert(tweet.id != null)
        assert(tweet.id.length > 1)
      }
    }
  }
}
