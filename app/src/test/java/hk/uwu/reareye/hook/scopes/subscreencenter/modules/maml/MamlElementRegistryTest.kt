package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class MamlElementRegistryTest {
    private val factory = MamlViewBackendFactory { error("Parsing should not run on lookup") }

    @Test
    fun tagsAreCaseInsensitiveAndUnknownTagsFallThrough() {
        val registry = MamlElementRegistry()
        registry.register("RearAppWidget", factory)
        assertSame(factory, registry.find("rearappwidget"))
        assertNull(registry.find("Image"))
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateRegistrationIsRejectedIgnoringCase() {
        MamlElementRegistry().apply {
            register("RearView", factory)
            register("rearview", factory)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidTagIsRejected() {
        MamlElementRegistry().register(" ", factory)
    }

    @Test
    fun carrierCopyPreservesGeometryAndAnimationsWithoutMutatingSource() {
        val original =
            xml("""<RearAppWidget name="clock" key="primary" x="screen_width/2" w="300" frameRate="30"><AlphaAnimation><Alpha a="0" time="0"/></AlphaAnimation><Triggers/></RearAppWidget>""")
        val clone = copyMamlCarrierXml(original)
        assertNotSame(original, clone)
        assertEquals("30", original.getAttribute("frameRate"))
        assertEquals("-1", clone.getAttribute("frameRate"))
        assertEquals("screen_width/2", clone.getAttribute("x"))
        assertEquals("clock", clone.getAttribute("name"))
        assertEquals(1, clone.getElementsByTagName("AlphaAnimation").length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun renderChildrenAreRejectedInsteadOfDisappearingSilently() {
        copyMamlCarrierXml(xml("<RearView><Image/></RearView>"))
    }

    @Test
    fun finishInvalidatesOldCallbacksAndReuseGetsNewEpoch() {
        val epoch = MamlSessionEpoch()
        val first = epoch.begin()
        assertTrue(epoch.accepts(first))
        assertTrue(epoch.finish())
        assertFalse(epoch.accepts(first))
        assertFalse(epoch.finish())
        val second = epoch.begin()
        assertTrue(second > first)
        assertTrue(epoch.accepts(second))
        assertFalse(epoch.accepts(first))
    }

    private fun xml(value: String): Element = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder().parse(ByteArrayInputStream(value.toByteArray())).documentElement
}
