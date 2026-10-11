package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

/** A continuation is prepared only after this stage fails, never during main-thread rendering. */
internal class AppWidgetPreviewStage<V>(
    val render: () -> V,
    val fallback: ((V) -> AppWidgetPreviewStage<V>?)? = null,
    val needsFallback: (V) -> Boolean = { true }
)

/** Serial, owner-thread arbiter. Only metadata is queued; one bounded plan/output is in flight.
 * Dispatchers must enqueue, not invoke inline. Android's frame dispatcher uses Choreographer.
 */
internal class AppWidgetPreviewScheduler<K, V>(
    private val dispatchOwner: (() -> Unit) -> Unit,
    private val dispatchFrame: (() -> Unit) -> Unit,
    private val currentGeneration: () -> Long,
    private val store: (K, V, Long) -> Unit,
    private val maxQueued: Int = 256,
    private val maxInFlightBytes: Long = 8L * 1024 * 1024,
) {
    private inner class Flight(
        val key: K, val generation: Long, val bytes: Long,
        val dispatchWorker: (() -> Unit) -> Unit, val prepare: () -> AppWidgetPreviewStage<V>,
    ) {
        val subscribers = linkedMapOf<Any, Pair<Int, (Result<V>) -> Unit>>()
        val priority get() = subscribers.values.minOfOrNull { it.first } ?: 2

        @Volatile
        var invalid = false

        // 0: queued worker, 1: executing worker, 2: invalidated before start, 3: awaiting frame.
        val phase = java.util.concurrent.atomic.AtomicInteger(0)
    }

    private val flights = linkedMapOf<K, Flight>()
    private var active: Flight? = null
    private var scheduled = false
    val queuedCount get() = flights.values.count { it !== active }
    val inFlightBytes get() = active?.bytes ?: 0L

    fun subscribe(
        key: K, generation: Long, priority: Int, bytes: Long,
        dispatchWorker: (() -> Unit) -> Unit, prepare: () -> AppWidgetPreviewStage<V>,
        callback: (Result<V>) -> Unit,
    ): MamlDataHandle {
        if (generation != currentGeneration()) return MamlDataHandle.NONE
        var flight = flights[key]
        if (flight != null && flight.generation != generation) {
            invalidate()
            flight = null
        }
        if (flight == null) {
            if (bytes !in 1..maxInFlightBytes) {
                callback(Result.failure(IllegalStateException("Preview exceeds in-flight budget")))
                return MamlDataHandle.NONE
            }
            if (queuedCount >= maxQueued) {
                val victim = flights.values.filter { it !== active }.maxByOrNull { it.priority }
                if (victim == null || victim.priority <= priority) {
                    callback(Result.failure(IllegalStateException("Preview queue is full")))
                    return MamlDataHandle.NONE
                }
                finish(
                    victim,
                    Result.failure(IllegalStateException("Preview displaced by visible work"))
                )
            }
            flight = Flight(key, generation, bytes, dispatchWorker, prepare)
            flights[key] = flight
        }
        val subscribed = flight
        val token = Any()
        subscribed.subscribers[token] = priority.coerceIn(0, 2) to callback
        schedule()
        var removed = false
        return MamlDataHandle {
            if (!removed) {
                removed = true
                subscribed.subscribers.remove(token)
                if (subscribed.subscribers.isEmpty()) {
                    if (subscribed !== active || subscribed.phase.compareAndSet(0, 2)) {
                        if (flights[key] === subscribed) flights.remove(key)
                        if (active === subscribed) active = null
                        subscribed.invalid = true
                        schedule()
                    }
                    // Executing work is not interrupted; its one current-generation result may cache.
                }
            }
        }
    }

    fun invalidate() {
        val stale = flights.values.filter { it.generation != currentGeneration() }
        stale.forEach { it.invalid = true; it.subscribers.clear(); flights.remove(it.key) }
        // A dispatched worker/frame retains its reservation until its completion, never overlaps.
        active?.takeIf { it.generation != currentGeneration() }?.let {
            it.invalid = true
            it.subscribers.clear()
            if (it.phase.compareAndSet(0, 2)) active = null
        }
        schedule()
    }

    private fun valid(flight: Flight) = !flight.invalid && flight.generation == currentGeneration()
    private fun schedule() {
        if (scheduled || active != null || flights.isEmpty()) return
        scheduled = true
        dispatchOwner {
            scheduled = false
            if (active == null) startNext()
        }
    }

    private fun startNext() {
        val flight = flights.values.filter { valid(it) && it.subscribers.isNotEmpty() }
            .minByOrNull { it.priority } ?: return
        active = flight
        prepareStage(flight, flight.prepare)
    }

    private fun prepareStage(flight: Flight, prepare: () -> AppWidgetPreviewStage<V>) {
        try {
            flight.phase.set(0)
            flight.dispatchWorker worker@{
                if (!flight.phase.compareAndSet(0, 1)) return@worker
                val prepared = if (valid(flight)) runCatching { prepare() }
                else Result.failure(IllegalStateException("Stale preview"))
                dispatchOwner {
                    if (!valid(flight) || prepared.isFailure) {
                        finish(
                            flight,
                            Result.failure(
                                prepared.exceptionOrNull() ?: IllegalStateException("Stale preview")
                            )
                        )
                    } else {
                        val stage = prepared.getOrThrow()
                        flight.phase.set(3)
                        dispatchFrame {
                            val rendered = if (valid(flight)) runCatching { stage.render() }
                            else Result.failure(IllegalStateException("Stale preview"))
                            if (valid(flight) && rendered.isSuccess && stage.fallback != null && stage.needsFallback(
                                    rendered.getOrThrow()
                                )
                            ) {
                                prepareStage(flight) {
                                    stage.fallback.invoke(rendered.getOrThrow())
                                        ?: AppWidgetPreviewStage({ rendered.getOrThrow() })
                                }
                            } else finish(flight, rendered)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            finish(flight, Result.failure(e))
        }
    }

    private fun finish(flight: Flight, result: Result<V>) {
        if (flights[flight.key] === flight) flights.remove(flight.key)
        if (active === flight) active = null
        if (valid(flight)) {
            result.getOrNull()?.let { store(flight.key, it, flight.generation) }
            val listeners = flight.subscribers.toList()
            listeners.forEach { (token, listener) ->
                if (flight.subscribers.containsKey(token)) {
                    try {
                        listener.second(result)
                    } catch (_: Exception) { /* Isolate subscribers. */
                    }
                }
            }
        }
        flight.subscribers.clear()
        schedule()
    }
}

/** Process-wide across Binders; application/provider configuration and injected-loader identity are keys. */
internal object AppWidgetSharedPreviews {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val worker = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0L,
        java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(1),
        java.util.concurrent.ThreadFactory { work ->
            Thread(
                work,
                "RearWidgetPreview"
            ).apply { isDaemon = true }
        })
    private val scheduler =
        AppWidgetPreviewScheduler<AppWidgetCatalogCache.PreviewKey, AppWidgetPreview>(
            { action -> main.post { action() } },
            { action -> android.view.Choreographer.getInstance().postFrameCallback { action() } },
            { AppWidgetCatalogCache.generation },
            { key, preview, generation ->
                AppWidgetCatalogCache.rememberPreview(
                    key,
                    preview,
                    generation
                )
            },
        )

    fun subscribe(
        key: AppWidgetCatalogCache.PreviewKey, generation: Long, priority: Int,
        executor: java.util.concurrent.ExecutorService?, prepare: () -> AppWidgetPreviewPlan,
        callback: (AppWidgetPreview) -> Unit,
    ): MamlDataHandle {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        if (generation != AppWidgetCatalogCache.generation) return MamlDataHandle.NONE
        AppWidgetCatalogCache.preview(key)?.let { callback(it); return MamlDataHandle.NONE }
        return scheduler.subscribe(
            key, generation, priority, key.width.toLong() * key.height * 4,
            { action -> (executor ?: worker).execute { action() } },
            { stage(key, prepare()) },
            { outcome ->
                val preview =
                    outcome.getOrElse { AppWidgetPreview(null, "none", it.javaClass.simpleName) }
                callback(preview)
            })
    }

    private fun stage(
        key: AppWidgetCatalogCache.PreviewKey,
        plan: AppWidgetPreviewPlan
    ): AppWidgetPreviewStage<AppWidgetPreview> {
        val continuation: ((AppWidgetPreview) -> AppWidgetPreviewStage<AppWidgetPreview>?)? =
            if (plan is AppWidgetFallbackPreviewPlan) {
                { _ -> plan.prepareFallback()?.let { stage(key, it) } }
            } else null
        return AppWidgetPreviewStage({
            val preview = plan.render()
            val bitmap = preview.bitmap
            if (bitmap == null || (bitmap.width <= key.width && bitmap.height <= key.height &&
                        bitmap.allocationByteCount.toLong() <= key.width.toLong() * key.height * 4)
            ) preview
            else AppWidgetPreview(null, "none", "Preview exceeds page pixel budget")
        }, continuation, { it.bitmap == null })
    }

    fun invalidate() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) scheduler.invalidate()
        else main.post { scheduler.invalidate() }
    }
}
