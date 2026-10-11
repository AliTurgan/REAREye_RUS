package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.app.PendingIntent
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class MamlDynamicInputsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val provider = "example/example.Provider"

    @Test
    fun expressionAttributesNeedNoFixedAttributesAndAreNotEvaluatedDuringPreparation() {
        val calls = mutableListOf<String>()
        val values = mutableMapOf("@rw_instance" to "one", "@rw_provider" to provider)
        val compiler = MamlStringExpressionCompiler { expression ->
            calls += "compile:$expression"
            val evaluator: () -> String? = { calls += "evaluate:$expression"; values[expression] }
            evaluator
        }
        val binding = AppWidgetElementFactory().prepare(
            xml("<RearAppWidget keyExp='@rw_instance' providerExp='@rw_provider'/>"), compiler,
        )
        assertEquals(listOf("compile:@rw_instance", "compile:@rw_provider"), calls)
        assertEquals(MamlBackendInputs("one", provider), binding.evaluateInputs())
        values["@rw_instance"] = "two"
        assertEquals("two", binding.evaluateInputs().key)
    }

    @Test
    fun expressionOverridesFixedValueAndPlainFactoryInterfaceRemainsAvailable() {
        val factory: MamlViewBackendFactory = AppWidgetElementFactory()
        assertNotNull(factory.create(xml("<RearAppWidget key='fixed' provider='$provider'/>")))
        val binding = (factory as MamlExpressionBackendFactory).prepare(
            xml("<RearAppWidget key='fixed' provider='invalid' keyExp='key' providerExp='provider'/>"),
            MamlStringExpressionCompiler { source -> { if (source == "key") "dynamic" else provider } },
        )
        assertEquals("dynamic", binding.evaluateInputs().key)
    }

    @Test
    fun emptyOrUnconfirmedOrHiddenInputsNeverOpenOrCreateHostView() {
        val platform = FakeBackendPlatform()
        val backend = backend(platform)
        backend.createView(context)
        backend.onInit()
        backend.onVisibilityChanged(true) // Native init has not yet confirmed visibility on tick.
        assertEquals(0, platform.opens.size)
        backend.onInputsChanged(MamlBackendInputs("", provider, true))
        backend.onInputsChanged(MamlBackendInputs("one", "", true))
        backend.onInputsChanged(MamlBackendInputs("one", provider, false))
        assertEquals(0, platform.opens.size)
        assertEquals(0, platform.hostViews)
        assertEquals(0, platform.widgets.allocations)
        backend.onInputsChanged(MamlBackendInputs("one", provider, true))
        assertEquals(listOf("one" to provider), platform.opens)
        assertEquals(0, platform.widgets.allocations)
    }

    @Test
    fun inputBeforeViewAndLateInputAfterInitBothOpenOnce() {
        val platform = FakeBackendPlatform()
        val first = backend(platform)
        first.onInputsChanged(MamlBackendInputs("before", provider, true))
        assertTrue(platform.opens.isEmpty())
        first.createView(context)
        first.onInit()
        val second = backend(platform)
        second.createView(context)
        second.onInit()
        second.onInputsChanged(MamlBackendInputs("late", provider, true))
        assertEquals(listOf("before", "late"), platform.opens.map { it.first })
    }

    @Test
    fun independentKeysRestoreOnlyWhenVisibleAndRejectChangingLiveSelection() {
        val platform = FakeBackendPlatform()
        platform.widgets.saved["one"] = WidgetBinding(1, provider, true)
        platform.widgets.saved["two"] = WidgetBinding(2, provider, true)
        val first = backend(platform)
        val second = backend(platform)
        first.createView(context)
        second.createView(context)
        first.onInputsChanged(MamlBackendInputs("one", provider, false))
        second.onInputsChanged(MamlBackendInputs("two", provider, false))
        assertEquals(0, platform.hostViews)
        first.onInputsChanged(MamlBackendInputs("one", provider, true))
        second.onInputsChanged(MamlBackendInputs("two", provider, true))
        first.onSizeChanged(200, 100)
        second.onSizeChanged(200, 100)
        assertEquals(2, platform.hostViews)
        assertEquals(2, platform.opens.size)
        first.onInputsChanged(MamlBackendInputs("different", "other/other.Provider", true))
        assertEquals(2, platform.opens.size)
        first.onInputsChanged(MamlBackendInputs("one", provider, true))
        first.onFinish()
        val reloaded = backend(platform)
        reloaded.createView(context)
        reloaded.onInputsChanged(MamlBackendInputs("one", provider, true))
        assertEquals(3, platform.opens.size)
        assertEquals(0, platform.widgets.allocations)
    }

    @Test
    fun errorsRetryByClickingTheExistingSessionWithoutReopening() {
        val platform = FakeBackendPlatform()
        val backend = backend(platform)
        val root = backend.createView(context) as FrameLayout
        backend.onInputsChanged(MamlBackendInputs("one", provider, true))
        val status = root.getChildAt(0) as TextView
        status.performClick()
        status.performClick()
        assertEquals(1, platform.opens.size)
        assertEquals(2, platform.widgets.providerChecks)
        assertEquals(0, platform.widgets.allocations)
    }

    @Test
    fun nativeBackgroundSamplesAreCoalescedAndDeliveredOnlyOnMain() {
        var value = MamlBackendInputs("one", provider, true)
        val backend = RecordingBackend()
        val session = session({ backend }) {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            value
        }
        session.init {}
        Thread {
            session.tickInputs()
            value = value.copy(key = "two")
            session.tickInputs()
        }.apply { start(); join() }
        assertTrue(backend.inputs.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(value), backend.inputs)
        assertTrue(backend.inputOnMain)
    }

    @Test
    fun queuedAndInFlightOldInputsCannotReachReinitializedBackend() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backends = mutableListOf<RecordingBackend>()
        var block = false
        var input = MamlBackendInputs("old", provider, true)
        val session = session({ RecordingBackend().also(backends::add) }) {
            val captured = input
            if (block) {
                started.countDown(); release.await()
            }
            captured
        }
        session.init {}
        Thread { session.tickInputs() }.apply { start(); join() }
        block = true
        val worker = Thread { session.tickInputs() }.apply { start() }
        started.await()
        session.finish()
        session.init {}
        assertTrue(backends.last().inputs.isEmpty())
        block = false
        input = MamlBackendInputs("fresh", provider, true)
        session.tickInputs()
        release.countDown()
        worker.join()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(input), backends.last().inputs)
        assertEquals(1, backends.first().finishes)
    }

    @Test
    fun nativeHideSuppressesEarlierQueuedVisibleInputWhenHiddenElementsStopTicking() {
        val platform = FakeBackendPlatform()
        val backend = backend(platform)
        val session = session({ backend }) { MamlBackendInputs("one", provider, true) }
        session.init {}
        Thread {
            session.tickInputs()
            session.visibility(false)
        }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(platform.opens.isEmpty())
        assertEquals(View.INVISIBLE, session.view!!.visibility)
        assertEquals(0, platform.hostViews)
    }

    @Test
    fun inputsPublishedBeforeInitAreRetainedForTheCreatedBackend() {
        val backend = RecordingBackend()
        val input = MamlBackendInputs("early", provider, false)
        val session = session({ backend }) { input }
        session.tickInputs()
        session.init {}
        assertEquals(listOf(input), backend.inputs)
    }

    @Test
    fun unchangedInputsDoNotRevalidateBindingOrTraverseWidgetSizeAndOneResizeUpdatesOnce() {
        val platform = FakeBackendPlatform()
        platform.widgets.saved["one"] = WidgetBinding(1, provider, true)
        val backend = backend(platform)
        backend.createView(context)
        val input = MamlBackendInputs("one", provider, true)
        backend.onInputsChanged(input)
        backend.onResume()
        backend.onAttachmentChanged(true)
        platform.onSizeUpdate = {
            // A vendor size call may synchronously publish another lifecycle/render.
            backend.onPause()
            backend.onResume()
        }
        backend.onSizeChanged(300, 200)
        val providerChecks = platform.widgets.providerChecks
        repeat(1000) {
            backend.onInputsChanged(input)
            backend.onVisibilityChanged(true)
            backend.onResume()
            backend.onAttachmentChanged(true)
            backend.onSizeChanged(300, 200)
        }
        assertEquals(1, platform.opens.size)
        assertEquals(1, platform.hostViews)
        assertEquals(providerChecks, platform.widgets.providerChecks)
        assertEquals(1, platform.sizeUpdates)
        platform.onSizeUpdate = null
        backend.onSizeChanged(320, 200)
        repeat(60) { backend.onSizeChanged(320, 200) }
        assertEquals(2, platform.sizeUpdates)
    }

    @Test
    fun remoteViewsPendingIntentClickRoutesThroughTheActualCarrierAndWidgetViewTree() {
        val action = "hk.uwu.reareye.TEST_WIDGET_CLICK"
        var clicks = 0
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                clicks++
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        val platform = FakeBackendPlatform()
        platform.widgets.saved["one"] = WidgetBinding(1, provider, true)
        val pending = PendingIntent.getBroadcast(
            context, 91, Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        platform.content = RemoteViews("android", android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "RemoteViews Button")
            setOnClickPendingIntent(android.R.id.text1, pending)
        }
        val backend = backend(platform)
        val session = session({ backend }) { MamlBackendInputs("one", provider, true) }
        val activity =
            org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            session.init {}
            activity.get().setContentView(session.view)
            session.tickInputs()
            repeat(1000) { session.tickInputs() }
            shadowOf(Looper.getMainLooper()).idle()
            val root = session.view!!
            val width = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)
            root.measure(width, height)
            root.layout(0, 0, 320, 200)
            // The live host is added after valid geometry; measure its first real frame.
            root.measure(width, height)
            root.layout(0, 0, 320, 200)
            // HostView is intentionally an Android root namespace: parent.findViewById
            // cannot search RemoteViews descendants, while touch dispatch must still reach them.
            val host = (root.getChildAt(0) as FrameLayout).getChildAt(0) as AppWidgetHostView
            val button = requireNotNull(host.findViewById<View>(android.R.id.text1))
            assertTrue(button.isClickable)
            val bounds = Rect()
            button.getDrawingRect(bounds)
            root.offsetDescendantRectToMyCoords(button, bounds)
            val time = SystemClock.uptimeMillis()
            for (event in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val motion = MotionEvent.obtain(
                    time,
                    time + event,
                    event,
                    bounds.exactCenterX(),
                    bounds.exactCenterY(),
                    0
                )
                assertTrue(root.dispatchTouchEvent(motion))
                motion.recycle()
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, clicks)
            assertEquals(1, platform.hostViews)
        } finally {
            session.finish()
            activity.pause().stop().destroy()
            context.unregisterReceiver(receiver)
            pending.cancel()
        }
    }

    @Test
    fun explicitConfirmationIsCompiledLazilyAndAllocatesOnlyOnce() {
        val values =
            mapOf("@key" to "one", "@provider" to provider, "@request" to "one|$provider|1")
        var evaluations = 0
        val binding = AppWidgetElementFactory().prepare(
            xml("<RearAppWidget keyExp='@key' providerExp='@provider' userRequestExp='@request'/>"),
            MamlStringExpressionCompiler { source -> { evaluations++; values[source] } },
        )
        assertEquals(0, evaluations)
        val input = binding.evaluateInputs().copy(visible = true)
        assertEquals("one|$provider|1", input.userRequest)
        val platform = FakeBackendPlatform()
        platform.widgets.available = true
        platform.widgets.allowBind = true
        val backend = backend(platform)
        backend.createView(context)
        repeat(20) { backend.onInputsChanged(input) }
        assertEquals(1, platform.widgets.allocations)
        assertTrue(platform.widgets.saved.getValue("one").complete)
        backend.onPause()
        backend.onResume()
        backend.onInputsChanged(input.copy(userRequest = "one|$provider|2"))
        assertEquals(1, platform.widgets.allocations)
    }

    @Test
    fun userRequestWaitsForInitialAttachmentButPauseRevokesIt() {
        val platform = FakeBackendPlatform()
        platform.widgets.available = true
        platform.widgets.allowBind = true
        val backend = AppWidgetElementBackend("", "", platformFactory = { platform })
        backend.createView(context)
        val input = MamlBackendInputs("one", provider, true, "one|$provider|1")
        backend.onInputsChanged(input)
        backend.onInit()
        backend.onResume()
        assertEquals(0, platform.widgets.allocations)
        backend.onPause()
        backend.onAttachmentChanged(true)
        backend.onResume()
        backend.onInputsChanged(input)
        assertEquals(0, platform.widgets.allocations)
        backend.onInputsChanged(input.copy(userRequest = "one|$provider|2"))
        assertEquals(1, platform.widgets.allocations)
    }

    @Test
    fun wrongIdentityAndRestoredSelectionNeverAuthorize() {
        val platform = FakeBackendPlatform()
        platform.widgets.available = true
        val backend = backend(platform)
        backend.createView(context)
        backend.onInputsChanged(MamlBackendInputs("one", provider, true, "other|$provider|1"))
        backend.onInputsChanged(MamlBackendInputs("one", provider, true))
        backend.onPause()
        backend.onResume()
        assertEquals(0, platform.widgets.allocations)
    }

    @Test
    fun readyHostIsCreatedInOneCancellableFrameAfterValidLifecycleAndGeometry() {
        val platform = FakeBackendPlatform()
        platform.widgets.saved["one"] = WidgetBinding(1, provider, true)
        val frames = mutableListOf<() -> Unit>()
        val backend =
            AppWidgetElementBackend("", "", platformFactory = { platform }, enqueueFrame = { work ->
                frames.add(work)
                val cancel: () -> Unit = { frames.remove(work); Unit }
                cancel
            })
        backend.createView(context)
        backend.onInputsChanged(MamlBackendInputs("one", provider, true))
        backend.onInit()
        backend.onResume()
        backend.onAttachmentChanged(true)
        assertTrue(frames.isEmpty())
        backend.onSizeChanged(200, 100)
        assertEquals(1, frames.size)
        assertEquals(0, platform.hostViews)
        backend.onPause()
        assertTrue(frames.isEmpty())
        backend.onResume()
        assertEquals(1, frames.size)
        frames.removeAt(0).invoke()
        assertEquals(1, platform.hostViews)
        assertEquals(0, platform.widgets.allocations)
    }

    @Test
    fun cancelledFramesCannotMountOrClearTheReplacementFrameAcrossLifecycleRecovery() {
        for (edge in listOf("visibility", "attachment", "resume")) {
            val platform = FakeBackendPlatform()
            platform.widgets.saved["one"] = WidgetBinding(1, provider, true)
            val frames = FrameQueue()
            val backend = AppWidgetElementBackend("", "", { platform }, frames::enqueue)
            val root = backend.createView(context) as FrameLayout
            backend.onInit()
            backend.onResume()
            backend.onAttachmentChanged(true)
            backend.onInputsChanged(MamlBackendInputs("one", provider, true))
            backend.onSizeChanged(200, 100)
            val stale = frames.pending.single()
            when (edge) {
                "visibility" -> backend.onVisibilityChanged(false)
                "attachment" -> backend.onAttachmentChanged(false)
                else -> backend.onPause()
            }
            assertTrue("$edge must cancel its frame", frames.pending.isEmpty())
            stale.invoke() // A scheduler can deliver a callback after cancellation.
            assertEquals(0, platform.hostViews)
            when (edge) {
                "visibility" -> backend.onVisibilityChanged(true)
                "attachment" -> backend.onAttachmentChanged(true)
                else -> backend.onResume()
            }
            assertEquals("$edge must restore mounting", 1, frames.pending.size)
            stale.invoke() // Must not reset framePending for the new generation.
            repeat(10) {
                backend.onSizeChanged(200, 100)
                backend.onVisibilityChanged(true)
                backend.onAttachmentChanged(true)
                backend.onResume()
            }
            assertEquals(1, frames.pending.size)
            frames.runNext()
            val host = root.getChildAt(0)
            assertTrue(host is AppWidgetHostView)
            assertEquals(1, platform.hostViews)
            assertEquals(1, platform.sizeUpdates)
            assertEquals(1, platform.opens.size)
            assertEquals(0, platform.widgets.allocations)
            assertEquals(1, platform.widgets.saved.getValue("one").id)
            repeat(10) {
                backend.onVisibilityChanged(false)
                backend.onAttachmentChanged(false)
                backend.onPause()
                backend.onResume()
                backend.onAttachmentChanged(true)
                backend.onVisibilityChanged(true)
                backend.onSizeChanged(200, 100)
            }
            assertSame(host, root.getChildAt(0))
            assertTrue(frames.pending.isEmpty())
            assertEquals(1, platform.hostViews)
            assertEquals(1, platform.sizeUpdates)
            assertEquals(0, platform.widgets.allocations)
            backend.onFinish()
        }
    }

    @Test
    fun hiddenInputRevokesConfirmationUntilFreshVisibleSampleAndRestoresPendingMount() {
        val platform = FakeBackendPlatform()
        platform.widgets.saved["one"] = WidgetBinding(1, provider, true)
        val frames = FrameQueue()
        val backend = AppWidgetElementBackend("", "", { platform }, frames::enqueue)
        backend.createView(context)
        backend.onInit()
        backend.onResume()
        backend.onAttachmentChanged(true)
        val input = MamlBackendInputs("one", provider, true)
        backend.onInputsChanged(input)
        backend.onSizeChanged(200, 100)
        val stale = frames.pending.single()
        backend.onInputsChanged(input.copy(visible = false))
        backend.onVisibilityChanged(true)
        backend.onSizeChanged(200, 100)
        assertTrue(frames.pending.isEmpty())
        assertEquals(0, platform.hostViews)
        backend.onInputsChanged(input)
        assertEquals(1, frames.pending.size)
        stale.invoke()
        repeat(100) {
            backend.onInputsChanged(input)
            backend.onInputsChanged(input.copy(userRequest = "unrelated|request|$it"))
            backend.onSizeChanged(200, 100)
        }
        assertEquals(1, frames.pending.size)
        val checks = platform.widgets.providerChecks
        frames.runNext()
        assertEquals(1, platform.hostViews)
        assertEquals(1, platform.sizeUpdates)
        assertEquals(checks, platform.widgets.providerChecks)
        assertEquals(1, platform.opens.size)
        assertEquals(0, platform.widgets.allocations)
        backend.onFinish()
    }

    @Test
    fun zeroGeometryCancelsFrameAndPositiveGeometryRestoresExactlyOneMount() {
        val platform = FakeBackendPlatform()
        platform.widgets.saved["one"] = WidgetBinding(1, provider, true)
        val frames = FrameQueue()
        val backend = AppWidgetElementBackend("", "", { platform }, frames::enqueue)
        backend.createView(context)
        backend.onInit()
        backend.onResume()
        backend.onAttachmentChanged(true)
        backend.onInputsChanged(MamlBackendInputs("one", provider, true))
        backend.onSizeChanged(200, 100)
        val stale = frames.pending.single()
        backend.onSizeChanged(0, 100)
        assertTrue(frames.pending.isEmpty())
        backend.onSizeChanged(200, 100)
        stale.invoke()
        repeat(100) { backend.onSizeChanged(200, 100) }
        assertEquals(1, frames.pending.size)
        frames.runNext()
        assertEquals(1, platform.hostViews)
        assertEquals(1, platform.sizeUpdates)
        assertEquals(0, platform.widgets.allocations)
        backend.onFinish()
    }

    @Test
    fun hidingInputsRevokesUserRequestAndShowingDoesNotReplayIt() {
        val platform = FakeBackendPlatform()
        platform.widgets.available = true
        platform.widgets.allowBind = true
        val backend = AppWidgetElementBackend("", "", { platform })
        backend.createView(context)
        backend.onInit()
        backend.onResume()
        val input = MamlBackendInputs("one", provider, true, "one|$provider|1")
        backend.onInputsChanged(input)
        backend.onInputsChanged(input.copy(visible = false))
        backend.onVisibilityChanged(true)
        backend.onAttachmentChanged(true)
        assertEquals(0, platform.widgets.allocations)
        backend.onInputsChanged(input)
        assertEquals(0, platform.widgets.allocations)
        backend.onInputsChanged(input.copy(userRequest = "one|$provider|2"))
        assertEquals(1, platform.widgets.allocations)
        backend.onFinish()
    }

    @Test
    fun duplicateKeyReportsConflictInsteadOfDefaultAddPlaceholder() {
        val platform = FakeBackendPlatform()
        val first = backend(platform)
        first.createView(context)
        first.onInputsChanged(MamlBackendInputs("one", provider, true))
        val second = backend(platform)
        val root = second.createView(context) as FrameLayout
        second.onInputsChanged(MamlBackendInputs("one", provider, true))
        assertTrue((root.getChildAt(0) as TextView).text.toString().contains("稳定键重复"))
        assertEquals(0, platform.widgets.allocations)
    }

    @Test
    fun configPublicationBootstrapsInputsWithoutWaitingForAnotherNativeTick() {
        val root = Any()
        val backend = RecordingBackend()
        var input = MamlBackendInputs("", "", false)
        val session = session({ backend }) { input }
        session.init {}
        MamlElementHook.registerInputs(root, session)
        input = MamlBackendInputs("restored", provider, true)
        MamlElementHook.refreshInputs(root)
        assertEquals(listOf(input), backend.inputs)
        session.finish()
        MamlElementHook.refreshInputs(root)
        assertEquals(1, backend.inputs.size)
    }

    private fun session(factory: () -> MamlViewBackend, evaluator: () -> MamlBackendInputs) =
        MamlElementSession(context, factory, Handler(Looper.getMainLooper()), {}, evaluator)

    private fun backend(platform: FakeBackendPlatform) = AppWidgetElementBackend(
        "", "", platformFactory = { platform }, enqueueFrame = { work -> work(); {} },
    ).apply {
        onInit()
        onResume()
        onAttachmentChanged(true)
    }

    private fun xml(value: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(value.toByteArray())).documentElement

    private class RecordingBackend : MamlViewBackend {
        val inputs = mutableListOf<MamlBackendInputs>()
        var inputOnMain = false
        var finishes = 0
        override fun createView(context: Context) = View(context)
        override fun onInputsChanged(inputs: MamlBackendInputs) {
            inputOnMain = Looper.myLooper() == Looper.getMainLooper()
            this.inputs += inputs
        }

        override fun onFinish() {
            finishes++
        }
    }

    private class FrameQueue {
        val pending = mutableListOf<() -> Unit>()
        fun enqueue(work: () -> Unit): () -> Unit {
            pending.add(work)
            return { pending.remove(work); Unit }
        }

        fun runNext() = pending.removeAt(0).invoke()
    }

    private class FakeBackendPlatform : AppWidgetBackendPlatform {
        val widgets = FakeWidgets()
        private val sessions = WidgetSessions(widgets)
        val opens = mutableListOf<Pair<String, String>>()
        var hostViews = 0
        var sizeUpdates = 0
        var content: RemoteViews? = null
        var onSizeUpdate: (() -> Unit)? = null
        override fun open(key: String, provider: String, changed: () -> Unit): WidgetSession {
            opens += key to provider
            return sessions.open(key, provider, changed)
        }

        override fun createHostView(
            context: Context,
            id: Int,
            provider: String
        ): AppWidgetHostView {
            hostViews++
            return AppWidgetHostView(context).apply {
                content?.let { remote ->
                    val info = AppWidgetProviderInfo().apply {
                        this.provider = ComponentName(context.packageName, "TestProvider")
                    }
                    // Framework HostView expects the package-manager-populated hidden
                    // ActivityInfo too; supply it in this no-device binding fixture.
                    AppWidgetProviderInfo::class.java.getDeclaredField("providerInfo").apply {
                        isAccessible = true
                    }.set(info, android.content.pm.ActivityInfo().apply {
                        packageName = context.packageName
                        name = "TestProvider"
                        applicationInfo = context.applicationInfo
                        nonLocalizedLabel = "Test Widget"
                    })
                    setAppWidget(id, info)
                    updateAppWidget(remote)
                }
            }
        }

        override fun updateSize(view: AppWidgetHostView, id: Int, width: Int, height: Int) {
            sizeUpdates++
            onSizeUpdate?.invoke()
        }

        override fun releaseHostView(id: Int, view: AppWidgetHostView) {}
        override fun invalidateSize(id: Int) {}
    }

    private class FakeWidgets : WidgetPlatform {
        val saved = mutableMapOf<String, WidgetBinding>()
        var allocations = 0
        var providerChecks = 0
        var available: Boolean? = null
        var allowBind = false
        private val bound = mutableMapOf<Int, String>()
        override fun read(key: String) = saved[key]
        override fun write(key: String, binding: WidgetBinding?) {
            if (binding == null) saved.remove(key) else saved[key] = binding
        }

        override fun ownedIds() = saved.values.map { it.id }.toSet()
        override fun providerOf(id: Int) =
            bound[id] ?: saved.values.firstOrNull { it.id == id && it.complete }?.provider

        override fun providerExists(provider: String): Boolean {
            providerChecks++
            return available ?: saved.values.any { it.provider == provider }
        }

        override fun needsConfiguration(provider: String) = false
        override fun allocate(): Int {
            allocations++; return 99
        }

        override fun delete(id: Int) {}
        override fun bind(id: Int, provider: String): Boolean {
            if (allowBind) bound[id] = provider
            return allowBind
        }

        override fun request(
            kind: WidgetRequest,
            id: Int,
            provider: String,
            result: (WidgetResult) -> Unit
        ): (() -> Unit)? = null

        override fun startListening() {}
        override fun stopListening() {}
    }
}
