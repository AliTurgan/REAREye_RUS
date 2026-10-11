package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.Context
import android.graphics.Bitmap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Output types deliberately do not reference AppWidget or a host MAML class. */
enum class MamlDataType { NUMBER, STRING, BITMAP, OBJECT }

data class MamlDataField(
    val name: String,
    val type: MamlDataType,
    val array: Boolean = false,
    val nullable: Boolean = false,
)

data class MamlDataSnapshot(val values: Map<String, Any?>, val count: Int)

sealed interface MamlDataResult {
    data class Success(val snapshot: MamlDataSnapshot) : MamlDataResult
    data class Failure(val message: String) : MamlDataResult
}

fun interface MamlDataHandle : AutoCloseable {
    override fun close()

    companion object {
        val NONE = MamlDataHandle {}
    }
}

/** Parameters have already been evaluated and validated; sources do not parse XML/Expressions. */
data class MamlDataRequest(val values: Map<String, Any?>) {
    fun number(name: String): Double = (values[name] as? Number)?.toDouble()
        ?: error("Missing numeric parameter: $name")

    fun string(name: String): String = values[name] as? String
        ?: error("Missing string parameter: $name")
}

/**
 * Calls to this interface run on main. query must return promptly; result may arrive on any thread.
 * A result can arrive synchronously. Cancellation is idempotent and must suppress further work;
 * the framework also rejects stale callbacks. Never mutate a snapshot after invoking result.
 * invalidate is only subscribed while the binder is active. close must release owned resources.
 */
interface MamlDataSource : AutoCloseable {
    fun query(request: MamlDataRequest, result: (MamlDataResult) -> Unit): MamlDataHandle
    fun subscribeInvalidations(invalidate: () -> Unit): MamlDataHandle = MamlDataHandle.NONE
    fun onResume() {}
    fun onPause() {}
    override fun close() {}
}

fun interface MamlDataSourceFactory {
    fun create(context: Context): MamlDataSource
}

/** Fixed/Expression values use the same coercion; unsupported types and non-finite numbers fail. */
data class MamlDataParameter(
    val name: String,
    val type: MamlDataType,
    val defaultValue: Any,
    val minimum: Double? = null,
    val maximum: Double? = null,
) {
    init {
        require(validMamlDataName(name)) { "Invalid parameter: $name" }
        require(type == MamlDataType.NUMBER || type == MamlDataType.STRING)
        require(minimum == null || minimum.isFinite())
        require(maximum == null || maximum.isFinite())
        require(minimum == null || maximum == null || minimum <= maximum)
        coerce(defaultValue)
    }

    fun coerce(value: Any?): Any = when (type) {
        MamlDataType.STRING -> requireNotNull(value as? String) { "Parameter $name must be a string" }
        MamlDataType.NUMBER -> {
            val number = when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
            require(number != null && number.isFinite()) { "Parameter $name must be a finite number" }
            require(minimum == null || number >= minimum) { "Parameter $name is below $minimum" }
            require(maximum == null || number <= maximum) { "Parameter $name is above $maximum" }
            number
        }

        else -> error("Unsupported parameter type")
    }
}

/** Normalizes numeric arrays to double[] for MAML and takes copies of all array containers. */
class MamlDataSchema(fields: List<MamlDataField>) {
    val fields: List<MamlDataField> = fields.toList()

    init {
        require(fields.map { it.name }.distinct().size == fields.size) { "Duplicate output field" }
        fields.forEach {
            require(it.type != MamlDataType.NUMBER || !it.nullable) {
                "MAML numeric variables cannot represent null: ${it.name}"
            }
            require(validMamlDataName(it.name) && it.name !in RESERVED_FIELDS) {
                "Invalid/reserved output field: ${it.name}"
            }
        }
    }

