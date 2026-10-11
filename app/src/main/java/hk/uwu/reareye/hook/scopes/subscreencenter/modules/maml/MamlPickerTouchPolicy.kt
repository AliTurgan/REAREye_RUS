package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Activity pre-dispatch arbitration. A scoped, visible picker gets its real DOWN before native
 * GestureManager can initialize candidates. Only a handled child DOWN confirms stream ownership.
 * No MotionEvents, roots, Activities, managers or Views are retained beyond their required lifetime.
 */
internal class MamlPickerTouchPolicy(
    private val pickerHit: (View, Float, Float) -> Boolean,
) : AutoCloseable {
    private class Dispatch(root: View, val event: MotionEvent) {
        val root = WeakReference(root)
        val action = event.actionMasked
        var managerVisited = false
    }

    private class Stream(
        view: View, manager: Any?, root: View, val eventDownTime: Long,
        val device: Int, val source: Int
    ) {
        val view = WeakReference(view)

        // Null explicitly denotes a parent-only stream: a later manager call must not be bypassed.
        val manager = manager?.let { WeakReference(it) }
        val root = WeakReference(root)
        val token = WeakReference(view.windowToken)
        var confirmed = false
    }

    private val views = WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val dispatches = mutableListOf<Dispatch>()
    private var stream: Stream? = null
    private val main = Handler(Looper.getMainLooper())
    private val closing = AtomicBoolean()

    @Volatile
    private var closed = false

    fun remember(view: View) {
        checkMain()
        if (closed || views.containsKey(view)) return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                release(v)
            }
        }
        views[view] = listener
        view.addOnAttachStateChangeListener(listener)
    }

    fun beginDispatch(root: View, event: MotionEvent) {
        checkMain()
        if (closed) return
        val active = stream
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            (active != null && (active.root.get() !== root || !sameEventStream(active, event)))
        ) release()
        dispatches += Dispatch(root, event)
    }

    fun endDispatch(event: MotionEvent) {
        checkMain()
        val dispatch = dispatches.lastOrNull { it.event === event } ?: return
        val root = dispatch.root.get()
        if (root == null) {
            dispatches.remove(dispatch); release()
        } else endDispatch(root, event)
    }

    /** Always called from the Activity dispatch after-hook, including original exceptions. */
    fun endDispatch(root: View, event: MotionEvent) {
        checkMain()
        if (closed) return
        val index = dispatches.indexOfLast { it.root.get() === root && it.event === event }
        val action = if (index >= 0) dispatches.removeAt(index).action else event.actionMasked
        val active = stream ?: return
        if (active.root.get() === root && sameEventStream(active, event) &&
            (!active.confirmed || terminal(action))
        ) release()
    }

    /** True means bypass this one GestureManager invocation with false, not consume the event. */
    fun beforeManager(manager: Any, event: MotionEvent): Boolean {
        checkMain()
        if (closed) return false
        val dispatch = dispatches.lastOrNull()?.takeIf { it.event === event } ?: return false
        dispatch.managerVisited = true
        val root = dispatch.root.get() ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            release()
            // Ambiguous overlapping picker candidates fail open to native rather than stealing a View.
            val candidates = views.keys.toList().mapNotNull { view ->
                localPoint(view, root, event)?.takeIf { point ->
                    runCatching { pickerHit(view, point[0], point[1]) }.getOrDefault(false)
                }?.let { view }
            }
            val view = candidates.singleOrNull() ?: return false
            stream = Stream(view, manager, root, event.downTime, event.deviceId, event.source)
            return true
        }
        val active = stream ?: return false
        val view = active.view.get()
        if (!active.confirmed || active.manager?.get() !== manager || active.root.get() !== root ||
            !sameEventStream(active, event) || view == null || !available(view, root) ||
            active.token.get() !== view.windowToken
        ) {
            release()
            return false
        }
        return true
    }

    fun afterTouch(view: View, event: MotionEvent, handled: Boolean): Boolean {
        checkMain()
        if (closed) return false
        val dispatch = dispatches.lastOrNull()?.takeIf {
            it.event.downTime == event.downTime && it.event.deviceId == event.deviceId && it.event.source == event.source
        }
        if (stream == null && handled && event.actionMasked == MotionEvent.ACTION_DOWN &&
            dispatch != null && dispatch.action == MotionEvent.ACTION_DOWN && !dispatch.managerVisited && views.containsKey(
                view
            )
        ) {
            val root = dispatch.root.get()
            if (root != null && localPoint(view, root, dispatch.event) != null &&
                runCatching { pickerHit(view, event.x, event.y) }.getOrDefault(false)
            ) {
                // Z=false goes straight to Activity.super: preserve the parent-pager DOWN fix,
                // but never revive an ineligible/ambiguous DOWN already examined by manager.
                stream = Stream(view, null, root, event.downTime, event.deviceId, event.source)
            }
        }
        val active = stream ?: return false
        if (active.view.get() !== view || !sameEventStream(active, event)) return false
        val action = dispatch?.action ?: event.actionMasked
        if (action == MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_CANCEL) {
            if (!handled) {
                release(); return false
            }
            active.confirmed = true
        }
        if (!active.confirmed) return false
        if (terminal(action) || event.actionMasked == MotionEvent.ACTION_CANCEL) release()
        else view.parent?.requestDisallowInterceptTouchEvent(true)
        // Preserve a picker stream even when native MAML temporarily reports an unhandled MOVE.
        return true
    }

    fun release(view: View) {
        checkMain()
        if (stream?.view?.get() === view) release()
    }

    private fun release() {
        val old = stream
        stream = null
        if (old?.confirmed == true) old.view.get()?.parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        closed =
            true // Runtime teardown may originate off-main: reject new arbitration immediately.
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup()
        else main.post { cleanup() }
    }

    private fun cleanup() {
        release()
        views.toList()
            .forEach { (view, listener) -> view.removeOnAttachStateChangeListener(listener) }
        views.clear()
        dispatches.clear()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun sameEventStream(active: Stream, event: MotionEvent) =
        active.eventDownTime == event.downTime && active.device == event.deviceId && active.source == event.source

    private fun terminal(action: Int) =
        action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL

    private fun available(view: View, root: View) =
        view.isAttachedToWindow && view.isShown && view.isEnabled &&
                view.windowVisibility == View.VISIBLE &&
                view.width > 0 && view.height > 0 && view.rootView === root &&
                view.windowToken != null && view.windowToken === root.windowToken

    /** Convert screen raw coordinates to View-local coordinates through all ancestor matrices. */
    private fun localPoint(view: View, root: View, event: MotionEvent): FloatArray? {
        if (!available(view, root)) return null
        val global = Matrix()
        view.transformMatrixToGlobal(global)
        val rootGlobal = Matrix()
        root.transformMatrixToGlobal(rootGlobal)
        val rootOrigin = floatArrayOf(0f, 0f)
        rootGlobal.mapPoints(rootOrigin)
        val rootScreen = IntArray(2)
        root.getLocationOnScreen(rootScreen)
        val point = floatArrayOf(
            event.rawX - rootScreen[0] + rootOrigin[0],
            event.rawY - rootScreen[1] + rootOrigin[1]
        )
        if (!point.all { it.isFinite() }) return null
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible) || point[0] < visible.left || point[0] >= visible.right ||
            point[1] < visible.top || point[1] >= visible.bottom
        ) return null
        val inverse = Matrix()
        if (!global.invert(inverse)) return null
        inverse.mapPoints(point)
        return point.takeIf {
            it.all { value -> value.isFinite() } &&
                    it[0] >= 0 && it[0] < view.width && it[1] >= 0 && it[1] < view.height
        }
    }
}

