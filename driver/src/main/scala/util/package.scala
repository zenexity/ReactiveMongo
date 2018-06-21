/*
 * Copyright 2012-2013 Stephane Godbillon (@sgodbillon) and Zenexity
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactivemongo

import java.io.InputStream

import java.net.URI

import scala.util.control.NonFatal

package object util {
  import scala.language.implicitConversions

  /** Makes an option of the value matching the condition. */
  def option[T](cond: => Boolean, value: => T): Option[T] =
    if (cond) Some(value) else None

  import scala.concurrent.{
    ExecutionContext,
    Future,
    Promise,
    duration
  }, duration.Duration

  case class EitherMappableFuture[A](future: Future[A]) {
    def mapEither[E <: Throwable, B](f: A => Either[E, B])(implicit ec: ExecutionContext) = {
      future.flatMap(
        f(_) match {
          case Left(e)  => Future.failed(e)
          case Right(b) => Future.successful(b)
        })
    }
  }

  object EitherMappableFuture {
    implicit def futureToEitherMappable[A](future: Future[A]): EitherMappableFuture[A] = new EitherMappableFuture[A](future)
  }

  object ExtendedFutures {
    import akka.actor.ActorSystem

    // better way to this?
    def DelayedFuture(millis: Long, system: ActorSystem): Future[Unit] = {
      implicit val ec = system.dispatcher
      val promise = Promise[Unit]()

      system.scheduler.scheduleOnce(Duration.apply(millis, "millis")) {
        promise.success({}); ()
      }

      promise.future
    }
  }

  // ---

  private[reactivemongo] def withContent[T](uri: URI)(f: InputStream => T): T = {
    lazy val in = if (uri.getScheme == "classpath") {
      Thread.currentThread().getContextClassLoader.
        getResourceAsStream(uri.getPath)

    } else {
      uri.toURL.openStream()
    }

    try {
      f(in)
    } catch {
      case NonFatal(cause) => throw cause
    } finally {
      in.close()
    }
  }

  // ---

  import scala.concurrent.duration.FiniteDuration

  import org.xbill.DNS.{ Lookup, Name, Record, SRVRecord, Type }

  private[reactivemongo] lazy val dnsTimeout = FiniteDuration(5, "seconds")

  /**
   * @param name the DNS name (e.g. `mycluster.mongodb.com`)
   * @param timeout the resolution timeout (default: 5 seconds)
   * @param srvPrefix the SRV prefix (default: `_mongodb._tcp`)
   */
  def srvRecords(
    name: String,
    timeout: FiniteDuration = dnsTimeout,
    srvPrefix: String = "_mongodb._tcp")(
    implicit
    ec: ExecutionContext): Future[List[(String, Int)]] = {

    val baseName = Name.fromString(
      name.dropWhile(_ != '.').drop(1), Name.root)

    def resolve: Future[Array[Record]] = {
      val service = Name.fromConstantString(name + '.')

      if (service.labels < 3) {
        Future.failed[Array[Record]](new IllegalArgumentException(
          s"Invalid DNS service name (e.g. 'service.domain.tld'): $service"))
      } else Future {
        // assert service.label >= 3

        val srvName = Name.concatenate(
          Name.fromConstantString(srvPrefix), service)

        val lookup = new Lookup(srvName, Type.SRV)

        lookup.setResolver {
          val r = Lookup.getDefaultResolver
          r.setTimeout(timeout.toSeconds.toInt)
          r
        }

        lookup.run()
      }
    }

    @annotation.tailrec
    def go(records: Array[Record], names: List[(String, Int)]): Future[List[(String, Int)]] =
      records.headOption match {
        case Some(rec: SRVRecord) => {
          val nme = rec.getAdditionalName

          if (nme.isAbsolute) {
            if (!nme.subdomain(baseName)) {
              Future.failed[List[(String, Int)]](new IllegalArgumentException(
                s"$nme is not subdomain of $baseName"))

            } else {
              go(records.tail, (nme.toString(true) -> rec.getPort) :: names)
            }
          } else {
            go(
              records.tail,
              (Name.concatenate(
                nme, baseName).toString(true) -> rec.getPort) :: names)
          }
        }

        case Some(rec) => Future.failed[List[(String, Int)]](
          new IllegalArgumentException(s"Unexpected record: $rec"))

        case _ => Future.successful(names.reverse)
      }

    // ---

    resolve.flatMap { records => go(records, List.empty) }
  }

  /**
   * @param name the DNS name (e.g. `mycluster.mongodb.com`)
   * @param timeout the resolution timeout (default: 5 seconds)
   */
  def txtRecords(
    name: String,
    timeout: FiniteDuration = dnsTimeout)(
    implicit
    ec: ExecutionContext): Future[List[String]] = Future {

    val lookup = new Lookup(name, Type.TXT)

    lookup.setResolver {
      val r = Lookup.getDefaultResolver
      r.setTimeout(timeout.toSeconds.toInt)
      r
    }

    lookup.run().map { rec =>
      val data = rec.rdataToString
      val stripped = data.stripPrefix("\"")

      if (stripped == data) {
        data
      } else {
        stripped.stripSuffix("\"")
      }
    }.toList
  }
}
