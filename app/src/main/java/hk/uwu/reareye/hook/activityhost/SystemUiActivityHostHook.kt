package hk.uwu.reareye.hook.activityhost

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.SurfaceControl
import hk.uwu.reareye.hook.hostbridge.HookHostBridgeBootstrapRegistry
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppContext
import hk.uwu.reareye.internal.activityhost.IActivityHostBroker
import hk.uwu.reareye.internal.activityhost.IActivityHostCallback
import hk.uwu.reareye.internal.activityhost.IActivityHostSession
import hk.uwu.reareye.internal.activityhost.IActivityHostTaskRegistry
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Privileged broker; XML never receives a TaskOrganizer, task token or trusted-overlay API. */
class SystemUiActivityHostHook : RoxyHooker() {
    private var adapter: ShellActivityHostAdapter? = null
    private var broker: ActivityHostBroker? = null
    private val bootstrap = HookHostBridgeBootstrapRegistry(
        ActivityHostContract.BROKER_ACTION,
        binderProvider = { broker }, logger = { YLog.debug("ActivityHost: $it") })

    override fun onHotReloadPreflight(): Boolean = adapter?.hasHosts() != true
    override fun onHotReloadQuiesce() {
        check(bootstrap.unregister()) { "ActivityHost bootstrap teardown failed" }
        broker?.shutdown()
        broker = null
        adapter = null
    }

    override fun PackageScope.onHook() {
        loadApp(ActivityHostContract.SYSTEM_UI_PACKAGE) {
            runCatching {
                val installedAdapter = ShellActivityHostAdapter(appClassLoader)
                with(installedAdapter) { install() }
                adapter = installedAdapter
                lifecycle {
                    onAttach(replay = true) {
                        val context = hookAppContext ?: this.context
                        if (broker == null) broker = ActivityHostBroker(context, installedAdapter)
                        check(bootstrap.register(context)) { "ActivityHost broker discovery unavailable" }
                    }
                }
                runtime.onClose {
                    bootstrap.unregister()
                    broker?.shutdown()
                    broker = null
                }
                YLog.info("ActivityHost Shell ABI installed; real-device validation still required")
            }.onFailure { YLog.warn("ActivityHost Shell ABI unavailable", it) }
        }
    }
}

