package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Main-thread hub; separate subscriptions share exactly one set of platform registrations. */
internal object AppWidgetCatalogWatcher {
    private val hubs = mutableMapOf<Context, Hub>()
    private val main = Handler(Looper.getMainLooper())

    fun subscribe(context: Context, callback: () -> Unit): MamlDataHandle {
        check(Looper.myLooper() == Looper.getMainLooper())
        val app = context.applicationContext ?: context
        val hub = hubs.getOrPut(app) { Hub(app) }
        val key = Any()
        hub.listeners[key] = callback
        var removed = false
        return MamlDataHandle {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!removed) {
                removed = true
                hub.listeners.remove(key)
                if (hub.listeners.isEmpty()) {
                    hub.close()
                    hubs.remove(app)
                }
            }
        }
    }

    private class Hub(private val context: Context) : ComponentCallbacks {
        val listeners = linkedMapOf<Any, () -> Unit>()
        private var pending = false
        private var closed = false
        private val delivery = Runnable {
            pending = false
            if (!closed) listeners.toList().forEach { (key, callback) ->
                if (listeners[key] === callback) {
                    try {
                        callback()
                    } catch (_: Exception) { /* One source must not break another. */
                    }
                }
            }
        }
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                invalidate()
            }
        }

        init {
            val packages = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(
                receiver,
                packages,
                Context.RECEIVER_NOT_EXPORTED
            )
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, packages)
            try {
                context.registerComponentCallbacks(this)
            } catch (e: Exception) {
                context.unregisterReceiver(receiver); throw e
            }
        }

        private fun invalidate() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post { invalidate() }; return
            }
            if (!closed && !pending) {
                AppWidgetCatalogCache.invalidate()
                pending = true
                main.post(delivery)
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            invalidate()
        }

        override fun onLowMemory() {}
        fun close() {
            closed = true
            main.removeCallbacks(delivery)
            context.unregisterReceiver(receiver)
            context.unregisterComponentCallbacks(this)
            listeners.clear()
            // No observer remains while paused/closed: never reuse entries across an unobserved gap.
            AppWidgetCatalogCache.invalidate()
        }
    }
}

/** Small synchronized critical sections only. Expensive discovery/rendering must happen outside these locks. */
internal class AppWidgetCatalogLru<K, V>(
    private val maxEntries: Int,
    private val maxWeight: Long,
    private val ttlMillis: Long,
    private val weightOf: (V) -> Long,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private data class Record<V>(
        val value: V,
        val weight: Long,
        val expires: Long,
        val generation: Long
    )

    private val entries = LinkedHashMap<K, Record<V>>(16, .75f, true)
    private var weight = 0L

    @Synchronized
    fun get(key: K, generation: Long): V? {
        val found = entries[key] ?: return null
        if (found.generation != generation || clock() >= found.expires) {
            entries.remove(key)
            weight -= found.weight
            return null
        }
        return found.value
    }

    @Synchronized
    fun put(key: K, value: V, generation: Long) {
        entries.remove(key)?.let { weight -= it.weight }
        val size = weightOf(value).coerceAtLeast(1)
        if (size > maxWeight) return
        entries[key] = Record(value, size, clock() + ttlMillis, generation)
        weight += size
        while (entries.size > maxEntries || weight > maxWeight) {
            val iterator = entries.entries.iterator()
            val removed = iterator.next().value
            iterator.remove()
            weight -= removed.weight
        }
    }

    @Synchronized
    fun clear() {
        entries.clear(); weight = 0
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun retainedWeight(): Long = weight
}

/** Shared by apps/catalog Binders; retained contexts are application-derived, never an Activity or root. */
internal object AppWidgetCatalogCache {
    data class Environment(val app: Context, val displayId: Int, val configuration: Configuration)
    data class DirectoryKey(val environment: Environment, val namespace: Any? = null)
    data class PreviewKey(
        val environment: Environment,
        val provider: String,
        val width: Int,
        val height: Int,
        val category: Int,
        val minWidth: Int,
        val minHeight: Int,
        val image: Int,
        val layout: Int,
        val icon: Int,
        val namespace: Any? = null,
        val profile: android.os.UserHandle? = null
    )

    private val epoch = java.util.concurrent.atomic.AtomicLong()
    val generation: Long get() = epoch.get()
    private val discoveryLock =
        Any() // Worker-only single-flight discovery across different Binders.
    private val catalogs = AppWidgetCatalogLru<DirectoryKey, List<AppWidgetCatalogEntry>>(
        4, 4096, 15_000,
        { it.size.toLong().coerceAtLeast(1) })
    private val previews = AppWidgetCatalogLru<PreviewKey, AppWidgetPreview>(
        128, 16L * 1024 * 1024, 30_000,
        { it.bitmap?.allocationByteCount?.toLong() ?: 1L })

    fun environment(context: Context) =
        Environment(
            context.applicationContext ?: error("Application context required"),
            runCatching { context.display?.displayId ?: -1 }.getOrDefault(-1),
            Configuration(context.resources.configuration)
        )

    /** Call only from a worker: a concurrent discovery may serialize here, never a main-thread query. */
    fun catalog(context: Context): List<AppWidgetCatalogEntry> = loadCatalog(context) {
        val pm = context.packageManager
        android.appwidget.AppWidgetManager.getInstance(context).installedProviders.mapNotNull { info ->
            try {
                @Suppress("DEPRECATION")
                val application = pm.getApplicationInfo(info.provider.packageName, 0)
                AppWidgetCatalogEntry(
                    info,
                    application.loadLabel(pm).toString(),
                    info.loadLabel(pm).toString()
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun loadCatalog(
        context: Context,
        namespace: Any? = null,
        enumerate: () -> List<AppWidgetCatalogEntry>
    )
            : List<AppWidgetCatalogEntry> = synchronized(discoveryLock) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val key = DirectoryKey(environment(context), namespace)
        val current = generation
        catalogs.get(key, current)?.let { return@synchronized it }
        val loaded = enumerate().toList()
        if (generation == current) catalogs.put(key, loaded, current)
        loaded
    }

    fun previewKey(
        context: Context, info: android.appwidget.AppWidgetProviderInfo, width: Int, height: Int,
        category: Int, namespace: Any? = null
    ) = PreviewKey(
        environment(context), info.provider.flattenToString(),
        width, height, category, info.minWidth, info.minHeight, info.previewImage,
        if (Build.VERSION.SDK_INT >= 31) info.previewLayout else 0, info.icon, namespace,
        runCatching { info.profile }.getOrNull()
    )

    fun preview(key: PreviewKey): AppWidgetPreview? = previews.get(key, generation)
    fun rememberPreview(key: PreviewKey, value: AppWidgetPreview, generation: Long) {
        if (generation == this.generation) previews.put(key, value, generation)
    }

    fun invalidate() {
        epoch.incrementAndGet()
        catalogs.clear()
        previews.clear() // Release references only: published bitmaps must never be recycled.
        AppWidgetSharedPreviews.invalidate()
    }
}
