package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.app.KeyguardManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import hk.uwu.reareye.hook.activityhost.ActivityHostSpec
import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import hk.uwu.reareye.hook.activityhost.ActivityHostGeometry
import hk.uwu.reareye.hook.activityhost.ActivityHostLayout
import hk.uwu.reareye.hook.activityhost.ActivityHostRect
import kotlin.math.roundToInt

/** Main-thread UI owner. A click is required after every close, detach or pause. */
internal class ActivityHostBackend(private var inputs: ActivityHostInputs? = null) :
    MamlViewBackend, ActivityHostClient.Events {
    private var view: ActivityHostSurfaceView? = null
    private var controls: LinearLayout? = null
    private var status: TextView? = null
    private var fullscreenButton: Button? = null
    private var client: ActivityHostClient? = null
    private val gate = ActivityHostInputGate()
    private val launches = ActivityHostLaunchGate()
    private var pendingLaunch: ActivityHostInputs? = null
    private var launchBoundary = 0L
    private var initialized = false
    private var resumed = false
    private var visible = false
    private var attached = false
    private var finished = false
    private var opened = false
    private var requested = false
    private var generation = 0L
    private var sequence = 0L
    private var last: ActivityHostLayout? = null
    private var stableFrames = 0
    private var navigating = false
    private var needsSurface = true
    private var taskCreated = false
    private var abiError: String? = null

    override fun createView(context: Context): View {
        check(!finished)
        view?.let { return it }
        val host = ActivityHostSurfaceView(context)
        view = host
        client = ActivityHostClient(context.applicationContext, this)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        }
        controls = panel
        status = TextView(context).apply { text = "点击启动独立应用任务"; gravity = Gravity.CENTER }
        panel.addView(status)
        panel.addView(Button(context).apply {
            text = "启动 / 按当前 DPI 重建"; setOnClickListener { launchFromClick() }
        })
        panel.addView(Button(context).apply {
            text = "主屏打开"; setOnClickListener { launchNormallyFromClick() }
        })
        host.addView(panel, FrameLayout.LayoutParams(-1, -1))
        // Keep an explicit exit outside content. Native fullscreen handoff is not yet verified.
        host.addView(
            Button(context).apply {
                fullscreenButton = this
                text = "退出"; contentDescription = "结束嵌入任务并显示主屏打开入口"
                setOnClickListener { if (opened) close("已结束嵌入；背屏全屏尚未验证，可在主屏正常打开") }
            },
            FrameLayout.LayoutParams(
                (24f * context.resources.displayMetrics.density).roundToInt(),
                -1,
                Gravity.RIGHT
            )
        )
        host.onFrame = { tryExplicitLaunch(); frame() }
        host.onSurfaceChanged = { needsSurface = true; revoke(); frame() }
        host.onSurfaceLost = { close("Surface已释放；请重新启动") }
        host.onAbiFailure = { reason -> abiError = reason; close(reason) }
        host.onNavigation = { down -> navigating = down; stableFrames = 0; host.setInputHole(null) }
        return host
    }

    private fun active(): Boolean {
        val host = view ?: return false
        val locked =
            (host.context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked != false
        return initialized && resumed && visible && attached && !finished && host.isAttachedToWindow && host.isShown &&
                host.windowVisibility == View.VISIBLE && !locked && abiError == null
    }

    private fun launchFromClick() {
        val host = view ?: return
        val snapshot = inputs ?: return show("缺少component配置")
        if (snapshot.validationErrors.isNotEmpty()) return show(
            snapshot.validationErrors.joinToString(
                "；"
            )
        )
        if (!active()) return show(abiError ?: "卡片尚不可见、未恢复或屏幕已锁定")
        if (!host.inputAbiReady) return show("输入区域ABI不可用，拒绝启动")
        val display = host.display ?: return show("宿主display不可用")
        close("正在连接SystemUI")
        requested = true
        generation = client?.open(display.displayId, snapshot.component, snapshot.dpi) ?: return
        show("正在连接SystemUI")
    }

    private fun launchNormallyFromClick() {
        if (opened || requested || finished) return
        val host = view ?: return
        val spec = inputs ?: return show("缺少component配置")
        if (spec.validationErrors.isNotEmpty() || ActivityHostSpec.parseComponent(spec.component) == null)
            return show("component配置无效")
        val locked =
            (host.context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked != false
        if (!initialized || !resumed || !visible || !attached || !host.isAttachedToWindow || !host.isShown ||
            host.windowVisibility != View.VISIBLE || locked
        ) return show("卡片不可见或屏幕已锁定")
        try {
            val component =
                ComponentName.unflattenFromString(spec.component) ?: error("component无效")
            val info = host.context.packageManager.getActivityInfo(component, 0)
            check(info.exported && info.enabled && info.applicationInfo.enabled && info.permission.isNullOrBlank()) {
                "只能打开正常公开且无权限保护的Activity入口"
            }
            host.context.startActivity(
                Intent().setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().apply { setLaunchDisplayId(0) }.toBundle()
            )
        } catch (error: Exception) {
            show("主屏打开失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun onInputsChanged(inputs: MamlBackendInputs) {
        val next = inputs.activityHost
        val old = this.inputs
        this.inputs = next
        // Component and fixed DPI are immutable within a session. Never automatically relaunch.
        if (requested && old != null && (old.component != next?.component || old.dpi != next?.dpi)) {
            close("启动配置已变化；点击按新DPI重建")
        } else if (old != next) {
            last = null; revoke()
        }
        if (next?.validationErrors?.isNotEmpty() == true) close(next.validationErrors.joinToString("；"))
        else if (next != null && next.launchPermit != null &&
            launches.accept(next.instance, next.component, next.dpi, next.userRequest) &&
            next.launchPermit.claim(next.instance, next.component, next.dpi, next.userRequest)
        ) {
            // The permit is consumed before waiting for visibility/Surface, and survives no pause.
            if (next.launchPermit.isNewerThan(launchBoundary) && initialized && resumed && attached && !finished) {
                pendingLaunch = next
                view?.postInvalidateOnAnimation()
                tryExplicitLaunch()
            } else next.launchPermit.revoke()
        }
    }

    private fun tryExplicitLaunch() {
        val next = pendingLaunch ?: return
        val current = inputs
        if (next.launchPermit?.isValid() != true || current?.instance != next.instance ||
            current.component != next.component || current.dpi != next.dpi
        ) {
            pendingLaunch = null; return
        }
        if (!active() || view?.inputAbiReady != true) return
        pendingLaunch = null
        launchFromClick()
    }

    override fun onInit() {
        initialized = true; tryExplicitLaunch()
    }

    override fun onResume() {
        resumed = true; tryExplicitLaunch()
    }

    private fun suspendLaunches() {
        launchBoundary = ActivityHostLaunchPermit.newBoundary()
        inputs?.launchPermit?.revoke()
        pendingLaunch?.launchPermit?.revoke()
        pendingLaunch = null
    }

    override fun onPause() {
        resumed = false; suspendLaunches(); close("卡片已暂停；点击重新启动")
    }

    override fun onVisibilityChanged(visible: Boolean) {
        this.visible = visible
        if (!visible) {
            suspendLaunches(); close("卡片已隐藏；点击重新启动")
        } else tryExplicitLaunch()
    }

    override fun onAttachmentChanged(attached: Boolean) {
        this.attached = attached
        if (!attached) {
            suspendLaunches(); close("卡片已移除；点击重新启动")
        } else tryExplicitLaunch()
    }

    override fun onSizeChanged(widthPx: Int, heightPx: Int) {
        last = null; revoke()
    }

    override fun onFinish() {
        finished = true; suspendLaunches(); close("已结束"); client?.dispose(); client = null
        view?.apply {
            onFrame = null; onSurfaceChanged = null; onSurfaceLost = null; onNavigation =
            null; onAbiFailure = null
        }
    }

    override fun onOpened(generation: Long) {
        if (generation != this.generation || !requested || !active()) {
            close("会话过期"); return
        }
        opened = true; needsSurface = true; last = null; frame()
    }

    override fun onLayoutApplied(generation: Long, sequence: Long, bounds: Rect) {
        if (gate.acknowledge(generation, sequence, bounds)) {
            updateHole()
            view?.postInvalidateOnAnimation()
        }
    }

    override fun onTaskCreated(generation: Long, taskId: Int, displayId: Int) {
        if (generation != this.generation || view?.display?.displayId != displayId) return
        taskCreated = true; controls?.visibility = View.GONE; updateHole()
    }

    override fun onClosed(generation: Long, reason: String) {
        if (generation != this.generation) return
        close(reason)
    }

    private fun frame() {
        val host = view ?: return
        if (!opened) {
            host.setInputHole(null); return
        }
        if (!active()) {
            close("不可见或屏幕锁定；点击重新启动"); return
        }
        val spec = inputs ?: return
        // SurfaceControl cannot reproduce a rotated/perspective Android view safely.
        var node: View? = host
        var sx = 1f;
        var sy = 1f
        while (node != null) {
            if (node.rotation != 0f || node.rotationX != 0f || node.rotationY != 0f || node.scaleX != 1f || node.scaleY != 1f || node.alpha < 0.99f) {
                last = null; revoke(); client?.visible(
                    generation,
                    false
                ); show("卡片变换中，任务输入已暂停"); return
            }
            sx *= node.scaleX; sy *= node.scaleY
            node = node.parent as? View
        }
        val origin = IntArray(2); host.getLocationOnScreen(origin)
        val outer = Rect(
            origin[0],
            origin[1],
            origin[0] + (host.width * sx).roundToInt(),
            origin[1] + (host.height * sy).roundToInt()
        )
        val clip = Rect()
        if (!host.getGlobalVisibleRect(clip)) {
            close("卡片没有可见区域"); return
        }
        val rootScreenOrigin = IntArray(2); host.rootView.getLocationOnScreen(rootScreenOrigin)
        clip.offset(rootScreenOrigin[0], rootScreenOrigin[1])
        // Actual system safe rectangle, not an assumed camera position.
        host.rootWindowInsets?.displayCutout?.let { cutout ->
            val root = host.rootView
            val rootOrigin = IntArray(2); root.getLocationOnScreen(rootOrigin)
            val safe = Rect(
                rootOrigin[0] + cutout.safeInsetLeft,
                rootOrigin[1] + cutout.safeInsetTop,
                rootOrigin[0] + root.width - cutout.safeInsetRight,
                rootOrigin[1] + root.height - cutout.safeInsetBottom
            )
            if (!clip.intersect(safe)) {
                last = null; revoke(); client?.visible(generation, false); return
            }
        }
        val scale = spec.carrierWidth?.let { outer.width() / it } ?: spec.mamlScale
        val layout = ActivityHostGeometry.resolve(
            outer.common(), clip.common(), scale,
            host.resources.displayMetrics.density, spec.gutterDp, spec.bounds
        )
        if (layout == null) {
            last = null; revoke(); client?.visible(
                generation,
                false
            ); show("bounds越界或内容区域不足"); return
        }
        if (taskCreated) controls?.visibility = View.GONE
        host.navigation(
            layout.leftNavigationBounds.android(),
            layout.rightNavigationBounds.android()
        )
        fullscreenButton?.let { button ->
            val width = layout.rightNavigationBounds.width.toInt()
            if (button.layoutParams.width != width) button.layoutParams =
                (button.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
        }
        val local = layout.surfaceBounds.android()
        host.positionSurface(
            Rect(
                (local.left / sx).roundToInt(), (local.top / sy).roundToInt(),
                (local.right / sx).roundToInt(), (local.bottom / sy).roundToInt()
            )
        )
        if (last != layout || needsSurface) {
            host.setInputHole(null); stableFrames = 0
            // Wait for SurfaceView layout to catch up; a late surface must not launch on the wrong rectangle.
            if (!host.surfaceReady || host.surface.width != (local.width() / sx).roundToInt() || host.surface.height != (local.height() / sy).roundToInt()) return
            val surface = if (needsSurface) host.surfaceControl() ?: return else null
            last = layout; needsSurface = false
            ++sequence
            gate.submit(generation, sequence, layout.taskBounds.android())
            client?.layout(
                generation,
                surface,
                layout.taskBounds.android(),
                layout.visibleBounds.android(),
                sequence
            )
            client?.visible(generation, true)
        } else stableFrames++
        updateHole()
        if (stableFrames < 2) host.postInvalidateOnAnimation()
    }

    private fun updateHole() {
        val enabled = gate.allows(
            active() && taskCreated,
            stableFrames >= 2,
            navigating
        ) && view?.inputAbiReady == true
        view?.setInputHole(if (enabled) last?.visibleBounds?.android() else null)
    }

    private fun revoke() {
        gate.revoke(); stableFrames = 0; view?.setInputHole(null)
    }

    private fun close(reason: String) {
        pendingLaunch = null
        revoke(); client?.close(); opened = false; requested = false; taskCreated = false; last =
            null; needsSurface = true
        show(reason)
    }

    private fun show(reason: String) {
        status?.text = reason; controls?.visibility = View.VISIBLE
    }
}

private fun Rect.common() = ActivityHostRect(left, top, right, bottom)
private fun ActivityHostRect.android() = Rect(left, top, right, bottom)
