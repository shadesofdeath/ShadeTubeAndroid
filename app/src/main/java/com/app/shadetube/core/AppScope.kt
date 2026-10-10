package com.app.shadetube.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/**
 * Threading model (replaces core/ThreadPool + Dispatcher + Async):
 *
 *  - C++ "UI thread"          -> Dispatchers.Main (state that the UI observes is exposed as StateFlow)
 *  - C++ worker pool lanes    -> [AppDispatchers.io] (network / disk), [AppDispatchers.low] (prefetch, background
 *                                matching, scans: limited parallelism so it never starves interactive work)
 *  - st::Lifetime / renew()   -> a Job / CoroutineScope that is cancelled
 *  - st::async(work, done)    -> scope.launch { val r = withContext(io) { work() }; done(r) }
 *
 * [appScope] lives as long as the process (services, stores, housekeeping). Failures are logged, never crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object AppDispatchers {
    val io = Dispatchers.IO
    val low = Dispatchers.IO.limitedParallelism(2)
    val cpu = Dispatchers.Default
    val main = Dispatchers.Main
}

private val loggingHandler = CoroutineExceptionHandler { _, t -> Log.e("async", "uncaught in appScope", t) }

val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + loggingHandler)
