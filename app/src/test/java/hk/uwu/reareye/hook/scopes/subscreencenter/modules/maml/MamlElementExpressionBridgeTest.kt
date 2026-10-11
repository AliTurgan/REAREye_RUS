package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MamlElementExpressionBridgeTest {
    private fun bridge() = MamlHostBridge(object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "com.miui.maml.elements.ScreenElementFactory" -> InputFixtureFactory::class.java
            "com.miui.maml.ScreenElementRoot" -> InputFixtureRoot::class.java
            "com.miui.maml.elements.LayerScreenElement" -> InputFixtureLayer::class.java
            "com.miui.maml.elements.ViewHolderScreenElement" -> InputFixtureHolder::class.java
            "com.miui.maml.elements.ScreenElement" -> InputFixtureScreen::class.java
            "com.miui.maml.elements.ElementGroup" -> InputFixtureGroup::class.java
            "com.miui.maml.data.Expression" -> InputFixtureExpression::class.java
            else -> super.loadClass(name)
        }
    })

    @Test
    fun stringBridgeBuildsAndEvaluatesOnlyOnTheCallingNativeThread() {
        val bridge = bridge()
        val root = InputFixtureRoot(RuntimeEnvironment.getApplication())
        root.variables.values["@provider"] = "one/one.Provider"
        val evaluate = bridge.expressions(root).compile("@provider")
        assertEquals(0, root.variableReads)
        assertEquals(0, root.variables.builds)
        val result = mutableListOf<String?>()
        val native = Thread {
            result += evaluate()
            root.variables.values["@provider"] = "two/two.Provider"
            result += evaluate()
        }.apply { start(); join() }
        assertEquals(listOf("one/one.Provider", "two/two.Provider"), result)
        assertEquals(1, root.variables.builds)
        assertEquals(2, root.variables.stringReads)
        assertSame(native, root.variables.thread)

        // Deterministically model GC clearing only the cached Expression, not the live root.
        // Kotlin captures the mutable cache in ObjectRef; avoid a flaky System.gc() test.
        val cacheField = evaluate.javaClass.declaredFields.single {
            it.type == kotlin.jvm.internal.Ref.ObjectRef::class.java
        }.apply { isAccessible = true }
        val cache = cacheField.get(evaluate) as kotlin.jvm.internal.Ref.ObjectRef<*>
        (cache.element as java.lang.ref.WeakReference<*>).clear()
        root.variables.values["@provider"] = "three/three.Provider"
        val rebuilt = Thread { result += evaluate() }.apply { start(); join() }
        assertEquals("three/three.Provider", result.last())
        assertEquals(2, root.variables.builds)
        assertEquals(2, root.variableReads)
        assertEquals(3, root.variables.stringReads)
        assertSame(rebuilt, root.variables.thread)
    }

    @Test
    fun nativeAnimationBodyDoesNotSelectTheViewHolderInvalidationBody() {
        val bridge = bridge()
        assertEquals(InputFixtureGroup::class.java, bridge.animationTick.declaringClass)
        val animated = InputFixtureGroup()
        repeat(1000) { bridge.animationTick.invoke(animated, it.toLong()) }
        assertEquals(1000, animated.animationTicks)
    }

    @Test
    fun staticLayerMetadataIsNotUpdatedForEveryTickButAnimatedOutlineStillIs() {
        val bridge = bridge()
        val holder = InputFixtureHolder()
        assertFalse(bridge.updateOutline(holder, true))
        repeat(1000) { assertFalse(bridge.updateOutline(holder, false)) }
        assertEquals(1, holder.outlineEvaluations)
        assertEquals(0, holder.outlineApplications)
        holder.mOutlineProviderConfig.configured = true
        assertTrue(bridge.updateOutline(holder, false))
        bridge.applyOutline(holder)
        repeat(60) {
            assertFalse(bridge.updateOutline(holder, false))
            bridge.applyOutline(holder)
        }
        assertEquals(1, holder.outlineApplications)
        holder.mOutlineProviderConfig.radius = 20f // Geometry is unchanged.
        assertTrue(bridge.updateOutline(holder, false))
        bridge.applyOutline(holder)
        assertEquals(2, holder.outlineApplications)
    }

    @Test
    fun identicalNativeOutlineIsAttachedOnceToAReplacementAndroidView() {
        val context: Context = RuntimeEnvironment.getApplication()
        val element = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .newDocument().createElement("Layer")
        val layer = InputFixtureLayer(element, InputFixtureRoot(context))
        layer.mOutlineProviderConfig.configured = true
        layer.mOutlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {}
        }
        val bridge = bridge()
        layer.carrierView = View(context)
        assertTrue(bridge.updateOutline(layer, true))
        bridge.applyOutline(layer)
        assertSame(layer.mOutlineProvider, layer.carrierView!!.outlineProvider)
        assertEquals(1, layer.outlineApplications)
        repeat(60) { assertFalse(bridge.updateOutline(layer, false)) }
        layer.carrierView = View(context) // Native element survives host finish -> init.
        assertTrue(bridge.updateOutline(layer, false))
        bridge.applyOutline(layer)
        assertSame(layer.mOutlineProvider, layer.carrierView!!.outlineProvider)
        assertEquals(2, layer.outlineApplications)
        repeat(1000) { assertFalse(bridge.updateOutline(layer, false)) }
        assertEquals(2, layer.outlineApplications)
    }
}

