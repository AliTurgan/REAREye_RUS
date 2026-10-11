package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class MamlElementSessionTest {
    private class Backend : MamlViewBackend {
        var init = 0
        var resume = 0
        var finish = 0
        var createdOnMain = false
        var inputChanges = 0
        var lastInput: MamlBackendInputs? = null
        lateinit var content: CountingView
        override fun createView(context: Context): View {
            createdOnMain = Looper.myLooper() == Looper.getMainLooper()
            return CountingView(context).also { content = it }
        }

        override fun onInputsChanged(inputs: MamlBackendInputs) {
            inputChanges++; lastInput = inputs
        }

        override fun onInit() {
            init++
        }

        override fun onResume() {
            resume++
        }

        override fun onFinish() {
            finish++
        }
    }

    @Test
    fun initIsIdempotentAndFinishThenInitCreatesNewBackend() {
        val backends = mutableListOf<Backend>()
        val session = session { Backend().also(backends::add) }
        var carrierInitializations = 0
        session.resume()
        session.init { carrierInitializations++ }
        session.init { carrierInitializations++ }
        assertEquals(1, carrierInitializations)
        assertEquals(1, backends.single().init)
        assertEquals(1, backends.single().resume)
        assertTrue(backends.single().createdOnMain)
        session.finish()
        session.finish()
        assertNull(session.view)
        assertEquals(1, backends.single().finish)
        session.init { carrierInitializations++ }
        assertEquals(2, backends.size)
        assertEquals(0, backends.last().resume)
        assertEquals(2, carrierInitializations)
    }

    @Test
    fun backgroundFinishIsDrainedBeforeImmediateMainThreadReinit() {
        val backends = mutableListOf<Backend>()
        val session = session { Backend().also(backends::add) }
        session.init {}
        Thread { session.finish() }.apply { start(); join() }
        // No idle() here: reinit must not overtake queued old-generation cleanup.
        session.init {}
        assertEquals(2, backends.size)
        assertEquals(1, backends.first().finish)
        assertNotNull(session.view)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(session.view)
        assertEquals(0, backends.last().finish)
    }

    @Test
    fun failedNativeInitializationReleasesBackendAndCanRetry() {
        val backends = mutableListOf<Backend>()
        val session = session { Backend().also(backends::add) }
        session.init { error("Native ViewManager failed") }
        assertNull(session.view)
        assertEquals(1, backends.single().finish)
        session.init {}
        assertEquals(2, backends.size)
        assertNotNull(session.view)
        assertTrue(session.canTick())
        session.finish()
        assertFalse(session.canTick())
    }

    @Test
    fun queuedInitCannotRunAfterFinish() {
        var creations = 0
        val session = session { creations++; Backend() }
        Thread { session.init {} }.apply { start(); join() }
        session.finish()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, creations)
        assertNull(session.view)
    }

    @Test
    fun pendingGeometryIsCoalescedAndCannotOverwriteReinitializedView() {
        val session = session { Backend() }
        session.init {}
        val params = ViewGroup.LayoutParams(-1, -1)
        Thread {
            session.geometry(geometry(10f), params)
            session.geometry(geometry(20f), params)
        }.apply { start(); join() }
        assertEquals(0f, session.view!!.x, 0f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(20f, session.view!!.x, 0f)
        assertEquals(200, params.width)
        Thread { session.geometry(geometry(99f), params) }.apply { start(); join() }
        session.finish()
        session.init {}
        val fresh = ViewGroup.LayoutParams(-1, -1)
        session.geometry(geometry(30f), fresh)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(30f, session.view!!.x, 0f)
    }

    @Test
    fun visibilityBeforeInitIsAppliedToCreatedContainer() {
        val session = session { Backend() }
        session.visibility(false)
        session.init {}
        assertEquals(View.INVISIBLE, session.view!!.visibility)
    }

    @Test
    fun thousandStableTicksShareOnePostThenProduceNoMoreNotificationsLayoutsOrOutlineDraws() {
        val backend = Backend()
        val handler = CountingHandler()
        var outlineApplications = 0
        var input = MamlBackendInputs("one", "example/example.Provider", true)
        val session = MamlElementSession(
            RuntimeEnvironment.getApplication(), { backend }, handler,
            { outlineApplications++ }, { input },
        )
        session.init {}
        val params = ViewGroup.LayoutParams(-1, -1)
        val original = geometry(10f)
        Thread {
            repeat(1000) {
                session.geometry(original, params)
                session.tickInputs()
                session.outlineChanged()
            }
        }.apply { start(); join() }
        assertEquals(1, handler.posts)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, backend.inputChanges)
        assertEquals(1, outlineApplications)
        val root = session.view!!
        root.measure(exact(200), exact(100))
        root.layout(0, 0, 200, 100)
        val measurements = backend.content.measurements
        assertFalse(root.isLayoutRequested)
        Thread {
            repeat(60) {
                assertFalse(session.geometry(original, params))
                session.tickInputs()
            }
        }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, handler.posts)
        assertEquals(1, backend.inputChanges)
        assertFalse(root.isLayoutRequested)
        assertEquals(measurements, backend.content.measurements)
        val canvas = Canvas(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888))
        repeat(60) { root.draw(canvas) }
        assertEquals(1, outlineApplications) // Draw no longer renders an empty native ElementGroup.

        val animated =
            original.copy(x = 30f, width = 350, rotation = 45f, alpha = .4f, scaleX = .9f)
        input = input.copy(key = "two")
        Thread {
            repeat(1000) {
                session.geometry(animated, params)
                session.tickInputs()
                session.outlineChanged()
            }
        }.apply { start(); join() }
        assertEquals(2, handler.posts)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, backend.inputChanges)
        assertEquals(2, outlineApplications)
        assertEquals(30f, root.x, 0f)
        assertEquals(45f, root.rotation, 0f)
        assertEquals(.4f, root.alpha, 0f)
        assertEquals(.9f, root.scaleX, 0f)
        assertEquals(350, params.width)
        assertTrue(root.isLayoutRequested)
        root.measure(exact(350), exact(100))
        root.layout(0, 0, 350, 100)
        assertEquals(measurements + 1, backend.content.measurements)
    }

    @Test
    fun revertingPendingInputAndGeometryToAppliedValuesDoesNotNotifyOrLayoutAgain() {
        val backend = Backend()
        val handler = CountingHandler()
        var input = MamlBackendInputs("one", "example/example.Provider", true)
        val session = MamlElementSession(
            RuntimeEnvironment.getApplication(),
            { backend },
            handler,
            {},
            { input })
        session.init {}
        val params = ViewGroup.LayoutParams(-1, -1)
        val original = geometry(10f)
        session.geometry(original, params)
        session.tickInputs()
        val root = session.view!!
        root.measure(exact(200), exact(100))
        root.layout(0, 0, 200, 100)
        Thread {
            session.geometry(original.copy(width = 300), params)
            input = input.copy(key = "two")
            session.tickInputs()
            session.geometry(original, params)
            input = input.copy(key = "one")
            session.tickInputs()
        }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, handler.posts)
        assertEquals(1, backend.inputChanges)
        assertFalse(root.isLayoutRequested)
        assertEquals(200, params.width)
    }

    @Test
    fun mainThreadReinitCancelsQueuedDrainAndInvalidatesOldOutlineAndGeometryCache() {
        val handler = CountingHandler()
        var outlines = 0
        val backends = mutableListOf<Backend>()
        val session = MamlElementSession(
            RuntimeEnvironment.getApplication(),
            { Backend().also(backends::add) },
            handler,
            { outlines++ })
        session.init {}
        session.geometry(geometry(10f), ViewGroup.LayoutParams(-1, -1))
        Thread {
            session.outlineChanged()
            session.geometry(geometry(99f), ViewGroup.LayoutParams(-1, -1))
            session.finish()
        }.apply { start(); join() }
        assertEquals(1, handler.posts)
        session.init {}
        assertTrue(session.geometry(geometry(10f), ViewGroup.LayoutParams(-1, -1)))
        assertEquals(10f, session.view!!.x, 0f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, outlines)
        assertEquals(1, backends.first().finish)
        assertEquals(0, backends.last().finish)
        assertEquals(
            0,
            handler.executed
        ) // Inline reinit removes, rather than later executing, the queued drain.
    }

    private class CountingView(context: Context) : View(context) {
        var measurements = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measurements++
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private class CountingHandler : Handler(Looper.getMainLooper()) {
        var posts = 0
        var executed = 0
        override fun sendMessageAtTime(msg: Message, uptimeMillis: Long): Boolean {
            posts++
            return super.sendMessageAtTime(msg, uptimeMillis)
        }

        override fun dispatchMessage(msg: Message) {
            executed++
            super.dispatchMessage(msg)
        }
    }

    @Test
    fun lateHiddenEvaluationCannotHideReopenedCarrierInSameGeneration() {
        val backend = Backend()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val shown = MamlBackendInputs("one", "example/example.Provider", true)
        val session = MamlElementSession(
            RuntimeEnvironment.getApplication(), { backend },
            Handler(Looper.getMainLooper()), {}, {
                if (Thread.currentThread().name == "old-hidden-evaluation") {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    shown.copy(visible = false)
                } else shown
            })
        session.init {}
        session.tickInputs()
        val worker = Thread({ session.tickInputs() }, "old-hidden-evaluation")
        worker.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            session.visibility(false)
            session.visibility(true)
            // No tick required: the native show edge itself invalidates old sampling.
        } finally {
            release.countDown(); worker.join(5000)
        }
        assertFalse(worker.isAlive)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(shown, backend.lastInput)
        assertEquals(View.VISIBLE, session.view!!.visibility)
    }

    @Test
    fun olderEvaluationCannotOverwriteNewerInputPublicationWithoutReinitializing() {
        val backend = Backend()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val latest = MamlBackendInputs("new", "example/example.Provider", true)
        val session = MamlElementSession(
            RuntimeEnvironment.getApplication(), { backend },
            Handler(Looper.getMainLooper()), {}, {
                if (Thread.currentThread().name == "old-input-evaluation") {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    latest.copy(key = "old", visible = false)
                } else latest
            })
        session.init {}
        val worker = Thread({ session.tickInputs() }, "old-input-evaluation")
        worker.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            session.tickInputs()
        } finally {
            release.countDown(); worker.join(5000)
        }
        assertFalse(worker.isAlive)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(latest, backend.lastInput)
        assertEquals(View.VISIBLE, session.view!!.visibility)
    }

    private fun exact(value: Int) =
        View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)

    private fun session(factory: () -> MamlViewBackend) = MamlElementSession(
        RuntimeEnvironment.getApplication(), factory, Handler(Looper.getMainLooper()), {},
    )

    private fun geometry(x: Float) = MamlViewGeometry(
        x, 5f, 200, 100, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, true, true, true,
    )
}
