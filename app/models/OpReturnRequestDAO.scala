package models

import config.OpReturnBotAppConfig
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.core.api.db.DbRowAutoInc
import org.bitcoins.crypto._
import org.bitcoins.db.{CRUDAutoInc, DbCommonsColumnMappers}
import org.scalastr.core.NostrEvent
import org.scalastr.core.NostrEvent._
import play.api.libs.json.Json
import scodec.bits.ByteVector
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class OpReturnRequestDb(
    id: Option[Long],
    messageBytes: ByteVector,
    noTwitter: Boolean,
    feeRate: SatoshisPerVirtualByte,
    nodeIdOpt: Option[NodeId],
    telegramIdOpt: Option[Long],
    nostrKey: Option[SchnorrPublicKey],
    dvmEvent: Option[NostrEvent],
    time: Long,
    txOpt: Option[Transaction],
    txIdOpt: Option[DoubleSha256DigestBE],
    profitOpt: Option[CurrencyUnit],
    chainFeeOpt: Option[CurrencyUnit],
    vsize: Option[Long],
    closed: Boolean
) extends DbRowAutoInc[OpReturnRequestDb] {

  override def copyWithId(id: Long): OpReturnRequestDb = copy(Some(id))

  def getMessage(): String = {
    Try(
      new String(messageBytes.toArray)
    ).getOrElse("Message is not a string")
  }
}

