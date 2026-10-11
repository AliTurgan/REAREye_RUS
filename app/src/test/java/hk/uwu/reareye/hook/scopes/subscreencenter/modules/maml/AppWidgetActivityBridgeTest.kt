package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppWidgetActivityBridgeTest {
    @Test
    fun frameworkConfigurationLaunchUsesSameRegisteredResultOwnership() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppWidgetActivityBridge.onResumed(activity)
        var requestCode = -1
        var result: WidgetResult? = null
        val cancel = AppWidgetActivityBridge.launch({ result = it }) { owner, code ->
            assertSame(activity, owner)
            requestCode =
                code // injected stand-in for AppWidgetHost's mediated configuration launch
        }
        assertNotNull(cancel)
        AppWidgetActivityBridge.onPaused(activity)
        assertTrue(
            AppWidgetActivityBridge.onActivityResult(
                activity,
                requestCode,
                Activity.RESULT_OK,
                null
            )
        )
        assertEquals(WidgetResult(true, null), result)
        assertFalse(
            AppWidgetActivityBridge.onActivityResult(
                activity,
                requestCode,
                Activity.RESULT_OK,
                null
            )
        )
        AppWidgetActivityBridge.onDestroyed(activity)
    }

    @Test
    fun onlyRegisteredMatchingActivityCanConsumeResult() {
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        val other = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppWidgetActivityBridge.onResumed(first)
        var result: WidgetResult? = null
        val cancel =
            AppWidgetActivityBridge.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)) {
                result = it
            }
        assertNotNull(cancel)
        val request = shadowOf(first).nextStartedActivityForResult
        assertFalse(
            AppWidgetActivityBridge.onActivityResult(
                first,
                request.requestCode + 1,
                Activity.RESULT_OK,
                null
            )
        )
        assertFalse(
            AppWidgetActivityBridge.onActivityResult(
                other,
                request.requestCode,
                Activity.RESULT_OK,
                null
            )
        )
        assertNull(result)
        assertTrue(
            AppWidgetActivityBridge.onActivityResult(
                first, request.requestCode, Activity.RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 7)
            )
        )
        assertEquals(WidgetResult(true, 7), result)
        assertFalse(
            AppWidgetActivityBridge.onActivityResult(
                first,
                request.requestCode,
                Activity.RESULT_OK,
                null
            )
        )
        AppWidgetActivityBridge.onDestroyed(first)
        AppWidgetActivityBridge.onDestroyed(other)
    }

    @Test
    fun cancelRetiresCodeAndPauseDoesNotLosePendingResult() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppWidgetActivityBridge.onResumed(activity)
        var oldCalls = 0
        val cancel = AppWidgetActivityBridge.launch(Intent("bind")) { oldCalls++ }!!
        val old = shadowOf(activity).nextStartedActivityForResult
        cancel()
        var fresh: WidgetResult? = null
        AppWidgetActivityBridge.launch(Intent("bind")) { fresh = it }
        val current = shadowOf(activity).nextStartedActivityForResult
        assertNotEquals(old.requestCode, current.requestCode)
        AppWidgetActivityBridge.onPaused(activity)
        assertNull(AppWidgetActivityBridge.launch(Intent("bind")) {})
        assertFalse(
            AppWidgetActivityBridge.onActivityResult(
                activity,
                old.requestCode,
                Activity.RESULT_OK,
                null
            )
        )
        assertTrue(
            AppWidgetActivityBridge.onActivityResult(
                activity,
                current.requestCode,
                Activity.RESULT_CANCELED,
                null
            )
        )
        assertEquals(0, oldCalls)
        assertEquals(WidgetResult(false, null), fresh)
        AppWidgetActivityBridge.onDestroyed(activity)
    }

    @Test
    fun destroyingOneActivityCancelsOnlyItsRequests() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val b = Robolectric.buildActivity(Activity::class.java).setup().get()
        var aResult: WidgetResult? = null
        var bResult: WidgetResult? = null
        AppWidgetActivityBridge.onResumed(a)
        AppWidgetActivityBridge.launch(Intent("bind")) { aResult = it }
        AppWidgetActivityBridge.onResumed(b)
        AppWidgetActivityBridge.launch(Intent("bind")) { bResult = it }
        val bRequest = shadowOf(b).nextStartedActivityForResult
        AppWidgetActivityBridge.onDestroyed(a)
        assertEquals(WidgetResult(false, null), aResult)
        assertNull(bResult)
        assertTrue(
            AppWidgetActivityBridge.onActivityResult(
                b,
                bRequest.requestCode,
                Activity.RESULT_OK,
                null
            )
        )
        assertEquals(WidgetResult(true, null), bResult)
        AppWidgetActivityBridge.onDestroyed(b)
    }
}
