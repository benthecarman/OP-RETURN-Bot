package controllers

import config.OpReturnBotAppConfig
import org.bitcoins.testkit.BitcoinSTestAppConfig.tmpDir
import org.bitcoins.testkit.util.BitcoinSAsyncTest

class CensorMessageTest extends BitcoinSAsyncTest {

  implicit val config: OpReturnBotAppConfig =
    OpReturnBotAppConfig(tmpDir(), Vector.empty)

  it must "censor a message properly" in {
    val string = "hello world penis"

    val msg = config.censorMessage(string, Vector("penis"))
    assert(msg != string)
  }
}
