package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one source without knowing anything about a particular MAML host. */
internal class MamlDataBinderSession(
    private val prefix: String,
    private val definition: MamlDataSourceDefinition,
    private val context: Context,
    private val sink: MamlDataSink,
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    private val lock = Any()

    // These gates are changed on the lifecycle caller, before queued work can publish.
    private var epoch = 0L
    private var sequence = 0L
    private var alive = false
    private var active = false
    private var requested = false
    private var latest: MamlDataRequest? = null
    private var lastFailure: String? = null
    private var queryOutstanding = false
    private var inputInvalid = false

    // State and every source/resource operation belong exclusively to main.
    private var state: State? = null

    private class Once(private val delegate: MamlDataHandle) : MamlDataHandle {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) delegate.close()
        }
    }

    private class Query(val sequence: Long) {
        var handle: Once? = null
        var completed = false
    }

    private inner class State(val epoch: Long) {
        var source: MamlDataSource? = null
        var active = false
        var subscription: Once? = null
        var query: Query? = null
        var subscriptionVersion = 0L
        var snapshot = definition.schema.empty()
        var revision = 0L
        var error: String? = null
    }

    fun init() = synchronized(lock) {
        if (alive) return@synchronized
        alive = true
        active = true
        requested = false
        latest = null
        lastFailure = null
        queryOutstanding = false
        inputInvalid = false
        val token = ++epoch
        ++sequence
        main.post {
            dispose(state)
            state = null
            if (!live(token)) return@post
            val current = State(token)
            state = current
            val serial = synchronized(lock) { sequence }
            publish(current, serial, loading = false, error = "", completed = false)
            try {
                current.source = definition.factory.create(context)
                activate(current)
            } catch (failure: Exception) {
                fail(
                    current,
                    serial,
                    failure.message ?: "Source creation failed",
                    completed = false
                )
            }
        }
        Unit
    }

    fun startQuery(request: MamlDataRequest) = synchronized(lock) {
        if (!alive) return@synchronized
        latest = copyRequest(request)
        requested = true
        inputInvalid = false
        lastFailure = null
        queryOutstanding = active
        val token = epoch
        val serial = ++sequence
        val captured = latest!!
        main.post {
            val current = stateFor(token) ?: return@post
            if (valid(token, serial)) query(current, captured, serial)
        }
        Unit
    }

    /** Tick only observes evaluated parameters; it never polls a not-yet-requested source. */
    fun tick(request: MamlDataRequest) = synchronized(lock) {
        if (!alive || (!inputInvalid && requestsEqual(latest, request))) return@synchronized
        latest = copyRequest(request)
        inputInvalid = false
        val token = epoch
        val serial = ++sequence
        if (requested) {
            lastFailure = null
            queryOutstanding = active
            val captured = latest!!
            main.post {
                val current = stateFor(token) ?: return@post
                if (valid(token, serial)) query(current, captured, serial)
            }
        }
        Unit
    }

    fun pause() = synchronized(lock) {
        if (!alive || !active) return@synchronized
        active = false
        queryOutstanding = false
        ++sequence
        val token = epoch
        main.post { stateFor(token)?.let(::deactivate) }
        Unit
    }

    fun resume() = synchronized(lock) {
        if (!alive || active) return@synchronized
        active = true
        if (requested && !inputInvalid) {
            lastFailure = null
            queryOutstanding = true
        }
        val token = epoch
        val serial = ++sequence
        main.post {
            val current = stateFor(token) ?: return@post
            activate(current)
            val request = synchronized(lock) { if (requested && !inputInvalid) latest else null }
            val failure = synchronized(lock) { if (inputInvalid) lastFailure else null }
            if (valid(token, serial)) {
                if (failure != null) {
                    if (current.error != failure) fail(current, serial, failure)
                    else publish(
                        current,
                        serial,
                        loading = false,
                        error = failure,
                        completed = false
                    )
                } else if (request != null) query(current, request, serial)
            }
        }
        Unit
    }

    fun finish() = synchronized(lock) {
        if (!alive) return@synchronized
        val token = epoch
        alive = false
        active = false
        requested = false
        latest = null
        lastFailure = null
        queryOutstanding = false
        inputInvalid = false
        ++epoch
        ++sequence
        // clear deliberately runs on the original host lifecycle thread, not Android main.
        safely { sink.clear() }
        main.post {
            stateFor(token)?.let {
                dispose(it)
                if (state === it) state = null
            }
        }
        Unit
    }

    fun reportFailure(message: String, queryRequested: Boolean = false) = synchronized(lock) {
        if (!alive) return@synchronized
        val firstRequest = queryRequested && !requested
        if (queryRequested) requested = true
        inputInvalid = true
        if (!firstRequest && !queryOutstanding && lastFailure == message) return@synchronized
        lastFailure = message
        queryOutstanding = false
        val token = epoch
        val serial = ++sequence
        main.post {
            val current = stateFor(token) ?: return@post
            if (valid(token, serial)) fail(current, serial, message)
        }
        Unit
    }

    private fun query(current: State, request: MamlDataRequest, serial: Long) {
        synchronized(lock) {
            if (!valid(current.epoch, serial)) return
            lastFailure = null
            queryOutstanding = true
        }
        cancelQuery(current)
        if (!valid(current.epoch, serial)) return
        current.error = null
        publish(current, serial, loading = true, error = "", completed = false)
        val source = try {
            current.source ?: definition.factory.create(context).also {
                current.source = it
                activate(current)
            }
        } catch (failure: Exception) {
            fail(current, serial, failure.message ?: "Source creation failed")
            return
        }
        val pending = Query(serial)
        current.query = pending
        try {
            val handle = source.query(request) { result ->
                // Validate and copy at callback time, before the source can reuse its arrays.
                val normalized = try {
                    when (result) {
                        is MamlDataResult.Success -> MamlDataResult.Success(
                            definition.schema.validate(
                                result.snapshot
                            )
                        )

                        is MamlDataResult.Failure -> result
                    }
                } catch (failure: Exception) {
                    MamlDataResult.Failure(failure.message ?: "Invalid source snapshot")
                }
                // Always enqueue, including synchronous callbacks: the returned handle is assigned first.
                main.post {
                    if (pending.completed || current.query !== pending || !valid(
                            current.epoch,
                            serial
                        )
                    ) return@post
                    pending.completed = true
                    current.query = null
                    safely { pending.handle?.close() }
                    when (normalized) {
                        is MamlDataResult.Success -> {
                            synchronized(lock) {
                                if (valid(current.epoch, serial)) {
                                    queryOutstanding = false
                                    lastFailure = null
                                    current.snapshot = normalized.snapshot
                                    ++current.revision
                                    publish(
                                        current,
                                        serial,
                                        loading = false,
                                        error = "",
                                        completed = true
                                    )
                                }
                            }
                        }

                        is MamlDataResult.Failure -> fail(current, serial, normalized.message)
                    }
                }
            }
            pending.handle = Once(handle)
        } catch (failure: Exception) {
            pending.completed = true
            if (current.query === pending) current.query = null
            fail(current, serial, failure.message ?: "Source query failed")
        }
    }

    private fun fail(current: State, serial: Long, message: String, completed: Boolean? = null) {
        cancelQuery(current)
        synchronized(lock) {
            if (!valid(current.epoch, serial)) return
            queryOutstanding = false
            lastFailure = message
            current.snapshot = definition.schema.empty()
            current.error = message
            // Invalid input before the first host request must not fire dependency triggers.
            val completion = completed ?: requested
            if (completion) ++current.revision
            publish(current, serial, loading = false, error = message, completed = completion)
        }
    }

    private fun activate(current: State) {
        if (current.active) return
        val source = current.source ?: return
        current.active = true
        safely { source.onResume() }
        val version = ++current.subscriptionVersion
        val queued = AtomicBoolean()
        try {
            current.subscription = Once(source.subscribeInvalidations {
                if (!queued.compareAndSet(false, true)) return@subscribeInvalidations
                main.post {
                    queued.set(false)
                    synchronized(lock) {
                        if (!alive || !active || !requested || inputInvalid || epoch != current.epoch || !current.active || current.subscriptionVersion != version) return@synchronized
                        val request = latest ?: return@synchronized
                        query(current, request, ++sequence)
                    }
                }
            })
        } catch (failure: Exception) {
            log(failure)
        }
    }

    private fun deactivate(current: State) {
        ++current.subscriptionVersion
        cancelQuery(current)
        safely { current.subscription?.close() }
        current.subscription = null
        if (current.active) {
            current.active = false
            safely { current.source?.onPause() }
        }
    }

    private fun dispose(current: State?) {
        if (current == null) return
        deactivate(current)
        safely { current.source?.close() }
        current.source = null
    }

    private fun cancelQuery(current: State) {
        val pending = current.query ?: return
        current.query = null
        pending.completed = true
        safely { pending.handle?.close() }
    }

    private fun publish(
        current: State,
        serial: Long,
        loading: Boolean,
        error: String,
        completed: Boolean
    ) {
        val publication =
            MamlDataPublication(current.snapshot, loading, error, current.revision, completed)
        safely {
            sink.enqueue {
                synchronized(lock) {
                    if (alive && active && epoch == current.epoch && sequence == serial) safely {
                        sink.publish(
                            publication
                        )
                    }
                }
            }
        }
    }

    private fun stateFor(token: Long): State? = state?.takeIf { it.epoch == token }
    private fun live(token: Long) = synchronized(lock) { alive && epoch == token }
    private fun valid(token: Long, serial: Long) = synchronized(lock) {
        alive && active && epoch == token && sequence == serial
    }

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (failure: Exception) {
            log(failure)
        }
    }

    private fun log(failure: Exception) {
        Log.w("MamlDataBinder", "$prefix: ${failure.message}", failure)
    }

    private fun copyRequest(request: MamlDataRequest) =
        MamlDataRequest(request.values.mapValues { (_, value) ->
            when (value) {
                is Array<*> -> value.copyOf()
                is DoubleArray -> value.copyOf()
                is FloatArray -> value.copyOf()
                is IntArray -> value.copyOf()
                is LongArray -> value.copyOf()
                else -> value
            }
        })

    private fun requestsEqual(left: MamlDataRequest?, right: MamlDataRequest): Boolean {
        if (left == null || left.values.keys != right.values.keys) return false
        return left.values.all { (name, value) ->
            java.util.Objects.deepEquals(
                value,
                right.values[name]
            )
        }
    }
}
