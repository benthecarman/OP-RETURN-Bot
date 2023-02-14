package models

import config.OpReturnBotAppConfig
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.LnInvoice
import org.bitcoins.core.protocol.ln.LnTag.PaymentHashTag
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import org.scalastr.core.NostrEvent
import play.api.libs.json.Json
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}

case class ZapDb(
    rHash: Sha256Digest,
    invoice: LnInvoice,
    myKey: SchnorrPublicKey,
    amount: MilliSatoshis,
    request: String,
    noteId: Option[Sha256Digest]) {
  def requestEvent: NostrEvent = Json.parse(request).as[NostrEvent]
}

case class ZapDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: OpReturnBotAppConfig)
    extends CRUD[ZapDb, Sha256Digest]
    with SlickUtil[ZapDb, Sha256Digest] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  import mappers._

  override val table: TableQuery[ZabTable] = TableQuery[ZabTable]

  override def createAll(ts: Vector[ZapDb]): Future[Vector[ZapDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[Sha256Digest]): Query[ZabTable, ZapDb, Seq] =
    table.filter(_.rHash.inSet(ids))

  override protected def findAll(
      ts: Vector[ZapDb]): Query[ZabTable, ZapDb, Seq] =
    findByPrimaryKeys(ts.map(_.rHash))

  class ZabTable(tag: Tag) extends Table[ZapDb](tag, schemaName, "zaps") {

    def rHash: Rep[Sha256Digest] = column("r_hash", O.PrimaryKey)

    def invoice: Rep[LnInvoice] = column("invoice", O.Unique)

    def myKey: Rep[SchnorrPublicKey] = column("my_key")

    def amount: Rep[MilliSatoshis] = column("amount")

    def request: Rep[String] = column("request")

    def noteIdOpt: Rep[Option[Sha256Digest]] = column("note_id", O.Unique)

    def * : ProvenShape[ZapDb] =
      (rHash, invoice, myKey, amount, request, noteIdOpt).<>(ZapDb.tupled,
                                                             ZapDb.unapply)
  }
}
