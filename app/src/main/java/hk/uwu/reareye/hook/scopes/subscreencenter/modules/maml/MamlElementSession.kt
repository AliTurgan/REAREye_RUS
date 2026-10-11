package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import hk.uwu.reareye.hook.support.YLog

/** Invalidates queued work immediately on finish; supports host finish -> init reuse. */
internal class MamlSessionEpoch {
    var generation = 0
        private set
    var finished = false
        private set

    fun begin(): Int {
        if (finished) {
            finished = false
            generation++
        }
        return generation
    }

    fun accepts(token: Int): Boolean = !finished && token == generation
    fun finish(): Boolean {
        if (finished) return false
        finished = true
        generation++
        return true
    }
}

internal data class MamlViewGeometry(
    val x: Float, val y: Float, val width: Int, val height: Int,
    val pivotX: Float, val pivotY: Float,
    val rotation: Float, val rotationX: Float, val rotationY: Float,
    val alpha: Float, val scaleX: Float, val scaleY: Float,
    val position: Boolean, val size: Boolean, val translation: Boolean,
    val layerX: Float = x, val layerY: Float = y,
)

/** Owns no strong reference to a ScreenElement or root. All Android mutations are main-only. */
internal class MamlElementSession(
    private val context: Context,
    private val factory: () -> MamlViewBackend,
    private val main: Handler,
    private val applyOutline: () -> Unit,
    private val evaluateInputs: (() -> MamlBackendInputs)? = null,
) {
    private val epoch = MamlSessionEpoch()
    private var backend: MamlViewBackend? = null

    @Volatile
    var view: FrameLayout? = null
        private set

    @Volatile
    private var initialized = false
    private var visible = evaluateInputs == null
    private var resumed = false
    private var pendingGeometry: Pair<MamlViewGeometry, ViewGroup.LayoutParams>? = null
    private var geometryPosted = false
    private var sampledGeometry: MamlViewGeometry? = null
    private var appliedGeometry: MamlViewGeometry? = null
    private var outlinePosted = false
    private var pendingInputs: MamlBackendInputs? = null
    private var lastInputs: MamlBackendInputs? = null
    private var inputsPosted = false

    // Epoch guards reinitialization; this serial also guards out-of-order sampling
    // and native visibility changes within a still-live carrier.
    private var inputSampleVersion = 0L
    private val operations = ArrayDeque<() -> Unit>()
    private var draining = false
    private var drainPosted = false
    private val drainRunnable = Runnable { drain() }

    @Synchronized
    fun init(initializeCarrier: () -> Unit) {
        val token = epoch.begin()
        dispatch {
            if (!accepts(token)) return@dispatch
            if (initialized) return@dispatch
            prepareView()
            view?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            try {
                initializeCarrier()
            } catch (error: Throwable) {
                safely { backend?.onFinish() }
                view?.let { container ->
                    (container.parent as? ViewGroup)?.removeView(container)
                    container.removeAllViews()
                }
                backend = null
                view = null
                throw error
            }
            if (!initialized) {
                initialized = true
                safely { backend?.onInit() }
                safely { backend?.onVisibilityChanged(visible) }
                safely { backend?.onAttachmentChanged(view?.isAttachedToWindow == true) }
                if (resumed) safely { backend?.onResume() }
            }
        }
    }

    @Synchronized
    fun resume() {
        resumed = true
        enqueue { backend?.onResume() }
    }

    @Synchronized
    fun pause() {
        resumed = false
        enqueue { backend?.onPause() }
    }

    @Synchronized
    fun visibility(value: Boolean) {
        if (epoch.finished) return
        ++inputSampleVersion
        // A pending hidden snapshot predates this show event. A fresh native sample
        // follows the transition; do not let the queued snapshot hide it again.
        if (value) pendingInputs = null
        if (visible == value && (value || pendingInputs?.visible != true)) return
        visible = value
        if (!value) {
            // A later native hide must suppress an earlier queued visible snapshot, even
            // when hidden elements stop ticking and never publish another input snapshot.
            pendingInputs = pendingInputs?.copy(visible = false)
            lastInputs = lastInputs?.copy(visible = false)
        }
        enqueue {
            view?.visibility = if (value) View.VISIBLE else View.INVISIBLE
            backend?.onVisibilityChanged(value)
        }
    }

    @Synchronized
    fun geometry(value: MamlViewGeometry, layout: ViewGroup.LayoutParams): Boolean {
        if (epoch.finished || sampledGeometry == value) return false
        sampledGeometry = value
        pendingGeometry = value to layout
        if (geometryPosted) return true
        geometryPosted = true
        val token = epoch.generation
        dispatch {
            val latest = synchronized(this) {
                // Validate and take the slot atomically: an old generation must not clear
                // a geometry update published by finish -> init on a different thread.
                if (!epoch.accepts(token)) return@synchronized null
                geometryPosted = false
                pendingGeometry.also { pendingGeometry = null }
            }
            if (accepts(token) && latest != null) applyGeometry(latest.first, latest.second)
        }
        return true
    }

    /** Outline metadata was evaluated on native tick; only changed providers reach Android main. */
    @Synchronized
    fun outlineChanged() {
        if (epoch.finished || outlinePosted) return
        outlinePosted = true
        val token = epoch.generation
        dispatch {
            synchronized(this) {
                if (!epoch.accepts(token)) return@dispatch
                outlinePosted = false
            }
            safely(applyOutline)
        }
    }

    /** Called only after native doTick; evaluate first, then publish immutable values to main. */
    fun tickInputs() {
        val evaluator = evaluateInputs ?: return
        val (token, sampleVersion) = synchronized(this) {
            if (epoch.finished) return
            epoch.generation to ++inputSampleVersion
        }
        val snapshot = runCatching(evaluator).getOrElse {
            YLog.warn("MAML input evaluation failed", it)
            // Never use a previous confirmed selection/visibility after a failed evaluation.
            MamlBackendInputs("", "", false)
        }
        synchronized(this) {
            if (!epoch.accepts(token) || sampleVersion != inputSampleVersion) return
            if (snapshot == (pendingInputs ?: lastInputs)) return
            pendingInputs = snapshot
            if (inputsPosted) return
            inputsPosted = true
            dispatch {
                val latest = synchronized(this) {
                    if (!epoch.accepts(token)) return@synchronized null
                    inputsPosted = false
                    pendingInputs.also { pendingInputs = null }
                } ?: return@dispatch
                if (!accepts(token) || lastInputs == latest) return@dispatch
                lastInputs = latest
                visible = latest.visible
                view?.visibility = if (latest.visible) View.VISIBLE else View.INVISIBLE
                safely { backend?.onInputsChanged(latest) }
            }
        }
    }

    /** Invalidate before posting removal so a late tick cannot resurrect a finished backend. */
    @Synchronized
    fun finish(finishCarrier: () -> Unit = {}) {
        if (!epoch.finish()) return
        geometryPosted = false
        resumed = false
        pendingGeometry = null
        sampledGeometry = null
        appliedGeometry = null
        outlinePosted = false
        pendingInputs = null
        lastInputs = null
        inputsPosted = false
        visible = evaluateInputs == null
        dispatch {
            // Host removal must see the replacement View until finishView has completed.
            safely(finishCarrier)
            safely { backend?.onFinish() }
            view?.let { container ->
                (container.parent as? ViewGroup)?.removeView(container)
                container.removeAllViews()
            }
            backend = null
            view = null
            initialized = false
        }
    }

    private fun prepareView() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val container = object : FrameLayout(context) {
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                if (view === this) safely { backend?.onAttachmentChanged(true) }
            }

            override fun onDetachedFromWindow() {
                if (view === this) safely { backend?.onAttachmentChanged(false) }
                super.onDetachedFromWindow()
            }

            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (view === this) safely { backend?.onSizeChanged(w, h) }
            }
        }
        view = container
        try {
            val created = factory()
            backend = created
            container.addView(created.createView(context), FrameLayout.LayoutParams(-1, -1))
            lastInputs?.let { safely { created.onInputsChanged(it) } }
        } catch (error: Throwable) {
            safely { backend?.onFinish() }
            backend = null
            container.removeAllViews()
            container.addView(TextView(context).apply {
                text = "MAML 扩展元素无法创建：${error.message}"
            }, FrameLayout.LayoutParams(-1, -1))
            YLog.warn("MAML backend creation failed", error)
        }
    }

    private fun applyGeometry(g: MamlViewGeometry, params: ViewGroup.LayoutParams) {
        val container = view ?: return
        val previous = appliedGeometry
        if (previous == g) return
        if (g.position) {
            if (container.x != g.x) container.x = g.x
            if (container.y != g.y) container.y = g.y
        }
        if (g.translation) {
            // First assignment marks an explicit pivot even if its numeric value is initially 0.
            if (previous == null || previous.pivotX != g.pivotX) container.pivotX = g.pivotX
            if (previous == null || previous.pivotY != g.pivotY) container.pivotY = g.pivotY
            if (container.rotation != g.rotation) container.rotation = g.rotation
            if (container.rotationX != g.rotationX) container.rotationX = g.rotationX
            if (container.rotationY != g.rotationY) container.rotationY = g.rotationY
            val alpha = g.alpha.coerceIn(0f, 1f)
            if (container.alpha != alpha) container.alpha = alpha
            if (container.scaleX != g.scaleX) container.scaleX = g.scaleX
            if (container.scaleY != g.scaleY) container.scaleY = g.scaleY
        }
        if (g.size) {
            var changed = false
            if (g.width > 0 && params.width != g.width) {
                params.width = g.width; changed = true
            }
            if (g.height > 0 && params.height != g.height) {
                params.height = g.height; changed = true
            }
            if (changed) container.layoutParams = params
        }
        appliedGeometry = g
    }

    @Synchronized
    fun canTick(): Boolean = initialized && !epoch.finished

    @Synchronized
    private fun accepts(token: Int) = epoch.accepts(token)

    private fun enqueue(block: () -> Unit) {
        if (epoch.finished) return
        val token = epoch.generation
        dispatch { if (accepts(token)) safely(block) }
    }

    private fun dispatch(block: () -> Unit) {
        val inline = Looper.myLooper() == Looper.getMainLooper()
        synchronized(this) {
            operations.addLast(block)
            if (inline) {
                if (drainPosted) {
                    main.removeCallbacks(drainRunnable)
                    drainPosted = false
                }
            } else if (!draining && !drainPosted) {
                drainPosted = true
                main.post(drainRunnable)
            }
        }
        if (inline) drain()
    }

    /** Main-thread calls also drain earlier off-main work before running their own operation. */
    private fun drain() {
        synchronized(this) {
            if (draining) return
            // Consume the scheduled-post flag and acquire drain ownership together: a
            // native producer must not enqueue a second Runnable between those transitions.
            drainPosted = false
            draining = true
        }
        while (true) {
            val next = synchronized(this) {
                operations.removeFirstOrNull().also {
                    // Empty-check and transition are atomic with dispatch(): no lost work or
                    // unnecessary post while this drain is already consuming the queue.
                    if (it == null) draining = false
                }
            } ?: break
            safely(next)
        }
    }

    private fun safely(block: () -> Unit) {
        runCatching(block).onFailure { YLog.warn("MAML backend callback failed", it) }
    }
}
