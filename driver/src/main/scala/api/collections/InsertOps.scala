package reactivemongo.api.collections

import scala.util.{ Failure, Success, Try }

import scala.concurrent.{ ExecutionContext, Future }

import reactivemongo.core.protocol.MongoWireVersion
import reactivemongo.core.errors.GenericDriverException

import reactivemongo.api.SerializationPack
import reactivemongo.api.commands.{
  BulkOps,
  LastError,
  MultiBulkWriteResult,
  ResolvedCollectionCommand,
  WriteConcern,
  WriteResult
}

/**
 * @define writeConcernParam the [[https://docs.mongodb.com/manual/reference/write-concern/ writer concern]] to be used
 * @define orderedParam the [[https://docs.mongodb.com/manual/reference/method/db.collection.insert/#perform-an-unordered-insert ordered]] behaviour
 */
trait InsertOps[P <: SerializationPack with Singleton] {
  collection: GenericCollection[P] =>

  private object InsertCommand
    extends reactivemongo.api.commands.InsertCommand[collection.pack.type] {
    val pack: collection.pack.type = collection.pack
  }

  /**
   * @param ordered $orderedParam
   * @param writeConcern writeConcernParam
   */
  private[reactivemongo] final def prepareInsert[T: pack.Writer](
    ordered: Boolean,
    writeConcern: WriteConcern): InsertBuilder[T] = {
    if (ordered) {
      new OrderedInsert[T](writeConcern, implicitly[pack.Writer[T]])
    } else {
      new UnorderedInsert[T](writeConcern, implicitly[pack.Writer[T]])
    }
  }

  private type InsertCmd = ResolvedCollectionCommand[InsertCommand.Insert]

  implicit private lazy val insertWriter: pack.Writer[InsertCmd] = {
    val underlying = reactivemongo.api.commands.InsertCommand.
      writer(pack)(InsertCommand)(collection.db.session)

    pack.writer[InsertCmd](underlying)
  }

  /** Builder for insert operations. */
  sealed trait InsertBuilder[T] {
    implicit protected def writer: pack.Writer[T]

    @inline private def metadata = db.connectionState.metadata

    /** The max BSON size, including the size of command envelope */
    private lazy val maxBsonSize: Int = {
      // Command envelope to compute accurate BSON size limit
      val emptyDoc: pack.Document = pack.newBuilder.document(Seq.empty)

      val emptyCmd = ResolvedCollectionCommand(
        collection.name,
        InsertCommand.Insert(
          emptyDoc, Seq.empty[pack.Document], ordered, writeConcern))

      val doc = pack.serialize(emptyCmd, insertWriter)

      metadata.maxBsonSize - pack.bsonSize(doc) + pack.bsonSize(emptyDoc)
    }

    /** $orderedParam */
    def ordered: Boolean

    /** $writeConcernParam */
    def writeConcern: WriteConcern

    protected def bulkRecover: Option[Exception => Future[WriteResult]]

    /**
     * Inserts a single document.
     *
     * {{{
     * import reactivemongo.bson.BSONDocument
     * import reactivemongo.api.collections.BSONCollection
     *
     * def insertOne(coll: BSONCollection, doc: BSONDocument) = {
     *   val insert = coll.insert(ordered = true)
     *
     *   insert.one(doc)
     * }
     * }}}
     */
    final def one(document: T)(implicit ec: ExecutionContext): Future[WriteResult] = Future(pack.serialize(document, writer)).flatMap { single =>
      execute(Seq(single))
    }

    /** Inserts many documents, according the ordered behaviour. */
    /**
     * [[https://docs.mongodb.com/manual/reference/method/db.collection.insertMany/ Inserts many documents]], according the ordered behaviour.
     *
     * {{{
     * import reactivemongo.bson.BSONDocument
     * import reactivemongo.api.collections.BSONCollection
     *
     * def insertMany(coll: BSONCollection, docs: Iterable[BSONDocument]) = {
     *   val insert = coll.insert(ordered = true)
     *
     *   insert.many(elements) // Future[MultiBulkWriteResult]
     * }
     * }}}
     */
    final def many(documents: Iterable[T])(implicit ec: ExecutionContext): Future[MultiBulkWriteResult] = {
      val bulkSz = metadata.maxBulkSize
      val maxSz = maxBsonSize

      for {
        docs <- serialize(documents)
        res <- {
          val bulkProducer = BulkOps.bulks(
            docs, maxSz, bulkSz) { pack.bsonSize(_) }

          BulkOps.bulkApply[pack.Document, WriteResult](bulkProducer)({ bulk =>
            execute(bulk.toSeq)
          }, bulkRecover)
        }
      } yield MultiBulkWriteResult(res)
    }

    // ---

    private def serialize(input: Iterable[T])(implicit ec: ExecutionContext): Future[Iterable[pack.Document]] = Future.sequence(input.map { v =>
      Try(pack.serialize(v, writer)) match {
        case Success(v) => Future.successful(v)
        case Failure(e) => Future.failed[pack.Document](e)
      }
    })

    private final def execute(documents: Seq[pack.Document])(implicit ec: ExecutionContext): Future[WriteResult] = documents.headOption match {
      case Some(head) => {
        if (metadata.maxWireVersion >= MongoWireVersion.V26) {
          val cmd = InsertCommand.Insert(
            head, documents.tail, ordered, writeConcern)

          runCommand(cmd, writePreference).flatMap { wr =>
            val flattened = wr.flatten

            if (!flattened.ok) {
              // was ordered, with one doc => fail if has an error
              Future.failed(WriteResult.lastError(flattened).
                getOrElse[Exception](GenericDriverException(
                  s"fails to insert: $documents")))

            } else Future.successful(wr)
          }
        } else { // Mongo < 2.6
          Future.failed[WriteResult](GenericDriverException(
            s"unsupported MongoDB version: $metadata"))
        }
      }

      case _ => Future.successful(WriteResult.empty) // No doc to insert
    }
  }

  // ---

  private val orderedRecover = Option.empty[Exception => Future[WriteResult]]

  private final class OrderedInsert[T](
    val writeConcern: WriteConcern,
    val writer: pack.Writer[T]) extends InsertBuilder[T] {

    val ordered = true
    val bulkRecover = orderedRecover
  }

  private val unorderedRecover: Option[Exception => Future[WriteResult]] =
    Some[Exception => Future[WriteResult]] {
      case lastError: WriteResult =>
        Future.successful(lastError)

      case cause => Future.successful(LastError(
        ok = false,
        errmsg = Option(cause.getMessage),
        code = Option.empty,
        lastOp = Some(2002), // InsertOp
        n = 0,
        singleShard = Option.empty[String],
        updatedExisting = false,
        upserted = Option.empty,
        wnote = Option.empty[WriteConcern.W],
        wtimeout = false,
        waited = Option.empty[Int],
        wtime = Option.empty[Int]))
    }

  private final class UnorderedInsert[T](
    val writeConcern: WriteConcern,
    val writer: pack.Writer[T]) extends InsertBuilder[T] {

    val ordered = false
    val bulkRecover = unorderedRecover
  }
}
