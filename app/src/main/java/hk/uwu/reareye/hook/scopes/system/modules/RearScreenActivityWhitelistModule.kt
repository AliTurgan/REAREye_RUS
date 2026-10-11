package hk.uwu.reareye.hook.scopes.system.modules

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.makeAccessible
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

class RearScreenActivityWhitelistModule : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadSystem {
            // MIUI records a short-lived target-package grant only for trusted PendingIntent
            // senders. The rear-screen widget host must follow the same path as the launcher;
            // otherwise a widget broadcast has an AOSP BAL token but MIUI still rejects it.
            // Keep MIUI's target resolution, wake checks and native 5-second expiry unchanged.
            runCatching {
                val senderField = "com.android.server.am.PendingIntentRecordImpl".toClass()
                    .getDeclaredField("sIgnorePackages").apply { makeAccessible() }

                @Suppress("UNCHECKED_CAST")
                val senders = senderField.get(null) as MutableSet<String>
                val host = "com.xiaomi.subscreencenter"
                val added = synchronized(senders) { senders.add(host) }
                if (added) runtime.onClose { synchronized(senders) { senders.remove(host) } }
                YLog.info("Rear Widget host registered for MIUI temporary PendingIntent launch grants")
            }.onFailure {
                YLog.warn(
                    "Rear Widget temporary PendingIntent launch grants unavailable",
                    it
                )
            }
            val asiRef = "com.android.server.wm.ActivityStarterImpl".toClass().resolve()
            val activityInfoClz = "android.content.pm.ActivityInfo".toClass()
            val activityRecordClz = "com.android.server.wm.ActivityRecord".toClass()
            asiRef.optional().firstMethodOrNull {
                name = "isShouldShowOnRearDisplay"
                parameters(activityInfoClz)
                returnType = Boolean::class.java
            }?.hook {
                hotReloadPolicy = HotReloadPolicy.KEEP
                before {
                    if (!hookPrefs.getBoolean(
                            ConfigKeys.HOOK_ACTIVITIES_WHITELIST,
                            true
                        )
                    ) return@before
                    val whitelist = hookPrefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS)
                    val field = asiRef.firstField {
                        name = "REAR_SCREEN_METADATA_WHITE_LIST"
                        type = Set::class.java
                    }
                    val set = field.get<HashSet<String>>() ?: return@before
                    set.clear()
                    set.add("com.retroarch")
                    set.addAll(whitelist)
                    YLog.debug("Injected Activities Whitelist")
                }

                after {
                    if (hookPrefs.getBoolean(ConfigKeys.ALLOW_ALL_ACTIVITIES, false)) {
                        result = true
                    }
                }
            } ?: asiRef.optional().firstMethodOrNull {
                name = "isShouldShowOnRearDisplay"
                parameters(activityRecordClz)
                returnType = Boolean::class.java
            }?.hook {
                hotReloadPolicy = HotReloadPolicy.KEEP
                before {
                    if (!hookPrefs.getBoolean(
                            ConfigKeys.HOOK_ACTIVITIES_WHITELIST,
                            true
                        )
                    ) return@before
                    val whitelist = hookPrefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS)
                    val field = asiRef.firstField {
                        name = "REAR_SCREEN_METADATA_WHITE_LIST"
                        type = Set::class.java
                    }
                    val set = field.get<HashSet<String>>() ?: return@before
                    set.clear()
                    set.add("com.retroarch")
                    set.addAll(whitelist)
                    YLog.debug("Injected Activities Whitelist")
                }

                after {
                    if (hookPrefs.getBoolean(ConfigKeys.ALLOW_ALL_ACTIVITIES, false)) {
                        result = true
                    }
                }
            }


            asiRef.firstMethod {
                name = "isAllowedToStartOnRearDisplay"
                returnType = Boolean::class.java
            }.hook {
                hotReloadPolicy = HotReloadPolicy.KEEP
                after {
                    if (hookPrefs.getBoolean(ConfigKeys.ALLOW_ALL_ACTIVITIES, false)) {
                        result = true
                        return@after
                    }
                    if (!hookPrefs.getBoolean(
                            ConfigKeys.HOOK_ACTIVITIES_WHITELIST,
                            true
                        )
                    ) return@after
                    val whitelist = hookPrefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS)
                    val inWhitelist = result<Boolean>()
                    if (inWhitelist == false) {
                        val arObj = args(0).value ?: return@after
                        val packageName = arObj.asResolver().firstField {
                            name = "packageName"
                            type = String::class.java
                        }.get<String>()
                        if (whitelist.contains(packageName)) {
                            result = true
                            YLog.debug("Allow starting $packageName while rear screen is locked")
                        }
                    }
                }
            }

            asiRef.firstMethod {
                name = "handlerTransitionFinished"
            }.hook {
                hotReloadPolicy = HotReloadPolicy.KEEP
                before {
                    if (hookPrefs.getBoolean(ConfigKeys.HOOK_SKIP_LOCK_BACK_HOME, false)) {
                        val arg = args(3)
                        arg.value = false
                    }
                }
            }
        }
    }
}
