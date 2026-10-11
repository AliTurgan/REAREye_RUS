package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class MamlDataSourceTest {
    private fun rejects(block: () -> Unit) {
        try {
            block(); fail("Expected invalid input to be rejected")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun definition(tag: String = "LabelBinder") = MamlDataSourceDefinition(
        tag, MamlDataSchema(listOf(MamlDataField("label", MamlDataType.STRING))), emptyList(),
        MamlDataSourceFactory {
            object : MamlDataSource {
                override fun query(
                    request: MamlDataRequest,
                    result: (MamlDataResult) -> Unit
                ): MamlDataHandle {
                    result(
                        MamlDataResult.Success(
                            MamlDataSnapshot(
                                mapOf("label" to request.string("label")),
                                1
                            )
                        )
                    )
                    return MamlDataHandle.NONE
                }
            }
        },
    )

    @Test
    fun registryLookupIsCaseInsensitiveAndUnknownReturnsNull() {
        val registry = MamlDataSourceRegistry()
        val label = definition()
        registry.register(label)
        assertSame(label, registry.find("LABELBINDER"))
        assertSame(label, registry.find("labelbinder"))
        assertNull(registry.find("MissingBinder"))
    }

    @Test
    fun registryRejectsCaseInsensitiveDuplicatesWithoutReplacingOriginal() {
        val registry = MamlDataSourceRegistry()
        val original = definition()
        registry.register(original)
        rejects { registry.register(definition("labelbinder")) }
        assertSame(original, registry.find("LabelBinder"))
    }

    @Test
    fun ordinaryNonWidgetSourceUsesSharedRequestAndSchemaContract() {
        val definition = definition()
        val source = definition.factory.create(org.robolectric.RuntimeEnvironment.getApplication())
        var output: MamlDataResult? = null
        source.query(MamlDataRequest(mapOf("label" to "No widgets involved"))) { output = it }
            .close()
        val snapshot = definition.schema.validate((output as MamlDataResult.Success).snapshot)
        assertEquals("No widgets involved", snapshot.values["label"])
        assertEquals(1, snapshot.count)
        source.close()
    }

    @Test
    fun schemaRejectsWrongScalarTypeAndMissingOrExtraColumns() {
        val schema = definition().schema
        rejects { schema.validate(MamlDataSnapshot(mapOf("label" to 10), 1)) }
        rejects { schema.validate(MamlDataSnapshot(emptyMap(), 0)) }
        rejects { schema.validate(MamlDataSnapshot(mapOf("label" to "ok", "extra" to "no"), 1)) }
    }

    @Test
    fun schemaRejectsArrayLengthMismatchAndNonArrayInput() {
        val schema =
            MamlDataSchema(listOf(MamlDataField("labels", MamlDataType.STRING, array = true)))
        rejects { schema.validate(MamlDataSnapshot(mapOf("labels" to arrayOf("one")), 2)) }
        rejects { schema.validate(MamlDataSnapshot(mapOf("labels" to "one"), 1)) }
    }

    @Test
    fun schemaRejectsNonFiniteNumbersForScalarAndArray() {
        val scalar = MamlDataSchema(listOf(MamlDataField("value", MamlDataType.NUMBER)))
        val array =
            MamlDataSchema(listOf(MamlDataField("values", MamlDataType.NUMBER, array = true)))
        for (number in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            rejects { scalar.validate(MamlDataSnapshot(mapOf("value" to number), 1)) }
            rejects {
                array.validate(
                    MamlDataSnapshot(
                        mapOf("values" to doubleArrayOf(number)),
                        1
                    )
                )
            }
        }
    }

    @Test
    fun schemaCopiesArrayContainersAndNormalizesNumericArrays() {
        val schema = MamlDataSchema(
            listOf(
                MamlDataField("numbers", MamlDataType.NUMBER, array = true),
                MamlDataField("labels", MamlDataType.STRING, array = true),
                MamlDataField("objects", MamlDataType.OBJECT, array = true),
            )
        )
        val numbers = intArrayOf(1, 2)
        val labels = arrayOf("one", "two")
        val reference = Any()
        val objects = arrayOf(reference, reference)
        val input = mutableMapOf<String, Any?>(
            "numbers" to numbers,
            "labels" to labels,
            "objects" to objects
        )
        val output = schema.validate(MamlDataSnapshot(input, 2))
        numbers[0] = 99
        labels[0] = "changed"
        objects[0] = Any()
        input.clear()
        assertArrayEquals(doubleArrayOf(1.0, 2.0), output.values["numbers"] as DoubleArray, 0.0)
        assertArrayEquals(arrayOf("one", "two"), output.values["labels"] as Array<*>)
        assertSame(reference, (output.values["objects"] as Array<*>)[0])
        assertNotSame(objects, output.values["objects"])
    }

    @Test
    fun schemaCopiesBoxedFloatAndLongNumericArrays() {
        val schema =
            MamlDataSchema(listOf(MamlDataField("values", MamlDataType.NUMBER, array = true)))
        for (input in listOf(
            arrayOf<Number>(1, 2.0),
            floatArrayOf(1f, 2f),
            longArrayOf(1, 2),
            doubleArrayOf(1.0, 2.0)
        )) {
            val output = schema.validate(MamlDataSnapshot(mapOf("values" to input), 2))
            assertArrayEquals(doubleArrayOf(1.0, 2.0), output.values["values"] as DoubleArray, 0.0)
            assertNotSame(input, output.values["values"])
        }
    }

    @Test
    fun schemaEmptyContainsEveryColumnAndFreshEmptyArrays() {
        val fields = listOf(
            MamlDataField("number", MamlDataType.NUMBER),
            MamlDataField("label", MamlDataType.STRING),
            MamlDataField("optional", MamlDataType.STRING, nullable = true),
            MamlDataField("numbers", MamlDataType.NUMBER, array = true),
            MamlDataField("labels", MamlDataType.STRING, array = true),
            MamlDataField("images", MamlDataType.BITMAP, array = true),
            MamlDataField("objects", MamlDataType.OBJECT, array = true),
        )
        val schema = MamlDataSchema(fields)
        val empty = schema.empty()
        assertEquals(fields.map { it.name }.toSet(), empty.values.keys)
        assertEquals(0, empty.count)
        assertEquals(0.0, empty.values["number"])
        assertEquals("", empty.values["label"])
        assertNull(empty.values["optional"])
        assertEquals(0, (empty.values["numbers"] as DoubleArray).size)
        for (name in listOf("labels", "images", "objects")) assertEquals(
            0,
            (empty.values[name] as Array<*>).size
        )
        assertNotSame(empty.values["numbers"], schema.empty().values["numbers"])
        assertEquals(0, schema.validate(empty).count)
    }

    @Test
    fun schemaRejectsNegativeCountNullRequiredAndInvalidArrayEntries() {
        rejects { definition().schema.validate(MamlDataSnapshot(mapOf("label" to "ok"), -1)) }
        rejects { definition().schema.validate(MamlDataSnapshot(mapOf("label" to null), 1)) }
        val schema =
            MamlDataSchema(listOf(MamlDataField("labels", MamlDataType.STRING, array = true)))
        rejects { schema.validate(MamlDataSnapshot(mapOf("labels" to arrayOf(10)), 1)) }
        rejects { schema.validate(MamlDataSnapshot(mapOf("labels" to arrayOfNulls<String>(1)), 1)) }
    }

    @Test
    fun nullableStringAndObjectColumnsRetainNullsAndReferences() {
        val marker = Any()
        val schema = MamlDataSchema(
            listOf(
                MamlDataField("optional", MamlDataType.STRING, nullable = true),
                MamlDataField("objects", MamlDataType.OBJECT, array = true, nullable = true),
            )
        )
        val snapshot = schema.validate(
            MamlDataSnapshot(
                mapOf(
                    "optional" to null,
                    "objects" to arrayOf(null, marker)
                ), 2
            )
        )
        assertNull(snapshot.values["optional"])
        assertSame(marker, (snapshot.values["objects"] as Array<*>)[1])
    }

    @Test
    fun schemaRejectsNullableNumbersBeforeHostPrimitiveDoublePublication() {
        rejects {
            MamlDataSchema(
                listOf(
                    MamlDataField(
                        "number",
                        MamlDataType.NUMBER,
                        nullable = true
                    )
                )
            )
        }
        rejects {
            MamlDataSchema(
                listOf(
                    MamlDataField(
                        "numbers",
                        MamlDataType.NUMBER,
                        array = true,
                        nullable = true
                    )
                )
            )
        }
    }

    @Test
    fun schemaRejectsDuplicateInvalidAndReservedFieldNames() {
        rejects {
            MamlDataSchema(
                listOf(
                    MamlDataField("label", MamlDataType.STRING),
                    MamlDataField("label", MamlDataType.STRING)
                )
            )
        }
        for (name in listOf("", "bad-name", "1name") + MamlDataSchema.RESERVED_FIELDS) {
            rejects { MamlDataSchema(listOf(MamlDataField(name, MamlDataType.STRING))) }
        }
    }

    @Test
    fun parameterCoercionAcceptsNumericStringsAndInclusiveLimits() {
        val parameter =
            MamlDataParameter("limit", MamlDataType.NUMBER, 2, minimum = 1.0, maximum = 3.0)
        assertEquals(1.0, parameter.coerce("1"))
        assertEquals(3.0, parameter.coerce(3L))
        assertEquals(2.5, parameter.coerce(2.5f))
        rejects { parameter.coerce(0) }
        rejects { parameter.coerce(4) }
    }

    @Test
    fun parameterCoercionRejectsNonFiniteAndUnsupportedTypes() {
        val parameter = MamlDataParameter("limit", MamlDataType.NUMBER, 1)
        for (value in listOf(
            null,
            true,
            "text",
            "NaN",
            Double.POSITIVE_INFINITY,
            Double.NaN
        )) rejects { parameter.coerce(value) }
        val label = MamlDataParameter("label", MamlDataType.STRING, "default")
        assertEquals("value", label.coerce("value"))
        rejects { label.coerce(1) }
        rejects { label.coerce(null) }
        rejects { MamlDataParameter("image", MamlDataType.BITMAP, Any()) }
    }

    @Test
    fun parameterDefinitionRejectsInvalidDefaultsNamesAndLimits() {
        rejects { MamlDataParameter("bad-name", MamlDataType.NUMBER, 1) }
        rejects { MamlDataParameter("limit", MamlDataType.NUMBER, "invalid") }
        rejects { MamlDataParameter("limit", MamlDataType.NUMBER, 0, minimum = 1.0) }
        rejects { MamlDataParameter("limit", MamlDataType.NUMBER, 2, minimum = 3.0, maximum = 1.0) }
        rejects { MamlDataParameter("limit", MamlDataType.NUMBER, 1, maximum = Double.NaN) }
    }

    @Test
    fun sourceDefinitionRejectsReservedAndDuplicateParameters() {
        val source = definition()
        for (name in listOf("name", "dependency", "queryAtStart")) {
            rejects {
                source.copy(
                    parameters = listOf(
                        MamlDataParameter(
                            name,
                            MamlDataType.STRING,
                            ""
                        )
                    )
                )
            }
        }
        val parameter = MamlDataParameter("label", MamlDataType.STRING, "")
        rejects { source.copy(parameters = listOf(parameter, parameter)) }
        rejects { source.copy(tag = "not a tag") }
    }
}
