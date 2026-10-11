package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Each host card gets a separate native MAML ConfigFile, never a shared preference selection. */
internal class MamlCardScopeFiles(private val directory: File) {
    fun config(storageIdentity: String): File {
        require(validStorageIdentity(storageIdentity)) { "A canonical host card storage identity is required" }
        // Numeric identities retain the exact v1 digest, including persistent card 0.
        val name =
            MessageDigest.getInstance("SHA-256").digest(storageIdentity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return File(directory, "$name.xml")
    }

    @Synchronized
    fun prepare(storageIdentity: String, legacyIdentity: String? = null): File {
        val file = config(storageIdentity)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create per-card MAML config directory" }
        check(!file.exists() || file.isFile) { "Per-card MAML config is not a file" }
        // Only the current native id is eligible. Never scan by provider or borrow another card.
        if (storageIdentity.startsWith("module:") && legacyIdentity != null) {
            require(legacyIdentity.toIntOrNull()?.toString() == legacyIdentity) {
                "A canonical numeric legacy card identity is required"
            }
            if (!file.exists()) {
                val legacy = config(legacyIdentity)
                if (legacy.exists()) {
                    check(legacy.isFile && legacy.canRead()) { "Legacy MAML config is not a readable file" }
                    // Move bytes unchanged, including empty files; validation belongs to the host bridge.
                    check(legacy.renameTo(file)) { "Cannot migrate legacy per-card MAML config" }
                }
            }
        }
        return file
    }

    companion object {
        private const val MODULE_PACKAGE = "hk.uwu.reareye"
        private val BUSINESS = Regex("[A-Za-z0-9_.-]+")
        private val CANONICAL_UUID =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun moduleIdentity(
            origin: String?,
            pkg: String?,
            business: String?,
            cardId: String?
        ): String? {
            if (origin != MODULE_PACKAGE || pkg != MODULE_PACKAGE || business == null ||
                !BUSINESS.matches(business) || cardId == null || !CANONICAL_UUID.matches(cardId)
            ) return null
            return "module:$MODULE_PACKAGE:$business:$cardId"
        }

        fun validStorageIdentity(value: String): Boolean {
            if (value.toIntOrNull()?.toString() == value ||
                (value.startsWith("app:") && value.removePrefix("app:").isNotBlank())
            ) return true
            val parts = value.split(':')
            return parts.size == 4 && parts[0] == "module" &&
                    moduleIdentity(MODULE_PACKAGE, parts[1], parts[2], parts[3]) == value
        }

        fun preservesBinding(identity: String): Boolean = validStorageIdentity(identity) &&
                (identity.startsWith("app:") || identity.startsWith("module:"))

        /** Native registry cleanup is never permission to erase an application or module definition. */
        fun onNativeRemoval(
            storageIdentity: String,
            retireRoots: () -> Unit,
            deleteBinding: () -> Unit
        ) {
            require(validStorageIdentity(storageIdentity))
            retireRoots()
            if (!preservesBinding(storageIdentity)) deleteBinding()
        }

        fun appIdentity(resId: String?): String? =
            resId?.takeIf { it.isNotBlank() }?.let { "app:$it" }

        fun storageIdentity(
            nativeId: String,
            type: Int?,
            appCard: Boolean,
            resId: String?,
            nativeClass: Boolean
        ): String? {
            if (nativeId.toIntOrNull()?.toString() != nativeId) return null
            if (type == 7 || appCard) return if (type == 7 && appCard) appIdentity(resId) else null
            return nativeId.takeIf { type != null || nativeClass }
        }

        fun newInstance(): String = "rear-card:${UUID.randomUUID()}"
        fun validInstance(value: String?): Boolean = value != null &&
                value.matches(Regex("rear-card:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    }
}
