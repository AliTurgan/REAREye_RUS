package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Injecting this provider avoids platform discovery in tests; enumerate executes on the worker. */
fun interface AppWidgetCatalogProvider {
    fun enumerate(): List<AppWidgetCatalogEntry>
}

data class AppWidgetCatalogEntry(
    val info: AppWidgetProviderInfo,
    val appName: String,
    val widgetName: String,
) {
    val packageName: String get() = info.provider.packageName
    val provider: String
        get() {
            val component = info.provider
            val className =
                component.className.let { if (it.startsWith('.')) component.packageName + it else it }
            return android.content.ComponentName(component.packageName, className).flattenToString()
        }
}

class AppWidgetCatalogDataSource(
    context: Context,
    catalogProvider: AppWidgetCatalogProvider? = null,
    previewLoader: AppWidgetPreviewLoader? = null,
    executor: ExecutorService? = null,
) : MamlDataSource {
    private val app = context.applicationContext ?: error("Application context required")
    private val displayContext =
        runCatching { app.createDisplayContext(context.display) }.getOrDefault(app)
    private val configuration = Configuration(context.resources.configuration)
    private val safeContext = displayContext.createConfigurationContext(configuration)
    private val main = Handler(Looper.getMainLooper())
    private val worker = executor ?: ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), java.util.concurrent.ThreadFactory { work ->
            Thread(work, "RearAppWidgetCatalog").apply { isDaemon = true }
        })
    private val ownsWorker = executor == null
    private val catalogNamespace = catalogProvider
    private val catalog = AppWidgetCatalogProvider {
        if (catalogProvider == null) AppWidgetCatalogCache.catalog(safeContext)
        else AppWidgetCatalogCache.loadCatalog(
            safeContext,
            catalogNamespace
        ) { catalogProvider.enumerate() }
    }
    private val systemPreviews = previewLoader == null
    private var previews = previewLoader ?: AppWidgetSystemPreviewLoader(safeContext)

    // Same injected instance can intentionally share work; distinct test loaders remain isolated.
    private val previewNamespace = previewLoader
    private val previewExecutor = executor
    private val jobs = mutableSetOf<Job>()
    private val subscriptions = mutableSetOf<Subscription>()
    private var paused = false
    private var closed = false

    private inner class Job : MamlDataHandle {
        val cancelled = AtomicBoolean()
        val generation = AppWidgetCatalogCache.generation
        val previews = mutableListOf<MamlDataHandle>()

        @Volatile
        var future: Future<*>? = null
        fun valid() =
            !cancelled.get() && !closed && !paused && generation == AppWidgetCatalogCache.generation

        fun detach() {
            previews.forEach { it.close() }
            previews.clear()
            jobs.remove(this)
        }

        override fun close() {
            if (!cancelled.compareAndSet(false, true)) return
            future?.cancel(true)
            main.removeCallbacksAndMessages(this)
            // The enumeration future belongs to this query; preview handles only detach subscribers.
            if (Looper.myLooper() == Looper.getMainLooper()) detach()
            else main.post { detach() }
        }

        fun post(action: () -> Unit) {
            if (!cancelled.get()) main.postAtTime({
                if (valid()) action()
            }, this, SystemClock.uptimeMillis())
        }
    }

    override fun query(request: MamlDataRequest, result: (MamlDataResult) -> Unit): MamlDataHandle {
        checkMain()
        if (closed || paused) return MamlDataHandle.NONE
        // Only the latest page is retained by this source; published bitmaps are never recycled.
        jobs.toList().forEach { it.close() }
        if (ownsWorker) (worker as ThreadPoolExecutor).purge()
        val job = Job().also(jobs::add)
        val packageFilter = request.string("package")
        val search = request.string("search")
        val offset = request.number("offset").toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val limit = request.number("limit").toInt().coerceIn(1, 32)
        val width = request.number("previewWidth").toInt().coerceIn(1, 512)
        val height = request.number("previewHeight").toInt().coerceIn(1, 512)
        val category =
            request.number("category").toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val priority = (request.values["priority"] as? Number)?.toInt()?.coerceIn(0, 2) ?: 0
        val loader = previews // Immutable loader/configuration for the lifetime of this request.
        try {
            job.future = worker.submit {
                try {
                    val selected = select(catalog.enumerate(), packageFilter, search, category)
                    val density = safeContext.resources.displayMetrics.density.toDouble()
                        .takeIf { it > 0 && it.isFinite() } ?: 1.0
                    if (job.cancelled.get()) return@submit
                    val page = selected.drop(offset).take(limit)
                    val (w, h) = AppWidgetPreviewBudget.dimensions(width, height, page.size)
                    job.post {
                        val rendered = arrayOfNulls<AppWidgetPreview>(page.size)
                        var remaining = page.size
                        fun publish() {
                            if (remaining != 0 || !job.valid()) return
                            // One terminal Success per request, independent of other offscreen pages.
                            job.cancelled.set(true)
                            job.detach()
                            result(
                                MamlDataResult.Success(
                                    snapshot(
                                        page, rendered.map { requireNotNull(it) },
                                        selected.size, offset, density
                                    )
                                )
                            )
                        }
                        if (page.isEmpty()) publish()
                        page.forEachIndexed { index, entry ->
                            if (!job.valid()) return@forEachIndexed
                            val key = AppWidgetCatalogCache.previewKey(
                                safeContext,
                                entry.info,
                                w,
                                h,
                                category,
                                previewNamespace
                            )
                            val handle = AppWidgetSharedPreviews.subscribe(
                                key,
                                job.generation,
                                priority,
                                previewExecutor,
                                {
                                    loader.prepare(entry.info, w, h, category)
                                }) { preview ->
                                if (job.valid() && rendered[index] == null) {
                                    rendered[index] = preview
                                    remaining--
                                    publish()
                                }
                            }
                            if (job.valid()) job.previews += handle else handle.close()
                        }
                    }
                } catch (e: Exception) {
                    job.post {
                        jobs.remove(job)
                        job.cancelled.set(true)
                        result(MamlDataResult.Failure(e.message ?: e.javaClass.simpleName))
                    }
                }
            }
            if (job.cancelled.get()) job.future?.cancel(true)
        } catch (e: Exception) {
            job.post {
                jobs.remove(job)
                job.cancelled.set(true)
                result(MamlDataResult.Failure(e.message ?: e.javaClass.simpleName))
            }
        }
        return job
    }

    private inner class Subscription(val callback: () -> Unit) : MamlDataHandle {
        var attached: MamlDataHandle? = null
        var removed = false
        fun attach() {
            if (!removed && attached == null && !paused && !closed) {
                attached = AppWidgetCatalogWatcher.subscribe(app) {
                    if (!removed && !paused && !closed) {
                        jobs.toList().forEach { it.close() }
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

    override fun onResume() {
        checkMain()
        if (closed) return
        refreshConfiguration()
        paused = false
        subscriptions.forEach { it.attach() }
    }

    override fun onPause() {
        checkMain()
        paused = true
        jobs.toList().forEach { it.close() }
        subscriptions.forEach { it.detach() }
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
        // The context is application-derived; never retain an Activity for resource refreshes.
        configuration.setTo(displayContext.resources.configuration)
        @Suppress("DEPRECATION")
        safeContext.resources.updateConfiguration(
            configuration,
            displayContext.resources.displayMetrics
        )
        if (systemPreviews) previews = AppWidgetSystemPreviewLoader(safeContext)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    companion object {
        const val TAG = "RearAppWidgetBinder"
        fun definition() = MamlDataSourceDefinition(
            TAG, schema, listOf(
                MamlDataParameter("package", MamlDataType.STRING, ""),
                MamlDataParameter("search", MamlDataType.STRING, ""),
                MamlDataParameter("offset", MamlDataType.NUMBER, 0, 0.0),
                MamlDataParameter("limit", MamlDataType.NUMBER, 12, 1.0, 32.0),
                MamlDataParameter("previewWidth", MamlDataType.NUMBER, 320, 1.0, 512.0),
                MamlDataParameter("previewHeight", MamlDataType.NUMBER, 200, 1.0, 512.0),
                MamlDataParameter("category", MamlDataType.NUMBER, 1, 0.0),
                MamlDataParameter("priority", MamlDataType.NUMBER, 0, 0.0, 2.0),
            ), MamlDataSourceFactory { AppWidgetCatalogDataSource(it) })

        val schema = MamlDataSchema(
            listOf(
                *listOf(
                    "appNames",
                    "widgetNames",
                    "packageNames",
                    "providers",
                    "previewKinds",
                    "previewErrors"
                )
                    .map { MamlDataField(it, MamlDataType.STRING, array = true) }.toTypedArray(),
                MamlDataField("previews", MamlDataType.BITMAP, array = true, nullable = true),
                *listOf("minWidths", "minHeights", "configurable")
                    .map { MamlDataField(it, MamlDataType.NUMBER, array = true) }.toTypedArray(),
                *listOf("total", "offset", "hasMore").map { MamlDataField(it, MamlDataType.NUMBER) }
                    .toTypedArray(),
            )
        )

        internal fun select(
            entries: List<AppWidgetCatalogEntry>,
            packageFilter: String,
            search: String,
            category: Int
        )
                : List<AppWidgetCatalogEntry> {
            val needle = search.trim().lowercase(Locale.ROOT)
            return entries.filter { entry ->
                (packageFilter.isEmpty() || entry.packageName == packageFilter) &&
                        (category == 0 || entry.info.widgetCategory and category != 0) &&
                        (needle.isEmpty() || listOf(entry.appName, entry.widgetName, entry.provider)
                            .any { it.lowercase(Locale.ROOT).contains(needle) })
            }.sortedWith(compareBy<AppWidgetCatalogEntry> { it.appName.lowercase(Locale.ROOT) }
                .thenBy { it.widgetName.lowercase(Locale.ROOT) }.thenBy { it.provider })
        }

        /** AppWidgetManager resolves minWidth/minHeight to pixels; Binder publishes density-independent sizes. */
        internal fun snapshot(
            page: List<AppWidgetCatalogEntry>,
            previews: List<AppWidgetPreview>,
            total: Int,
            offset: Int,
            density: Double = 1.0
        ) = MamlDataSnapshot(
            mapOf(
                "appNames" to page.map { it.appName }.toTypedArray(),
                "widgetNames" to page.map { it.widgetName }.toTypedArray(),
                "packageNames" to page.map { it.packageName }.toTypedArray(),
                "providers" to page.map { it.provider }.toTypedArray(),
                "previews" to previews.map { it.bitmap }.toTypedArray(),
                "previewKinds" to previews.map { it.kind }.toTypedArray(),
                "previewErrors" to previews.map { it.error }.toTypedArray(),
                "minWidths" to page.map { it.info.minWidth.toDouble() / density }.toDoubleArray(),
                "minHeights" to page.map { it.info.minHeight.toDouble() / density }.toDoubleArray(),
                "configurable" to page.map { if (it.info.configure != null) 1.0 else 0.0 }
                    .toDoubleArray(),
                "total" to total.toDouble(), "offset" to offset.toDouble(),
                "hasMore" to if (offset.toLong() + page.size < total) 1.0 else 0.0,
            ), page.size
        )
    }
}
