package models

import config.OpReturnBotAppConfig
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.crypto._
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}

case class InvoiceDb(
    rHash: Sha256Digest,
    opReturnRequestId: Long, // Foreign key to OpReturnRequestDb.id
    invoice: LnInvoice,
    paid: Boolean)

case class InvoiceDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: OpReturnBotAppConfig)
    extends CRUD[InvoiceDb, Sha256Digest]
    with SlickUtil[InvoiceDb, Sha256Digest] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)
  import mappers._

  // Reference to the OpReturnRequestDAO table for foreign key
  private val opReturnRequestTableQuery: TableQuery[
    OpReturnRequestDAO#OpReturnRequestTable] =
    OpReturnRequestDAO().table

  override val table: TableQuery[InvoiceTable] = TableQuery[InvoiceTable]

  override def createAll(ts: Vector[InvoiceDb]): Future[Vector[InvoiceDb]] =
    createAllNoAutoInc(ts, safeDatabase) // rHash is not auto-incrementing

  override protected def findByPrimaryKeys(
      ids: Vector[Sha256Digest]): Query[InvoiceTable, InvoiceDb, Seq] =
    table.filter(_.rHash.inSet(ids))

  override protected def findAll(
      ts: Vector[InvoiceDb]): Query[InvoiceTable, InvoiceDb, Seq] =
    findByPrimaryKeys(ts.map(_.rHash))

  def findByOpReturnRequestIdAction(
      opReturnRequestId: Long): DBIOAction[Option[InvoiceDb],
                                           NoStream,
                                           Effect.Read] = {
    table.filter(_.opReturnRequestId === opReturnRequestId).result.headOption
  }

  def findOpReturnRequestByRHashAction(
      rHash: Sha256Digest): DBIOAction[Option[(InvoiceDb, OpReturnRequestDb)],
                                       NoStream,
                                       Effect.Read] = {
    table
      .filter(_.rHash === rHash)
      .join(opReturnRequestTableQuery)
      .on(_.opReturnRequestId === _.id)
      .result
      .headOption
  }

  def findOpReturnRequestByRHash(
      rHash: Sha256Digest): Future[Option[(InvoiceDb, OpReturnRequestDb)]] = {
    safeDatabase.run(findOpReturnRequestByRHashAction(rHash))
  }

  def findUnclosed(): Future[Vector[(InvoiceDb, OpReturnRequestDb)]] = {
    val query = table
      .join(opReturnRequestTableQuery)
      .on(_.opReturnRequestId === _.id)
      .filter(_._2.closed === false)

    safeDatabase.runVec(query.result)
  }

  class InvoiceTable(tag: Tag)
      extends Table[InvoiceDb](tag, schemaName, "invoices") {

    def rHash: Rep[Sha256Digest] = column("r_hash", O.PrimaryKey)
    def opReturnRequestId: Rep[Long] = column("op_return_request_id")
    def invoice: Rep[LnInvoice] = column("invoice")
    def paid: Rep[Boolean] = column("paid")

    def * : ProvenShape[InvoiceDb] =
      (rHash, opReturnRequestId, invoice, paid).<>(InvoiceDb.tupled,
                                                   InvoiceDb.unapply)
  }
}
