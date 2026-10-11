package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppWidgetFitHostViewTest {
    private fun host(
        width: Int,
        height: Int,
        supportsRtl: Boolean = false
    ): Pair<Activity, AppWidgetFitHostView> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        if (supportsRtl) {
            // The test package's ApplicationInfo is not guaranteed to mirror the merged manifest.
            // View gates resolution on this bit, even for an explicit RTL setter. Set before ctor.
            activity.applicationInfo.flags =
                activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            activity.applicationInfo.targetSdkVersion =
                maxOf(17, activity.applicationInfo.targetSdkVersion)
        }
        val view = AppWidgetFitHostView(activity, activity.resources.displayMetrics.density)
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(activity.packageName, "FakeWidget")
            AppWidgetProviderInfo::class.java.getDeclaredField("providerInfo")
                .apply { isAccessible = true }
                .set(this, ActivityInfo().apply {
                    applicationInfo = activity.applicationInfo
                    packageName = activity.packageName
                    name = "FakeWidget"
                })
            minWidth = width
            minHeight = height
            // Deliberately smaller resize minima must not shrink the default provider canvas.
            minResizeWidth = 20
            minResizeHeight = 20
        }
        view.setAppWidget(1, info) // Shadow's public getter metadata
        Shadow.directlyOn<Any, AppWidgetHostView>(
            view, AppWidgetHostView::class.java, "setAppWidget",
            ClassParameter.from(Int::class.javaPrimitiveType!!, 1),
            ClassParameter.from(AppWidgetProviderInfo::class.java, info)
        ) // real framework state/padding
        view.removeAllViews()
        view.setPadding(0, 0, 0, 0)
        return activity to view
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
    }

    @Test
    fun providerCanvasIsMeasuredNaturallyAndWideContentFitsEntireViewport() {
        val (activity, host) = host(600, 200)
        val child = View(activity)
        host.addView(child, FrameLayout.LayoutParams(-1, -1))
        layout(host, 200, 200)
        assertEquals(200, host.measuredWidth)
        assertEquals(200, host.measuredHeight)
        assertEquals(600, child.measuredWidth)
        assertEquals(200, child.measuredHeight)
        assertEquals(1f / 3f, child.scaleX, 0.0001f)
        assertEquals(child.scaleX, child.scaleY, 0f)
        assertEquals(0f, child.left + child.translationX, 0.0001f)
        assertEquals(200f / 3f, child.top + child.translationY, 0.0001f)
        assertTrue(child.top + child.translationY + child.height * child.scaleY <= host.height)
        activity.finish()
    }

    @Test
    fun tallContentFitsWithoutCroppingAndViewportResizeDoesNotRemeasureItSmall() {
        val (activity, host) = host(200, 600)
        val child = View(activity)
        host.addView(child, FrameLayout.LayoutParams(-1, -1))
        layout(host, 200, 200)
        assertEquals(1f / 3f, child.scaleY, 0.0001f)
        assertEquals(200f / 3f, child.translationX, 0.0001f)
        layout(host, 400, 200)
        assertEquals(200, child.measuredWidth)
        assertEquals(600, child.measuredHeight)
        assertEquals(1f / 3f, child.scaleX, 0.0001f)
        assertEquals(500f / 3f, child.translationX, 0.0001f)
        activity.finish()
    }

    @Test
    fun fixedRootAndNestedFixedRowsExpandNaturalCanvasRatherThanClip() {
        val (activity, host) = host(100, 100)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(View(activity), LinearLayout.LayoutParams(300, 250))
        row.addView(View(activity), LinearLayout.LayoutParams(300, 250))
        host.addView(row, FrameLayout.LayoutParams(-1, -1))
        layout(host, 200, 200)
        assertEquals(WidgetNaturalSize(600, 250), host.naturalSize())
        assertEquals(600, row.measuredWidth)
        assertEquals(250, row.measuredHeight)
        assertEquals(row.scaleX, row.scaleY, 0f)
        assertTrue(row.width * row.scaleX <= host.width + 0.001f)
        assertTrue(row.height * row.scaleY <= host.height + 0.001f)
        activity.finish()
    }

    @Test
    fun replacementContentGetsFreshFitAndNaturalSizeNotificationsAreCoalesced() {
        val (activity, host) = host(200, 100)
        activity.setContentView(host, ViewGroup.LayoutParams(200, 200))
        val sizes = mutableListOf<WidgetNaturalSize>()
        host.onNaturalSizeChanged = { sizes.add(it) }
        host.addView(View(activity), FrameLayout.LayoutParams(600, 300))
        layout(host, 200, 200)
        shadowOf(activity.mainLooper).idle()
        assertEquals(listOf(WidgetNaturalSize(600, 300)), sizes)
        repeat(3) { layout(host, 200, 200) }
        layout(host, 300, 100)
        shadowOf(activity.mainLooper).idle()
        assertEquals(1, sizes.size)
        host.removeAllViews()
        val replacement = View(activity)
        host.addView(replacement, FrameLayout.LayoutParams(300, 600))
        layout(host, 200, 200)
        shadowOf(activity.mainLooper).idle()
        assertEquals(WidgetNaturalSize(300, 600), sizes.last())
        assertEquals(2, sizes.size)
        assertEquals(1f / 3f, replacement.scaleX, 0.0001f)
        assertEquals(replacement.scaleX, replacement.scaleY, 0f)
        host.onNaturalSizeChanged = null
        activity.finish()
    }

    @Test
    fun naturalCenterGravityRespectsPaddingMarginsAndRtlInsteadOfViewportBounds() {
        val (activity, host) = host(600, 300, supportsRtl = true)
        assertTrue(host.context.applicationInfo.flags and ApplicationInfo.FLAG_SUPPORTS_RTL != 0)
        assertTrue(host.context.applicationInfo.targetSdkVersion >= 17)
        assertTrue(requireNotNull(host.appWidgetInfo).activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SUPPORTS_RTL != 0)
        host.setPadding(24, 24, 24, 24)
        val child = View(activity)
        val params = FrameLayout.LayoutParams(100, 100, Gravity.CENTER).apply {
            leftMargin = 7
            rightMargin = 3
            topMargin = 5
            bottomMargin = 1
        }
        host.addView(child, params)
        layout(host, 200, 200)
        assertEquals(278, child.left)
        assertEquals(128, child.top)
        assertEquals(648, host.naturalSize().widthPx)
        assertEquals(348, host.naturalSize().heightPx)
        params.gravity = Gravity.END or Gravity.BOTTOM
        host.layoutDirection = View.LAYOUT_DIRECTION_RTL
        child.layoutParams = params
        host.requestLayout() // force another resolution/measure/layout pass; do not assume the setter.
        layout(host, 200, 200)
        assertEquals(View.LAYOUT_DIRECTION_RTL, host.layoutDirection)
        assertEquals(Gravity.LEFT, Gravity.getAbsoluteGravity(Gravity.END, host.layoutDirection))
        assertEquals(31, child.left)
        assertEquals(223, child.top)
        assertEquals(200, host.measuredWidth)
        assertEquals(200, host.measuredHeight)
        activity.finish()
    }

    @Test
    fun scaledPaddingDoesNotClipContentPixelsAtUnscaledViewportInsets() {
        val (activity, host) = host(600, 300)
        host.setPadding(24, 24, 24, 24)
        val child = View(activity).apply { setBackgroundColor(Color.RED) }
        host.addView(child, FrameLayout.LayoutParams(-1, -1))
        layout(host, 200, 200)
        assertFalse(host.clipToPadding)
        assertTrue(host.clipChildren)
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        host.draw(Canvas(bitmap))
        val drawn = RectF(0f, 0f, child.width.toFloat(), child.height.toFloat())
        child.matrix.mapRect(drawn)
        drawn.offset(child.left.toFloat(), child.top.toFloat())
        val firstX = kotlin.math.ceil(drawn.left).toInt() + 1
        val lastX = kotlin.math.floor(drawn.right).toInt() - 2
        val y = ((drawn.top + drawn.bottom) / 2).toInt()
        assertTrue(firstX < host.paddingLeft) // Regression region formerly clipped away.
        assertTrue(lastX >= host.width - host.paddingRight)
        assertEquals(Color.RED, bitmap.getPixel(firstX, y))
        assertEquals(Color.RED, bitmap.getPixel(lastX, y))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        bitmap.recycle()
        activity.finish()
    }

    @Test
    fun remoteViewsReapplyUpdatesNaturalCanvasAndKeepsUniformFit() {
        val (activity, host) = host(200, 100)
        fun content(width: Float, height: Float) =
            RemoteViews("android", android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "Widget content")
                setViewLayoutWidth(android.R.id.text1, width, TypedValue.COMPLEX_UNIT_PX)
                setViewLayoutHeight(android.R.id.text1, height, TypedValue.COMPLEX_UNIT_PX)
            }
        // ShadowAppWidgetHostView inflates with null parent before reapply: root LP actions are
        // ignored there. Call the real implementation, which applies RemoteViews with the host parent.
        Shadow.directlyOn<Any, AppWidgetHostView>(
            host, AppWidgetHostView::class.java, "updateAppWidget",
            ClassParameter.from(RemoteViews::class.java, content(600f, 300f))
        )
        layout(host, 200, 200)
        assertEquals(WidgetNaturalSize(600, 300), host.naturalSize())
        assertEquals(1f / 3f, host.getChildAt(0).scaleX, 0.0001f)
        Shadow.directlyOn<Any, AppWidgetHostView>(
            host, AppWidgetHostView::class.java, "updateAppWidget",
            ClassParameter.from(RemoteViews::class.java, content(300f, 600f))
        )
        layout(host, 200, 200)
        assertEquals(WidgetNaturalSize(300, 600), host.naturalSize())
        val child = host.getChildAt(0)
        assertEquals(child.scaleX, child.scaleY, 0f)
        assertEquals(1f / 3f, child.scaleY, 0.0001f)
        activity.finish()
    }

    @Test
    fun scaledChildReceivesInverseMappedTouchAndLetterboxDoesNotClick() {
        val (activity, host) = host(600, 300)
        var clicks = 0
        var local: Pair<Float, Float>? = null
        val child = object : View(activity) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    local = event.x to event.y
                    performClick()
                }
                return true
            }
        }
        child.setOnClickListener { clicks++ }
        activity.setContentView(host, ViewGroup.LayoutParams(200, 200))
        host.addView(child, FrameLayout.LayoutParams(-1, -1))
        layout(host, 200, 200)
        val drawn = RectF(0f, 0f, child.width.toFloat(), child.height.toFloat())
        child.matrix.mapRect(drawn)
        drawn.offset(child.left.toFloat(), child.top.toFloat())
        assertEquals(0f, drawn.left, 0.001f)
        assertEquals(50f, drawn.top, 0.001f)
        assertEquals(200f, drawn.right, 0.001f)
        assertEquals(150f, drawn.bottom, 0.001f)
        fun touch(x: Float, y: Float) {
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(0, 1, action, x, y, 0)
                try {
                    host.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
        touch(150f, 125f) // natural (450, 225), translated by a 50px top letterbox
        assertEquals(1, clicks)
        assertEquals(450f, local!!.first, 0.001f)
        assertEquals(225f, local!!.second, 0.001f)
        touch(100f, 10f)
        assertEquals(1, clicks)
        activity.finish()
    }
}
