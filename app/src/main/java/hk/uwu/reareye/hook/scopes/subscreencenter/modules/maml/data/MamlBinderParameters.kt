package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import org.w3c.dom.Element

/** XML configuration is compiled by the bridge; data source implementations never see XML. */
internal class MamlBinderParameters(
    private val parameters: List<BoundParameter>,
    private val compile: (String, MamlDataType) -> (() -> Any?),
) {
    internal data class BoundParameter(
        val specification: MamlDataParameter,
        val literal: Any,
        val expression: String?,
    )

    private val evaluators = mutableMapOf<String, () -> Any?>()

    @Synchronized
    fun evaluate(): MamlDataRequest = MamlDataRequest(parameters.associate { bound ->
        val spec = bound.specification
        val value = if (bound.expression != null) {
            evaluators.getOrPut(spec.name) { compile(bound.expression, spec.type) }.invoke()
        } else bound.literal
        spec.name to spec.coerce(value)
    })

    /** Compiled host Expressions may own Variables; release them on finish, recompile on reuse. */
    @Synchronized
    fun clearExpressions() {
        evaluators.clear()
    }

    companion object {
        fun parse(
            element: Element,
            definition: MamlDataSourceDefinition,
            compile: (String, MamlDataType) -> (() -> Any?),
        ): MamlBinderParameters {
            val parameters = definition.parameters.map { spec ->
                val expressionName = "${spec.name}Exp"
                val expression = element.getAttribute(expressionName)
                    .takeIf { element.hasAttribute(expressionName) }
                require(expression == null || expression.isNotBlank()) { "Empty expression: $expressionName" }
                val literal =
                    if (element.hasAttribute(spec.name)) spec.coerce(element.getAttribute(spec.name))
                    else spec.coerce(spec.defaultValue)
                BoundParameter(spec, literal, expression)
            }
            return MamlBinderParameters(parameters, compile)
        }
    }
}
