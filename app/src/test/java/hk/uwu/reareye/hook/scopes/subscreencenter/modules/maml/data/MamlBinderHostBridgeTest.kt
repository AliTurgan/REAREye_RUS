package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MamlBinderHostBridgeTest {
    private fun bridge() = MamlBinderHostBridge(object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "com.miui.maml.ScreenElementRoot" -> BinderFixtureRoot::class.java
            "com.miui.maml.data.VariableBinder" -> BinderFixtureBase::class.java
            "com.miui.maml.data.BroadcastBinder" -> BinderFixtureCarrier::class.java
            "com.miui.maml.data.VariableBinderManager" -> BinderFixtureManager::class.java
            "com.miui.maml.data.Expression" -> BinderFixtureExpression::class.java
            "com.miui.maml.data.ContentProviderBinder\$QueryCompleteListener" -> BinderFixtureListener::class.java
            else -> super.loadClass(name)
        }
    })

    private fun root() = BinderFixtureRoot(RuntimeEnvironment.getApplication())
    private val schema =
        MamlDataSchema(listOf(MamlDataField("names", MamlDataType.STRING, array = true)))

    @Test
    fun carrierKeepsBaseConfigurationAndListenerButDoesNotRegisterReceiverOnConstruction() {
        val bridge = bridge()
        val source =
            xml("<Example name='demo' queryAtStart='false' dependency='upstream'><Trigger/></Example>")
        val manager = BinderFixtureManager()
        val carrier = bridge.carrier(source, root(), manager) as BinderFixtureCarrier
        assertEquals("demo", carrier.xml.getAttribute("name"))
        assertEquals("false", carrier.xml.getAttribute("queryAtStart"))
        assertEquals("upstream", carrier.xml.getAttribute("dependency"))
        assertEquals(1, carrier.xml.getElementsByTagName("Trigger").length)
        assertFalse(source.hasAttribute("action"))
        assertSame(manager, carrier.listener)
        assertEquals(0, carrier.registrations)
        assertEquals(BinderFixtureBase::class.java, bridge.baseInit.declaringClass)
        assertEquals(BinderFixtureCarrier::class.java, bridge.carrierInit.declaringClass)
    }

    @Test
    fun publicationsRunOnRootQueueAndWriteCommitFieldsLast() {
        val bridge = bridge()
        val root = root()
        val manager = BinderFixtureManager()
        val carrier =
            bridge.carrier(xml("<Example name='demo'/>"), root, manager) as BinderFixtureBase
        val sink = bridge.sink(root, carrier, "demo", schema)
        val publication = MamlDataPublication(
            MamlDataSnapshot(mapOf("names" to arrayOf("Clock")), 1),
            false,
            "",
            7,
            true
        )
        sink.enqueue { sink.publish(publication) }
        assertTrue(root.variables.values.isEmpty())
        root.queue.removeAt(0).run()
        assertArrayEquals(arrayOf("Clock"), root.variables.values["demo.names"] as Array<*>)
        assertEquals(1.0, root.variables.values["demo.count"])
        assertEquals(7.0, root.variables.values["demo.revision"])
        assertEquals(listOf("demo.count", "demo.revision"), root.variables.writes.takeLast(2))
        assertEquals(1, manager.completions)
        sink.clear()
        assertEquals(0.0, root.variables.values["demo.count"])
        assertEquals(0, (root.variables.values["demo.names"] as Array<*>).size)
        assertEquals(1, manager.completions)
    }

    @Test
    fun loadingPublicationDoesNotFireCompletionDependency() {
        val bridge = bridge()
        val root = root()
        val manager = BinderFixtureManager()
        val carrier = bridge.carrier(xml("<Example name='demo'/>"), root, manager)
        bridge.sink(root, carrier, "demo", schema)
            .publish(MamlDataPublication(schema.empty(), true, "", 0, false))
        assertEquals(1.0, root.variables.values["demo.loading"])
        assertEquals(0, manager.completions)
    }

    @Test
    fun expressionBridgeUsesTheTypedHostEvaluator() {
        val bridge = bridge()
        assertEquals(3.0, bridge.compile(root(), "number", MamlDataType.NUMBER).invoke())
        assertEquals("query", bridge.compile(root(), "text", MamlDataType.STRING).invoke())
    }

    @Test
    fun unsupportedOutputDeclarationsAreRejectedInsteadOfSilentlyIgnored() {
        assertThrows(IllegalArgumentException::class.java) {
            bridge().carrier(
                xml("<Example name='demo'><Variable name='custom'/></Example>"),
                root(),
                BinderFixtureManager()
            )
        }
    }

    @Test
    fun dataBinderCannotOverwriteAnExistingHostBinderName() {
        val manager = BinderFixtureManager()
        manager.binders += BinderFixtureBase(xml("<ContentProviderBinder name='taken'/>"), root())
        val bridge = bridge()
        bridge.requireAvailableName(manager, "available")
        assertThrows(IllegalStateException::class.java) {
            bridge.requireAvailableName(
                manager,
                "taken"
            )
        }
    }

    private fun xml(text: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(text.toByteArray())).documentElement
}

class BinderFixtureContext(@JvmField val mContext: Context)
class BinderFixtureVariables {
    val values = linkedMapOf<String, Any?>()
    val writes = mutableListOf<String>()
    fun put(name: String, value: Double) {
        writes += name; values[name] = value
    }

    fun put(name: String, value: Any?) {
        writes += name; values[name] = value
    }
}

open class BinderFixtureScreen(private val context: Context) {
    val variables = BinderFixtureVariables()
    val queue = mutableListOf<Runnable>()
    fun getContext() = BinderFixtureContext(context)
    fun postRunnable(task: Runnable) {
        queue += task
    }

    fun requestUpdate() {}
}

class BinderFixtureRoot(context: Context) : BinderFixtureScreen(context)
fun interface BinderFixtureListener {
    fun onQueryCompleted(name: String)
}

open class BinderFixtureBase(val xml: Element, val root: BinderFixtureRoot) {
    var listener: BinderFixtureListener? = null
        private set

    open fun init() {}
    open fun finish() {}
    fun startQuery() {}
    fun refresh() {}
    fun tick() {}
    fun pause() {}
    fun resume() {}
    fun setQueryCompleteListener(value: BinderFixtureListener) {
        listener = value
    }

    fun onUpdateComplete() {
        listener?.onQueryCompleted(xml.getAttribute("name"))
    }
}

class BinderFixtureCarrier(xml: Element, root: BinderFixtureRoot) : BinderFixtureBase(xml, root) {
    var registrations = 0

    init {
        require(xml.getAttribute("action").isNotEmpty())
    }

    override fun init() {
        registrations++
    }

    override fun finish() {}
}

class BinderFixtureManager : BinderFixtureListener {
    val binders = mutableListOf<BinderFixtureBase>()
    fun findBinder(name: String): BinderFixtureBase? =
        binders.firstOrNull { it.xml.getAttribute("name") == name }

    var completions = 0
    override fun onQueryCompleted(name: String) {
        completions++
    }

    companion object {
        @JvmStatic
        fun createBinder(
            element: Element,
            root: BinderFixtureRoot,
            manager: BinderFixtureManager
        ): BinderFixtureBase =
            BinderFixtureBase(element, root).also { it.setQueryCompleteListener(manager) }
    }
}

class BinderFixtureExpression {
    fun evaluate() = 3.0
    fun evaluateStr() = "query"

    companion object {
        @JvmStatic
        fun build(variables: BinderFixtureVariables, text: String): BinderFixtureExpression? =
            if (text.isEmpty()) null else BinderFixtureExpression()
    }
}
