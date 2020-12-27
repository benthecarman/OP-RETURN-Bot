package models

import config.OpReturnBotAppConfig
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.crypto._
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}

case class InvoiceDb(
    invoice: LnInvoice,
    tx: Transaction,
    txId: DoubleSha256DigestBE)

case class InvoiceDAO()(implicit
    val ec: ExecutionContext,
    override val appConfig: OpReturnBotAppConfig)
    extends CRUD[InvoiceDb, LnInvoice]
    with SlickUtil[InvoiceDb, LnInvoice] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  implicit val invoiceMapper: BaseColumnType[LnInvoice] =
    MappedColumnType.base[LnInvoice, String](_.toString(), LnInvoice.fromString)

  import mappers._

  override val table: TableQuery[InvoiceTable] = TableQuery[InvoiceTable]

  override def createAll(ts: Vector[InvoiceDb]): Future[Vector[InvoiceDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[LnInvoice]): Query[InvoiceTable, InvoiceDb, Seq] =
    table.filter(_.invoice.inSet(ids))

  override protected def findAll(
      ts: Vector[InvoiceDb]): Query[InvoiceTable, InvoiceDb, Seq] =
    findByPrimaryKeys(ts.map(_.invoice))

  def findByTxId(txId: DoubleSha256DigestBE): Future[Option[InvoiceDb]] = {
    val query = table.filter(_.txId === txId)

    safeDatabase.run(query.result).map(_.headOption)
  }

  class InvoiceTable(tag: Tag)
      extends Table[InvoiceDb](tag, schemaName, "invoices") {

    def invoice: Rep[LnInvoice] = column("invoice", O.PrimaryKey)

    def transaction: Rep[Transaction] = column("transaction")

    def txId: Rep[DoubleSha256DigestBE] = column("txid")

    def * : ProvenShape[InvoiceDb] =
      (invoice, transaction, txId).<>(InvoiceDb.tupled, InvoiceDb.unapply)
  }
}
