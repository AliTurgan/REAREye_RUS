package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Locale
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppWidgetAppsDataSourceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())

    private class QueueWorker : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        var stopped = false
        override fun execute(command: Runnable) {
            tasks += command
        }

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true; return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
        fun drain() {
            val pending = tasks.toList().also { tasks.clear() }
            Thread { pending.forEach { it.run() } }.apply { start(); join() }
        }
    }

    private fun entry(
        pkg: String,
        label: String,
        component: String = "$pkg.Widget",
        category: Int = 1
    ) =
        AppWidgetAppProvider(ComponentName(pkg, component), label, category)

    private fun request(vararg changes: Pair<String, Any>) = MamlDataRequest(
        AppWidgetAppsDataSource.definition().parameters.associate { it.name to it.coerce(it.defaultValue) } + changes)

    @Test
    fun groupingDeduplicatesCanonicalComponentsAndSortsWithRootLocale() {
        val old = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            val rows = AppWidgetAppsDataSource.group(
                listOf(
                    entry("z", "Alpha"), entry("a", "ALPHA", ".Widget"), entry("a", "ALPHA"),
                    entry("a", "ALPHA", "a.Other"), entry("c", "Iota"), entry("b", "beta")
                )
            )
            assertEquals(listOf("a", "z", "b", "c"), rows.map { it.packageName })
            assertEquals(listOf(2, 1, 1, 1), rows.map { it.widgetCount })
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun categoryFilterRunsBeforeGroupingAndCountsMatchWidgetCatalog() {
        val rows = listOf(
            entry("a", "A", "a.Lock", category = 2),
            entry("a", "A", "a.Home", category = 1), entry("a", "A", "a.Both", category = 3),
            entry("b", "B", category = 2), entry("c", "C", category = 0)
        )
        val home = AppWidgetAppsDataSource.group(rows)
        assertEquals(listOf("a"), home.map { it.packageName })
        assertEquals(2, home.single().widgetCount)
        val all = AppWidgetAppsDataSource.group(rows, 0)
        assertEquals(listOf("a", "b", "c"), all.map { it.packageName })
        assertEquals(listOf(3, 1, 1), all.map { it.widgetCount })
        assertEquals(
            listOf("a", "b"),
            AppWidgetAppsDataSource.group(rows, 2).map { it.packageName })
        val worker = QueueWorker()
        val source = AppWidgetAppsDataSource(context, AppWidgetAppsProvider { rows }, worker)
        try {
            var output: MamlDataResult? = null
            source.query(MamlDataRequest(emptyMap())) {
                output = it
            } // Missing params use category=1.
            worker.drain(); main.idle()
            val snapshot = (output as MamlDataResult.Success).snapshot
            assertEquals(1, snapshot.count)
            assertEquals(1.0, snapshot.values["total"])
            assertArrayEquals(
                doubleArrayOf(2.0),
                snapshot.values["widgetCounts"] as DoubleArray,
                0.0
            )
            source.query(request("category" to 0.0)) { output = it }
            worker.drain(); main.idle()
            val unfiltered =
                AppWidgetAppsDataSource.schema.validate((output as MamlDataResult.Success).snapshot)
            assertEquals(3, unfiltered.count)
            assertArrayEquals(
                doubleArrayOf(3.0, 1.0, 1.0),
                unfiltered.values["widgetCounts"] as DoubleArray,
                0.0
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun asynchronousWorkerPagesApplicationsNotProvidersAndBoundsEmptyPage() {
        val worker = QueueWorker()
        var workerThread = false
        val source = AppWidgetAppsDataSource(context, AppWidgetAppsProvider {
            workerThread = Looper.myLooper() != Looper.getMainLooper()
            (0..39).map { entry("pkg%02d".format(it), "App%02d".format(it)) } + entry(
                "pkg00",
                "App00",
                "pkg00.Other"
            )
        }, worker)
        try {
            var output: MamlDataResult? = null
            source.query(request("limit" to 32.0)) { output = it }
            assertNull(output)
            worker.drain(); main.idle()
            assertTrue(workerThread)
            val page =
                AppWidgetAppsDataSource.schema.validate((output as MamlDataResult.Success).snapshot)
            assertEquals(32, page.count)
            assertEquals(40.0, page.values["total"])
            assertEquals(1.0, page.values["hasMore"])
            assertEquals(2.0, (page.values["widgetCounts"] as DoubleArray)[0], 0.0)
            source.query(request("offset" to Int.MAX_VALUE.toDouble())) { output = it }
            worker.drain(); main.idle()
            val empty =
                AppWidgetAppsDataSource.schema.validate((output as MamlDataResult.Success).snapshot)
            assertEquals(0, empty.count)
            assertEquals(Int.MAX_VALUE.toDouble(), empty.values["offset"])
            assertEquals(0.0, empty.values["hasMore"])
        } finally {
            source.close()
        }
        assertFalse(worker.stopped)
    }

    @Test
    fun invalidRangesFailBeforeDiscoveryAndDefinitionRejectsThem() {
        val worker = QueueWorker()
        var discoveries = 0
        val source = AppWidgetAppsDataSource(
            context,
            AppWidgetAppsProvider { discoveries++; emptyList() },
            worker
        )
        try {
            val cases = listOf(
                "offset" to -1.0, "offset" to Int.MAX_VALUE.toDouble() + 1,
                "limit" to 33.0, "limit" to 0.0, "limit" to Double.NaN,
                "category" to -1.0, "category" to Int.MAX_VALUE.toDouble() + 1
            )
            for ((name, value) in cases) {
                val parameter =
                    AppWidgetAppsDataSource.definition().parameters.single { it.name == name }
                assertThrows(IllegalArgumentException::class.java) { parameter.coerce(value) }
                var output: MamlDataResult? = null
                source.query(request(name to value)) { output = it }
                worker.drain(); main.idle()
                assertTrue(output is MamlDataResult.Failure)
            }
            assertEquals(0, discoveries)
        } finally {
            source.close()
        }
    }

    @Test
    fun cancellationReplacementPauseAndCloseSuppressOldResults() {
        val worker = QueueWorker()
        val source = AppWidgetAppsDataSource(
            context,
            AppWidgetAppsProvider { listOf(entry("a", "A")) },
            worker
        )
        var callbacks = 0
        val handle = source.query(request()) { callbacks++ }
        handle.close(); handle.close()
        worker.drain(); main.idle()
        source.query(request()) { callbacks += 100 }
        worker.drain()
        source.query(request()) { callbacks++ }
        worker.drain(); main.idle()
        assertEquals(1, callbacks)
        source.query(request()) { callbacks++ }
        worker.drain(); source.onPause(); main.idle()
        source.query(request()) { callbacks++ }
        assertTrue(worker.tasks.isEmpty())
        source.onResume()
        source.query(request()) { callbacks++ }
        worker.drain(); source.close(); source.close(); main.idle()
        source.query(request()) { callbacks++ }
        assertEquals(1, callbacks)
    }

    @Test
    fun slowDiscoveryKeepsOnlyOneQueuedReplacementAndOwnedWorkerCloses() {
        val entered = CountDownLatch(1)
        val release = Semaphore(0)
        val source = AppWidgetAppsDataSource(context, AppWidgetAppsProvider {
            entered.countDown()
            release.acquireUninterruptibly() // Simulate a platform call which ignores Future.cancel.
            emptyList()
        })
        val worker = AppWidgetAppsDataSource::class.java.getDeclaredField("worker")
            .apply { isAccessible = true }
            .get(source) as ThreadPoolExecutor
        var callbacks = 0
        try {
            source.query(request()) { callbacks++ }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(100) { source.query(request()) { callbacks++ } }
            assertTrue(worker.queue.size <= 1)
            source.onPause()
            source.close()
            assertTrue(worker.isShutdown)
        } finally {
            release.release(); source.close()
        }
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        main.idle()
        assertEquals(0, callbacks)
    }

    @Test
    fun subscriptionsShareHubPauseAndCloseUnregister() {
        val source =
            AppWidgetAppsDataSource(context, AppWidgetAppsProvider { emptyList() }, QueueWorker())
        val before = shadowOf(context).registeredReceivers.size
        var callbacks = 0
        try {
            val handle = source.subscribeInvalidations { callbacks++ }
            source.subscribeInvalidations { callbacks++ }
            assertEquals(before + 1, shadowOf(context).registeredReceivers.size)
            source.onPause()
            assertEquals(before, shadowOf(context).registeredReceivers.size)
            source.onResume()
            context.sendBroadcast(Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:a")))
            main.idle()
            assertEquals(2, callbacks)
            handle.close(); handle.close()
            source.close()
            assertEquals(before, shadowOf(context).registeredReceivers.size)
        } finally {
            source.close()
        }
    }
}