case class OpReturnRequestDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: OpReturnBotAppConfig)
    extends CRUDAutoInc[OpReturnRequestDb] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  import mappers._

  implicit val nostrEventMapper: BaseColumnType[NostrEvent] =
    MappedColumnType.base[NostrEvent, String](
      { event =>
        Json.toJson(event).toString
      },
      { str =>
        val x: NostrEvent = Json
          .fromJson(Json.parse(str))
          .getOrElse(throw new RuntimeException(s"Could not parse $str"))
        x
      }
    )

  override val table: TableQuery[OpReturnRequestTable] =
    TableQuery[OpReturnRequestTable]

  def findByTxId(
      txId: DoubleSha256DigestBE): Future[Option[OpReturnRequestDb]] = {
    val query = table.filter(_.txIdOpt === txId)
    safeDatabase.run(query.result).map(_.headOption)
  }

  def completedAction(): DBIOAction[Vector[OpReturnRequestDb],
                                    NoStream,
                                    Effect.Read] = {
    table.filter(_.txIdOpt.isDefined).result.map(_.toVector)
  }

  def completedAction(
      afterTimeOpt: Option[Long]): DBIOAction[Vector[OpReturnRequestDb],
                                              NoStream,
                                              Effect.Read] = {
    afterTimeOpt match {
      case None =>
        table.filter(_.txIdOpt.isDefined).result.map(_.toVector)
      case Some(afterTime) =>
        table
          .filter(_.txIdOpt.isDefined)
          .filter(_.time > afterTime)
          .result
          .map(_.toVector)
    }
  }

  def numCompletedAction(
      afterTimeOpt: Option[Long]): DBIOAction[Int, NoStream, Effect.Read] = {
    afterTimeOpt match {
      case None =>
        table.filter(_.txIdOpt.isDefined).size.result
      case Some(afterTime) =>
        table
          .filter(_.time > afterTime)
          .filter(_.txIdOpt.isDefined)
          .size
          .result
    }
  }

  def numNonStdCompletedAction(
      afterTimeOpt: Option[Long]): DBIOAction[Int, NoStream, Effect.Read] = {
    afterTimeOpt match {
      case None =>
        table
          .filter(_.txIdOpt.isDefined)
          .filter(t => {
            SimpleFunction
              .unary[ByteVector, Int]("length")
              .apply(t.messageBytes) > 80
          })
          .size
          .result
      case Some(afterTime) =>
        table
          .filter(_.time > afterTime)
          .filter(t => {
            SimpleFunction
              .unary[ByteVector, Int]("length")
              .apply(t.messageBytes) > 80
          })
          .filter(_.txIdOpt.isDefined)
          .size
          .result
    }
  }

  def completed(): Future[Vector[OpReturnRequestDb]] = {
    safeDatabase.run(completedAction())
  }

  def numCompleted(): Future[Int] = {
    safeDatabase.run(numCompletedAction(None))
  }

  def lastFiveCompleted(): Future[Vector[DoubleSha256DigestBE]] = {
    val query =
      table
        .filter(_.txIdOpt.isDefined) // get completed
        .filterNot(_.noTwitter) // remove non-public ones
        .map(_.txIdOpt) // just get txid
        .result
        .map(
          _.flatten.takeRight(5)
        ) // Assuming txIdOpt is Option[DoubleSha256DigestBE]

    safeDatabase.runVec(query)
  }

  def totalProfitAction(afterTimeOpt: Option[Long]): DBIOAction[CurrencyUnit,
                                                                NoStream,
                                                                Effect.Read] = {
    afterTimeOpt match {
      case Some(t) =>
        table
          .filter(_.profitOpt.isDefined)
          .filter(_.time > t)
          .map(_.profitOpt)
          .sum
          .getOrElse(CurrencyUnits.zero)
          .result
      case None =>
        table
          .filter(_.profitOpt.isDefined)
          .map(_.profitOpt)
          .sum
          .getOrElse(CurrencyUnits.zero)
          .result
    }
  }

  def totalChainFeesAction(
      afterTimeOpt: Option[Long]): DBIOAction[CurrencyUnit,
                                              NoStream,
                                              Effect.Read] = {
    afterTimeOpt match {
      case Some(t) =>
        table
          .filter(_.chainFeeOpt.isDefined)
          .filter(_.time > t)
          .map(_.chainFeeOpt)
          .sum
          .getOrElse(CurrencyUnits.zero)
          .result
      case None =>
        table
          .filter(_.chainFeeOpt.isDefined)
          .map(_.chainFeeOpt)
          .sum
          .getOrElse(CurrencyUnits.zero)
          .result
    }
  }

  def totalChainSizeAction(
      afterTimeOpt: Option[Long]): DBIOAction[Long, NoStream, Effect.Read] = {
    afterTimeOpt match {
      case Some(t) =>
        table
          .filter(_.vsize.isDefined)
          .filter(_.time > t)
          .map(_.vsize)
          .sum
          .getOrElse(0L)
          .result
      case None =>
        table
          .filter(_.vsize.isDefined)
          .map(_.vsize)
          .sum
          .getOrElse(0L)
          .result
    }
  }

  def totalNonStdChainSizeAction(
      afterTimeOpt: Option[Long]): DBIOAction[Long, NoStream, Effect.Read] = {
    afterTimeOpt match {
      case Some(t) =>
        table
          .filter(_.vsize.isDefined)
          .filter(_.time > t)
          .filter(t => {
            SimpleFunction
              .unary[ByteVector, Int]("length")
              .apply(t.messageBytes) > 80
          })
          .map(_.vsize)
          .sum
          .getOrElse(0L)
          .result
      case None =>
        table
          .filter(_.vsize.isDefined)
          .filter(t => {
            SimpleFunction
              .unary[ByteVector, Int]("length")
              .apply(t.messageBytes) > 80
          })
          .map(_.vsize)
          .sum
          .getOrElse(0L)
          .result
    }
  }

  def findUnclosed(): Future[Vector[OpReturnRequestDb]] = {
    val query = table.filterNot(_.closed)
    safeDatabase.runVec(query.result)
  }

  class OpReturnRequestTable(tag: Tag)
      extends TableAutoInc[OpReturnRequestDb](tag,
                                              schemaName,
                                              "op_return_requests") {

    def messageBytes: Rep[ByteVector] = column("message_bytes")

    def noTwitter: Rep[Boolean] = column("no_twitter")
    def feeRate: Rep[SatoshisPerVirtualByte] = column("fee_rate")
    def nodeId: Rep[Option[NodeId]] = column("node_id")
    def telegramId: Rep[Option[Long]] = column("telegram_id")
    def nostrKey: Rep[Option[SchnorrPublicKey]] = column("nostr_key")
    def dvmEvent: Rep[Option[NostrEvent]] = column("dvm_event")
    def time: Rep[Long] = column("time")
    def transactionOpt: Rep[Option[Transaction]] = column("transaction")
    def txIdOpt: Rep[Option[DoubleSha256DigestBE]] = column("txid")
    def profitOpt: Rep[Option[CurrencyUnit]] = column("profit")
    def chainFeeOpt: Rep[Option[CurrencyUnit]] = column("chain_fee")
    def vsize: Rep[Option[Long]] = column("vsize")
    def closed: Rep[Boolean] = column("closed")

    def * : ProvenShape[OpReturnRequestDb] =
      (id.?, // id is Option[Long] in OpReturnRequestDb
       messageBytes,
       noTwitter,
       feeRate,
       nodeId,
       telegramId,
       nostrKey,
       dvmEvent,
       time,
       transactionOpt,
       txIdOpt,
       profitOpt,
       chainFeeOpt,
       vsize,
       closed).<>(OpReturnRequestDb.tupled, OpReturnRequestDb.unapply)
  }
}
