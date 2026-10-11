package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Looper
import android.os.UserHandle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import kotlin.math.min
import kotlin.math.sqrt

/** prepare runs on a worker; render runs on main, at most once per Choreographer frame. */
fun interface AppWidgetPreviewLoader {
    fun prepare(
        info: AppWidgetProviderInfo,
        width: Int,
        height: Int,
        category: Int
    ): AppWidgetPreviewPlan
}

fun interface AppWidgetPreviewPlan {
    fun render(): AppWidgetPreview
}

/** Optional system-only continuation. Custom injected loaders keep their original single-method API. */
internal interface AppWidgetFallbackPreviewPlan : AppWidgetPreviewPlan {
    fun prepareFallback(): AppWidgetPreviewPlan?
}

/** kind is generated, layout, image, icon, or none; failed candidate provenance stays in errors. */
data class AppWidgetPreview(val bitmap: Bitmap?, val kind: String, val error: String = "")

/** No widget IDs, binding, hidden APIs, activities, or bitmap recycling. */
class AppWidgetSystemPreviewLoader(context: Context) : AppWidgetPreviewLoader {
    private val context = context.let { caller ->
        val app = caller.applicationContext ?: error("Application context required")
        val display = runCatching { app.createDisplayContext(caller.display) }.getOrDefault(app)
        display.createConfigurationContext(android.content.res.Configuration(caller.resources.configuration))
    }

    override fun prepare(
        info: AppWidgetProviderInfo,
        width: Int,
        height: Int,
        category: Int
    ): AppWidgetPreviewPlan {
        val key = AppWidgetCatalogCache.previewKey(context, info, width, height, category)
        val generation = AppWidgetCatalogCache.generation
        AppWidgetCatalogCache.preview(key)?.let { cached ->
            return AppWidgetPreviewPlan {
                check(Looper.myLooper() == Looper.getMainLooper()) { "Preview rendering requires main" }
                cached
            }
        }
        check(Looper.myLooper() != Looper.getMainLooper()) { "Preview preparation requires worker" }
        val errors = mutableListOf<String>()
        // Factories load only the candidate currently needed, on the worker. Failed RemoteViews
        // return a continuation instead of loading resources or applying another view on that frame.
        val candidates = listOf<Pair<String, () -> (() -> Bitmap)?>>(
            "generated" to {
                val manager = AppWidgetManager.getInstance(context)
                val method = manager.javaClass.methods.firstOrNull {
                    it.name == "getWidgetPreview" && it.parameterTypes.contentEquals(
                        arrayOf(
                            ComponentName::class.java,
                            UserHandle::class.java,
                            Int::class.javaPrimitiveType
                        )
                    )
                }
                val views =
                    method?.invoke(manager, info.provider, info.profile, category) as? RemoteViews
                views?.let { { renderRemoteViews(it, info, width, height) } }
            },
            "previewLayout" to {
                if (Build.VERSION.SDK_INT >= 31 && info.previewLayout != 0) {
                    val views = RemoteViews(info.provider.packageName, info.previewLayout)
                    val render: () -> Bitmap = { renderRemoteViews(views, info, width, height) }
                    render
                } else null
            },
            "previewImage" to {
                info.loadPreviewImage(context, context.resources.displayMetrics.densityDpi)
                    ?.let { prepareDrawable(it, width, height) }
            },
            "providerIcon" to {
                if (info.icon != 0) {
                    val resources =
                        context.packageManager.getResourcesForApplication(info.provider.packageName)
                    prepareDrawable(
                        requireNotNull(
                            resources.getDrawableForDensity(
                                info.icon,
                                context.resources.displayMetrics.densityDpi, null
                            )
                        ), width, height
                    )
                } else null
            },
            "appIcon" to {
                prepareDrawable(
                    context.packageManager.getApplicationIcon(info.provider.packageName),
                    width,
                    height
                )
            },
        )

        fun next(start: Int): AppWidgetPreviewPlan {
            check(Looper.myLooper() != Looper.getMainLooper())
            for (index in start until candidates.size) {
                val (kind, factory) = candidates[index]
                val render = try {
                    factory()
                } catch (e: Exception) {
                    errors += "$kind: ${e.javaClass.simpleName}"
                    null
                } ?: continue
                return object : AppWidgetFallbackPreviewPlan {
                    override fun render(): AppWidgetPreview {
                        check(Looper.myLooper() == Looper.getMainLooper()) { "Preview rendering requires main" }
                        val preview = try {
                            val outputKind = when (kind) {
                                "previewLayout" -> "layout"
                                "previewImage" -> "image"
                                "providerIcon", "appIcon" -> "icon"
                                else -> kind
                            }
                            AppWidgetPreview(render(), outputKind, errors.joinToString("; "))
                        } catch (e: Exception) {
                            errors += "$kind: ${e.javaClass.simpleName}"
                            AppWidgetPreview(null, "none", errors.joinToString("; "))
                        }
                        if (preview.bitmap != null) AppWidgetCatalogCache.rememberPreview(
                            key,
                            preview,
                            generation
                        )
                        return preview
                    }

                    override fun prepareFallback(): AppWidgetPreviewPlan = next(index + 1)
                }
            }
            return AppWidgetPreviewPlan {
                check(Looper.myLooper() == Looper.getMainLooper())
                AppWidgetPreview(
                    null,
                    "none",
                    errors.joinToString("; ").ifEmpty { "No preview available" })
                    .also { AppWidgetCatalogCache.rememberPreview(key, it, generation) }
            }
        }
        return next(0)
    }

