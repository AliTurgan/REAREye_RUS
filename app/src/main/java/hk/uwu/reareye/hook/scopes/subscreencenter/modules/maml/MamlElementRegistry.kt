package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import org.w3c.dom.Element
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Each hook installation owns its registry; unknown tags never replace host factories. */
class MamlElementRegistry {
    private val factories = ConcurrentHashMap<String, MamlViewBackendFactory>()

    fun register(tag: String, factory: MamlViewBackendFactory) {
        require(tag.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*"))) { "Invalid MAML tag: $tag" }
        check(factories.putIfAbsent(tag.lowercase(Locale.ROOT), factory) == null) {
            "MAML tag already registered: $tag"
        }
    }

    fun find(tag: String): MamlViewBackendFactory? = factories[tag.lowercase(Locale.ROOT)]
}

internal fun copyMamlCarrierXml(source: Element): Element {
    // Preserve ScreenElement animation/trigger configuration, but reject render children.
    for (index in 0 until source.childNodes.length) {
        val child = source.childNodes.item(index) as? Element ?: continue
        require(
            child.tagName.endsWith("Animation", ignoreCase = true) ||
                    child.tagName.equals("Triggers", ignoreCase = true)
        ) {
            "Backend elements cannot contain MAML render children: ${child.tagName}"
        }
    }
    val copy = source.cloneNode(true) as Element
    copy.setAttribute("frameRate", "-1")
    // Keep named elements exportable through the host ViewHolder registry.
    return copy
}
