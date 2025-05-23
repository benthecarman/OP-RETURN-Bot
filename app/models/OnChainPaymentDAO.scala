package models

import config.OpReturnBotAppConfig
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.crypto.DoubleSha256DigestBE
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}

case class OnChainPaymentDb(
    address: BitcoinAddress,
    opReturnRequestId: Long, // Foreign key to OpReturnRequestDb.id
    expectedAmount: CurrencyUnit,
    amountPaid: Option[CurrencyUnit],
    txid: Option[DoubleSha256DigestBE])

case class OnChainPaymentDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: OpReturnBotAppConfig)
    extends CRUD[OnChainPaymentDb, BitcoinAddress]
    with SlickUtil[OnChainPaymentDb, BitcoinAddress] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)
  import mappers._

  // Reference to the OpReturnRequestDAO table for foreign key
  private val opReturnRequestTableQuery: TableQuery[
    OpReturnRequestDAO#OpReturnRequestTable] =
    OpReturnRequestDAO().table

  override val table: TableQuery[OnChainPaymentTable] =
    TableQuery[OnChainPaymentTable]

  override def createAll(
      ts: Vector[OnChainPaymentDb]): Future[Vector[OnChainPaymentDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[BitcoinAddress]): Query[OnChainPaymentTable,
                                          OnChainPaymentDb,
                                          Seq] =
    table.filter(_.address.inSet(ids))

  override protected def findAll(
      ts: Vector[OnChainPaymentDb]): Query[OnChainPaymentTable,
                                           OnChainPaymentDb,
                                           Seq] =
    findByPrimaryKeys(ts.map(_.address))

  def findByOpReturnRequestIdAction(
      opReturnRequestId: Long): DBIOAction[Option[OnChainPaymentDb],
                                           NoStream,
                                           Effect.Read] = {
    table.filter(_.opReturnRequestId === opReturnRequestId).result.headOption
  }

  def findOpReturnRequestByAddressAction(
      address: BitcoinAddress): DBIOAction[Option[(OnChainPaymentDb,
                                                   OpReturnRequestDb)],
                                           NoStream,
                                           Effect.Read] = {
    table
      .filter(_.address === address)
      .join(opReturnRequestTableQuery)
      .on(_.opReturnRequestId === _.id)
      .result
      .headOption
  }

  def findOpReturnRequestByAddress(address: BitcoinAddress): Future[
    Option[(OnChainPaymentDb, OpReturnRequestDb)]] = {
    safeDatabase.run(findOpReturnRequestByAddressAction(address))
  }

  class OnChainPaymentTable(tag: Tag)
      extends Table[OnChainPaymentDb](tag, schemaName, "on_chain_payments") {

    def address: Rep[BitcoinAddress] = column("address", O.PrimaryKey)

    def opReturnRequestId: Rep[Long] = column("op_return_request_id")

    def expectedAmount: Rep[CurrencyUnit] = column("expected_amount")

    def amountPaid: Rep[Option[CurrencyUnit]] = column("amount_paid")

    def txid: Rep[Option[DoubleSha256DigestBE]] = column("txid")

    def * : ProvenShape[OnChainPaymentDb] =
      (address, opReturnRequestId, expectedAmount, amountPaid, txid).<>(
        OnChainPaymentDb.tupled,
        OnChainPaymentDb.unapply)
  }
}
