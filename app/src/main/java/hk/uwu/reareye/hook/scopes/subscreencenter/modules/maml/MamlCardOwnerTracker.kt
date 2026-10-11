package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Capture real card creation, not q2.c's localized-title cache or a preview's declared localId. */
internal class MamlCardOwnerTracker(
    private val localId: (Any) -> String,
    private val currentView: (Any) -> Any?,
    private val identity: (Any) -> String? = { localId(it) },
) : AutoCloseable {
    private data class Owner(
        val card: WeakReference<Any>,
        val localId: String,
        val storageIdentity: String?
    )

    private val owners = WeakHashMap<Any, Owner>()
    private var closed = false

    @Synchronized
    fun remember(card: Any, view: Any) {
        if (closed) return
        check(currentView(card) === view) { "Card creation returned a different view" }
        owners[view] = Owner(WeakReference(card), localId(card), identity(card))
    }

    @Synchronized
    fun owns(view: Any, declaredId: String): Boolean {
        if (closed) return false
        val record = owners[view] ?: return false
        val card = record.card.get() ?: return false
        return record.localId == declaredId && localId(card) == declaredId && currentView(card) === view &&
                record.storageIdentity != null && identity(card) == record.storageIdentity
    }

    @Synchronized
    fun nativeLocalId(view: Any): String? = owners[view]?.localId?.takeIf { owns(view, it) }

    @Synchronized
    fun storageIdentity(view: Any): String? =
        owners[view]?.takeIf { owns(view, it.localId) }?.storageIdentity

    /** A native destroy may already have cleared currentView; only retire this card's recorded views. */
    @Synchronized
    fun views(card: Any): List<Any> = if (closed) emptyList() else
        owners.entries.filter { it.value.card.get() === card }.map { it.key }

    @Synchronized
    fun remove(card: Any) {
        owners.entries.removeAll {
            it.value.card.get().let { owner -> owner == null || owner === card }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        owners.clear()
    }
}
