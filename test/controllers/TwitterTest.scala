package controllers

import com.typesafe.config.{Config, ConfigFactory}
import config.OpReturnBotAppConfig
import org.bitcoins.core.util.EnvUtil
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.rpc.client.v24.BitcoindV24RpcClient
import org.bitcoins.testkit.BitcoinSTestAppConfig.tmpDir
import org.bitcoins.testkit.fixtures.{DualLndFixture, LndFixture}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

class TwitterTest extends DualLndFixture {

  val clientId = ""
  val clientSecret = ""
  val accessToken = ""
  val accessSecret = ""

  val twitterConfig: Config = {
    ConfigFactory.parseString(s"""
                                 |twitter {
                                 |  clientid = "$clientId"
                                 |  clientsecret = "$clientSecret"
                                 |  access.token = "$accessToken"
                                 |  access.secret = "$accessSecret"
                                 |}
                                 |""".stripMargin)
  }

  implicit val config: OpReturnBotAppConfig =
    OpReturnBotAppConfig(tmpDir(), Vector(twitterConfig))

  it must "send a tweet" in { param =>
    if (EnvUtil.isCI || clientId.isEmpty) {
      Future.successful(succeed)
    } else {
      val (b, lnd, _) = param
      val bitcoind = new OpReturnBitcoindClient(b.instance)

      val monitor =
        new InvoiceMonitor(lnd, bitcoind, None, ArrayBuffer.empty)

      for {
        tweet <- monitor.sendTweet("we're so back")
      } yield {
        assert(tweet.id != null)
        assert(tweet.id.length > 1)
      }
    }
  }
}