/** Physical MamlView-local hit test; only our mounted RemoteViews canvas can claim input. */
internal object MamlAppWidgetHitTest {
    fun contains(root: View, x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || !available(root)) return false
        val global = Matrix()
        root.transformMatrixToGlobal(global)
        val point = floatArrayOf(x, y)
        global.mapPoints(point)
        fun hit(view: View): Boolean {
            if (!available(view) || view.windowToken !== root.windowToken) return false
            val clipped = Rect()
            if (!view.getGlobalVisibleRect(clipped) || !clipped.contains(
                    point[0].toInt(),
                    point[1].toInt()
                )
            ) return false
            val matrix = Matrix()
            view.transformMatrixToGlobal(matrix)
            val inverse = Matrix()
            if (!matrix.invert(inverse)) return false
            val local = point.copyOf()
            inverse.mapPoints(local)
            return local.all { it.isFinite() } && local[0] >= 0f && local[0] < view.width &&
                    local[1] >= 0f && local[1] < view.height
        }

        fun visit(view: View): Boolean {
            if (!hit(view)) return false
            if (view is AppWidgetFitHostView) {
                // The host viewport includes fit-center letterboxing. Protect the actual
                // scaled RemoteViews root only; blank margins keep native navigation.
                return (0 until view.childCount).any { hit(view.getChildAt(it)) }
            }
            return view is ViewGroup && (0 until view.childCount).any { visit(view.getChildAt(it)) }
        }
        return visit(root)
    }

    private fun available(view: View): Boolean =
        view.isAttachedToWindow && view.isShown && view.isEnabled &&
                view.windowVisibility == View.VISIBLE && view.alpha > 0f && view.width > 0 && view.height > 0 &&
                view.windowToken != null
}
