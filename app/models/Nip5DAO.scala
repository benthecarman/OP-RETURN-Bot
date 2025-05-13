package models

import config.OpReturnBotAppConfig
import org.bitcoins.crypto._
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.{ForeignKeyQuery, ProvenShape}

import scala.concurrent.{ExecutionContext, Future}

case class Nip5Db(
    opReturnRequestId: Long, // Foreign key to OpReturnRequestDb.id
    name: String,
    publicKey: SchnorrPublicKey)

case class Nip5DAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: OpReturnBotAppConfig)
    extends CRUD[Nip5Db, Long]
    with SlickUtil[Nip5Db, Long] {

  import profile.api._

  private val paymentTable: TableQuery[PaymentDAO#PaymentTable] =
    PaymentDAO().table

  private val opReturnRequestTable: TableQuery[
    OpReturnRequestDAO#OpReturnRequestTable] =
    OpReturnRequestDAO().table

  private val mappers = new DbCommonsColumnMappers(profile)

  import mappers._

  override val table: TableQuery[Nip5Table] = TableQuery[Nip5Table]

  override def createAll(ts: Vector[Nip5Db]): Future[Vector[Nip5Db]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[Long]): Query[Nip5Table, Nip5Db, Seq] =
    table.filter(_.opReturnRequestId.inSet(ids))

  override protected def findAll(
      ts: Vector[Nip5Db]): Query[Nip5Table, Nip5Db, Seq] =
    findByPrimaryKeys(ts.map(_.opReturnRequestId))

  def getPublicKeyAction(name: String): DBIOAction[Option[SchnorrPublicKey],
                                                   NoStream,
                                                   Effect.Read] = {
    table
      .join(opReturnRequestTable)
      .on(_.opReturnRequestId === _.id)
      .filter(_._1.name === name)
      .filter(_._2.txIdOpt.isDefined)
      .result
      .map(_.headOption.map(_._1.publicKey))
  }

  def getPublicKey(name: String): Future[Option[SchnorrPublicKey]] = {
    safeDatabase.run(getPublicKeyAction(name))
  }

  def getNumCompletedAction(
      afterTimeOpt: Option[Long]): DBIOAction[Int, NoStream, Effect.Read] = {
    afterTimeOpt match {
      case None =>
        table
          .join(opReturnRequestTable)
          .on(_.opReturnRequestId === _.id)
          .filter(_._2.txIdOpt.isDefined)
          .result
          .map(_.size)
      case Some(afterTime) =>
        table
          .join(opReturnRequestTable)
          .on(_.opReturnRequestId === _.id)
          .filter(_._2.txIdOpt.isDefined)
          .filter(_._2.time > afterTime)
          .result
          .map(_.size)
    }

  }

  class Nip5Table(tag: Tag) extends Table[Nip5Db](tag, schemaName, "nip5") {

    def opReturnRequestId: Rep[Long] =
      column("op_return_request_id", O.PrimaryKey)

    def name: Rep[String] = column("name")

    def publicKey: Rep[SchnorrPublicKey] = column("public_key")

    def * : ProvenShape[Nip5Db] =
      (opReturnRequestId, name, publicKey).<>(Nip5Db.tupled, Nip5Db.unapply)

    def fk: ForeignKeyQuery[_, OpReturnRequestDb] = {
      foreignKey("nip5_fk", opReturnRequestId, opReturnRequestTable)(_.id)
    }
  }
}