    private fun prepareDrawable(drawable: Drawable, width: Int, height: Int): () -> Bitmap {
        check(Looper.myLooper() != Looper.getMainLooper())
        // Exact platform classes only: subclasses may execute arbitrary/UI drawing callbacks.
        val staticClass =
            drawable.javaClass == android.graphics.drawable.ColorDrawable::class.java ||
                    drawable.javaClass == android.graphics.drawable.BitmapDrawable::class.java
        val owned = if (staticClass && drawable.callback == null)
            drawable.constantState?.newDrawable(context.resources)?.mutate() else null
        val safe = owned != null && owned.callback == null &&
                (owned.javaClass == android.graphics.drawable.ColorDrawable::class.java ||
                        (owned.javaClass == android.graphics.drawable.BitmapDrawable::class.java &&
                                (owned as android.graphics.drawable.BitmapDrawable).bitmap.config != Bitmap.Config.HARDWARE))
        if (safe) {
            val bitmap = drawDrawable(requireNotNull(owned), width, height)
            return { bitmap }
        }
        return { renderDrawable(drawable, width, height) }
    }

    private fun renderRemoteViews(
        views: RemoteViews,
        info: AppWidgetProviderInfo,
        width: Int,
        height: Int
    ): Bitmap {
        check(Looper.myLooper() == Looper.getMainLooper())
        val parent = FrameLayout(context)
        val view = views.apply(context, parent)
        val density = context.resources.displayMetrics.density
        val naturalWidth = info.minWidth.takeIf { it > 0 } ?: (300 * density).toInt()
        val naturalHeight = info.minHeight.takeIf { it > 0 } ?: (150 * density).toInt()
        return renderView(view, naturalWidth, naturalHeight, width, height)
    }

    /** Natural layout is independent of the target canvas aspect ratio. No intermediate large bitmap. */
    internal fun renderView(
        view: View,
        naturalWidth: Int,
        naturalHeight: Int,
        width: Int,
        height: Int
    ): Bitmap {
        check(Looper.myLooper() == Looper.getMainLooper())
        val fixed = fixedExtent(view, includeMargins = false)
        fun spec(layoutSize: Int?, natural: Int, fixedLowerBound: Int): Int = when {
            layoutSize == ViewGroup.LayoutParams.WRAP_CONTENT -> View.MeasureSpec.makeMeasureSpec(
                4096,
                View.MeasureSpec.AT_MOST
            )

            else -> {
                val preferred = layoutSize?.takeIf { it > 0 } ?: natural
                View.MeasureSpec.makeMeasureSpec(
                    maxOf(preferred, fixedLowerBound).coerceIn(
                        1,
                        4096
                    ), View.MeasureSpec.EXACTLY
                )
            }
        }
        view.measure(
            spec(view.layoutParams?.width, naturalWidth, fixed.first),
            spec(view.layoutParams?.height, naturalHeight, fixed.second)
        )
        val measuredWidth = view.measuredWidth.coerceIn(1, 4096)
        val measuredHeight = view.measuredHeight.coerceIn(1, 4096)
        view.layout(0, 0, measuredWidth, measuredHeight)
        return fitBitmap(measuredWidth, measuredHeight, width, height) { view.draw(it) }
    }

