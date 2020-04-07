package reactivemongo.api

import scala.util.Try

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration.{ FiniteDuration, SECONDS }

import scala.collection.immutable.ListSet

import akka.util.Timeout
import akka.actor.ActorRef

import reactivemongo.io.netty.channel.{ ChannelId, DefaultChannelId }

import reactivemongo.io.netty.channel.Channel
import reactivemongo.io.netty.buffer.{ ByteBuf, Unpooled }

import reactivemongo.core.errors.DatabaseException
import reactivemongo.core.protocol.{
  Query,
  MessageHeader,
  Request,
  Reply,
  Response,
  ResponseInfo,
  ResponseFrameDecoder,
  ResponseDecoder
}
import reactivemongo.core.netty.ChannelFactory

import reactivemongo.core.nodeset.{ Authenticate, NodeSet }
import reactivemongo.core.actors, actors.{
  ChannelConnected,
  ChannelDisconnected,
  MongoDBSystem,
  RequestIdGenerator,
  StandardDBSystem
}

import reactivemongo.api.bson.BSONDocument
import reactivemongo.api.bson.collection.BSONSerializationPack

import reactivemongo.api.collections.QueryCodecs

package object tests { self =>
  type Pack = Serialization.Pack
  val pack: Pack = Serialization.internalSerializationPack

  object PrimaryAvailable {
    def unapply(msg: Any) = msg match {
      case reactivemongo.core.actors.PrimaryAvailable(metadata) =>
        Some(metadata)

      case _ => None
    }
  }

  def Authenticate(
    db: String,
    user: String,
    password: Option[String]) =
    reactivemongo.core.nodeset.Authenticate(db, user, password)

  def maxWireVersion(db: DB) = db.connectionState.metadata.maxWireVersion

  type ParsedURI = reactivemongo.api.MongoConnection.ParsedURI

  def ParsedURI(
    hosts: ListSet[(String, Int)],
    options: MongoConnectionOptions,
    ignoredOptions: List[String],
    db: Option[String]) = new reactivemongo.api.MongoConnection.ParsedURI(hosts, options, ignoredOptions, db)

  @inline def RegisterMonitor = reactivemongo.core.actors.RegisterMonitor

  @inline def SimpleRing[T](capacity: Int)(implicit cls: scala.reflect.ClassTag[T]) = new reactivemongo.util.SimpleRing[T](capacity)

  lazy val decoder = pack.newDecoder
  lazy val builder = pack.newBuilder

  def system(drv: AsyncDriver) = drv.system

  def mongosystem(con: MongoConnection) = con.mongosystem

  @inline def md5Hex(bytes: Array[Byte]): String = {
    import reactivemongo.api.bson.Digest
    Digest.hex2Str(Digest.md5(bytes))
  }

  def logger(category: String) = reactivemongo.util.LazyLogger(category)

  def newBuilder[P <: SerializationPack](pack: P) = pack.newBuilder

  def reader[T](f: pack.Document => T): pack.Reader[T] = pack.reader[T](f)

  def writer[T](f: T => pack.Document): pack.Writer[T] = pack.writer[T](f)

  def numConnections(d: AsyncDriver): Int = d.numConnections

  type Response = reactivemongo.core.protocol.Response

  import reactivemongo.api.commands.AggregationFramework

  object AggFramework extends PackSupport[Pack]
    with AggregationFramework[Pack] {
    val pack = self.pack
  }

  def makePipe[P <: SerializationPack](a: AggregationFramework[P])(o: a.PipelineOperator) = o.makePipe

  def makeFunction[P <: SerializationPack](a: AggregationFramework[P])(f: a.GroupFunction) = f.makeFunction

  // Test alias
  def _failover2[A](c: MongoConnection, s: FailoverStrategy)(p: () => Future[A])(implicit ec: ExecutionContext): Failover[A] = Failover.apply(c, s)(p)(ec)

  def isAvailable(con: MongoConnection, timeout: FiniteDuration)(implicit ec: ExecutionContext): Future[Boolean] = con.probe(timeout).map(_ => true).recover {
    case _ => false
  }

  def waitIsAvailable(con: MongoConnection, failoverStrategy: FailoverStrategy)(implicit ec: ExecutionContext) = con.waitIsAvailable(failoverStrategy, Array.empty)

  def standardDBSystem(supervisor: String, name: String, nodes: Seq[String], authenticates: Seq[Authenticate], options: MongoConnectionOptions) = new StandardDBSystem(supervisor, name, nodes, authenticates, options)

  def addConnection(d: AsyncDriver, name: String, nodes: Seq[String], options: MongoConnectionOptions, mongosystem: ActorRef): Future[Any] = {
    import akka.pattern.ask

    def message = d.addConnectionMsg(name, nodes, options, mongosystem)
    implicit def timeout = Timeout(10, SECONDS)

    d.supervisorActor ? message
  }

  def history(sys: MongoDBSystem): Iterable[(Long, String)] = {
    val snap = sys.history.synchronized {
      sys.history.toArray()
    }

    snap.toList.collect {
      case (time: Long, event: String) => time -> event
    }
  }

  def channelConnected(id: ChannelId) = ChannelConnected(id)

  def channelClosed(id: ChannelId) = ChannelDisconnected(id)

  def makeRequest[T](cursor: Cursor[T], maxDocs: Int)(implicit ec: ExecutionContext): Future[Response] = cursor.asInstanceOf[CursorOps[T]].makeRequest(maxDocs)

  def nextResponse[T](cursor: Cursor[T], maxDocs: Int) =
    cursor.asInstanceOf[CursorOps[T]].nextResponse(maxDocs)

  @inline def channelBuffer(doc: BSONDocument) = {
    val buf = reactivemongo.api.bson.buffer.WritableBuffer.empty

    BSONSerializationPack.writeToBuffer(buf, doc)
  }

  @inline def bufferSeq(doc: BSONDocument) =
    reactivemongo.core.netty.BufferSequence.single(BSONSerializationPack)(doc)

  def fakeResponse(
    doc: BSONDocument,
    reqID: Int = 2,
    respTo: Int = 1,
    chanId: ChannelId = DefaultChannelId.newInstance()): Response = {
    val reply = new Reply(
      flags = 1,
      cursorID = 1,
      startingFrom = 0,
      numberReturned = 1)

    val message = bufferSeq(doc).merged

    val header = new MessageHeader(
      messageLength = message.capacity,
      requestID = reqID,
      responseTo = respTo,
      opCode = -1)

    new Response(
      header,
      reply,
      documents = message,
      info = new ResponseInfo(chanId))
  }

  def fakeResponseError(
    doc: pack.Document,
    reqID: Int = 2,
    respTo: Int = 1,
    chanId: ChannelId = DefaultChannelId.newInstance()): Response = {
    val reply = new Reply(
      flags = 1,
      cursorID = 1,
      startingFrom = 0,
      numberReturned = 1)

    val message = bufferSeq(doc).merged

    val header = new MessageHeader(
      messageLength = message.capacity,
      requestID = reqID,
      responseTo = respTo,
      opCode = -1)

    Response.CommandError(
      _header = header,
      _reply = reply,
      _info = ResponseInfo(chanId),
      cause = DatabaseException(pack)(doc))
  }

/* TODO: Remove
  def foldResponses[T](
    makeRequest: ExecutionContext => Future[Response],
    next: (ExecutionContext, Response) => Future[Option[Response]],
    killCursors: (Long, String) => Unit,
    z: => T,
    maxDocs: Int,
    suc: (T, Response) => Future[Cursor.State[T]],
    err: Cursor.ErrorHandler[T])(implicit sys: akka.actor.ActorSystem, ec: ExecutionContext): Future[T] =
    FoldResponses[T](
      z, makeRequest, next, killCursors, suc, err, maxDocs)(sys, ec)
*/

  val bsonReadPref: ReadPreference => BSONDocument = {
    val writeReadPref = QueryCodecs.writeReadPref(BSONSerializationPack)

    writeReadPref(_: ReadPreference)
  }

  @inline def RefreshAll = actors.RefreshAll

  @inline def updateNodeSet(sys: StandardDBSystem, event: String)(f: NodeSet => NodeSet) = sys.updateNodeSet(event)(f)

  @inline def nodeSet(sys: StandardDBSystem) = sys._nodeSet

  @inline def isMasterReqId(): Int = RequestIdGenerator.isMaster.next

  @inline def connectAll(sys: StandardDBSystem, ns: NodeSet) =
    sys.connectAll(ns)

  @inline def channelFactory(supervisorName: String, connectionName: String, options: MongoConnectionOptions): ChannelFactory = new ChannelFactory(supervisorName, connectionName, options)

  @inline def createChannel(
    factory: ChannelFactory,
    receiver: ActorRef,
    host: String,
    port: Int): Try[Channel] = factory.create(host, port, receiver)

  @inline def releaseChannelFactory(f: ChannelFactory, clb: Promise[Unit]) =
    f.release(clb)

  object IsMasterCommand
    extends reactivemongo.api.commands.IsMasterCommand[Pack]

  @inline def isMasterRequest(reqId: Int = RequestIdGenerator.isMaster.next): Request = {
    import reactivemongo.api.commands.Command
    import IsMasterCommand.{ IsMaster, writer => mw }

    val isMaster = Command.buildRequestMaker(BSONSerializationPack)(
      new IsMaster(None, None),
      mw(BSONSerializationPack),
      reactivemongo.api.ReadPreference.primaryPreferred,
      "admin") // only "admin" DB for the admin command

    isMaster(reqId) // RequestIdGenerator.isMaster
  }

  object IsMasterResponse {
    def unapply(response: Response) =
      Option(response).filter(RequestIdGenerator.isMaster.accepts)
  }

  def decodeResponse[T]: Array[Byte] => (Tuple2[ByteBuf, Response] => T) => T = {
    val decoder = new ResponseDecoder()

    { bytes =>
      val buf = Unpooled.buffer(bytes.size, bytes.size)
      val out = new java.util.ArrayList[Object](1)

      buf.retain()

      buf.writeBytes(bytes)
      buf.resetReaderIndex()

      decoder.decode(null, buf, out)

      { f: (Tuple2[ByteBuf, Response] => T) =>
        try {
          f(buf -> out.get(0).asInstanceOf[Response])
        } finally {
          buf.release()
          ()
        }
      }
    }
  }

  val decodeFrameResp: Array[Byte] => List[ByteBuf] = {
    val decoder = new ResponseFrameDecoder()

    { bytes =>
      val buf = Unpooled.buffer(bytes.size, bytes.size)
      val out = new java.util.ArrayList[Object](1)

      buf.writeBytes(bytes)
      buf.resetReaderIndex()

      decoder.decode(null, buf, out)

      val frames = List.newBuilder[ByteBuf]
      val it = out.iterator

      while (it.hasNext) {
        frames += it.next().asInstanceOf[ByteBuf]
      }

      frames.result()
    }
  }

  @inline def initChannel(
    factory: ChannelFactory,
    channel: Channel,
    host: String, port: Int,
    receiver: ActorRef) = factory.initChannel(channel, host, port, receiver)

  def getBytes(buf: ByteBuf, size: Int): Array[Byte] = {
    val bytes = Array.ofDim[Byte](size)

    buf.resetReaderIndex()
    buf.getBytes(0, bytes)

    bytes
  }

  def withContent[T](uri: java.net.URI)(f: java.io.InputStream => T): T =
    reactivemongo.util.withContent[T](uri)(f)

  def srvRecords(name: String, srvPrefix: String)(implicit ec: ExecutionContext): Future[List[(String, Int)]] = srvRecords(name)(reactivemongo.util.dnsResolve(srvPrefix = srvPrefix))

  def srvRecords(name: String)(resolver: reactivemongo.util.SRVRecordResolver)(implicit ec: ExecutionContext): Future[List[(String, Int)]] = reactivemongo.util.srvRecords(name)(resolver)

  def parseURI(
    uri: String,
    srvResolver: reactivemongo.util.SRVRecordResolver,
    txtResolver: reactivemongo.util.TXTResolver)(
    implicit
    ec: ExecutionContext) =
    MongoConnection.fromString(uri, srvResolver, txtResolver)

  def preload(resp: Response)(implicit ec: ExecutionContext): Future[(Response, BSONDocument)] = reactivemongo.core.protocol.Response.preload(resp)

  @inline def session(db: DB): Option[Session] = db.session

  def messageHeader(
    messageLength: Int,
    requestID: Int,
    responseTo: Int,
    opCode: Int): MessageHeader =
    new MessageHeader(messageLength, requestID, responseTo, opCode)

  def reply(
    flags: Int,
    cursorID: Long,
    startingFrom: Int,
    numberReturned: Int) = new Reply(flags, cursorID, startingFrom, numberReturned)

  def query(
    flags: Int,
    fullCollectionName: String,
    numberToSkip: Int,
    numberToReturn: Int) = Query(flags, fullCollectionName, numberToSkip, numberToReturn)

  def response(
    header: MessageHeader,
    reply: Reply,
    documents: ByteBuf,
    info: ResponseInfo) =
    reactivemongo.core.protocol.Response(header, reply, documents, info)

  def responseInfo(cid: ChannelId) = new ResponseInfo(cid)

  def readMessageHeader(buffer: ByteBuf) = MessageHeader.readFrom(buffer)

  def readReply(buffer: ByteBuf) = Reply.readFrom(buffer)

  object commands {
    implicit val replSetMaintenanceWriter =
      reactivemongo.api.commands.ReplSetMaintenance.writer(pack)

    implicit val unitBoxReader =
      reactivemongo.api.commands.CommandCodecs.unitBoxReader(pack)

    implicit val replSetGetStatusWriter =
      reactivemongo.api.commands.ReplSetGetStatus.writer(pack)

    import reactivemongo.api.commands.ReplSetStatus

    implicit val replSetStatusReader: pack.Reader[ReplSetStatus] =
      reactivemongo.api.commands.ReplSetGetStatus.reader(pack)

  }
}
