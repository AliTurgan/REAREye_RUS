package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.os.Looper
import org.junit.Assert.*
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
class MamlDataBinderSessionTest {
    private class Sink : MamlDataSink {
        val publications = mutableListOf<MamlDataPublication>()
        val queued = mutableListOf<() -> Unit>()
        var defer = false
        var clears = 0
        var clearThread: Thread? = null
        override fun enqueue(block: () -> Unit) {
            if (defer) queued.add(block) else block()
        }

        override fun publish(publication: MamlDataPublication) {
            publications.add(publication)
        }

        override fun clear() {
            clears++; clearThread = Thread.currentThread(); publications.clear()
        }

        fun flush() {
            queued.toList().also { queued.clear() }.forEach { it() }
        }
    }

    private class Source : MamlDataSource {
        val requests = mutableListOf<MamlDataRequest>()
        val callbacks = mutableListOf<(MamlDataResult) -> Unit>()
        val cancellations = mutableListOf<Int>()
        val events = mutableListOf<String>()
        val watchers = mutableListOf<() -> Unit>()
        var invalidate: (() -> Unit)? = null
        var resumes = 0
        var pauses = 0
        var closes = 0
        var unsubscribes = 0
        var synchronous = false
        var throwQuery = false
        var throwClose = false
        private fun main() {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
        }

        override fun query(
            request: MamlDataRequest,
            result: (MamlDataResult) -> Unit
        ): MamlDataHandle {
            main()
            if (throwQuery) error("broken query")
            val index = requests.size
            requests.add(request)
            callbacks.add(result)
            cancellations.add(0)
            if (synchronous) result(success(request.string("label")))
            return MamlDataHandle {
                main()
                cancellations[index]++
                if (throwClose) error("broken handle")
            }
        }

        override fun subscribeInvalidations(invalidate: () -> Unit): MamlDataHandle {
            main()
            this.invalidate = invalidate
            watchers.add(invalidate)
            return MamlDataHandle { main(); unsubscribes++; this.invalidate = null }
        }

        override fun onResume() {
            main(); resumes++; events.add("resume")
        }

        override fun onPause() {
            main(); pauses++; events.add("pause")
        }

        override fun close() {
            main(); closes++; events.add("close"); if (throwClose) error("broken close")
        }
    }

    private val schema = MamlDataSchema(listOf(MamlDataField("label", MamlDataType.STRING)))
    private fun session(sink: Sink, factory: () -> Source): MamlDataBinderSession =
        MamlDataBinderSession(
            "label",
            MamlDataSourceDefinition("LabelBinder", schema, emptyList(), MamlDataSourceFactory {
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                factory()
            }),
            RuntimeEnvironment.getApplication(), sink,
        )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun request(label: String = "hello") = MamlDataRequest(mapOf("label" to label))
    private fun ready(source: Source, sink: Sink = Sink()): Pair<MamlDataBinderSession, Sink> {
        val session = session(sink) { source }
        session.init(); idle()
        return session to sink
    }

    @Test
    fun initOnlyPublishesEmptyAndNeverImplementsQueryAtStart() {
        val source = Source()
        val (session, sink) = ready(source)
        session.init(); idle()
        assertTrue(source.requests.isEmpty())
        assertEquals(1, source.resumes)
        assertEquals(1, sink.publications.size)
        val empty = sink.publications.single()
        assertEquals("", empty.snapshot.values["label"])
        assertEquals(0, empty.snapshot.count)
        assertEquals(0L, empty.revision)
        assertFalse(empty.loading)
        assertFalse(empty.completed)
    }

    @Test
    fun ordinaryLabelQueryHasLoadingThenCompletedFullSnapshot() {
        val source = Source()
        val (session, sink) = ready(source)
        session.startQuery(request()); idle()
        val loading = sink.publications.last()
        assertTrue(loading.loading)
        assertFalse(loading.completed)
        assertEquals(0L, loading.revision)
        Thread { source.callbacks.single()(success("worker")) }.apply { start(); join() }
        idle()
        val result = sink.publications.last()
        assertEquals("worker", result.snapshot.values["label"])
        assertEquals(1, result.snapshot.count)
        assertEquals(1L, result.revision)
        assertTrue(result.completed)
        assertFalse(result.loading)
        assertEquals(listOf(1), source.cancellations)
    }

    @Test
    fun synchronousCallbackClosesCorrectHandleExactlyOnce() {
        val source = Source().apply { synchronous = true }
        val (session, sink) = ready(source)
        session.startQuery(request("one")); idle()
        session.startQuery(request("two")); idle()
        source.callbacks.first()(success("late")); idle()
        session.finish(); session.finish(); idle()
        assertEquals(listOf(1, 1), source.cancellations)
        assertEquals(1, source.closes)
        assertEquals(1, sink.clears)
    }