class InputFixtureContext(@JvmField val mContext: Context)
class InputFixtureRoot(context: Context) {
    private val context = InputFixtureContext(context)

    @JvmField
    val variables = InputFixtureVariables()
    var variableReads = 0
    fun getContext() = context
    fun getVariables(): InputFixtureVariables {
        variableReads++; return variables
    }
}

class InputFixtureVariables {
    val values = mutableMapOf<String, String>()
    var builds = 0
    var stringReads = 0
    var thread: Thread? = null
}

class InputFixtureExpression(
    private val variables: InputFixtureVariables,
    private val source: String
) {
    fun evaluate(): Double = error("Numeric evaluation must never be used for widget STRING inputs")
    fun evaluateStr(): String? {
        variables.stringReads++
        variables.thread = Thread.currentThread()
        return variables.values[source]
    }

    companion object {
        @JvmStatic
        fun build(variables: InputFixtureVariables, source: String): InputFixtureExpression {
            variables.builds++
            return InputFixtureExpression(variables, source)
        }
    }
}

open class InputFixtureScreen {
    open fun init() {}
    open fun doTick(time: Long) {}
    open fun doRender(canvas: Canvas) {}
    fun resume() {}
    fun pause() {}
    fun isVisible() = true
}

open class InputFixtureGroup : InputFixtureScreen() {
    var animationTicks = 0
    override fun doTick(time: Long) {
        animationTicks++
    }
}

class InputFixtureOutlineConfig {
    var configured = false
    var radius = 10f
    fun hasConfig() = configured
}

open class InputFixtureHolder : InputFixtureGroup() {
    @JvmField
    var mLayoutParams = ViewGroup.LayoutParams(-1, -1)

    @JvmField
    var mUpdatePosition = true

    @JvmField
    var mUpdateSize = true

    @JvmField
    var mUpdateTranslation = true

    @JvmField
    val mOutlineProviderConfig = InputFixtureOutlineConfig()

    @JvmField
    var mOutlineProvider: android.view.ViewOutlineProvider? = null
    open fun getView(): View? = null
    var outlineEvaluations = 0
    var outlineApplications = 0
    private var evaluatedRadius = -1f
    private var outlineDirty = false
    fun updateOutlineProvider() {
        outlineEvaluations++
        if (mOutlineProviderConfig.configured && evaluatedRadius != mOutlineProviderConfig.radius) {
            evaluatedRadius = mOutlineProviderConfig.radius
            outlineDirty = true
        }
    }

    fun shouldApplyOutlineProvider() = outlineDirty
    fun applyOutlineProvider() {
        outlineApplications++
        getView()?.outlineProvider = mOutlineProvider
        outlineDirty = false
    }

    fun finish() {}
    fun onVisibilityChange(visible: Boolean) {}
    fun onUpdateView(view: View) {}
    override fun init() {}
    override fun doTick(time: Long) {}
    override fun doRender(canvas: Canvas) {}
}

class InputFixtureLayer(element: Element, root: InputFixtureRoot) : InputFixtureHolder() {
    var carrierView: View? = null
    override fun getView(): View? = carrierView
    override fun init() {}
    override fun doTick(time: Long) {}
    override fun doRender(canvas: Canvas) {}
    fun getAbsoluteLeft() = 0f
    fun getAbsoluteTop() = 0f
    fun getX() = 0f
    fun getY() = 0f
    fun getWidth() = 100f
    fun getHeight() = 100f
    fun getPivotX() = 0f
    fun getPivotY() = 0f
    fun getRotation() = 0f
    fun getRotationX() = 0f
    fun getRotationY() = 0f
    fun getAlpha() = 255f
    fun getScaleX() = 1f
    fun getScaleY() = 1f
}

class InputFixtureFactory {
    fun createInstance(element: Element, root: InputFixtureRoot): InputFixtureScreen =
        InputFixtureLayer(element, root)
}
