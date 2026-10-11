package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWidgetPreviewSchedulerTest {
    private class Harness(maxQueued: Int = 256, budget: Long = 1024) {
        var generation = 1L
        val owner = ArrayDeque<() -> Unit>()
        val worker = ArrayDeque<() -> Unit>()
        val frames = ArrayDeque<() -> Unit>()
        val prepared = mutableListOf<String>()
        val rendered = mutableListOf<String>()
        val stored = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        val scheduler = AppWidgetPreviewScheduler<String, String>(
            { owner.addLast(it) }, { frames.addLast(it) }, { generation },
            { key, _, _ -> stored += key }, maxQueued, budget
        )

        fun subscribe(key: String, priority: Int = 0, subscriber: String = key, bytes: Long = 16) =
            scheduler.subscribe(
                key,
                generation,
                priority,
                bytes,
                { worker.addLast(it) },
                {
                    prepared += key
                    AppWidgetPreviewStage({ rendered += key; key })
                },
                { result -> delivered += if (result.isSuccess) subscriber else "$subscriber:failed" })

        fun owner() {
            while (owner.isNotEmpty()) owner.removeFirst().invoke()
        }

        fun frame() {
            frames.removeFirst().invoke(); owner()
        }

        fun drain() {
            repeat(512) {
                owner()
                if (worker.isNotEmpty()) worker.removeFirst().invoke()
                owner()
                if (frames.isNotEmpty()) frame()
                if (owner.isEmpty() && worker.isEmpty() && frames.isEmpty()) return
            }
            error("Scheduler did not become idle")
        }
    }

    @Test
    fun visibleOvertakesOffscreenPageAndEachFrameStartsOneRender() {
        val h = Harness()
        repeat(6) { h.subscribe("off$it", 2) }
        h.subscribe("visible", 0)
        h.owner()
        assertEquals(1, h.worker.size)
        h.worker.removeFirst().invoke(); h.owner()
        assertEquals(1, h.frames.size)
        assertTrue(h.rendered.isEmpty())
        h.frame()
        assertEquals(listOf("visible"), h.rendered)
        assertEquals(listOf("visible"), h.delivered)
        h.drain()
        assertEquals(7, h.rendered.size)
    }

    @Test
    fun sameKeyAcrossSubscribersPreparesAndRendersOnceAndPromotesPendingFlight() {
        val h = Harness()
        h.subscribe("other", 1)
        h.subscribe("shared", 2, "prefetch")
        h.subscribe("shared", 0, "visible")
        h.drain()
        assertEquals(listOf("shared", "other"), h.prepared)
        assertEquals(listOf("shared", "other"), h.rendered)
        assertEquals(listOf("prefetch", "visible", "other"), h.delivered)
        assertEquals(listOf("shared", "other"), h.stored)
    }

    @Test
    fun cancellationDetachesOnlySubscriberAndPendingOrphanNeverPrepares() {
        val h = Harness()
        val cancelled = h.subscribe("shared", 0, "cancelled")
        h.subscribe("shared", 1, "remaining")
        val orphan = h.subscribe("orphan", 2)
        cancelled.close(); cancelled.close(); orphan.close()
        h.drain()
        assertEquals(listOf("shared"), h.prepared)
        assertEquals(listOf("remaining"), h.delivered)
        assertEquals(0, h.scheduler.queuedCount)
        assertEquals(0L, h.scheduler.inFlightBytes)
    }

    @Test
    fun orphanedDispatchedButUnstartedWorkerDoesNotPrepareOrWarmCache() {
        val h = Harness()
        val handle = h.subscribe("cancelled")
        h.owner()
        handle.close()
        h.subscribe("next")
        h.drain()
        assertEquals(listOf("next"), h.prepared)
        assertEquals(listOf("next"), h.stored)
    }

    @Test
    fun startedOrphanMayWarmOneCurrentGenerationResultButNeverPublishes() {
        val h = Harness()
        val handle = h.subscribe("started")
        h.owner(); h.worker.removeFirst().invoke(); h.owner()
        handle.close()
        h.drain()
        assertEquals(listOf("started"), h.stored)
        assertTrue(h.delivered.isEmpty())
    }

    @Test
    fun cancelledPromotionRestoresRemainingSubscribersPriority() {
        val h = Harness()
        h.subscribe("shared", 2, "prefetch")
        val visible = h.subscribe("shared", 0, "visible")
        h.subscribe("neighbor", 1)
        visible.close()
        h.drain()
        assertEquals(listOf("neighbor", "shared"), h.rendered)
        assertFalse(h.delivered.contains("visible"))
    }

    @Test
    fun generationChangeAfterPrepareRejectsStaleFrameCacheAndDelivery() {
        val h = Harness()
        h.subscribe("same")
        h.owner(); h.worker.removeFirst().invoke(); h.owner()
        assertEquals(16L, h.scheduler.inFlightBytes)
        h.generation++
        h.scheduler.invalidate()
        h.subscribe("same", subscriber = "new")
        assertEquals(
            16L,
            h.scheduler.inFlightBytes
        ) // Old prepared references still own their reservation.
        h.drain()
        assertEquals(listOf("same"), h.rendered)
        assertEquals(listOf("same"), h.stored)
        assertEquals(listOf("new"), h.delivered)
        assertEquals(0L, h.scheduler.inFlightBytes)
    }

    @Test
    fun generationChangeBeforeWorkerStartsReleasesReservationWithoutPreparingOldTask() {
        val h = Harness()
        h.subscribe("old")
        h.owner()
        h.generation++
        h.scheduler.invalidate()
        assertEquals(0L, h.scheduler.inFlightBytes)
        h.subscribe("new")
        h.drain()
        assertEquals(listOf("new"), h.prepared)
        assertEquals(listOf("new"), h.stored)
    }

    @Test
    fun boundedQueueAdmitsVisibleByDisplacingOffscreenAndRejectsOversizeReservation() {
        val h = Harness(maxQueued = 2, budget = 32)
        h.subscribe("offA", 2); h.subscribe("offB", 2)
        h.subscribe("visible", 0)
        h.subscribe("tooLarge", bytes = 33)
        assertEquals(2, h.scheduler.queuedCount)
        assertTrue(h.delivered.contains("tooLarge:failed"))
        h.drain()
        assertEquals("visible", h.rendered.first())
        assertEquals(2, h.rendered.size)
        assertEquals(1, h.delivered.count { it.startsWith("off") && it.endsWith(":failed") })
    }

    @Test
    fun fallbackIsPreparedOnWorkerOnlyAfterFailureAndRendersOnSeparateFrame() {
        val h = Harness()
        var fallbackPrepared = 0
        h.scheduler.subscribe("staged", h.generation, 0, 16, { h.worker.addLast(it) }, {
            AppWidgetPreviewStage({ "failed" }, {
                fallbackPrepared++
                AppWidgetPreviewStage({ "success" })
            })
        }, { h.delivered += it.getOrThrow() })
        h.owner(); h.worker.removeFirst().invoke(); h.owner()
        assertEquals(0, fallbackPrepared)
        h.frame()
        assertEquals(0, fallbackPrepared)
        assertTrue(h.delivered.isEmpty())
        h.drain()
        assertEquals(1, fallbackPrepared)
        assertEquals(listOf("success"), h.delivered)
        assertEquals(listOf("staged"), h.stored)
    }

    @Test
    fun failureInOneSubscriberDoesNotBlockOthersOrNextFlight() {
        val h = Harness()
        h.scheduler.subscribe(
            "shared", h.generation, 0, 16, { h.worker.addLast(it) },
            { AppWidgetPreviewStage({ "shared" }) }, { error("broken subscriber") })
        h.subscribe("shared", subscriber = "healthy")
        h.subscribe("next")
        h.drain()
        assertEquals(listOf("healthy", "next"), h.delivered)
    }
}