    fun validate(snapshot: MamlDataSnapshot): MamlDataSnapshot {
        require(snapshot.count >= 0) { "Negative row count" }
        require(snapshot.values.keys == fields.map { it.name }
            .toSet()) { "Output fields do not match schema" }
        val normalized = fields.associate { field ->
            val value = snapshot.values[field.name]
            field.name to if (field.array) {
                val entries: List<Any?> = when (value) {
                    is Array<*> -> value.toList()
                    is DoubleArray -> value.toList()
                    is FloatArray -> value.toList()
                    is IntArray -> value.toList()
                    is LongArray -> value.toList()
                    else -> error("Field ${field.name} must be an array")
                }
                require(entries.size == snapshot.count) { "Array length mismatch: ${field.name}" }
                val checked = entries.map { validateValue(field, it) }
                when (field.type) {
                    MamlDataType.NUMBER -> {
                        require(!field.nullable) { "Numeric arrays cannot contain null" }
                        checked.map { it as Double }.toDoubleArray()
                    }

                    MamlDataType.STRING -> checked.map { it as String? }.toTypedArray()
                    MamlDataType.BITMAP -> checked.map { it as Bitmap? }.toTypedArray()
                    MamlDataType.OBJECT -> checked.toTypedArray()
                }
            } else validateValue(field, value)
        }
        return MamlDataSnapshot(normalized, snapshot.count)
    }

    fun empty(): MamlDataSnapshot = MamlDataSnapshot(fields.associate { field ->
        field.name to if (field.array) when (field.type) {
            MamlDataType.NUMBER -> doubleArrayOf()
            MamlDataType.STRING -> emptyArray<String?>()
            MamlDataType.BITMAP -> emptyArray<Bitmap?>()
            MamlDataType.OBJECT -> emptyArray<Any?>()
        } else if (field.nullable) null else when (field.type) {
            MamlDataType.NUMBER -> 0.0
            MamlDataType.STRING -> ""
            MamlDataType.BITMAP, MamlDataType.OBJECT -> null
        }
    }, 0)

    private fun validateValue(field: MamlDataField, value: Any?): Any? {
        if (value == null) {
            require(field.nullable) { "Null field: ${field.name}" }
            return null
        }
        return when (field.type) {
            MamlDataType.NUMBER -> (value as? Number)?.toDouble()?.also {
                require(it.isFinite()) { "Non-finite output: ${field.name}" }
            } ?: error("Field ${field.name} must be a number")

            MamlDataType.STRING -> value as? String ?: error("Field ${field.name} must be a string")
            MamlDataType.BITMAP -> value as? Bitmap ?: error("Field ${field.name} must be a Bitmap")
            MamlDataType.OBJECT -> value
        }
    }

    companion object {
        val RESERVED_FIELDS = setOf("loading", "error", "revision", "count")
    }
}

data class MamlDataSourceDefinition(
    val tag: String,
    val schema: MamlDataSchema,
    val parameters: List<MamlDataParameter>,
    val factory: MamlDataSourceFactory,
) {
    init {
        require(tag.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*"))) { "Invalid Binder tag" }
        require(parameters.map { it.name }
            .distinct().size == parameters.size) { "Duplicate parameter" }
        val attributes = parameters.flatMap { listOf(it.name, "${it.name}Exp") }
        require(attributes.distinct().size == attributes.size) { "Parameter conflicts with an Expression attribute" }
        require(parameters.none { it.name in setOf("name", "dependency", "queryAtStart") }) {
            "Parameter uses a Binder lifecycle attribute"
        }
    }
}

class MamlDataSourceRegistry {
    private val definitions = ConcurrentHashMap<String, MamlDataSourceDefinition>()
    fun register(definition: MamlDataSourceDefinition) {
        check(definitions.putIfAbsent(definition.tag.lowercase(Locale.ROOT), definition) == null) {
            "Data source tag already registered: ${definition.tag}"
        }
    }

    fun find(tag: String): MamlDataSourceDefinition? = definitions[tag.lowercase(Locale.ROOT)]
}

internal fun validMamlDataName(name: String): Boolean =
    name.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*"))
