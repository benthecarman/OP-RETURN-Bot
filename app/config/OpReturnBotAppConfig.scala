package config

import akka.actor.ActorSystem
import com.typesafe.config.Config
import grizzled.slf4j.Logging
import models.InvoiceDAO
import org.bitcoins.commons.config._
import org.bitcoins.db._
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.config.{LndInstance, LndInstanceLocal}
import org.bitcoins.rpc.client.common.BitcoindVersion

import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.concurrent._
import scala.util.Properties

/** Configuration for the Bitcoin-S wallet
  *
  * @param directory The data directory of the wallet
  * @param conf      Optional sequence of configuration overrides
  */
case class OpReturnBotAppConfig(
    private val directory: Path,
    private val conf: Config*)(implicit system: ActorSystem)
    extends DbAppConfig
    with JdbcProfileComponent[OpReturnBotAppConfig]
    with DbManagement
    with Logging {
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  override val configOverrides: List[Config] = conf.toList
  override val moduleName: String = OpReturnBotAppConfig.moduleName
  override type ConfigType = OpReturnBotAppConfig

  override val appConfig: OpReturnBotAppConfig = this

  import profile.api._

  override def newConfigOfType(configs: Seq[Config]): OpReturnBotAppConfig =
    OpReturnBotAppConfig(directory, configs: _*)

  val baseDatadir: Path = directory

  lazy val startBinaries: Boolean =
    config.getBoolean(s"bitcoin-s.$moduleName.startBinaries")

  lazy val lndDataDir: Path =
    Paths.get(config.getString(s"bitcoin-s.lnd.datadir"))

  lazy val lndBinary: File =
    Paths.get(config.getString(s"bitcoin-s.lnd.binary")).toFile

  lazy val bitcoindBinary: File =
    Paths.get(config.getString(s"bitcoin-s.bitcoind.binary")).toFile

  lazy val telegramCreds: String =
    config.getString(s"bitcoin-s.$moduleName.telegramCreds")

  lazy val telegramId: String =
    config.getString(s"bitcoin-s.$moduleName.telegramId")

  lazy val bitcoindVersion: BitcoindVersion = BitcoindVersion.newest

  override def start(): Future[Unit] = {
    logger.info(s"Initializing setup")

    if (Files.notExists(baseDatadir)) {
      Files.createDirectories(baseDatadir)
    }

    if (Files.notExists(lndDataDir)) {
      throw new RuntimeException(
        s"Cannot find lnd data dir at ${lndDataDir.toString}")
    }

    val numMigrations = migrate()
    logger.info(s"Applied $numMigrations")

    Future.unit
  }

  override def stop(): Future[Unit] = Future.unit

  override lazy val dbPath: Path = baseDatadir

  lazy val lndInstance: LndInstance =
    LndInstanceLocal.fromDataDir(lndDataDir.toFile)

  lazy val lndRpcClient: LndRpcClient =
    LndRpcClient(lndInstance, Some(lndBinary))

  override val allTables: List[TableQuery[Table[_]]] =
    List(InvoiceDAO()(ec, this).table)
}

object OpReturnBotAppConfig
    extends AppConfigFactoryBase[OpReturnBotAppConfig, ActorSystem] {

  val DEFAULT_DATADIR: Path = Paths.get(Properties.userHome, ".op-return-bot")

  override def fromDefaultDatadir(confs: Vector[Config] = Vector.empty)(implicit
      ec: ActorSystem): OpReturnBotAppConfig = {
    fromDatadir(DEFAULT_DATADIR, confs)
  }

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      ec: ActorSystem): OpReturnBotAppConfig =
    OpReturnBotAppConfig(datadir, confs: _*)

  override val moduleName: String = "opreturnbot"
}
