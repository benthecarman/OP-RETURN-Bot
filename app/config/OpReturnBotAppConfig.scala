package config

import com.typesafe.config.Config
import controllers.EclairBitcoindPair
import grizzled.slf4j.Logging
import models.InvoiceDAO
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.db.{
  AppConfigFactory,
  DbAppConfig,
  DbManagement,
  JdbcProfileComponent
}
import org.bitcoins.rpc.client.common.BitcoindVersion

import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Properties

/** Configuration for the Bitcoin-S wallet
  *
  * @param directory The data directory of the wallet
  * @param conf      Optional sequence of configuration overrides
  */
case class OpReturnBotAppConfig(
    private val directory: Path,
    private val conf: Config*)(implicit ec: ExecutionContext)
    extends DbAppConfig
    with JdbcProfileComponent[OpReturnBotAppConfig]
    with DbManagement
    with Logging {
  override val configOverrides: List[Config] = conf.toList
  override val moduleName: String = "opreturnbot"
  override type ConfigType = OpReturnBotAppConfig

  override val appConfig: OpReturnBotAppConfig = this

  import profile.api._

  override def newConfigOfType(configs: Seq[Config]): OpReturnBotAppConfig =
    OpReturnBotAppConfig(directory, configs: _*)

  val baseDatadir: Path = directory

  lazy val eclairDataDir: Path =
    Paths.get(config.getString(s"bitcoin-s.eclair.datadir"))

  lazy val eclairBinary: File =
    Paths.get(config.getString(s"bitcoin-s.eclair.binary")).toFile

  lazy val bitcoindDataDir: Path =
    Paths.get(config.getString(s"bitcoin-s.bitcoind.datadir"))

  lazy val bitcoindBinary: File =
    Paths.get(config.getString(s"bitcoin-s.bitcoind.binary")).toFile

  lazy val bitcoindVersion: BitcoindVersion = BitcoindVersion.newest
  //  = {
  //    val versionStr = config.getString(s"$moduleName.bitcoind.version")
  //    val versionOpt = BitcoindVersion.fromString(versionStr)
  //    versionOpt match {
  //      case None =>
  //        throw new RuntimeException(
  //          s"$versionStr is not a valid bitcoind version")
  //      case Some(version) =>
  //        version
  //    }
  //  }

  override def start(): Future[Unit] = {
    logger.debug(s"Initializing setup")

    if (Files.notExists(baseDatadir)) {
      Files.createDirectories(baseDatadir)
    }

    if (Files.notExists(eclairDataDir)) {
      throw new RuntimeException(
        s"Cannot find eclair data dir at ${eclairDataDir.toString}")
    }

    if (Files.notExists(bitcoindDataDir)) {
      throw new RuntimeException(
        s"Cannot find bitcoind data dir at ${bitcoindDataDir.toString}")
    }

    logger.debug(s"Initializing eclair with bitcoind version $bitcoindVersion")

    FutureUtil
      .sequentially(allTables)(table => createTable(table))
      .map(_ => ())
  }

  override def stop(): Future[Unit] = FutureUtil.unit

  override lazy val dbPath: Path = baseDatadir

  lazy val eclairBitcoindPair: EclairBitcoindPair =
    EclairBitcoindPair.fromConfig(this)

  override val allTables: List[TableQuery[Table[_]]] =
    List(InvoiceDAO()(ec, this).table)
}

object OpReturnBotAppConfig extends AppConfigFactory[OpReturnBotAppConfig] {

  val DEFAULT_DATADIR: Path = Paths.get(Properties.userHome, ".op-return-bot")

  override def fromDefaultDatadir(confs: Vector[Config] = Vector.empty)(implicit
      ec: ExecutionContext): OpReturnBotAppConfig = {
    fromDatadir(DEFAULT_DATADIR, confs)
  }

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      ec: ExecutionContext): OpReturnBotAppConfig =
    OpReturnBotAppConfig(datadir, confs: _*)
}
