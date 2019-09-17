package zio.interop.reactiveStreams

import org.reactivestreams.tck.TestEnvironment
import org.reactivestreams.tck.TestEnvironment.ManualSubscriberWithSubscriptionSupport
import scala.jdk.CollectionConverters._
import zio.{ Task, UIO, ZIO }
import zio.blocking._
import zio.interop.reactiveStreams.SubscriberToSinkSpecUtil._
import zio.stream.Stream
import zio.test._
import zio.test.Assertion._

object SubscriberToSinkSpec
    extends DefaultRunnableSpec(
      suite("A `Subscriber` converted to a `Sink` correctly")(
        testM("works on the happy path ") {
          for {
            probe         <- makeSubscriber
            errorSink     <- probe.underlying.toSink[Throwable]
            (error, sink) = errorSink
            fiber         <- Stream.fromIterable(seq).run(sink).catchAll(t => error.fail(t)).fork
            _             <- probe.request(length + 1)
            elements      <- probe.nextElements(length).run
            completion    <- probe.expectCompletion.run
            _             <- fiber.join
          } yield assert(elements, succeeds(equalTo(seq))) && assert(completion, succeeds(isUnit))
        },
        testM("transports errors") {
          for {
            probe         <- makeSubscriber
            errorSink     <- probe.underlying.toSink[Throwable]
            (error, sink) = errorSink
            fiber         <- Stream.fromIterable(seq).++(Stream.fail(e)).run(sink).catchAll(t => error.fail(t)).fork
            _             <- probe.request(length + 1)
            elements      <- probe.nextElements(length).run
            err           <- probe.expectError.run
            _             <- fiber.join
          } yield assert(elements, succeeds(equalTo(seq))) && assert(err, succeeds(equalTo(e)))
        }
      )
    )

object SubscriberToSinkSpecUtil {
  // ManualSubscriberWithSubscriptionSupport has an internal buffer of at most 32 elements.
  val seq: List[Int] = List.range(0, 31)
  val length: Long   = seq.length.toLong
  val e: Throwable   = new RuntimeException("boom")

  case class Probe[T](underlying: ManualSubscriberWithSubscriptionSupport[T]) {
    def request(n: Long): UIO[Unit] =
      UIO(underlying.request(n))
    def nextElements(n: Long): ZIO[Blocking, Throwable, List[T]] =
      blocking(Task(underlying.nextElements(n.toLong).asScala.toList))
    def expectError: ZIO[Blocking, Throwable, Throwable] =
      blocking(Task(underlying.expectError(classOf[Throwable])))
    def expectCompletion: ZIO[Blocking, Throwable, Unit] =
      blocking(Task(underlying.expectCompletion()))
  }

  val testEnv: TestEnvironment = new TestEnvironment(1000)
  val makeSubscriber           = UIO(new ManualSubscriberWithSubscriptionSupport[Int](testEnv)).map(Probe.apply)
}
