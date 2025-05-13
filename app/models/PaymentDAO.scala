package models

import config.OpReturnBotAppConfig
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.crypto._
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.{ForeignKeyQuery, ProvenShape}

import scala.concurrent.{ExecutionContext, Future}

case class PaymentDb(
    rHash: Sha256Digest,
    opReturnRequestId: Long, // Foreign key to OpReturnRequestDb.id
    invoice: LnInvoice,
    paid: Boolean)

case class PaymentDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: OpReturnBotAppConfig)
    extends CRUD[PaymentDb, Sha256Digest]
    with SlickUtil[PaymentDb, Sha256Digest] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)
  import mappers._

  // Reference to the OpReturnRequestDAO table for foreign key
  private val opReturnRequestTableQuery: TableQuery[
    OpReturnRequestDAO#OpReturnRequestTable] =
    OpReturnRequestDAO().table

  override val table: TableQuery[PaymentTable] = TableQuery[PaymentTable]

  override def createAll(ts: Vector[PaymentDb]): Future[Vector[PaymentDb]] =
    createAllNoAutoInc(ts, safeDatabase) // rHash is not auto-incrementing

  override protected def findByPrimaryKeys(
      ids: Vector[Sha256Digest]): Query[PaymentTable, PaymentDb, Seq] =
    table.filter(_.rHash.inSet(ids))

  override protected def findAll(
      ts: Vector[PaymentDb]): Query[PaymentTable, PaymentDb, Seq] =
    findByPrimaryKeys(ts.map(_.rHash))

  def findByOpReturnRequestId(
      opReturnRequestId: Long): Future[Option[PaymentDb]] = {
    val query = table.filter(_.opReturnRequestId === opReturnRequestId)
    safeDatabase.run(query.result).map(_.headOption)
  }

  def findOpReturnRequestByRHashAction(
      rHash: Sha256Digest): DBIOAction[Option[(PaymentDb, OpReturnRequestDb)],
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
      rHash: Sha256Digest): Future[Option[(PaymentDb, OpReturnRequestDb)]] = {
    safeDatabase.run(findOpReturnRequestByRHashAction(rHash))
  }

  def findPendingPaymentsForProcessing(
      limit: Option[Int]): Future[Vector[(PaymentDb, OpReturnRequestDb)]] = {
    val query = table
      .filter(_.paid === true) // Payment is marked as paid
      .join(opReturnRequestTableQuery)
      .on(_.opReturnRequestId === _.id)
      .filter(
        _._2.txIdOpt.isEmpty
      ) // But OpReturnRequest has no txId yet (not broadcasted)
      .filter(_._2.closed === false) // And OpReturnRequest is not closed

    val limitedQuery = limit match {
      case Some(l) => query.take(l)
      case None    => query
    }

    safeDatabase
      .run(limitedQuery.result)
      .map(_.toVector.map { case (payment, opReturn) => (payment, opReturn) })
  }

  def numWaitingAction(
      afterTimeOpt: Option[Long]): DBIOAction[Int, NoStream, Effect.Read] = {
    // todo this should be in the OpReturnRequestDAO
    val baseQuery = table
      .filter(_.paid === true)
      .join(opReturnRequestTableQuery)
      .on(_.opReturnRequestId === _.id)
      .filter(_._2.txIdOpt.isEmpty)

    val timedQuery = afterTimeOpt match {
      case None => baseQuery
      case Some(afterTime) =>
        baseQuery.filter(_._2.time > afterTime)
    }

    timedQuery.length.result
  }

  def findUnclosed(): Future[Vector[(PaymentDb, OpReturnRequestDb)]] = {
    val query = table
      .join(opReturnRequestTableQuery)
      .on(_.opReturnRequestId === _.id)
      .filter(_._2.closed === false)

    safeDatabase.runVec(query.result)
  }

  class PaymentTable(tag: Tag)
      extends Table[PaymentDb](tag, schemaName, "payments") {

    def rHash: Rep[Sha256Digest] = column("r_hash", O.PrimaryKey)
    def opReturnRequestId: Rep[Long] = column("op_return_request_id")
    def invoice: Rep[LnInvoice] = column("invoice")
    def paid: Rep[Boolean] = column("paid")

    def * : ProvenShape[PaymentDb] =
      (rHash, opReturnRequestId, invoice, paid).<>(PaymentDb.tupled,
                                                   PaymentDb.unapply)

    def opReturnRequestFk: ForeignKeyQuery[
      OpReturnRequestDAO#OpReturnRequestTable,
      OpReturnRequestDb] = {
      foreignKey("payment_op_return_request_fk",
                 opReturnRequestId,
                 opReturnRequestTableQuery)(_.id)
    }
  }
}
