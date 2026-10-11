package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetHostView
import android.content.Intent
import android.util.Pair
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class WidgetLaunchPolicyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val displayKey = "android.activity.launchDisplayId"

    private fun options() = ActivityOptions.makeBasic().setLaunchDisplayId(1)
    private fun widgetChild(): View {
        val host = AppWidgetFitHostView(context, 1f)
        val nested = FrameLayout(context)
        val child = View(context)
        host.addView(nested)
        nested.addView(child)
        return child
    }

    private fun checkRemoteResponse(broadcast: Boolean) {
        val intent = Intent("widget.open").setPackage(context.packageName).putExtra("widget", 50)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = if (broadcast) PendingIntent.getBroadcast(context, 50, intent, flags)
        else PendingIntent.getActivity(context, 50, intent, flags)
        val responseClass = Class.forName("android.widget.RemoteViews\$RemoteResponse")
        val response = responseClass.getMethod("fromPendingIntent", PendingIntent::class.java)
            .invoke(null, pending)
        val child = widgetChild()
        val launch = responseClass.getMethod("getLaunchOptions", View::class.java)
            .invoke(response, child) as Pair<*, *>
        val fillIn = launch.first
        val originalOptions = launch.second as ActivityOptions
        originalOptions.setLaunchDisplayId(1)
        val before = originalOptions.toBundle()
        assertTrue(WidgetLaunchPolicy.routeToMainDisplay(child, originalOptions))
        assertSame(fillIn, launch.first)
        assertSame(originalOptions, launch.second)
        val after = originalOptions.toBundle()
        assertEquals(0, after.getInt(displayKey, -1))
        assertEquals(before.keySet(), after.keySet())
        for (key in before.keySet().filter { it != displayKey }) {
            assertEquals("Changed launch option: $key", before.get(key), after.get(key))
        }
    }

    @Test
    fun activityPendingIntentKeepsOtherLaunchOptions() = checkRemoteResponse(false)

    @Test
    fun broadcastPendingIntentAlsoRoutesToMainDisplay() = checkRemoteResponse(true)

    @Test
    fun ordinaryCardIsUntouched() {
        val card = FrameLayout(context)
        val button = View(context)
        card.addView(button)
        val options = options()
        assertFalse(WidgetLaunchPolicy.routeToMainDisplay(button, options))
        assertEquals(1, options.toBundle().getInt(displayKey))
    }

    @Test
    fun foreignWidgetIsUntouched() {
        val host = AppWidgetHostView(context)
        val button = View(context)
        host.addView(button)
        val options = options()
        assertFalse(WidgetLaunchPolicy.routeToMainDisplay(button, options))
        assertEquals(1, options.toBundle().getInt(displayKey))
    }

    @Test
    fun nearestForeignHostStopsAncestrySearch() {
        val ownHost = AppWidgetFitHostView(context, 1f)
        val foreignHost = AppWidgetHostView(context)
        val button = View(context)
        ownHost.addView(foreignHost)
        foreignHost.addView(button)
        val options = options()
        assertFalse(WidgetLaunchPolicy.routeToMainDisplay(button, options))
        assertEquals(1, options.toBundle().getInt(displayKey))
    }

    @Test
    fun detachedViewIsUntouched() {
        val options = options()
        assertFalse(WidgetLaunchPolicy.routeToMainDisplay(View(context), options))
        assertEquals(1, options.toBundle().getInt(displayKey))
    }

    @Test
    fun ownHostRoutesIdempotently() {
        val host = AppWidgetFitHostView(context, 1f)
        val options = options()
        assertTrue(WidgetLaunchPolicy.routeToMainDisplay(host, options))
        assertTrue(WidgetLaunchPolicy.routeToMainDisplay(host, options))
        assertEquals(0, options.toBundle().getInt(displayKey))
    }
}
