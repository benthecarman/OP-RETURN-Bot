package models

import config.OpReturnBotAppConfig
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}

case class InvoiceDb(
    rHash: Sha256Digest,
    invoice: LnInvoice,
    message: String,
    hash: Boolean,
    feeRate: SatoshisPerVirtualByte,
    txOpt: Option[Transaction],
    txIdOpt: Option[DoubleSha256DigestBE],
    profitOpt: Option[CurrencyUnit])

case class InvoiceDAO()(implicit
    val ec: ExecutionContext,
    override val appConfig: OpReturnBotAppConfig)
    extends CRUD[InvoiceDb, Sha256Digest]
    with SlickUtil[InvoiceDb, Sha256Digest] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  import mappers._

  override val table: TableQuery[InvoiceTable] = TableQuery[InvoiceTable]

  override def createAll(ts: Vector[InvoiceDb]): Future[Vector[InvoiceDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[Sha256Digest]): Query[InvoiceTable, InvoiceDb, Seq] =
    table.filter(_.rHash.inSet(ids))

  override protected def findAll(
      ts: Vector[InvoiceDb]): Query[InvoiceTable, InvoiceDb, Seq] =
    findByPrimaryKeys(ts.map(_.rHash))

  def findByTxId(txId: DoubleSha256DigestBE): Future[Option[InvoiceDb]] = {
    val query = table.filter(_.txIdOpt === txId)

    safeDatabase.run(query.result).map(_.headOption)
  }

  def lastFiveCompleted(): Future[Vector[DoubleSha256DigestBE]] = {
    val query = table.filter(_.txIdOpt.isDefined).map(_.txIdOpt)

    safeDatabase.runVec(query.result).map(_.flatten.takeRight(5))
  }

  def totalProfit(): Future[CurrencyUnit] = {
    val query = table.filter(_.profitOpt.isDefined).map(_.profitOpt)

    safeDatabase.runVec(query.result).map(_.flatten.sum)
  }

  class InvoiceTable(tag: Tag)
      extends Table[InvoiceDb](tag, schemaName, "invoices") {

    def rHash: Rep[Sha256Digest] = column("r_hash", O.PrimaryKey)

    def invoice: Rep[LnInvoice] = column("invoice")

    def message: Rep[String] = column("message")

    def hash: Rep[Boolean] = column("hash")

    def feeRate: Rep[SatoshisPerVirtualByte] = column("fee_rate")

    def transactionOpt: Rep[Option[Transaction]] = column("transaction")

    def txIdOpt: Rep[Option[DoubleSha256DigestBE]] = column("txid")

    def profitOpt: Rep[Option[CurrencyUnit]] = column("profit")

    def * : ProvenShape[InvoiceDb] =
      (rHash,
       invoice,
       message,
       hash,
       feeRate,
       transactionOpt,
       txIdOpt,
       profitOpt).<>(InvoiceDb.tupled, InvoiceDb.unapply)
  }
}
