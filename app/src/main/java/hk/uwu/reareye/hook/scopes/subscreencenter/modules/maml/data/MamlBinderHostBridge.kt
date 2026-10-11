package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.Context
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.MamlHostBridge
import org.w3c.dom.Element
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

/** An independent ABI bridge: custom data sources do not require the element/ViewHolder hooks. */
internal class MamlBinderHostBridge(loader: ClassLoader) {
    val rootClass = loader.loadClass("com.miui.maml.ScreenElementRoot")
    val binderClass = loader.loadClass("com.miui.maml.data.VariableBinder")
    val carrierClass = loader.loadClass("com.miui.maml.data.BroadcastBinder")
    val managerClass = loader.loadClass("com.miui.maml.data.VariableBinderManager")
    private val expressionClass = loader.loadClass("com.miui.maml.data.Expression")
    private val listenerClass =
        loader.loadClass("com.miui.maml.data.ContentProviderBinder\$QueryCompleteListener")
    val create = MamlHostBridge.method(
        managerClass,
        "createBinder",
        Element::class.java,
        rootClass,
        managerClass
    )
    private val constructor = carrierClass.getConstructor(Element::class.java, rootClass)
    val carrierInit = MamlHostBridge.method(carrierClass, "init")
    val carrierFinish = MamlHostBridge.method(carrierClass, "finish")
    val baseInit = MamlHostBridge.method(binderClass, "init")
    val baseFinish = MamlHostBridge.method(binderClass, "finish")
    val startQuery = MamlHostBridge.method(binderClass, "startQuery")
    val refresh = MamlHostBridge.method(binderClass, "refresh")
    val tick = MamlHostBridge.method(binderClass, "tick")
    val pause = MamlHostBridge.method(binderClass, "pause")
    val resume = MamlHostBridge.method(binderClass, "resume")
    private val findBinder = MamlHostBridge.method(managerClass, "findBinder", String::class.java)
    private val setListener =
        MamlHostBridge.method(binderClass, "setQueryCompleteListener", listenerClass)
    private val updateComplete = MamlHostBridge.method(binderClass, "onUpdateComplete")
    private val rootContext = MamlHostBridge.method(rootClass, "getContext")
    private val androidContext = rootContext.returnType.getField("mContext")
    private val getVariables = MamlHostBridge.method(rootClass, "getVariables")
    private val variablesClass = getVariables.returnType
    private val putNumber = MamlHostBridge.method(
        variablesClass,
        "put",
        String::class.java,
        Double::class.javaPrimitiveType!!
    )
    private val putObject =
        MamlHostBridge.method(variablesClass, "put", String::class.java, Any::class.java)
    private val postRunnable =
        MamlHostBridge.method(rootClass, "postRunnable", Runnable::class.java)
    private val requestUpdate = MamlHostBridge.method(rootClass, "requestUpdate")
    private val expressionBuild =
        MamlHostBridge.method(expressionClass, "build", variablesClass, String::class.java)
    private val evaluateNumber = MamlHostBridge.method(expressionClass, "evaluate")
    private val evaluateString = MamlHostBridge.method(expressionClass, "evaluateStr")

    init {
        check(Modifier.isStatic(create.modifiers) && binderClass.isAssignableFrom(create.returnType))
        check(binderClass.isAssignableFrom(carrierClass))
        check(listenerClass.isAssignableFrom(managerClass))
        check(Context::class.java.isAssignableFrom(androidContext.type))
        check(Modifier.isStatic(expressionBuild.modifiers))
    }

    fun context(root: Any): Context = androidContext.get(rootContext.invoke(root)) as Context

    fun requireAvailableName(manager: Any, name: String) {
        check(findBinder.invoke(manager, name) == null) { "Duplicate Binder name: $name" }
    }

    fun carrier(source: Element, root: Any, manager: Any): Any {
        // Keep only the base Binder's configuration and completion trigger. Our schema owns outputs.
        val copy = source.cloneNode(true) as Element
        for (index in copy.childNodes.length - 1 downTo 0) {
            val child = copy.childNodes.item(index) as? Element ?: continue
            require(child.tagName == "Trigger") {
                "Data Binder outputs are defined by its schema; unsupported child: ${child.tagName}"
            }
        }
        copy.setAttribute("action", "hk.uwu.reareye.internal.MAML_DATA_CARRIER")
        return constructor.newInstance(copy, root).also { setListener.invoke(it, manager) }
    }

    fun compile(root: Any, expression: String, type: MamlDataType): () -> Any? {
        val built =
            requireNotNull(expressionBuild.invoke(null, getVariables.invoke(root), expression)) {
                "Invalid MAML expression: $expression"
            }
        val method = if (type == MamlDataType.STRING) evaluateString else evaluateNumber
        return { method.invoke(built) }
    }

    fun sink(root: Any, carrier: Any, prefix: String, schema: MamlDataSchema): MamlDataSink {
        val weakRoot = WeakReference(root)
        val weakCarrier = WeakReference(carrier)
        return object : MamlDataSink {
            override fun enqueue(block: () -> Unit) {
                weakRoot.get()?.let {
                    postRunnable.invoke(it, Runnable(block))
                    requestUpdate.invoke(it)
                }
            }

            override fun publish(publication: MamlDataPublication) {
                val owner = weakRoot.get() ?: return
                write(owner, prefix, schema, publication)
                if (publication.completed) weakCarrier.get()?.let { updateComplete.invoke(it) }
                else requestUpdate.invoke(owner)
            }

            override fun clear() {
                weakRoot.get()?.let {
                    write(
                        it,
                        prefix,
                        schema,
                        MamlDataPublication(schema.empty(), false, "", 0L, false)
                    )
                }
            }
        }
    }

    private fun write(
        root: Any,
        prefix: String,
        schema: MamlDataSchema,
        publication: MamlDataPublication
    ) {
        val variables = getVariables.invoke(root)
        schema.fields.forEach { field ->
            val value = publication.snapshot.values[field.name]
            val method =
                if (!field.array && field.type == MamlDataType.NUMBER) putNumber else putObject
            method.invoke(variables, "$prefix.${field.name}", value)
        }
        putNumber.invoke(variables, "$prefix.loading", if (publication.loading) 1.0 else 0.0)
        putObject.invoke(variables, "$prefix.error", publication.error)
        putNumber.invoke(variables, "$prefix.count", publication.snapshot.count.toDouble())
        putNumber.invoke(variables, "$prefix.revision", publication.revision.toDouble())
    }
}