    /** Includes fixed descendants before measurement, rather than discovering clipped content afterwards. */
    internal fun fixedExtent(view: View, includeMargins: Boolean = true): Pair<Int, Int> {
        if (view.visibility == View.GONE) return 0 to 0
        val params = view.layoutParams
        var width = maxOf(params?.width?.coerceAtLeast(0) ?: 0, view.minimumWidth)
        var height = maxOf(params?.height?.coerceAtLeast(0) ?: 0, view.minimumHeight)
        if (view is ViewGroup && view !is android.widget.ScrollView &&
            view !is android.widget.HorizontalScrollView && view !is android.widget.AdapterView<*>
        ) {
            var childrenWidth = 0L
            var childrenHeight = 0L
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                if (child.visibility == View.GONE) continue
                val fixed = fixedExtent(child)
                if (view is android.widget.LinearLayout && view.orientation == android.widget.LinearLayout.HORIZONTAL)
                    childrenWidth += fixed.first else childrenWidth =
                    maxOf(childrenWidth, fixed.first.toLong())
                if (view is android.widget.LinearLayout && view.orientation == android.widget.LinearLayout.VERTICAL)
                    childrenHeight += fixed.second else childrenHeight =
                    maxOf(childrenHeight, fixed.second.toLong())
            }
            width = maxOf(
                width,
                (childrenWidth + view.paddingLeft + view.paddingRight).coerceIn(0, 4096).toInt()
            )
            height = maxOf(
                height,
                (childrenHeight + view.paddingTop + view.paddingBottom).coerceIn(0, 4096).toInt()
            )
        }
        val margins = if (includeMargins) params as? ViewGroup.MarginLayoutParams else null
        return (width.toLong() + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0)).coerceIn(
            0,
            4096
        ).toInt() to
                (height.toLong() + (margins?.topMargin ?: 0) + (margins?.bottomMargin
                    ?: 0)).coerceIn(0, 4096).toInt()
    }

    internal fun renderDrawable(drawable: Drawable, width: Int, height: Int): Bitmap {
        check(Looper.myLooper() == Looper.getMainLooper())
        return drawDrawable(drawable, width, height)
    }

    private fun drawDrawable(drawable: Drawable, width: Int, height: Int): Bitmap {
        val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: width
        val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: height
        val oldBounds = android.graphics.Rect(drawable.bounds)
        return try {
            drawable.setBounds(0, 0, iw, ih)
            fitBitmap(iw, ih, width, height) { drawable.draw(it) }
        } finally {
            drawable.bounds = oldBounds
        }
    }

    private fun fitBitmap(
        naturalWidth: Int,
        naturalHeight: Int,
        width: Int,
        height: Int,
        draw: (Canvas) -> Unit
    ): Bitmap {
        val scale = min(width.toFloat() / naturalWidth, height.toFloat() / naturalHeight)
        val fittedWidth = kotlin.math.round(naturalWidth * scale).toInt().coerceIn(1, width)
        val fittedHeight = kotlin.math.round(naturalHeight * scale).toInt().coerceIn(1, height)
        // Let the theme perform its one fit. Adding a budget-sized transparent canvas here would fit twice.
        val drawScale =
            min(fittedWidth.toFloat() / naturalWidth, fittedHeight.toFloat() / naturalHeight)
        return Bitmap.createBitmap(fittedWidth, fittedHeight, Bitmap.Config.ARGB_8888)
            .also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.scale(drawScale, drawScale)
                draw(canvas)
            }
    }
}

/** ARGB_8888 output budget is 8 MiB per page; shared retained previews have a separate 16 MiB bound. */
object AppWidgetPreviewBudget {
    const val PAGE_BYTES = 8 * 1024 * 1024
    fun dimensions(width: Int, height: Int, count: Int): Pair<Int, Int> {
        val w = width.coerceIn(1, 512)
        val h = height.coerceIn(1, 512)
        val rows = count.coerceIn(1, 32)
        // Uniform 256px cap across pages avoids cache-key changes when a preview is promoted.
        val scale = min(
            min(1.0, min(256.0 / w, 256.0 / h)),
            sqrt(PAGE_BYTES.toDouble() / (rows.toLong() * w * h * 4))
        )
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }
}