    @Test
    fun replacingParametersRejectsOldCallbacksImmediately() {
        val source = Source()
        val (session, sink) = ready(source)
        session.startQuery(request("old")); idle()
        session.startQuery(request("new"))
        source.callbacks.first()(success("old"))
        idle()
        assertEquals(listOf("old", "new"), source.requests.map { it.string("label") })
        source.callbacks.last()(success("new")); idle()
        assertEquals("new", sink.publications.last().snapshot.values["label"])
        assertEquals(1L, sink.publications.last().revision)
        assertEquals(listOf(1, 1), source.cancellations)
    }

    @Test
    fun pauseRecordsLatestParametersAndResumeRefreshesOnlyOncePerTransition() {
        val source = Source()
        val (session, sink) = ready(source)
        session.startQuery(request("first")); idle()
        session.pause(); session.pause(); idle()
        session.tick(request("latest")); session.startQuery(request("newest")); idle()
        source.callbacks.first()(success("discard")); idle()
        assertEquals(1, source.requests.size)
        session.resume(); session.resume(); idle()
        assertEquals("newest", source.requests.last().string("label"))
        assertEquals(2, source.resumes)
        assertEquals(1, source.pauses)
        assertEquals(1, source.unsubscribes)
        assertFalse(sink.publications.any { it.snapshot.values["label"] == "discard" })
    }

    @Test
    fun tickDoesNotPollAndInvalidationsCoalesceOnlyAfterFirstRequest() {
        val source = Source()
        val (session, _) = ready(source)
        session.tick(request("before")); idle()
        repeat(10) { source.invalidate!!() }; idle()
        assertTrue(source.requests.isEmpty())
        session.startQuery(request()); idle()
        repeat(10) { session.tick(request()) }; idle()
        assertEquals(1, source.requests.size)
        repeat(10) { source.invalidate!!() }; idle()
        assertEquals(2, source.requests.size)
        session.tick(request("changed")); idle()
        assertEquals(3, source.requests.size)
    }

    @Test
    fun pausedSubscriptionEventsCannotRefreshResumedGeneration() {
        val source = Source()
        val (session, _) = ready(source)
        session.startQuery(request()); idle()
        val oldWatcher = source.watchers.single()
        session.pause(); session.resume()
        oldWatcher(); idle()
        assertEquals(2, source.requests.size)
        oldWatcher(); idle()
        assertEquals(2, source.requests.size)
    }

    @Test
    fun finishInvalidatesCallbacksAndClearsOnLifecycleCallerBeforeMainCleanup() {
        val source = Source()
        val (session, sink) = ready(source)
        session.startQuery(request()); idle()
        val worker = Thread { session.finish() }.apply { start(); join() }
        assertEquals(1, sink.clears)
        assertSame(worker, sink.clearThread)
        assertEquals(0, source.closes)
        source.callbacks.single()(success("stale")); idle()
        assertTrue(sink.publications.isEmpty())
        assertEquals(1, source.closes)
        assertEquals(listOf(1), source.cancellations)
    }

    @Test
    fun workerFinishThenImmediateMainInitHonorsCleanupFifo() {
        val sources = mutableListOf<Source>()
        val sink = Sink()
        val session = session(sink) { Source().also(sources::add) }
        session.init(); idle()
        session.startQuery(request()); idle()
        Thread { session.finish() }.apply { start(); join() }
        session.init()
        session.startQuery(request("fresh")); idle()
        assertEquals(2, sources.size)
        assertEquals(listOf("resume", "pause", "close"), sources.first().events)
        assertEquals(0, sources.last().closes)
        sources.first().callbacks.single()(success("stale"))
        sources.last().callbacks.single()(success("fresh")); idle()
        assertEquals("fresh", sink.publications.last().snapshot.values["label"])
        assertEquals(1L, sink.publications.last().revision)
    }

    @Test
    fun independentInstancesNeverCloseEachOthersResources() {
        val first = Source()
        val second = Source()
        val (a, _) = ready(first)
        val (b, _) = ready(second)
        a.startQuery(request()); b.startQuery(request()); idle()
        a.finish(); idle()
        assertEquals(1, first.closes)
        assertEquals(0, second.closes)
        assertEquals(listOf(0), second.cancellations)
        assertNotNull(second.invalidate)
        b.finish(); idle()
    }

    @Test
    fun rootQueuedPublicationsRecheckFinishAndReinitialization() {
        val sources = mutableListOf<Source>()
        val sink = Sink().apply { defer = true }
        val session = session(sink) { Source().also(sources::add) }
        session.init(); session.startQuery(request()); idle()
        sources.single().callbacks.single()(success("stale")); idle()
        session.finish()
        session.init(); idle()
        sink.flush()
        assertEquals(1, sink.publications.size)
        assertEquals(0L, sink.publications.single().revision)
        assertEquals("", sink.publications.single().snapshot.values["label"])
    }

