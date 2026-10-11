package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Looper
import java.lang.ref.WeakReference

/** Feed these entry points from the launcher's Activity hooks, always on the main thread. */
object AppWidgetActivityBridge {
    private data class Pending(
        val activity: WeakReference<Activity>,
        val result: (WidgetResult) -> Unit
    )

    private var launcher = WeakReference<Activity>(null)
    private val requests = mutableMapOf<Int, Pending>()

    // Never reuse codes within this process: late results cannot target a new session.
    private var nextCode = 0x6000

    @JvmStatic
    fun onResumed(activity: Activity) {
        checkMain()
        launcher = WeakReference(activity)
    }

    @JvmStatic
    fun onPaused(activity: Activity) {
        checkMain()
        if (launcher.get() === activity) launcher.clear()
    }

    @JvmStatic
    fun onDestroyed(activity: Activity) {
        checkMain()
        if (launcher.get() === activity) launcher.clear()
        val abandoned =
            requests.filterValues { it.activity.get() === activity || it.activity.get() == null }
        abandoned.keys.forEach(requests::remove)
        abandoned.values.forEach { it.result(WidgetResult(false, null)) }
    }

    @JvmStatic
    fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ): Boolean {
        checkMain()
        val pending = requests[requestCode] ?: return false
        if (pending.activity.get() !== activity) return false
        requests.remove(requestCode)
        val id = runCatching {
            if (data?.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID) == true)
                data.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
            else null
        }.getOrNull()
        pending.result(WidgetResult(resultCode == Activity.RESULT_OK, id))
        return true
    }

    internal fun launch(intent: Intent, result: (WidgetResult) -> Unit): (() -> Unit)? =
        launch(result) { activity, code -> activity.startActivityForResult(intent, code) }

    /** Shares request ownership for framework-mediated configuration and normal Intent launches. */
    internal fun launch(
        result: (WidgetResult) -> Unit,
        start: (Activity, Int) -> Unit
    ): (() -> Unit)? {
        checkMain()
        val activity = launcher.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed || nextCode > 0x7fff) return null
        val requestCode = nextCode++
        requests[requestCode] = Pending(WeakReference(activity), result)
        try {
            start(activity, requestCode)
        } catch (exception: Exception) {
            requests.remove(requestCode)
            throw exception
        }
        return { checkMain(); requests.remove(requestCode); Unit }
    }

    internal fun checkMain() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "AppWidget must run on the main thread" }
    }
}
