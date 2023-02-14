package models

import config.OpReturnBotAppConfig
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.protocol.ln.LnTag.PaymentHashTag
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.{ForeignKeyQuery, ProvenShape}

import scala.concurrent.{ExecutionContext, Future}

case class Nip5Db(
    rHash: Sha256Digest,
    name: String,
    publicKey: SchnorrPublicKey)

case class Nip5DAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: OpReturnBotAppConfig)
    extends CRUD[Nip5Db, Sha256Digest]
    with SlickUtil[Nip5Db, Sha256Digest] {

  import profile.api._

  private val invoiceTable: TableQuery[InvoiceDAO#InvoiceTable] =
    InvoiceDAO().table

  private val mappers = new DbCommonsColumnMappers(profile)

  import mappers._

  override val table: TableQuery[Nip5Table] = TableQuery[Nip5Table]

  override def createAll(ts: Vector[Nip5Db]): Future[Vector[Nip5Db]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[Sha256Digest]): Query[Nip5Table, Nip5Db, Seq] =
    table.filter(_.rHash.inSet(ids))

  override protected def findAll(
      ts: Vector[Nip5Db]): Query[Nip5Table, Nip5Db, Seq] =
    findByPrimaryKeys(ts.map(_.rHash))

  def getPublicKeyAction(name: String): DBIOAction[Option[SchnorrPublicKey],
                                                   NoStream,
                                                   Effect.Read] = {
    table
      .join(invoiceTable)
      .on(_.rHash === _.rHash)
      .filter(_._1.name === name)
      .filter(_._2.txIdOpt.isDefined)
      .result
      .map(_.headOption.map(_._1.publicKey))
  }

  def getPublicKey(name: String): Future[Option[SchnorrPublicKey]] = {
    safeDatabase.run(getPublicKeyAction(name))
  }

  def getNumCompletedAction(): DBIOAction[Int, NoStream, Effect.Read] = {
    table
      .join(invoiceTable)
      .on(_.rHash === _.rHash)
      .filter(_._2.txIdOpt.isDefined)
      .result
      .map(_.size)
  }

  class Nip5Table(tag: Tag) extends Table[Nip5Db](tag, schemaName, "nip5") {

    def rHash: Rep[Sha256Digest] = column("r_hash", O.PrimaryKey)

    def name: Rep[String] = column("name")

    def publicKey: Rep[SchnorrPublicKey] = column("public_key")

    def * : ProvenShape[Nip5Db] =
      (rHash, name, publicKey).<>(Nip5Db.tupled, Nip5Db.unapply)

    def fk: ForeignKeyQuery[_, InvoiceDb] = {
      foreignKey("nip5_fk", rHash, invoiceTable)(_.rHash)
    }
  }
}