    @Test
    fun rootQueuedPublicationRejectsOldParameters() {
        val source = Source()
        val sink = Sink().apply { defer = true }
        val (session, _) = ready(source, sink)
        session.startQuery(request("old")); idle()
        source.callbacks.single()(success("old")); idle()
        session.startQuery(request("new"))
        sink.flush()
        assertTrue(sink.publications.isEmpty())
        idle()
        source.callbacks.last()(success("new")); idle(); sink.flush()
        assertEquals("new", sink.publications.last().snapshot.values["label"])
    }

    @Test
    fun failuresClearOldPageAndIncrementRevisionOnlyOnCompletion() {
        val source = Source()
        val (session, sink) = ready(source)
        session.startQuery(request()); idle()
        source.callbacks.last()(success("old page")); idle()
        session.startQuery(request()); idle()
        assertEquals(1L, sink.publications.last().revision)
        source.callbacks.last()(MamlDataResult.Failure("network")); idle()
        val failed = sink.publications.last()
        assertEquals("", failed.snapshot.values["label"])
        assertEquals(0, failed.snapshot.count)
        assertEquals("network", failed.error)
        assertEquals(2L, failed.revision)
        assertTrue(failed.completed)
        session.reportFailure("bad parameters"); idle()
        assertEquals(3L, sink.publications.last().revision)
        assertEquals("bad parameters", sink.publications.last().error)
    }

    @Test
    fun wrongSchemaIsPublishedAsCompletedFailure() {
        val source = Source()
        val (session, sink) = ready(source)
        session.startQuery(request()); idle()
        source.callbacks.single()(MamlDataResult.Success(MamlDataSnapshot(mapOf("label" to 42), 1)))
        idle()
        assertTrue(sink.publications.last().completed)
        assertTrue(sink.publications.last().error.isNotEmpty())
        assertEquals("", sink.publications.last().snapshot.values["label"])
    }

    @Test
    fun queryAndCancellationExceptionsDoNotEscapeLifecycle() {
        val source = Source().apply { throwQuery = true; throwClose = true }
        val (session, sink) = ready(source)
        session.startQuery(request()); idle()
        assertEquals("broken query", sink.publications.last().error)
        source.throwQuery = false
        session.startQuery(request()); idle()
        session.pause(); idle()
        session.finish(); idle()
        assertEquals(listOf(1), source.cancellations)
        assertEquals(1, source.closes)
    }

    @Test
    fun callbacksSnapshotArraysBeforePostingToMain() {
        val source = Source()
        val sink = Sink()
        val arraySchema =
            MamlDataSchema(listOf(MamlDataField("label", MamlDataType.STRING, array = true)))
        val session = MamlDataBinderSession(
            "arrays",
            MamlDataSourceDefinition(
                "Labels", arraySchema,
                emptyList(), MamlDataSourceFactory { source }),
            RuntimeEnvironment.getApplication(),
            sink
        )
        session.init(); session.startQuery(request()); idle()
        val labels = arrayOf("original")
        source.callbacks.single()(
            MamlDataResult.Success(
                MamlDataSnapshot(
                    mapOf("label" to labels),
                    1
                )
            )
        )
        labels[0] = "mutated"
        idle()
        assertArrayEquals(
            arrayOf("original"),
            sink.publications.last().snapshot.values["label"] as Array<*>
        )
    }

    @Test
    fun repeatedHostFailuresAreDeduplicatedUntilQueryResumes() {
        val source = Source()
        val (session, sink) = ready(source)
        session.startQuery(request()); idle()
        repeat(10) { session.reportFailure("invalid input") }; idle()
        val failedCount = sink.publications.size
        val revision = sink.publications.last().revision
        repeat(10) { session.reportFailure("invalid input"); idle() }
        assertEquals(failedCount, sink.publications.size)
        assertEquals(revision, sink.publications.last().revision)
        assertEquals(listOf(1), source.cancellations)
        session.startQuery(request()); idle()
        session.reportFailure("invalid input"); idle()
        assertEquals(revision + 1, sink.publications.last().revision)
        assertEquals(listOf(1, 1), source.cancellations)
    }

    @Test
    fun invalidInputNeverRefreshesLastValidArgumentsAndSameValidTickRecovers() {
        val source = Source()
        val (session, sink) = ready(source)
        session.startQuery(request("valid")); idle()
        session.reportFailure("bad expression"); idle()
        source.invalidate!!(); idle()
        session.pause(); session.resume(); idle()
        source.invalidate!!(); idle()
        assertEquals(1, source.requests.size)
        assertEquals("bad expression", sink.publications.last().error)
        session.tick(request("valid")); idle()
        assertEquals(2, source.requests.size)
        source.callbacks.last()(success("valid")); idle()
        assertEquals("valid", sink.publications.last().snapshot.values["label"])
        assertEquals("", sink.publications.last().error)
    }

