package reactivemongo.api.commands

import scala.util.control.NoStackTrace

import reactivemongo.api.WriteConcern
import reactivemongo.api.bson.BSONValue

import reactivemongo.core.errors.DatabaseException

sealed trait WriteResult {
  def ok: Boolean
  def n: Int
  def writeErrors: Seq[WriteError]
  def writeConcernError: Option[WriteConcernError]

  /** The result code */
  def code: Option[Int]

  /** If the result is a failure, the error message */
  private[commands] def errmsg: Option[String]

  private[reactivemongo] def hasErrors: Boolean =
    !writeErrors.isEmpty || !writeConcernError.isEmpty

  private[reactivemongo] def inError: Boolean = !ok || code.isDefined

  protected def message = errmsg.getOrElse("<none>")
}

object WriteResult {
  private[reactivemongo] def lastError(result: WriteResult): Option[LastError] =
    result match {
      case error: LastError => Some(error)
      case _ if (result.ok) => None
      case _ => Some(new LastError(
        false, // ok
        result.errmsg,
        result.code,
        None, // lastOp
        result.n,
        None, // singleShard
        false, // updatedExisting,
        None, // upserted
        None, // wnote
        false, // wtimeout,
        None, // waited,
        None, // wtime,
        result.writeErrors,
        result.writeConcernError))
    }

  /**
   * Code extractor for [[WriteResult]]
   *
   * {{{
   * import reactivemongo.api.commands.WriteResult
   *
   * def codeOr(res: WriteResult, or: => Int): Int = res match {
   *   case WriteResult.Code(code) => code
   *   case _ => or
   * }
   * }}}
   */
  object Code {
    def unapply(result: WriteResult): Option[Int] = result.code
  }

  /**
   * Code extractor for [[WriteResult]]
   *
   * {{{
   * import reactivemongo.api.commands.WriteResult
   *
   * def messageOr(res: WriteResult, or: => String): String = res match {
   *   case WriteResult.Message(msg) => msg
   *   case _ => or
   * }
   * }}}
   */
  object Message {
    def unapply(result: WriteResult): Option[String] = result.errmsg
  }

  private[reactivemongo] def empty: WriteResult = new DefaultWriteResult(
    true, 0, Seq.empty, Option.empty, Option.empty, Option.empty)
}

private[reactivemongo] final class LastError(
  val ok: Boolean,
  val errmsg: Option[String],
  val code: Option[Int],
  val lastOp: Option[Long],
  val n: Int,
  val singleShard: Option[String],
  val updatedExisting: Boolean,
  val upserted: Option[BSONValue], // TODO: Review
  val wnote: Option[WriteConcern.W],
  val wtimeout: Boolean,
  val waited: Option[Int],
  val wtime: Option[Int],
  val writeErrors: Seq[WriteError],
  val writeConcernError: Option[WriteConcernError]) extends DatabaseException with WriteResult with NoStackTrace {

  type Document = Nothing

  override def inError: Boolean = !ok || errmsg.isDefined

  private[reactivemongo] def originalDocument = Option.empty[Nothing]

  override lazy val message = errmsg.getOrElse("<none>")

  override protected lazy val tupled = Tuple14(ok, errmsg, code, lastOp, n, singleShard, updatedExisting, upserted, wnote, wtimeout, waited, wtime, writeErrors, writeConcernError)

  override def equals(that: Any): Boolean = that match {
    case other: LastError =>
      this.tupled == other.tupled

    case _ =>
      false
  }

  override def toString = s"LastError${tupled.hashCode}"
}

/**
 * @param code the error code
 * @param errmsg the error message
 */
private[reactivemongo] final class WriteError private[api] (
  val index: Int,
  val code: Int,
  val errmsg: String) {

  private[api] lazy val tupled = Tuple3(index, code, errmsg)

  private[api] def copy(
    index: Int = this.index,
    code: Int = this.code,
    errmsg: String = this.errmsg): WriteError =
    new WriteError(index, code, errmsg)

  override def equals(that: Any): Boolean = that match {
    case other: WriteError =>
      this.tupled == other.tupled

    case _ =>
      false
  }

  override def hashCode: Int = tupled.hashCode

  override def toString = s"WriteError${tupled.hashCode}"
}

private[reactivemongo] object WriteError {
  def apply(index: Int, code: Int, errmsg: String): WriteError =
    new WriteError(index, code, errmsg)
}

/**
 * @param code the error code
 * @param errmsg the error message
 */
final class WriteConcernError private[api] (
  val code: Int,
  val errmsg: String) {

  private[api] lazy val tupled = code -> errmsg

  override def equals(that: Any): Boolean = that match {
    case other: WriteConcernError =>
      this.tupled == other.tupled

    case _ =>
      false
  }

  override def hashCode: Int = tupled.hashCode

  override def toString = s"WriteConcernError${tupled.toString}"
}

private[reactivemongo] case class DefaultWriteResult(
  ok: Boolean,
  n: Int,
  writeErrors: Seq[WriteError],
  writeConcernError: Option[WriteConcernError],
  code: Option[Int],
  errmsg: Option[String]) extends WriteResult {
  private[api] def flatten = writeErrors.headOption.fold(this) { firstError =>
    DefaultWriteResult(
      ok = false,
      n = n,
      writeErrors = writeErrors,
      writeConcernError = writeConcernError,
      code = code.orElse(Some(firstError.code)),
      errmsg = errmsg.orElse(Some(firstError.errmsg)))
  }
}

final class UpdateWriteResult private[api] (
  val ok: Boolean,
  val n: Int,
  val nModified: Int,
  val upserted: Seq[Upserted],
  val writeErrors: Seq[WriteError],
  val writeConcernError: Option[WriteConcernError],
  val code: Option[Int],
  val errmsg: Option[String]) extends WriteResult {

  private[api] def flatten = writeErrors.headOption.fold(this) { firstError =>
    new UpdateWriteResult(
      ok = false,
      n = n,
      nModified = nModified,
      upserted = upserted,
      writeErrors = writeErrors,
      writeConcernError = writeConcernError,
      code = code.orElse(Some(firstError.code)),
      errmsg = errmsg.orElse(Some(firstError.errmsg)))
  }

  private lazy val tupled = Tuple8(ok, n, nModified, upserted, writeErrors, writeConcernError, code, errmsg)

  override def equals(that: Any): Boolean = that match {
    case other: UpdateWriteResult =>
      other.tupled == this.tupled

    case _ =>
      false
  }

  override def hashCode: Int = tupled.hashCode

  override def toString = s"UpdateWriteResult${tupled.toString}"
}
