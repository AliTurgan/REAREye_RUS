package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Discovery only: no widget previews, bindings, or cross-profile enumeration. */
fun interface AppWidgetAppsProvider {
    fun enumerate(): List<AppWidgetAppProvider>
}

data class AppWidgetAppProvider(
    val component: ComponentName,
    val appName: String,
    val widgetCategory: Int = 1
)

internal data class AppWidgetAppRow(
    val packageName: String,
    val appName: String,
    val widgetCount: Int
)

class AppWidgetAppsDataSource(
    context: Context,
    provider: AppWidgetAppsProvider? = null,
    executor: ExecutorService? = null,
) : MamlDataSource {
    private val app = context.applicationContext ?: error("Application context required")
    private val displayContext =
        runCatching { app.createDisplayContext(context.display) }.getOrDefault(app)
    private val configuration = Configuration(context.resources.configuration)
    private val safeContext = displayContext.createConfigurationContext(configuration)
    private val main = Handler(Looper.getMainLooper())

    // One running discovery and at most one replacement; a slow system call cannot build a backlog.
    private val worker = executor ?: ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), ThreadFactory { work ->
            Thread(work, "RearAppWidgetApps").apply { isDaemon = true }
        })
    private val ownsWorker = executor == null
    private val providers = provider ?: AppWidgetAppsProvider {
        // Apps and widget pages reuse the same host-user discovery, labels and display configuration.
        AppWidgetCatalogCache.catalog(safeContext).map { row ->
            AppWidgetAppProvider(row.info.provider, row.appName, row.info.widgetCategory)
        }
    }
    private var job: Job? = null
    private val subscriptions = mutableSetOf<Subscription>()
    private var paused = false
    private var closed = false

    private inner class Job : MamlDataHandle {
        val cancelled = AtomicBoolean()

        @Volatile
        var future: Future<*>? = null
        override fun close() {
            if (!cancelled.compareAndSet(false, true)) return
            future?.cancel(true)
            main.removeCallbacksAndMessages(this)
        }

        fun deliver(value: MamlDataResult, result: (MamlDataResult) -> Unit) {
            if (cancelled.get()) return
            main.postAtTime({
                if (!closed && !paused && !cancelled.get() && job === this) {
                    job = null
                    cancelled.set(true)
                    result(value)
                }
            }, this, SystemClock.uptimeMillis())
        }
    }

    override fun query(request: MamlDataRequest, result: (MamlDataResult) -> Unit): MamlDataHandle {
        checkMain()
        if (closed || paused) return MamlDataHandle.NONE
        job?.close()
        if (ownsWorker) (worker as ThreadPoolExecutor).purge()
        val current = Job().also { job = it }
        try {
            val offset = integerParameter(request, "offset", 0, 0, Int.MAX_VALUE)
            val limit = integerParameter(request, "limit", 3, 1, 32)
            val category = integerParameter(request, "category", 1, 0, Int.MAX_VALUE)
            current.future = worker.submit {
                if (current.cancelled.get()) return@submit
                val output = try {
                    val rows = group(providers.enumerate(), category)
                    MamlDataResult.Success(snapshot(rows, offset, limit))
                } catch (e: Exception) {
                    MamlDataResult.Failure(e.message ?: e.javaClass.simpleName)
                }
                current.deliver(output, result)
            }
            if (current.cancelled.get()) current.future?.cancel(true)
        } catch (e: Exception) {
            current.deliver(MamlDataResult.Failure(e.message ?: e.javaClass.simpleName), result)
        }
        return current
    }

    private inner class Subscription(val callback: () -> Unit) : MamlDataHandle {
        var attached: MamlDataHandle? = null
        var removed = false
        fun attach() {
            if (!removed && attached == null && !closed && !paused) {
                attached = AppWidgetCatalogWatcher.subscribe(app) {
                    if (!removed && !closed && !paused) {
                        job?.close(); job = null
                        refreshConfiguration()
                        callback()
                    }
                }
            }
        }

        fun detach() {
            attached?.close(); attached = null
        }

        override fun close() {
            checkMain()
            if (!removed) {
                removed = true; detach(); subscriptions.remove(this)
            }
        }
    }

    override fun subscribeInvalidations(invalidate: () -> Unit): MamlDataHandle {
        checkMain()
        if (closed) return MamlDataHandle.NONE
        return Subscription(invalidate).also { subscriptions.add(it); it.attach() }
    }

    override fun onPause() {
        checkMain()
        paused = true
        job?.close(); job = null
        subscriptions.forEach { it.detach() }
    }

    override fun onResume() {
        checkMain()
        if (closed) return
        refreshConfiguration()
        paused = false
        subscriptions.forEach { it.attach() }
    }

    override fun close() {
        checkMain()
        if (closed) return
        onPause()
        closed = true
        subscriptions.toList().forEach { it.close() }
        if (ownsWorker) worker.shutdownNow()
    }

    private fun refreshConfiguration() {
        configuration.setTo(displayContext.resources.configuration)
        @Suppress("DEPRECATION")
        safeContext.resources.updateConfiguration(
            configuration,
            displayContext.resources.displayMetrics
        )
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    companion object {
        const val TAG = "RearAppWidgetAppsBinder"
        val schema = MamlDataSchema(
            listOf(
                MamlDataField("appNames", MamlDataType.STRING, array = true),
                MamlDataField("packageNames", MamlDataType.STRING, array = true),
                MamlDataField("widgetCounts", MamlDataType.NUMBER, array = true),
                *listOf("total", "offset", "hasMore").map { MamlDataField(it, MamlDataType.NUMBER) }
                    .toTypedArray(),
            )
        )

        fun definition() = MamlDataSourceDefinition(
            TAG, schema, listOf(
                MamlDataParameter("offset", MamlDataType.NUMBER, 0, 0.0, Int.MAX_VALUE.toDouble()),
                MamlDataParameter("limit", MamlDataType.NUMBER, 3, 1.0, 32.0),
                MamlDataParameter(
                    "category",
                    MamlDataType.NUMBER,
                    1,
                    0.0,
                    Int.MAX_VALUE.toDouble()
                ),
            ), MamlDataSourceFactory { AppWidgetAppsDataSource(it) })

        private fun integerParameter(
            request: MamlDataRequest,
            name: String,
            default: Int,
            min: Int,
            max: Int
        ): Int {
            val value = if (name in request.values) request.number(name) else default.toDouble()
            require(value.isFinite() && value >= min && value <= max) { "Invalid $name range" }
            return value.toInt()
        }

        /** Match RearAppWidgetBinder: category 1 is home-screen widgets, 0 includes all categories. */
        internal fun group(
            entries: List<AppWidgetAppProvider>,
            category: Int = 1
        ): List<AppWidgetAppRow> = entries
            .filter { category == 0 || it.widgetCategory and category != 0 }
            .distinctBy { entry ->
                val component = entry.component
                val name =
                    component.className.let { if (it.startsWith('.')) component.packageName + it else it }
                ComponentName(component.packageName, name)
            }
            .groupBy { it.component.packageName }
            .map { (pkg, rows) ->
                // Platform labels are identical within a package; deterministic even for injected data.
                val label = rows.map { it.appName }
                    .minWithOrNull(compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it })!!
                AppWidgetAppRow(pkg, label, rows.size)
            }
            .sortedWith(compareBy<AppWidgetAppRow> { it.appName.lowercase(Locale.ROOT) }.thenBy { it.packageName })

        internal fun snapshot(
            rows: List<AppWidgetAppRow>,
            offset: Int,
            limit: Int
        ): MamlDataSnapshot {
            require(offset >= 0 && limit in 1..32)
            val page = rows.drop(offset).take(limit)
            return MamlDataSnapshot(
                mapOf(
                    "appNames" to page.map { it.appName }.toTypedArray(),
                    "packageNames" to page.map { it.packageName }.toTypedArray(),
                    "widgetCounts" to page.map { it.widgetCount.toDouble() }.toDoubleArray(),
                    "total" to rows.size.toDouble(), "offset" to offset.toDouble(),
                    "hasMore" to if (offset.toLong() + page.size < rows.size) 1.0 else 0.0,
                ), page.size
            )
        }
    }
}
