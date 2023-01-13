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
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}

case class InvoiceDb(
    rHash: Sha256Digest,
    invoice: LnInvoice,
    message: String,
    noTwitter: Boolean,
    feeRate: SatoshisPerVirtualByte,
    closed: Boolean,
    nodeIdOpt: Option[NodeId],
    telegramIdOpt: Option[Long],
    nostrKey: Option[SchnorrPublicKey],
    txOpt: Option[Transaction],
    txIdOpt: Option[DoubleSha256DigestBE],
    profitOpt: Option[CurrencyUnit],
    chainFeeOpt: Option[CurrencyUnit])

case class InvoiceDAO()(implicit
    override val ec: ExecutionContext,
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

  def completed(): Future[Vector[InvoiceDb]] = {
    val query = table.filter(_.txIdOpt.isDefined)

    safeDatabase.runVec(query.result)
  }

  def numCompleted(): Future[Int] = {
    val query = table.filter(_.txIdOpt.isDefined).size

    safeDatabase.run(query.result)
  }

  def lastFiveCompleted(): Future[Vector[DoubleSha256DigestBE]] = {
    val query =
      table
        .filter(_.txIdOpt.isDefined) // get completed
        .filterNot(_.noTwitter) // remove non-public ones
        .map(_.txIdOpt) // just get txid
        .result
        .map(_.flatten.takeRight(5))

    safeDatabase.runVec(query)
  }

  def totalProfitAction(): DBIOAction[CurrencyUnit, NoStream, Effect.Read] = {
    table
      .filter(_.profitOpt.isDefined)
      .map(_.profitOpt)
      .result
      .map(_.flatten.sum)
  }

  def totalChainFeesAction(): DBIOAction[CurrencyUnit,
                                         NoStream,
                                         Effect.Read] = {
    table
      .filter(_.chainFeeOpt.isDefined)
      .map(_.chainFeeOpt)
      .result
      .map(_.flatten.sum)
  }

  def findUnclosed(): Future[Vector[InvoiceDb]] = {
    val query = table.filterNot(_.closed)

    safeDatabase.runVec(query.result)
  }

  class InvoiceTable(tag: Tag)
      extends Table[InvoiceDb](tag, schemaName, "invoices") {

    def rHash: Rep[Sha256Digest] = column("r_hash", O.PrimaryKey)

    def invoice: Rep[LnInvoice] = column("invoice")

    def message: Rep[String] = column("message")

    def noTwitter: Rep[Boolean] = column("hash")

    def feeRate: Rep[SatoshisPerVirtualByte] = column("fee_rate")

    def closed: Rep[Boolean] = column("closed")

    def nodeId: Rep[Option[NodeId]] = column("node_id")

    def telegramId: Rep[Option[Long]] = column("telegram_id")

    def nostrKey: Rep[Option[SchnorrPublicKey]] = column("nostr_key")

    def transactionOpt: Rep[Option[Transaction]] = column("transaction")

    def txIdOpt: Rep[Option[DoubleSha256DigestBE]] = column("txid")

    def profitOpt: Rep[Option[CurrencyUnit]] = column("profit")

    def chainFeeOpt: Rep[Option[CurrencyUnit]] = column("chain_fee")

    def * : ProvenShape[InvoiceDb] =
      (rHash,
       invoice,
       message,
       noTwitter,
       feeRate,
       closed,
       nodeId,
       telegramId,
       nostrKey,
       transactionOpt,
       txIdOpt,
       profitOpt,
       chainFeeOpt).<>(InvoiceDb.tupled, InvoiceDb.unapply)
  }
}