internal class ActivityHostBroker(
    private val context: Context,
    private val adapter: ShellActivityHostAdapter,
) : IActivityHostBroker.Stub() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "REAREye-ActivityHost-broker").apply { isDaemon = true }
    }
    private val sessions = mutableSetOf<Session>()
    private val closed = AtomicBoolean()

    private fun enforceHost(): Int {
        check(!closed.get()) { "ActivityHost broker closed" }
        val uid = Binder.getCallingUid()
        val expected =
            context.packageManager.getApplicationInfo(ActivityHostContract.HOST_PACKAGE, 0).uid
        if (uid != expected || uid / 100000 != Process.myUid() / 100000) {
            throw SecurityException("ActivityHost caller is not the rear-screen host")
        }
        return uid
    }

    override fun getProtocolVersion(): Int {
        enforceHost(); return ActivityHostContract.VERSION
    }

    override fun openSession(
        displayId: Int,
        generation: Long,
        callback: IActivityHostCallback
    ): IActivityHostSession {
        val uid = enforceHost()
        require(generation > 0) { "Invalid ActivityHost generation" }
        require(displayId == 1) { "ActivityHost only supports the independent rear display" }
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            ?: error("Rear display is unavailable")
        check(
            ActivityHostReflection.invoke(
                display,
                "isTrusted"
            ) == true
        ) { "Rear display is not trusted" }
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Device is locked" }
        check(adapter.available()) { "Live Shell TaskViewFactory not captured; restart SystemUI" }
        return synchronized(sessions) {
            check(sessions.size < ActivityHostContract.MAX_SESSIONS) { "ActivityHost session quota reached" }
            Session(uid, displayId, generation, callback).also { session ->
                callback.asBinder().linkToDeath(session.death, 0)
                sessions += session
                main.post {
                    if (!session.finished.get()) adapter.create(
                        context.createDisplayContext(display),
                        session
                    ) { view ->
                        if (session.finished.get()) adapter.release(
                            view,
                            false
                        ) else session.carrierReady(view)
                    }
                }
            }
        }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(sessions) { sessions.toList() }.forEach { it.dispose("broker_shutdown") }
        worker.shutdown()
    }

    internal inner class Session(
        private val ownerUid: Int,
        private val displayId: Int,
        private val generation: Long,
        private val callback: IActivityHostCallback,
    ) : IActivityHostSession.Stub(), ShellActivityHostOwner {
        val finished = AtomicBoolean()
        val death = IBinder.DeathRecipient { dispose("client_died") }
        private val lastSequence = AtomicLong()
        @Volatile
        override var surface: SurfaceControl? = null
            private set
        @Volatile
        override var bounds = Rect()
            private set
        @Volatile
        private var clip = Rect()
        @Volatile
        private var carrier: Any? = null
        @Volatile
        private var initialized = false
        @Volatile
        private var wantedVisible = true
        @Volatile
        private var component: ComponentName? = null
        @Volatile
        private var dpi = 0
        @Volatile
        private var pendingIntent: PendingIntent? = null
        @Volatile
        private var registry: IActivityHostTaskRegistry? = null
        @Volatile
        private var cookie: IBinder? = null
        @Volatile
        private var ownedTask = false
        @Volatile
        private var configurationConfirmed = false
        private val configurationCheckQueued = AtomicBoolean()
        @Volatile
        private var launchQueued = false
        @Volatile
        private var taskBounds = Rect()
        @Volatile
        private var existingTaskIds: Set<Int> = emptySet()

        private fun enforceOwner() {
            if (Binder.getCallingUid() != ownerUid) throw SecurityException("ActivityHost session belongs to another UID")
            check(!finished.get()) { "ActivityHost session closed" }
        }

        private fun acceptLayout(screenBounds: Rect, visibleBounds: Rect, sequence: Long): Boolean {
            require(sequence > 0) { "Invalid layout sequence" }
            require(
                !screenBounds.isEmpty && (screenBounds.right.toLong() - screenBounds.left.toLong()) in 2..ActivityHostContract.MAX_DIMENSION.toLong() &&
                        (screenBounds.bottom.toLong() - screenBounds.top.toLong()) in 2..ActivityHostContract.MAX_DIMENSION.toLong()
            ) { "Invalid task bounds" }
            require(!visibleBounds.isEmpty && screenBounds.contains(visibleBounds)) { "Invalid visible bounds" }
            synchronized(this) {
                if (sequence <= lastSequence.get()) return false
                bounds = Rect(screenBounds)
                clip = Rect(visibleBounds)
                lastSequence.set(sequence)
                return true
            }
        }

        override fun setSurface(
            surface: SurfaceControl,
            screenBounds: Rect,
            visibleBounds: Rect,
            sequence: Long
        ) {
            enforceOwner()
            require(surface.isValid) { "Invalid ActivityHost Surface" }
            if (!acceptLayout(screenBounds, visibleBounds, sequence)) {
                surface.release(); return
            }
            val replacement = this.surface
            this.surface = surface
            main.post {
                if (finished.get() || lastSequence.get() != sequence) {
                    if (this.surface !== surface) surface.release()
                    return@post
                }
                runCatching {
                    carrier?.let { view ->
                        if (replacement != null) adapter.detachSurface(view)
                        adapter.attachSurface(view)
                        adapter.updateBounds(view, bounds)
                    }
                    replacement?.release()
                }.onFailure { dispose("surface_attach_failed") }
            }
        }

        override fun updateLayout(screenBounds: Rect, visibleBounds: Rect, sequence: Long) {
            enforceOwner()
            if (!acceptLayout(screenBounds, visibleBounds, sequence)) return
            main.post {
                if (finished.get() || lastSequence.get() != sequence) return@post
                runCatching {
                    carrier?.let {
                        adapter.updateBounds(
                            it,
                            bounds
                        )
                    }; acknowledgeIfAligned()
                }
                    .onFailure { dispose("layout_update_failed") }
            }
        }

        fun carrierReady(view: Any) {
            carrier = view
            runCatching { if (surface != null) adapter.attachSurface(view) }
                .onFailure { dispose("surface_initialization_failed") }
        }

        override fun initialized() {
            initialized = true; maybeLaunch()
        }

        override fun launch(component: String, dpi: Int) {
            enforceOwner()
            check(this.component == null) { "ActivityHost launch is one-shot; create a new session to change DPI" }
            val target =
                requireNotNull(ComponentName.unflattenFromString(component)) { "Invalid Activity component" }
            require(dpi == 0 || dpi in ActivityHostContract.MIN_DPI..ActivityHostContract.MAX_DPI) { "Invalid DPI" }
            synchronized(this) {
                check(this.component == null) { "Launch already requested" }
                this.component = target
                this.dpi = dpi
            }
            worker.execute {
                runCatching {
                    val info = context.packageManager.getActivityInfo(target, 0)
                    require(info.exported && info.enabled && info.applicationInfo.enabled && info.permission.isNullOrEmpty()) {
                        "ActivityHost requires an enabled exported, unprotected Activity entry"
                    }
                    check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Device is locked" }
                    val running =
                        context.getSystemService(ActivityManager::class.java).getRunningTasks(100)
                    existingTaskIds = running.map { it.taskId }.toSet()
                    check(running.none { it.baseActivity?.packageName == target.packageName || it.topActivity?.packageName == target.packageName }) {
                        "Target application already has a task; ActivityHost will not take ownership of it"
                    }
                    val connected = ActivityHostRegistryClient.connect(context)
                    if (finished.get()) return@execute
                    registry = connected
                    val intent = Intent().setComponent(target)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    pendingIntent = PendingIntent.getActivity(
                        context, System.identityHashCode(this), intent,
                        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    main.post { maybeLaunch() }
                }.onFailure { dispose("launch_preflight_failed:${it.javaClass.simpleName}") }
            }
        }

        private fun maybeLaunch() {
            val view = carrier ?: return
            val pending = pendingIntent ?: return
            if (!initialized || bounds.isEmpty || launchQueued || finished.get()) return
            launchQueued = true
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            runCatching { adapter.launch(view, pending, options, bounds) }
                .onFailure { dispose("task_launch_failed") }
        }

        override fun prepareLaunch(options: ActivityOptions) {
            check(!finished.get()) { "Session closed before launch" }
            val launchCookie = ActivityHostReflection.invoke(options, "getLaunchCookie") as? IBinder
                ?: error("Native Shell did not supply a launch cookie")
            requireNotNull(registry).registerLaunch(
                launchCookie, asBinder(),
                requireNotNull(component).flattenToString(), displayId, dpi, Rect(bounds)
            )
            cookie = launchCookie
        }

        override fun taskChanged(info: ActivityManager.RunningTaskInfo) {
            if (finished.get()) return
            val actualDisplayId = runCatching {
                ActivityHostReflection.field(info.javaClass, "displayId").getInt(info)
            }.getOrDefault(-1)
            if (actualDisplayId != displayId || info.taskId in existingTaskIds) {
                dispose("task_ownership_or_display_mismatch"); return
            }
            if (!ownedTask) {
                val cookies = runCatching {
                    ActivityHostReflection.field(info.javaClass, "launchCookies")
                        .get(info) as? List<*>
                }.getOrNull()
                if (cookie == null || cookies?.contains(cookie) != true) {
                    dispose("unverified_task_cookie"); return
                }
                ownedTask = true
                val launchCookie = requireNotNull(cookie)
                if (configurationCheckQueued.compareAndSet(false, true)) worker.execute {
                    runCatching {
                        check(registry?.isLaunchConfigured(launchCookie) == true) {
                            "First Activity configuration was not confirmed"
                        }
                    }.onSuccess {
                        main.post {
                            if (finished.get()) return@post
                            configurationConfirmed = true
                            runCatching {
                                callback.onTaskCreated(
                                    generation,
                                    info.taskId,
                                    actualDisplayId
                                )
                            }
                                .onFailure { dispose("client_callback_failed") }
                            acknowledgeIfAligned()
                        }
                    }.onFailure { dispose("task_configuration_not_confirmed") }
                }
            }
            taskBounds = runCatching {
                val configuration = requireNotNull(
                    ActivityHostReflection.field(info.javaClass, "configuration").get(info)
                )
                val window =
                    ActivityHostReflection.field(configuration.javaClass, "windowConfiguration")
                        .get(configuration)
                Rect(ActivityHostReflection.invoke(window, "getBounds") as Rect)
            }.getOrDefault(Rect())
            acknowledgeIfAligned()
        }

        private fun acknowledgeIfAligned() {
            if (finished.get() || !ownedTask || !configurationConfirmed || taskBounds != bounds) return
            if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                dispose("device_locked"); return
            }
            runCatching { callback.onLayoutApplied(generation, lastSequence.get(), Rect(bounds)) }
                .onFailure { dispose("client_callback_failed") }
        }

        override fun taskClosed(reason: String) {
            dispose(reason)
        }

        override fun setVisible(visible: Boolean) {
            enforceOwner()
            wantedVisible = visible
            main.post {
                if (finished.get()) return@post
                runCatching { carrier?.let { adapter.setVisible(it, visible) } }
                    .onFailure { dispose("visibility_update_failed") }
            }
        }

        override fun moveToFullscreen() {
            enforceOwner()
            // The OEM method only queues a transition. Releasing immediately destroys the
            // controller/token used by that transition; no verified detach completion ABI exists.
            throw UnsupportedOperationException("背屏全屏交接尚未验证；请结束嵌入后使用主屏打开")
        }

        override fun close() {
            enforceOwner(); dispose("client_closed")
        }

        fun dispose(reason: String) {
            if (!finished.compareAndSet(false, true)) return
            synchronized(sessions) { sessions.remove(this) }
            runCatching { callback.asBinder().unlinkToDeath(death, 0) }
            runCatching { callback.onClosed(generation, reason) }
            pendingIntent?.cancel()
            val launchCookie = cookie
            if (launchCookie != null && !worker.isShutdown) worker.execute {
                runCatching {
                    registry?.unregisterLaunch(
                        launchCookie
                    )
                }
            }
            main.post {
                carrier?.let { runCatching { adapter.release(it, ownedTask) } }
                carrier = null
                surface?.release()
                surface = null
            }
        }
    }
}
