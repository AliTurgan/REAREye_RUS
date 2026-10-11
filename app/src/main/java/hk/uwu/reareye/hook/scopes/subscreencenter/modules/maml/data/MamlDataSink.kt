package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

/** One complete publication. completed=false updates loading/state without firing query triggers. */
data class MamlDataPublication(
    val snapshot: MamlDataSnapshot,
    val loading: Boolean,
    val error: String,
    val revision: Long,
    val completed: Boolean,
)

/** Host-specific writer. Data sources never touch this interface or host reflection. */
interface MamlDataSink {
    /** Execute on the host root's update queue, not merely Android main. */
    fun enqueue(block: () -> Unit)
    fun publish(publication: MamlDataPublication)

    /** Called from the Binder finish lifecycle thread, after pending publications are invalidated. */
    fun clear()
}