    @Test
    fun failureBeforeFirstRequestDoesNotCompleteQueryOrTriggerAutomaticRefresh() {
        val source = Source()
        val (session, sink) = ready(source)
        session.reportFailure("bad initial expression"); idle()
        assertFalse(sink.publications.last().completed)
        assertEquals(0L, sink.publications.last().revision)
        source.invalidate!!(); idle()
        session.pause(); session.resume(); idle()
        session.tick(request()); idle()
        assertTrue(source.requests.isEmpty())
        session.startQuery(request()); idle()
        source.callbacks.single()(success("recovered")); idle()
        assertTrue(sink.publications.last().completed)
        assertEquals(1L, sink.publications.last().revision)
    }

    @Test
    fun failedFactoryCanRetryOnNextRequestedQuery() {
        val source = Source()
        val sink = Sink()
        var attempts = 0
        val session =
            session(sink) { if (++attempts == 1) error("factory unavailable") else source }
        session.init(); idle()
        assertEquals("factory unavailable", sink.publications.last().error)
        assertFalse(sink.publications.last().completed)
        session.startQuery(request()); idle()
        assertEquals(2, attempts)
        assertEquals(1, source.resumes)
        assertEquals(1, source.requests.size)
        source.callbacks.single()(success("recovered")); idle()
        assertEquals("recovered", sink.publications.last().snapshot.values["label"])
        session.finish(); idle()
        assertEquals(1, source.closes)
    }

    @Test
    fun failureReportedWhilePausedIsPublishedOnResumeWithoutQueryingStaleInput() {
        val source = Source()
        val (session, sink) = ready(source)
        session.startQuery(request()); idle()
        source.callbacks.single()(success("old page")); idle()
        session.pause(); idle()
        session.reportFailure("invalid while paused"); idle()
        session.resume(); idle()
        assertEquals(1, source.requests.size)
        assertEquals("invalid while paused", sink.publications.last().error)
        assertEquals("", sink.publications.last().snapshot.values["label"])
        assertEquals(2L, sink.publications.last().revision)
        val revision = sink.publications.last().revision
        session.pause(); session.resume(); idle()
        assertEquals(revision, sink.publications.last().revision)
        assertFalse(sink.publications.last().completed)
    }

    @Test
    fun workerPauseThenImmediateMainResumePreservesTransitionOrder() {
        val source = Source()
        val (session, _) = ready(source)
        session.startQuery(request()); idle()
        Thread { session.pause() }.apply { start(); join() }
        session.resume(); idle()
        assertEquals(listOf("resume", "pause", "resume"), source.events)
        assertEquals(2, source.requests.size)
        assertEquals(1, source.unsubscribes)
    }

    @Test
    fun factoryRetryAfterPauseAndResumeActivatesNewSource() {
        val source = Source()
        var attempts = 0
        val session = session(Sink()) { if (++attempts == 1) error("unavailable") else source }
        session.init(); idle()
        session.pause(); session.startQuery(request()); idle()
        session.resume(); idle()
        assertEquals(2, attempts)
        assertEquals(1, source.resumes)
        assertNotNull(source.invalidate)
        assertEquals(1, source.requests.size)
    }

    @Test
    fun firstRequestedQueryFailureRecoversViaValidTickWithoutUsingOldParameters() {
        val source = Source()
        val (session, sink) = ready(source)
        session.reportFailure("invalid first query", queryRequested = true); idle()
        assertTrue(sink.publications.last().completed)
        source.invalidate!!(); idle()
        session.pause(); session.resume(); idle()
        assertTrue(source.requests.isEmpty())
        session.tick(request("valid now")); idle()
        assertEquals("valid now", source.requests.single().string("label"))
        source.callbacks.single()(success("valid now")); idle()
        assertEquals("valid now", sink.publications.last().snapshot.values["label"])
        assertTrue(sink.publications.last().completed)
    }

    @Test
    fun firstExplicitFailedRequestIsNotDeduplicatedAgainstUnrequestedTickFailure() {
        val source = Source()
        val (session, sink) = ready(source)
        session.reportFailure("same error"); idle()
        assertFalse(sink.publications.last().completed)
        session.reportFailure("same error", queryRequested = true); idle()
        assertTrue(sink.publications.last().completed)
        assertEquals(1L, sink.publications.last().revision)
        val count = sink.publications.size
        session.reportFailure("same error", queryRequested = true); idle()
        assertEquals(count, sink.publications.size)
    }

    companion object {
        private fun success(label: String) =
            MamlDataResult.Success(MamlDataSnapshot(mapOf("label" to label), 1))
    }
}
