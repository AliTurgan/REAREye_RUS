package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class MamlBinderParametersTest {
    private val definition = MamlDataSourceDefinition(
        "ExampleBinder", MamlDataSchema(emptyList()),
        listOf(
            MamlDataParameter("limit", MamlDataType.NUMBER, 12.0, 1.0, 32.0),
            MamlDataParameter("search", MamlDataType.STRING, ""),
        ),
        MamlDataSourceFactory { error("No source construction while parsing") },
    )

    @Test
    fun fixedValuesAndDefaultsAreTyped() {
        val defaults = MamlBinderParameters.parse(
            xml("<ExampleBinder/>"),
            definition
        ) { _, _ -> error("No expression") }
        assertEquals(12.0, defaults.evaluate().number("limit"), 0.0)
        val fixed = MamlBinderParameters.parse(
            xml("<ExampleBinder limit='3' search='Clock'/>"),
            definition
        ) { _, _ -> error("No expression") }
        assertEquals(3.0, fixed.evaluate().number("limit"), 0.0)
        assertEquals("Clock", fixed.evaluate().string("search"))
    }

    @Test
    fun expressionOverridesLiteralAndIsCachedUntilFinish() {
        var current = 5.0
        var compilations = 0
        val parsed = MamlBinderParameters.parse(
            xml("<ExampleBinder limit='12' limitExp='page_limit'/>"),
            definition
        ) { source, type ->
            assertEquals("page_limit", source)
            assertEquals(MamlDataType.NUMBER, type)
            compilations++
            { current }
        }
        assertEquals(5.0, parsed.evaluate().number("limit"), 0.0)
        current = 6.0
        assertEquals(6.0, parsed.evaluate().number("limit"), 0.0)
        assertEquals(1, compilations)
        parsed.clearExpressions()
        parsed.evaluate()
        assertEquals(2, compilations)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidFixedLimitFailsBeforeSourceCreation() {
        MamlBinderParameters.parse(
            xml("<ExampleBinder limit='999'/>"),
            definition
        ) { _, _ -> { 0.0 } }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidExpressionResultCannotSilentlyUseTheLiteral() {
        val parsed = MamlBinderParameters.parse(
            xml("<ExampleBinder search='literal' searchExp='query'/>"),
            definition
        ) { _, _ -> { null } }
        parsed.evaluate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyExpressionIsRejected() {
        MamlBinderParameters.parse(
            xml("<ExampleBinder limitExp=''/>"),
            definition
        ) { _, _ -> { 1.0 } }
    }

    private fun xml(text: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(text.toByteArray())).documentElement
}
