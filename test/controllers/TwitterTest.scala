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
    ConfigFactory.parseString(
      """
        |twitter {
        |  clientid = "4o7jynudksbCA51FtrOedXGwX"
        |  clientsecret = "uW44r8udEycx1k4cZC4kgrSwJ367NPBLzkWwdRGX3ro9zEGBaG"
        |  access.token = "1234956880742682625-79ilHjBjn89LXRMhNVRehn0yk9RI1p"
        |  access.secret = "rZjnJWjco2CZgCoCstz9IMzuncg9ggECychOTD32YjjbI"
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
        tweet <- monitor.sendTweet("we're so back")
      } yield {
        assert(tweet.id != null)
        assert(tweet.id.length > 1)
      }
    }
  }
}
