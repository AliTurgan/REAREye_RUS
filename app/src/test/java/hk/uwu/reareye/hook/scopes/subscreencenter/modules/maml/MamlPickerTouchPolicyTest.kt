package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.app.Activity
import android.graphics.Matrix
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** The host fixture intentionally calls its native manager BEFORE Activity.super, like the APK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MamlPickerTouchPolicyTest {
    class HostActivity : Activity() {
        internal lateinit var policy: MamlPickerTouchPolicy
        val manager = Any()
        val order = mutableListOf<String>()
        val nativeEvents = mutableListOf<Int>()
        val bypassed = mutableListOf<Int>()
        var matchMove = true
        var managerEnabled = true
        private var nativeCandidate = false
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            policy.beginDispatch(window.decorView, event)
            try {
                if (!managerEnabled) return super.dispatchTouchEvent(event) // Real host Z=false branch.
                order += "manager:${event.actionMasked}"
                val matched = if (policy.beforeManager(manager, event)) {
                    bypassed += event.actionMasked
                    false
                } else {
                    nativeEvents += event.actionMasked
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) nativeCandidate = true
                    val match =
                        nativeCandidate && matchMove && event.actionMasked == MotionEvent.ACTION_MOVE
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) nativeCandidate =
                        false
                    match
                }
                if (matched) {
                    val cancel = MotionEvent.obtain(event)
                    try {
                        cancel.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(cancel)
                    } finally {
                        cancel.recycle()
                    }
                    return true
                }
                return super.dispatchTouchEvent(event)
            } finally {
                policy.endDispatch(event)
            }
        }
    }

    private class Fixture : AutoCloseable {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        val activity = controller.get()
        var interceptMoves = false
        var interceptedMoves = 0
        val disallowRequests = mutableListOf<Boolean>()
        val parent = object : FrameLayout(activity) {
            override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
                disallowRequests += disallow
                super.requestDisallowInterceptTouchEvent(disallow)
            }

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                if (interceptMoves && event.actionMasked == MotionEvent.ACTION_MOVE) {
                    interceptedMoves++
                    return true
                }
                return false
            }

            override fun onTouchEvent(event: MotionEvent) = true
        }
        var selected = false
        var appCard = false
        var afterDispatch = false
        var widgetClicks = 0
        val buttonEvents = mutableListOf<Int>()
        lateinit var widget: AppWidgetFitHostView
        lateinit var button: Button
        var ready = true
        var enabled = true
        var handled = true
        var selectOnUp = false
        var readyOnChildDown = false
        var rewriteUpAsMove = false
        var extraCandidate: View? = null
        val childEvents = mutableListOf<Int>()
        val remoteEvents = mutableListOf<Int>()
        val policy: MamlPickerTouchPolicy = MamlPickerTouchPolicy { view, x, y ->
            (view === child || view === extraCandidate) && enabled && ready &&
                    ((!selected && x / 2 >= 50 && x / 2 < 250 && y / 2 >= 0 && y / 2 < 200) ||
                            (appCard && MamlAppWidgetHitTest.contains(view, x, y)))
        }
        val child = object : FrameLayout(activity) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val result = super.dispatchTouchEvent(event)
                // Mirrors the real MamlView dispatch AFTER hook, not a second child dispatch.
                return if (appCard && afterDispatch) policy.afterTouch(
                    this,
                    event,
                    result
                ) || result else result
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                childEvents += event.actionMasked
                activity.order += "child:${event.actionMasked}"
                if (readyOnChildDown && event.actionMasked == MotionEvent.ACTION_DOWN) ready = true
                if (selectOnUp && event.actionMasked == MotionEvent.ACTION_UP) selected = true
                if (rewriteUpAsMove && event.actionMasked == MotionEvent.ACTION_UP) event.action =
                    MotionEvent.ACTION_MOVE
                return policy.afterTouch(this, event, handled) || handled
            }
        }

        init {
            activity.policy = policy
            parent.addView(
                child,
                FrameLayout.LayoutParams(600, 500).apply { leftMargin = 100; topMargin = 100 })
            activity.setContentView(parent)
            val decor = activity.window.decorView
            // ActivityController attaches the window, but no real WindowManager server publishes
            // app visibility. Deliver its actual IWindow callback before hit-testing this window.
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(
                decor.windowToken, "dispatchAppVisibility",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                    Boolean::class.javaPrimitiveType!!,
                    true
                )
            )
            shadowOf(Looper.getMainLooper()).idle()
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
            )
            decor.layout(0, 0, 1000, 800)
            policy.remember(child)
            assertTrue(
                "Picker fixture must use a genuinely attached window",
                child.isAttachedToWindow
            )
            assertSame(decor, child.rootView)
            assertNotNull(child.windowToken)
            assertSame(decor.windowToken, child.windowToken)
            assertEquals(
                "WindowManager must publish app visibility before input",
                View.VISIBLE,
                child.windowVisibility
            )
            val visible = android.graphics.Rect()
            assertTrue(
                "Picker must have actual clipped window bounds",
                child.getGlobalVisibleRect(visible)
            )
            assertFalse(visible.isEmpty)
        }

        fun installWidget() {
            selected = true
            widget = AppWidgetFitHostView(activity, activity.resources.displayMetrics.density)
            widget.setPadding(0, 0, 0, 0)
            button = object : Button(activity) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    buttonEvents += event.actionMasked
                    return super.onTouchEvent(event)
                }
            }.apply { setOnClickListener { widgetClicks++ } }
            // A 400x200 natural canvas fitted into 200x200 leaves 50px top/bottom letterbox.
            widget.addView(button, FrameLayout.LayoutParams(400, 200))
            child.addView(widget, FrameLayout.LayoutParams(200, 200).apply { leftMargin = 100 })
            widget.measure(
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)
            )
            widget.layout(100, 0, 300, 200)
            assertEquals(0.5f, button.scaleX, 0f)
            assertEquals(50f, button.translationY, 0f)
            assertTrue(widget.isAttachedToWindow)
        }

        fun event(action: Int, x: Float = 200f, y: Float = 100f, down: Long = 10L): MotionEvent {
            val decor = activity.window.decorView
            val global = Matrix()
            child.transformMatrixToGlobal(global)
            val point = floatArrayOf(x, y)
            global.mapPoints(point)
            val origin = floatArrayOf(0f, 0f)
            val rootMatrix = Matrix()
            decor.transformMatrixToGlobal(rootMatrix)
            rootMatrix.mapPoints(origin)
            val screen = IntArray(2)
            decor.getLocationOnScreen(screen)
            val rawX = point[0] + screen[0] - origin[0]
            val rawY = point[1] + screen[1] - origin[1]
            val event =
                if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                    val properties = Array(2) { index ->
                        MotionEvent.PointerProperties().apply {
                            id = index
                            toolType = MotionEvent.TOOL_TYPE_FINGER
                        }
                    }
                    val coordinates = Array(2) { index ->
                        MotionEvent.PointerCoords().apply {
                            this.x = rawX + index
                            this.y = rawY + index
                            pressure = 1f
                            size = 1f
                        }
                    }
                    MotionEvent.obtain(
                        down,
                        down + action + 1,
                        action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        2,
                        properties,
                        coordinates,
                        0,
                        0,
                        1f,
                        1f,
                        0,
                        0,
                        InputDevice.SOURCE_TOUCHSCREEN,
                        0
                    )
                } else MotionEvent.obtain(down, down + action + 1, action, rawX, rawY, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
            return event.apply { offsetLocation(-screen[0].toFloat(), -screen[1].toFloat()) }
        }

        fun send(action: Int, x: Float = 200f, down: Long = 10L, y: Float = 100f) {
            val event = event(action, x, y, down)
            try {
                activity.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }

        override fun close() {
            policy.close(); controller.pause().stop().destroy()
        }
    }

    @Test
    fun appWidgetButtonWithoutDispatchAfterLosesDownClaimAndParentCancelsMove() {
        Fixture().use { f ->
            f.installWidget()
            f.appCard = true
            f.activity.matchMove =
                false // Isolate the parent pager, not native manager cancellation.
            f.interceptMoves = true
            f.send(MotionEvent.ACTION_DOWN)
            assertTrue("Button consumes DOWN without MAML.onTouchEvent", f.childEvents.isEmpty())
            assertTrue(f.disallowRequests.isEmpty())
            f.send(MotionEvent.ACTION_MOVE, x = 201f)
            f.send(MotionEvent.ACTION_UP, x = 201f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), f.buttonEvents)
            assertEquals(1, f.interceptedMoves)
            assertEquals(0, f.widgetClicks)
            assertEquals(listOf(MotionEvent.ACTION_DOWN), f.activity.bypassed)
            assertEquals(
                listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
                f.activity.nativeEvents
            )
        }
    }

    @Test
    fun appWidgetDispatchAfterConfirmsConsumedChildDownAndClicksExactlyOnce() {
        Fixture().use { f ->
            f.installWidget()
            f.appCard = true
            f.afterDispatch = true
            f.interceptMoves = true
            f.send(MotionEvent.ACTION_DOWN)
            assertTrue("No MAML.onTouchEvent confirmation is available", f.childEvents.isEmpty())
            assertEquals(listOf(true), f.disallowRequests)
            f.send(MotionEvent.ACTION_MOVE, x = 201f)
            f.send(MotionEvent.ACTION_UP, x = 201f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                listOf(0, 2, 1),
                f.buttonEvents
            ) // Original delivery only; no duplicate forwarding.
            assertTrue(f.childEvents.isEmpty())
            assertEquals(1, f.widgetClicks)
            assertEquals(0, f.interceptedMoves)
            assertEquals(listOf(0, 2, 1), f.activity.bypassed)
            assertTrue(f.activity.nativeEvents.isEmpty())
            assertEquals(listOf(true, true, false), f.disallowRequests)
        }
    }

    @Test
    fun appWidgetDispatchAfterProtectsParentOnlyStreamWhenNativeManagerIsDisabled() {
        Fixture().use { f ->
            f.installWidget()
            f.appCard = true
            f.afterDispatch = true
            f.activity.managerEnabled = false
            f.interceptMoves = true
            f.send(MotionEvent.ACTION_DOWN)
            f.send(MotionEvent.ACTION_MOVE, x = 201f)
            f.send(MotionEvent.ACTION_UP, x = 201f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(0, 2, 1), f.buttonEvents)
            assertTrue(f.childEvents.isEmpty())
            assertEquals(1, f.widgetClicks)
            assertEquals(0, f.interceptedMoves)
            assertTrue(f.activity.bypassed.isEmpty())
            assertTrue(f.activity.nativeEvents.isEmpty())
        }
    }

    @Test
    fun ordinarySelectedCardKeepsNativeButtonClickWithoutAppWidgetArbitration() {
        Fixture().use { f ->
            f.installWidget()
            f.afterDispatch =
                true // Hook is app-card scoped even when the confirmation switch is set.
            f.activity.matchMove = false
            f.send(MotionEvent.ACTION_DOWN)
            f.send(MotionEvent.ACTION_MOVE, x = 201f)
            f.send(MotionEvent.ACTION_UP, x = 201f)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(f.appCard)
            assertEquals(listOf(0, 2, 1), f.buttonEvents)
            assertEquals(1, f.widgetClicks)
            assertTrue(f.childEvents.isEmpty())
            assertEquals(listOf(0, 2, 1), f.activity.nativeEvents)
            assertTrue(f.activity.bypassed.isEmpty())
            assertTrue(f.disallowRequests.isEmpty())
        }
    }

    @Test
    fun appWidgetOutsideCanvasAndLetterboxKeepNativeGestureManager() {
        for ((x, y) in listOf(50f to 100f, 200f to 25f, 200f to 175f)) {
            Fixture().use { f ->
                f.installWidget()
                f.appCard = true
                f.afterDispatch = true
                assertFalse(MamlAppWidgetHitTest.contains(f.child, x, y))
                f.send(MotionEvent.ACTION_DOWN, x = x, y = y)
                f.send(MotionEvent.ACTION_MOVE, x = x, y = y)
                f.send(MotionEvent.ACTION_UP, x = x, y = y)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(listOf(0, 2, 1), f.activity.nativeEvents)
                assertTrue(f.activity.bypassed.isEmpty())
                assertTrue(f.buttonEvents.isEmpty())
                assertEquals(0, f.widgetClicks)
                assertTrue(f.disallowRequests.isEmpty())
                assertEquals(listOf(0, 3), f.childEvents)
            }
        }
    }

    @Test
    fun appWidgetHitUsesScaledOffsetCanvasAndTransformedMamlCoordinates() {
        Fixture().use { f ->
            f.installWidget()
            f.appCard = true
            f.afterDispatch = true
            f.interceptMoves = true
            f.widget.translationX = 20f
            f.widget.translationY = 30f
            f.child.pivotX = 0f
            f.child.pivotY = 0f
            f.child.scaleX = 0.5f
            f.child.scaleY = 0.5f
            f.child.translationX = 15f
            f.child.translationY = 10f
            assertTrue(MamlAppWidgetHitTest.contains(f.child, 220f, 130f))
            assertFalse(MamlAppWidgetHitTest.contains(f.child, 110f, 130f))
            assertFalse(MamlAppWidgetHitTest.contains(f.child, 220f, 55f))
            assertFalse(MamlAppWidgetHitTest.contains(f.child, 220f, 205f))
            f.send(MotionEvent.ACTION_DOWN, x = 220f, y = 130f)
            f.send(MotionEvent.ACTION_MOVE, x = 221f, y = 130f)
            f.send(MotionEvent.ACTION_UP, x = 221f, y = 130f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(0, 2, 1), f.buttonEvents)
            assertEquals(listOf(0, 2, 1), f.activity.bypassed)
            assertEquals(1, f.widgetClicks)
            assertEquals(0, f.interceptedMoves)
            assertTrue(f.childEvents.isEmpty())
        }
    }

    @Test
    fun hiddenOrRemovedAppWidgetAndCanvasCannotProtectNewGesture() {
        val hideOrRemove: List<(Fixture) -> Unit> = listOf(
            { it.widget.visibility = View.INVISIBLE },
            { it.widget.visibility = View.GONE },
            { it.button.visibility = View.INVISIBLE },
            { it.button.visibility = View.GONE },
            { it.child.removeView(it.widget) },
            { it.widget.removeView(it.button) },
        )
        for (change in hideOrRemove) {
            Fixture().use { f ->
                f.installWidget()
                f.appCard = true
                f.afterDispatch = true
                assertTrue(MamlAppWidgetHitTest.contains(f.child, 200f, 100f))
                change(f)
                assertFalse(MamlAppWidgetHitTest.contains(f.child, 200f, 100f))
                f.send(MotionEvent.ACTION_DOWN)
                f.send(MotionEvent.ACTION_MOVE)
                f.send(MotionEvent.ACTION_UP)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(listOf(0, 2, 1), f.activity.nativeEvents)
                assertTrue(f.activity.bypassed.isEmpty())
                assertTrue(f.buttonEvents.isEmpty())
                assertEquals(0, f.widgetClicks)
                assertTrue(f.disallowRequests.isEmpty())
            }
        }
    }

    @Test
    fun managerDisabledStreamClaimsHandledSafeDownBeforeParentFirstMoveOnly() {
        Fixture().use { f ->
            f.activity.managerEnabled = false
            f.interceptMoves = true
            f.send(MotionEvent.ACTION_DOWN)
            f.send(MotionEvent.ACTION_MOVE)
            f.send(MotionEvent.ACTION_UP)
            assertEquals(listOf(0, 2, 1), f.childEvents)
            assertEquals(0, f.interceptedMoves)
            assertTrue(f.activity.nativeEvents.isEmpty())
            assertTrue(f.activity.bypassed.isEmpty())
            f.send(MotionEvent.ACTION_DOWN, x = 50f, down = 20)
            f.send(MotionEvent.ACTION_MOVE, x = 50f, down = 20)
            assertEquals(1, f.interceptedMoves)
            assertEquals(3, f.childEvents.last())
        }
    }

    @Test
    fun managerDisabledUnhandledDownOrMissingDispatchContextCannotClaimParent() {
        Fixture().use { f ->
            f.activity.managerEnabled = false
            f.handled = false
            f.send(MotionEvent.ACTION_DOWN)
            assertTrue(f.disallowRequests.isEmpty())
            val event = f.event(MotionEvent.ACTION_DOWN, down = 20)
            try {
                assertFalse(f.policy.afterTouch(f.child, event, true))
            } finally {
                event.recycle()
            }
            assertTrue(f.disallowRequests.isEmpty())
        }
    }

    @Test
    fun visitedIneligibleOrAmbiguousDownCannotBecomeParentOnlyFallback() {
        Fixture().use { f ->
            f.activity.matchMove = false
            f.interceptMoves = true
            f.ready = false
            f.readyOnChildDown =
                true // Eligibility changes only AFTER manager already rejected DOWN.
            f.send(MotionEvent.ACTION_DOWN)
            f.send(MotionEvent.ACTION_MOVE)
            assertTrue(f.activity.bypassed.isEmpty())
            assertEquals(listOf(0, 2), f.activity.nativeEvents)
            assertEquals(1, f.interceptedMoves)
            assertEquals(listOf(0, 3), f.childEvents)
        }
        Fixture().use { f ->
            f.activity.matchMove = false
            f.interceptMoves = true
            val extra = View(f.activity)
            f.extraCandidate = extra
            f.parent.addView(
                extra,
                FrameLayout.LayoutParams(600, 500).apply { leftMargin = 100; topMargin = 100 })
            extra.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY)
            )
            extra.layout(100, 100, 700, 600)
            f.policy.remember(extra)
            f.send(MotionEvent.ACTION_DOWN)
            f.send(MotionEvent.ACTION_MOVE)
            assertTrue(f.activity.bypassed.isEmpty())
            assertEquals(listOf(0, 2), f.activity.nativeEvents)
            assertEquals(1, f.interceptedMoves)
            assertEquals(listOf(0, 3), f.childEvents)
        }
    }

    @Test
    fun managerUnexpectedlyReturningDuringParentOnlyStreamIsNotBypassed() {
        Fixture().use { f ->
            f.activity.managerEnabled = false
            f.send(MotionEvent.ACTION_DOWN)
            f.activity.managerEnabled = true
            f.send(MotionEvent.ACTION_MOVE)
            assertEquals(listOf(2), f.activity.nativeEvents)
            assertTrue(f.activity.bypassed.isEmpty())
            f.send(MotionEvent.ACTION_UP)
            assertEquals(listOf(0, 2, 1), f.childEvents)
        }
    }

    @Test
    fun originalDispatchUpReleasesEvenIfNativeChildRewritesItsAction() {
        Fixture().use { f ->
            f.activity.managerEnabled = false
            f.rewriteUpAsMove = true
            f.interceptMoves = true
            f.send(MotionEvent.ACTION_DOWN)
            f.send(MotionEvent.ACTION_UP)
            assertEquals(listOf(0, 1), f.childEvents)
            assertEquals(listOf(true, false), f.disallowRequests)
            f.send(MotionEvent.ACTION_DOWN, x = 50f, down = 20)
            f.send(MotionEvent.ACTION_MOVE, x = 50f, down = 20)
            assertEquals(1, f.interceptedMoves)
        }
    }

    @Test
    fun preDispatchBypassPreservesPickerDownMoveUpWithoutNativeCancel() {
        Fixture().use { f ->
            assertTrue(f.child.isAttachedToWindow)
            f.send(MotionEvent.ACTION_DOWN)
            f.send(MotionEvent.ACTION_MOVE)
            f.send(MotionEvent.ACTION_UP)
            assertEquals(listOf(0, 2, 1), f.childEvents)
            assertEquals(listOf(0, 2, 1), f.activity.bypassed)
            assertTrue(f.activity.nativeEvents.isEmpty())
            assertEquals(
                listOf(
                    "manager:0",
                    "child:0",
                    "manager:2",
                    "child:2",
                    "manager:1",
                    "child:1"
                ), f.activity.order
            )
            f.send(MotionEvent.ACTION_DOWN, x = 50f, down = 20)
            f.send(MotionEvent.ACTION_MOVE, x = 50f, down = 20)
            assertEquals(listOf(0, 2), f.activity.nativeEvents)
            assertEquals(MotionEvent.ACTION_CANCEL, f.childEvents.last())
        }
    }

    @Test
    fun unsafePartOfSameMamlViewAndUnregisteredViewUseNativeManager() {
        Fixture().use { f ->
            f.send(MotionEvent.ACTION_DOWN, x = 50f)
            f.send(MotionEvent.ACTION_MOVE, x = 50f)
            assertTrue(f.activity.bypassed.isEmpty())
            assertEquals(listOf(0, 3), f.childEvents)
            f.policy.close()
            f.send(MotionEvent.ACTION_DOWN, down = 20)
            f.send(MotionEvent.ACTION_MOVE, down = 20)
            assertEquals(listOf(0, 2, 0, 2), f.activity.nativeEvents)
        }
    }

    @Test
    fun selectedRemoteViewsReceiveTheirRealDownUpWithoutPickerOwnership() {
        Fixture().use { f ->
            f.selected = true
            val remote = android.widget.RemoteViews("android", android.R.layout.simple_list_item_1)
                .apply(f.activity, f.child)
            remote.setOnTouchListener { _, event -> f.remoteEvents += event.actionMasked; true }
            f.child.addView(remote, FrameLayout.LayoutParams(600, 500))
            remote.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY)
            )
            remote.layout(0, 0, 600, 500)
            f.send(MotionEvent.ACTION_DOWN)
            f.send(MotionEvent.ACTION_UP)
            assertEquals(listOf(0, 1), f.remoteEvents)
            f.activity.managerEnabled = false
            f.send(MotionEvent.ACTION_DOWN, down = 20)
            f.send(MotionEvent.ACTION_UP, down = 20)
            assertEquals(listOf(0, 1, 0, 1), f.remoteEvents)
            assertEquals(listOf(0, 1), f.activity.nativeEvents)
            assertTrue(f.childEvents.isEmpty())
            assertTrue(f.activity.bypassed.isEmpty())
        }
    }

    @Test
    fun selectionOnUpDoesNotDropThatUpAndNextStreamUsesNative() {
        Fixture().use { f ->
            f.selectOnUp = true
            f.send(MotionEvent.ACTION_DOWN)
            f.send(MotionEvent.ACTION_UP)
            assertEquals(listOf(0, 1), f.childEvents)
            assertTrue(f.selected)
            f.send(MotionEvent.ACTION_DOWN, down = 20)
            f.send(MotionEvent.ACTION_MOVE, down = 20)
            assertEquals(listOf(0, 2), f.activity.nativeEvents)
            assertEquals(3, f.childEvents.last())
        }
    }

    @Test
    fun failedChildDownAndNoChildDeliveryReleasePendingClaim() {
        Fixture().use { f ->
            f.handled = false
            f.send(MotionEvent.ACTION_DOWN)
            f.send(MotionEvent.ACTION_MOVE)
            assertEquals(listOf(0), f.activity.bypassed)
            assertEquals(listOf(2), f.activity.nativeEvents)
            val down = f.event(MotionEvent.ACTION_DOWN, down = 30)
            val move = f.event(MotionEvent.ACTION_MOVE, down = 30)
            try {
                f.policy.beginDispatch(f.activity.window.decorView, down)
                assertTrue(f.policy.beforeManager(f.activity.manager, down))
                f.policy.endDispatch(down) // Child never received DOWN.
                f.policy.beginDispatch(f.activity.window.decorView, move)
                assertFalse(f.policy.beforeManager(f.activity.manager, move))
                f.policy.endDispatch(move)
            } finally {
                down.recycle(); move.recycle()
            }
        }
    }

    @Test
    fun viewMatrixAndRootScaleHitActualRegionRatherThanWholeView() {
        Fixture().use { f ->
            f.child.pivotX = 0f
            f.child.pivotY = 0f
            f.child.scaleX = 0.5f
            f.child.scaleY = 0.5f
            f.send(MotionEvent.ACTION_DOWN, x = 150f)
            f.send(MotionEvent.ACTION_UP, x = 150f)
            assertEquals(listOf(0, 1), f.activity.bypassed)
            f.send(MotionEvent.ACTION_DOWN, x = 50f, down = 20)
            f.send(MotionEvent.ACTION_UP, x = 50f, down = 20)
            assertEquals(listOf(0, 1), f.activity.nativeEvents)
        }
    }

    @Test
    fun pointerActionsStayOwnedAndCancelNewDownDetachCloseRelease() {
        Fixture().use { f ->
            f.send(MotionEvent.ACTION_DOWN)
            f.send(MotionEvent.ACTION_POINTER_DOWN)
            f.send(MotionEvent.ACTION_POINTER_UP)
            f.send(MotionEvent.ACTION_CANCEL)
            assertEquals(listOf(0, 5, 6, 3), f.childEvents)
            assertEquals(listOf(0, 5, 6, 3), f.activity.bypassed)
            f.send(MotionEvent.ACTION_DOWN, down = 20)
            f.send(
                MotionEvent.ACTION_DOWN,
                x = 50f,
                down = 30
            ) // Abandoned old stream cannot lock a new one.
            assertEquals(0, f.activity.nativeEvents.last())
            f.send(MotionEvent.ACTION_DOWN, down = 40)
            f.parent.removeView(f.child)
            val move = f.event(MotionEvent.ACTION_MOVE, down = 40)
            try {
                f.policy.beginDispatch(f.activity.window.decorView, move)
                assertFalse(f.policy.beforeManager(f.activity.manager, move))
                f.policy.endDispatch(move)
            } finally {
                move.recycle()
            }
            f.policy.close(); f.policy.close()
        }
    }

    @Test
    fun backgroundCloseRejectsFurtherArbitrationAndCleansUpOnMain() {
        Fixture().use { f ->
            f.send(MotionEvent.ACTION_DOWN)
            Thread { f.policy.close(); f.policy.close() }.apply { start(); join() }
            shadowOf(Looper.getMainLooper()).idle()
            f.send(MotionEvent.ACTION_DOWN, down = 20)
            f.send(MotionEvent.ACTION_UP, down = 20)
            assertEquals(listOf(0), f.activity.bypassed)
            assertEquals(listOf(0, 1), f.activity.nativeEvents)
        }
    }

    @Test
    fun otherWindowOtherManagerAndUnreadyOrDisabledRootsAreNotClaimed() {
        Fixture().use { first ->
            Fixture().use { second ->
                val event = second.event(MotionEvent.ACTION_DOWN)
                try {
                    first.policy.beginDispatch(second.activity.window.decorView, event)
                    assertFalse(first.policy.beforeManager(second.activity.manager, event))
                    first.policy.endDispatch(event)
                } finally {
                    event.recycle()
                }
                first.ready = false
                first.send(MotionEvent.ACTION_DOWN)
                first.send(MotionEvent.ACTION_UP)
                first.ready = true; first.enabled = false
                first.send(MotionEvent.ACTION_DOWN, down = 20)
                first.send(MotionEvent.ACTION_UP, down = 20)
                assertTrue(first.activity.bypassed.isEmpty())
                first.enabled = true
                first.send(MotionEvent.ACTION_DOWN, down = 30)
                val move = first.event(MotionEvent.ACTION_MOVE, down = 30)
                try {
                    first.policy.beginDispatch(first.activity.window.decorView, move)
                    assertFalse(first.policy.beforeManager(Any(), move))
                    first.policy.endDispatch(move)
                } finally {
                    move.recycle()
                }
            }
        }
    }
}
