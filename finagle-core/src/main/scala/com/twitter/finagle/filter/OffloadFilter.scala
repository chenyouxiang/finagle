package com.twitter.finagle.filter

import com.twitter.concurrent.NamedPoolThreadFactory
import com.twitter.finagle.offload.numWorkers
import com.twitter.finagle.stats.FinagleStatsReceiver
import com.twitter.finagle.tracing.{Annotation, Trace}
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.util.{Future, FuturePool, Promise}
import java.util.concurrent.{ExecutorService, Executors}

/**
 * These modules introduce async-boundary into the future chain, effectively shifting continuations
 * off of IO threads (into a given [[FuturePool]]).
 *
 * This filter can be enabled by default through the flag `com.twitter.finagle.offload.numWorkers`.
 */
object OffloadFilter {

  private[this] val Role = Stack.Role("OffloadWorkFromIO")
  private[this] val Description = "Offloading computations from IO threads"

  private[this] lazy val (defaultPool, defautPoolStats) = {
    numWorkers.get match {
      case None =>
        (None, Seq.empty)
      case Some(threads) =>
        val factory = new NamedPoolThreadFactory("finagle/offload", makeDaemons = true)
        val pool = FuturePool(Executors.newFixedThreadPool(threads, factory))
        val stats = FinagleStatsReceiver.scope("offload_pool")
        val gauges = Seq(
          stats.addGauge("pool_size") { pool.poolSize },
          stats.addGauge("active_tasks") { pool.numActiveTasks },
          stats.addGauge("completed_tasks") { pool.numCompletedTasks }
        )
        (Some(pool), gauges)
    }
  }

  private[finagle] sealed abstract class Param
  private[finagle] object Param {

    def apply(pool: FuturePool): Param = Enabled(pool)
    def apply(executor: ExecutorService): Param = Enabled(FuturePool(executor))

    final case class Enabled(pool: FuturePool) extends Param
    final case object Disabled extends Param

    implicit val param: Stack.Param[Param] =
      Stack.Param(defaultPool.map(Enabled(_)).getOrElse(Disabled))
  }

  private[finagle] def client[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Module[Req, Rep](new Client(_))

  private[finagle] def server[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Module[Req, Rep](new Server(_))

  final class Client[Req, Rep](pool: FuturePool) extends SimpleFilter[Req, Rep] {

    // Has to be lazy to see the number of workers in the pool at the point at which the annotation
    // is generated.
    private lazy val offloadAnnotation = Annotation.Message(
      s"clnt/OffloadFilter: Offloaded continuation from IO threads to pool with ${pool.poolSize} workers")

    def apply(request: Req, service: Service[Req, Rep]): Future[Rep] = {
      // What we're trying to achieve is to ensure all continuations spawn out of a future returned
      // from this method are run outside of the IO thread (in the given FuturePool).
      //
      // Simply doing:
      //
      //   service(request).flatMap(pool.apply(_))
      //
      // is not enough as the successful offloading is contingent on winning the race between
      // pool.apply and promise linking (via flatMap). In our high load simulations we observed that
      // offloading won't happen (because the race is lost) in about 6% of the cases.
      //
      // One way of increasing our chances is making the race unfair by also including the dispatch
      // time in the equation: the offloading won't happen if both making an RPC dispatch and
      // bouncing through the executor (FuturePool) somehow happened to outrun a synchronous code in
      // this method.
      //
      // You would be surprised but this can happen. Same simulations report that we lose a race in
      // about 1 in 1 000 000 of cases this way (0.0001%).
      //
      // There is no better explanation to this than in Bryce's own words:
      //
      // > Thread pauses are nuts.
      // > Kernels are crazy.
      val response = service(request)
      val shifted = Promise.interrupts[Rep](response)
      response.respond { t =>
        pool(shifted.update(t))

        val tracing = Trace()
        if (tracing.isActivelyTracing) {
          tracing.record(offloadAnnotation)
        }
      }

      shifted
    }
  }

  final class Server[Req, Rep](pool: FuturePool) extends SimpleFilter[Req, Rep] {

    // Has to be lazy to see the number of workers in the pool at the point at which the annotation
    // is generated.
    private lazy val offloadAnnotation = Annotation.Message(
      s"srv/OffloadFilter: Offloaded continuation from IO threads to pool with ${pool.poolSize} workers")

    def apply(request: Req, service: Service[Req, Rep]): Future[Rep] = {

      // Offloading on the server-side is fairly straightforward: it comes down to running
      // service.apply (users' work) outside of the IO thread.
      //
      // Unfortunately, there is no (easy) way to bounce back to the IO thread as we return into
      // the stack. It's more or less fine as we will switch back to IO as we enter the pipeline
      // (Netty land).
      //
      // Note: we use an indirection promise so that we can disallow interrupting the `Future`
      // returned by the `FuturePool`. It is not generally safe to interrupt the thread performing
      // the `service.apply` call and thread interruption is a behavior of some FuturePool
      // implementations (see `FuturePool.interruptible`). By using an intermediate `Promise` we can
      // still allow chaining of interrupts without allowing interruption of the `FuturePool` thread
      // itself. This is handled nicely by using `Promise.become` inside the FuturePool task which
      // results in proper propagation of interrupts from `shifted` to the result of `service(req)`.
      // It's worth noting that the order of interrupting and setting interrupt handlers works as
      // expected when using the `.become` method. Both the following cases correctly propagate
      // interrupts:
      // val a,b = new Promise[Unit]()
      // 1: a.raise(ex); b.setInterruptHandler(h); a.become(b) -> h(ex) called.
      // 2: a.raise(ex); a.become(b); b.setInterruptHandler(h) -> h(ex) called.
      val shifted = Promise[Rep]()
      pool { shifted.become(service(request)) }

      val tracing = Trace()
      if (tracing.isActivelyTracing) {
        tracing.record(offloadAnnotation)
      }
      shifted
    }
  }

  private final class Module[Req, Rep](makeFilter: FuturePool => SimpleFilter[Req, Rep])
      extends Stack.Module1[Param, ServiceFactory[Req, Rep]] {

    def make(p: Param, next: ServiceFactory[Req, Rep]): ServiceFactory[Req, Rep] = p match {
      case Param.Enabled(pool) => makeFilter(pool).andThen(next)
      case Param.Disabled => next
    }

    def role: Stack.Role = Role
    def description: String = Description
  }
}
