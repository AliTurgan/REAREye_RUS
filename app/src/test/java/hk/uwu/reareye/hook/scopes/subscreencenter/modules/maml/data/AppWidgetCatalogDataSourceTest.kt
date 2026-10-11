package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppWidgetCatalogDataSourceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())

    @org.junit.Before
    fun resetCaches() {
        AppWidgetCatalogCache.invalidate()
    }

    private class QueueWorker : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        var stopped = false
        override fun execute(command: Runnable) {
            tasks += command
        }

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true; return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
        fun drain() {
            val pending = tasks.toList().also { tasks.clear() }
            Thread { pending.forEach { it.run() } }.apply { start(); join() }
        }
    }

    private fun pump(worker: QueueWorker) {
        repeat(192) { worker.drain(); main.idleFor(17, TimeUnit.MILLISECONDS) }
    }

    private fun renderWithFallback(initial: AppWidgetPreviewPlan): AppWidgetPreview {
        var plan = initial
        repeat(6) {
            val preview = plan.render()
            if (preview.bitmap != null || plan !is AppWidgetFallbackPreviewPlan) return preview
            val failed = plan as AppWidgetFallbackPreviewPlan
            var next: AppWidgetPreviewPlan? = null
            Thread { next = failed.prepareFallback() }.apply { start(); join() }
            plan = next ?: return preview
        }
        error("Too many fallback stages")
    }

    private fun entry(
        pkg: String,
        app: String,
        name: String,
        provider: String = "$pkg.Widget",
        category: Int = 1
    ) = AppWidgetCatalogEntry(AppWidgetProviderInfo().apply {
        this.provider = ComponentName(pkg, provider)
        widgetCategory = category
        minWidth = 12
        minHeight = 8
    }, app, name)

    private fun request(vararg changes: Pair<String, Any>) = MamlDataRequest(
        AppWidgetCatalogDataSource.definition().parameters.associate { it.name to it.coerce(it.defaultValue) } + changes)

    private val none = AppWidgetPreviewLoader { _, _, _, _ ->
        AppWidgetPreviewPlan {
            AppWidgetPreview(
                null,
                "none"
            )
        }
    }

    @Test
    fun sortingSearchCategoryAndCanonicalProviderAreStable() {
        val rows = listOf(
            entry("z", "Alpha", "B"),
            entry("y", "Alpha", "a"),
            entry("x", "beta", "A", category = 2)
        )
        assertEquals(
            listOf("y", "z"),
            AppWidgetCatalogDataSource.select(rows, "", "", 1).map { it.packageName })
        assertEquals(
            listOf("x"),
            AppWidgetCatalogDataSource.select(rows, "", "BETA", 0).map { it.packageName })
        assertEquals(
            listOf("y"),
            AppWidgetCatalogDataSource.select(rows, "y", "widget", 1).map { it.packageName })
        assertEquals("y/y.Widget", rows[1].provider)
        assertEquals("y/y.Widget", entry("y", "Y", "Widget", provider = ".Widget").provider)
    }

    @Test
    fun pageAndEmptyResultHaveSchemaSizedArrays() {
        val worker = QueueWorker()
        var onWorker = false
        val source = AppWidgetCatalogDataSource(context, AppWidgetCatalogProvider {
            onWorker = Looper.myLooper() != Looper.getMainLooper()
            listOf(entry("b", "B", "Widget"), entry("a", "A", "Widget"), entry("c", "C", "Widget"))
        }, none, worker)
        try {
            var output: MamlDataResult? = null
            source.query(request("offset" to 1.0, "limit" to 1.0)) { output = it }
            assertNull(output)
            pump(worker)
            assertTrue(onWorker)
            val snapshot =
                AppWidgetCatalogDataSource.schema.validate((output as MamlDataResult.Success).snapshot)
            assertEquals(1, snapshot.count)
            assertArrayEquals(arrayOf("B"), snapshot.values["appNames"] as Array<*>)
            assertEquals(3.0, snapshot.values["total"])
            assertEquals(1.0, snapshot.values["hasMore"])
            source.query(request("offset" to 100.0)) { output = it }
            pump(worker)
            val empty =
                AppWidgetCatalogDataSource.schema.validate((output as MamlDataResult.Success).snapshot)
            assertEquals(0, empty.count)
            assertEquals(100.0, empty.values["offset"])
            assertEquals(0.0, empty.values["hasMore"])
        } finally {
            source.close()
        }
        assertFalse(worker.stopped) // Injected executors remain caller-owned.
    }

    @Test
    fun perProviderFailureDoesNotPoisonPageAndRenderRunsOnMain() {
        val worker = QueueWorker()
        val source = AppWidgetCatalogDataSource(
            context,
            AppWidgetCatalogProvider { listOf(entry("a", "A", "A"), entry("b", "B", "B")) },
            AppWidgetPreviewLoader { info, width, height, _ ->
                assertTrue(Looper.getMainLooper() != Looper.myLooper())
                if (info.provider.packageName == "a") error("bad provider")
                AppWidgetPreviewPlan {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    AppWidgetPreview(
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
                        "icon"
                    )
                }
            }, worker
        )
        try {
            var output: MamlDataResult? = null
            source.query(request()) { output = it }
            pump(worker)
            val snapshot = (output as MamlDataResult.Success).snapshot
            assertArrayEquals(arrayOf("none", "icon"), snapshot.values["previewKinds"] as Array<*>)
            assertNull((snapshot.values["previews"] as Array<*>)[0])
            assertTrue((snapshot.values["previewErrors"] as Array<*>)[0].toString().isNotEmpty())
        } finally {
            source.close()
        }
    }

    @Test
    fun pixelDimensionsArePublishedAsDpAtDensityTwo() {
        val row = entry("a", "A", "A")
        val snapshot = AppWidgetCatalogDataSource.schema.validate(
            AppWidgetCatalogDataSource.snapshot(
                listOf(row), listOf(AppWidgetPreview(null, "none")), 1, 0, density = 2.0
            )
        )
        assertArrayEquals(doubleArrayOf(6.0), snapshot.values["minWidths"] as DoubleArray, 0.0)
        assertArrayEquals(doubleArrayOf(4.0), snapshot.values["minHeights"] as DoubleArray, 0.0)
    }

    @Test
    fun pixelBudgetShrinksThirtyTwoLargePreviews() {
        val (width, height) = AppWidgetPreviewBudget.dimensions(512, 512, 32)
        assertTrue(width < 512)
        assertTrue(32L * width * height * 4 <= AppWidgetPreviewBudget.PAGE_BYTES)
        assertEquals(256 to 160, AppWidgetPreviewBudget.dimensions(320, 200, 12))
        assertEquals(
            AppWidgetPreviewBudget.dimensions(512, 320, 1),
            AppWidgetPreviewBudget.dimensions(512, 320, 32)
        ) // Promotion must reuse the same cache key.
        val (w, h) = AppWidgetPreviewBudget.dimensions(Int.MAX_VALUE, 1, 32)
        assertTrue(w in 1..512 && h in 1..512)
    }

    @Test
    fun cancellationPauseAndFinishSuppressQueuedResultsIdempotently() {
        val worker = QueueWorker()
        val source = AppWidgetCatalogDataSource(
            context,
            AppWidgetCatalogProvider { listOf(entry("a", "A", "A")) },
            none,
            worker
        )
        var callbacks = 0
        val cancelled = source.query(request()) { callbacks++ }
        cancelled.close(); cancelled.close()
        pump(worker)
        assertEquals(0, callbacks)
        source.query(request()) { callbacks++ }
        worker.drain() // Result rendering is queued on main.
        source.onPause(); main.idle()
        assertEquals(0, callbacks)
        source.onResume()
        source.query(request()) { callbacks++ }
        worker.drain()
        source.close(); source.close(); main.idle()
        assertEquals(0, callbacks)
    }

    @Test
    fun multipleSourcesShareWatchersAndDetachDoesNotAffectOtherSource() {
        val first = AppWidgetCatalogDataSource(
            context,
            AppWidgetCatalogProvider { emptyList() },
            none,
            QueueWorker()
        )
        val second = AppWidgetCatalogDataSource(
            context,
            AppWidgetCatalogProvider { emptyList() },
            none,
            QueueWorker()
        )
        val before = shadowOf(context).registeredReceivers.size
        var a = 0
        var b = 0
        try {
            first.subscribeInvalidations { a++ }
            second.subscribeInvalidations { b++ }
            assertEquals(before + 1, shadowOf(context).registeredReceivers.size)
            first.onPause()
            assertEquals(before + 1, shadowOf(context).registeredReceivers.size)
            context.sendBroadcast(
                Intent(
                    Intent.ACTION_PACKAGE_REPLACED,
                    Uri.parse("package:example")
                )
            )
            context.sendBroadcast(Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:other")))
            main.idle()
            assertEquals(0, a)
            assertEquals(1, b) // Same-main-turn package changes coalesce.
            context.onConfigurationChanged(android.content.res.Configuration(context.resources.configuration))
            main.idle()
            assertEquals(2, b)
            second.close()
            assertEquals(before, shadowOf(context).registeredReceivers.size)
            first.onResume()
            assertEquals(before + 1, shadowOf(context).registeredReceivers.size)
        } finally {
            first.close(); second.close()
        }
        assertEquals(before, shadowOf(context).registeredReceivers.size)
    }

    @Test
    fun publicPreviewLayoutIsRenderedWithoutBinding() {
        val info = entry("android", "Android", "Layout").info.apply {
            previewLayout = android.R.layout.simple_list_item_1
        }
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val before = manager.getAppWidgetIds(info.provider)
        lateinit var plan: AppWidgetPreviewPlan
        Thread {
            plan = AppWidgetSystemPreviewLoader(context).prepare(info, 48, 24, 1)
        }.apply { start(); join() }
        val result = renderWithFallback(plan)
        assertEquals("layout", result.kind)
        assertTrue(result.bitmap!!.width in 1..48)
        assertTrue(result.bitmap.height in 1..24)
        assertTrue(result.bitmap.width == 48 || result.bitmap.height == 24)
        assertArrayEquals(before, manager.getAppWidgetIds(info.provider))
    }

    @Test
    fun brokenPreviewLayoutFallsBackToApplicationIcon() {
        val info = entry(context.packageName, "App", "Broken").info.apply {
            previewLayout = 999; icon = 999
        }
        // Config.NONE does not guarantee that the test application has a drawable icon.
        shadowOf(context.packageManager).setApplicationIcon(
            context.packageName,
            android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE)
        )
        lateinit var plan: AppWidgetPreviewPlan
        Thread {
            plan = AppWidgetSystemPreviewLoader(context).prepare(info, 32, 24, 1)
        }.apply { start(); join() }
        val result = renderWithFallback(plan)
        assertEquals("icon", result.kind)
        assertNotNull(result.bitmap)
        assertTrue(result.error.contains("previewLayout"))
    }

    @Test
    fun roundTripPagesReuseDirectoryAndMatchingPreviewUntilPackageInvalidation() {
        val worker = QueueWorker()
        var enumerations = 0
        var preparations = 0
        var renders = 0
        val source = AppWidgetCatalogDataSource(context, AppWidgetCatalogProvider {
            enumerations++
            listOf(entry("a", "A", "A"), entry("b", "B", "B"))
        }, AppWidgetPreviewLoader { _, w, h, _ ->
            preparations++
            AppWidgetPreviewPlan {
                renders++; AppWidgetPreview(
                Bitmap.createBitmap(
                    w,
                    h,
                    Bitmap.Config.ARGB_8888
                ), "layout"
            )
            }
        }, worker)
        source.subscribeInvalidations {}
        try {
            var output: MamlDataResult? = null
            fun page(offset: Int): Bitmap? {
                source.query(request("offset" to offset.toDouble(), "limit" to 1.0)) { output = it }
                pump(worker)
                return ((output as MamlDataResult.Success).snapshot.values["previews"] as Array<*>)[0] as Bitmap?
            }

            val first = page(0)
            page(1)
            assertSame(first, page(0))
            assertEquals(1, enumerations)
            assertEquals(2, preparations)
            assertEquals(2, renders)
            context.sendBroadcast(Intent(Intent.ACTION_PACKAGE_REPLACED, Uri.parse("package:a")))
            main.idle()
            assertNotSame(first, page(0))
            assertEquals(2, enumerations)
            assertEquals(3, preparations)
            assertEquals(3, renders)
            assertFalse(first!!.isRecycled)
        } finally {
            source.close()
        }
    }

    @Test
    fun appsAndWidgetBindersShareHostUserDirectoryDiscovery() {
        var discoveries = 0
        Thread {
            AppWidgetCatalogCache.loadCatalog(context) {
                discoveries++
                listOf(entry("shared.app", "Shared", "SharedWidget"))
            }
        }.apply { start(); join() }
        val appsWorker = QueueWorker()
        val catalogWorker = QueueWorker()
        val apps = AppWidgetAppsDataSource(context, executor = appsWorker)
        val catalog =
            AppWidgetCatalogDataSource(context, previewLoader = none, executor = catalogWorker)
        try {
            var appsOutput: MamlDataResult? = null
            var catalogOutput: MamlDataResult? = null
            apps.query(
                MamlDataRequest(
                    mapOf(
                        "offset" to 0.0,
                        "limit" to 3.0,
                        "category" to 1.0
                    )
                )
            ) { appsOutput = it }
            catalog.query(request()) { catalogOutput = it }
            appsWorker.drain(); pump(catalogWorker)
            assertEquals(1, discoveries)
            assertEquals(1, (appsOutput as MamlDataResult.Success).snapshot.count)
            assertArrayEquals(
                arrayOf("Shared"),
                (appsOutput as MamlDataResult.Success).snapshot.values["appNames"] as Array<*>
            )
            assertArrayEquals(
                arrayOf("SharedWidget"),
                (catalogOutput as MamlDataResult.Success).snapshot.values["widgetNames"] as Array<*>
            )
        } finally {
            apps.close(); catalog.close()
        }
    }

    @Test
    fun previewLruIsBoundedExpiresAndNeverRecyclesPublishedBitmaps() {
        var clock = 0L
        val cache = AppWidgetCatalogLru<String, Bitmap>(
            2,
            16,
            100,
            { it.allocationByteCount.toLong() },
            { clock })
        val a = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        val c = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        cache.put("a", a, 1); cache.put("b", b, 1)
        assertSame(a, cache.get("a", 1))
        cache.put("c", c, 1)
        assertNull(cache.get("b", 1))
        assertEquals(2, cache.size())
        assertTrue(cache.retainedWeight() <= 16)
        assertFalse(b.isRecycled)
        clock = 101
        assertNull(cache.get("a", 1))
        assertNull(cache.get("c", 2)) // A changed generation never reuses an old preview.
        assertEquals(0, cache.retainedWeight())
        cache.put("a", a, 2); cache.clear()
        assertFalse(a.isRecycled)
        assertEquals(0, cache.size())
    }

    @Test
    fun defaultLoaderSharesMatchingBitmapAndSeparatesConfigurationAndCategory() {
        shadowOf(context.packageManager).setApplicationIcon(
            context.packageName,
            android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE)
        )
        val info = entry(context.packageName, "App", "Widget").info
        fun load(
            loaderContext: android.content.Context = context,
            category: Int = 1
        ): AppWidgetPreview {
            lateinit var plan: AppWidgetPreviewPlan
            Thread {
                plan = AppWidgetSystemPreviewLoader(loaderContext).prepare(info, 32, 24, category)
            }.apply { start(); join() }
            return plan.render()
        }

        val first = load()
        assertEquals("icon", first.kind)
        assertSame(first.bitmap, load().bitmap)
        assertNotSame(first.bitmap, load(category = 2).bitmap)
        val changed = android.content.res.Configuration(context.resources.configuration)
            .apply { fontScale += .1f }
        assertNotSame(first.bitmap, load(context.createConfigurationContext(changed)).bitmap)
        AppWidgetCatalogCache.invalidate()
        assertNotSame(first.bitmap, load().bitmap)
        assertFalse(first.bitmap!!.isRecycled)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun landscapeAndPortraitViewsFitWithoutCropOrDistortion() {
        val loader = AppWidgetSystemPreviewLoader(context)
        fun draw(naturalWidth: Int, naturalHeight: Int): Bitmap {
            val view = object : android.view.View(context) {
                private val paint = android.graphics.Paint()
                override fun onDraw(canvas: android.graphics.Canvas) {
                    paint.color = android.graphics.Color.RED
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                    paint.color = android.graphics.Color.BLUE
                    canvas.drawRect(
                        width * .75f,
                        height * .75f,
                        width.toFloat(),
                        height.toFloat(),
                        paint
                    )
                }
            }.apply { layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1) }
            val bitmap = loader.renderView(view, naturalWidth, naturalHeight, 100, 100)
            assertEquals(naturalWidth, view.measuredWidth)
            assertEquals(naturalHeight, view.measuredHeight)
            return bitmap
        }

        val wide = draw(200, 50)
        assertEquals(100, wide.width)
        assertEquals(25, wide.height)
        assertEquals(android.graphics.Color.RED, wide.getPixel(1, 1))
        assertEquals(android.graphics.Color.BLUE, wide.getPixel(98, 23))
        val tall = draw(50, 200)
        assertEquals(25, tall.width)
        assertEquals(100, tall.height)
        assertEquals(android.graphics.Color.RED, tall.getPixel(1, 1))
        assertEquals(android.graphics.Color.BLUE, tall.getPixel(23, 98))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun fixedRootLayoutAndDrawableIntrinsicRatioOverrideCanvasAspect() {
        val loader = AppWidgetSystemPreviewLoader(context)
        val view = android.view.View(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(240, 60)
            setBackgroundColor(android.graphics.Color.RED)
        }
        val preview = loader.renderView(view, 100, 100, 120, 120)
        assertEquals(240, view.measuredWidth)
        assertEquals(60, view.measuredHeight)
        assertEquals(120, preview.width)
        assertEquals(30, preview.height)
        assertEquals(android.graphics.Color.RED, preview.getPixel(1, 1))
        assertEquals(android.graphics.Color.RED, preview.getPixel(118, 28))
        val drawable =
            object : android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE) {
                override fun getIntrinsicWidth() = 60
                override fun getIntrinsicHeight() = 240
            }
        val icon = loader.renderDrawable(drawable, 120, 120)
        assertEquals(30, icon.width)
        assertEquals(120, icon.height)
        assertEquals(android.graphics.Color.BLUE, icon.getPixel(1, 1))
        assertEquals(android.graphics.Color.BLUE, icon.getPixel(28, 118))
        assertEquals(android.graphics.Rect(), drawable.bounds)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun matchParentRootExpandsForOversizedFixedChildAndShowsEveryEdge() {
        val loader = AppWidgetSystemPreviewLoader(context)
        val root = android.widget.FrameLayout(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
            setPadding(10, 20, 30, 40)
        }
        val child = object : android.view.View(context) {
            private val paint = android.graphics.Paint()
            override fun onDraw(canvas: android.graphics.Canvas) {
                paint.color = android.graphics.Color.RED
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.color = android.graphics.Color.BLUE
                canvas.drawRect(width - 8f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.color = android.graphics.Color.GREEN
                canvas.drawRect(0f, height - 8f, width.toFloat(), height.toFloat(), paint)
            }
        }
        root.addView(
            child,
            android.widget.FrameLayout.LayoutParams(600, 100).apply { setMargins(7, 11, 9, 13) })
        val preview = loader.renderView(root, 300, 80, 328, 92)
        assertEquals(656, root.measuredWidth)
        assertEquals(184, root.measuredHeight)
        assertEquals(600, child.measuredWidth)
        assertEquals(android.graphics.Color.RED, preview.getPixel(9, 16))
        assertEquals(
            android.graphics.Color.BLUE,
            preview.getPixel(306, 30)
        ) // Far right must survive, not just the left half.
        assertEquals(
            android.graphics.Color.GREEN,
            preview.getPixel(100, 63)
        ) // Complete bottom edge survives too.
        assertEquals(android.graphics.Color.TRANSPARENT, preview.getPixel(327, 91))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun linearLayoutFixedChildrenSumWithMarginsAndPaddingBeforeFit() {
        val loader = AppWidgetSystemPreviewLoader(context)
        fun layout(orientation: Int): android.widget.LinearLayout {
            val root = android.widget.LinearLayout(context).apply {
                this.orientation = orientation
                layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
                setPadding(8, 6, 12, 10)
            }
            root.addView(
                android.view.View(context).apply { setBackgroundColor(android.graphics.Color.RED) },
                android.widget.LinearLayout.LayoutParams(300, 80).apply { setMargins(5, 3, 7, 5) })
            root.addView(
                android.view.View(context)
                    .apply { setBackgroundColor(android.graphics.Color.BLUE) },
                android.widget.LinearLayout.LayoutParams(400, 120)
                    .apply { setMargins(9, 7, 11, 13) })
            root.addView(
                android.view.View(context).apply { visibility = android.view.View.GONE },
                android.widget.LinearLayout.LayoutParams(2000, 2000)
            )
            return root
        }

        val horizontal = layout(android.widget.LinearLayout.HORIZONTAL)
        assertEquals(752 to 156, loader.fixedExtent(horizontal, includeMargins = false))
        val wide = loader.renderView(horizontal, 200, 100, 376, 78)
        assertEquals(752, horizontal.measuredWidth)
        assertEquals(android.graphics.Color.BLUE, wide.getPixel(363, 65))
        val vertical = layout(android.widget.LinearLayout.VERTICAL)
        assertEquals(440 to 244, loader.fixedExtent(vertical, includeMargins = false))
        val tall = loader.renderView(vertical, 200, 100, 220, 122)
        assertEquals(244, vertical.measuredHeight)
        assertEquals(android.graphics.Color.BLUE, tall.getPixel(100, 109))
    }

    @Test
    fun scrollingViewportDoesNotExpandToOffscreenFixedContent() {
        val loader = AppWidgetSystemPreviewLoader(context)
        val horizontal = android.widget.HorizontalScrollView(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(240, 80)
            addView(android.view.View(context), android.widget.FrameLayout.LayoutParams(3000, 100))
        }
        val vertical = android.widget.ScrollView(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(120, 240)
            addView(android.view.View(context), android.widget.FrameLayout.LayoutParams(100, 3000))
        }
        assertEquals(240 to 80, loader.fixedExtent(horizontal))
        assertEquals(120 to 240, loader.fixedExtent(vertical))
    }

    @Test
    fun crossBinderSingleflightAndCancellationKeepOtherSubscriberAlive() {
        val worker = QueueWorker()
        val provider = AppWidgetCatalogProvider { listOf(entry("shared", "Shared", "Widget")) }
        var preparations = 0
        var renders = 0
        val loader = AppWidgetPreviewLoader { _, w, h, _ ->
            preparations++
            AppWidgetPreviewPlan {
                renders++; AppWidgetPreview(
                Bitmap.createBitmap(
                    w,
                    h,
                    Bitmap.Config.ARGB_8888
                ), "icon"
            )
            }
        }
        val first = AppWidgetCatalogDataSource(context, provider, loader, worker)
        val second = AppWidgetCatalogDataSource(context, provider, loader, worker)
        var firstCalls = 0
        var secondCalls = 0
        try {
            val cancelled = first.query(request("priority" to 2.0)) { firstCalls++ }
            second.query(request("priority" to 0.0)) { secondCalls++ }
            worker.drain(); main.idle() // Both subscribed; shared preparation is queued.
            cancelled.close()
            pump(worker)
            assertEquals(1, preparations)
            assertEquals(1, renders)
            assertEquals(0, firstCalls)
            assertEquals(1, secondCalls)
        } finally {
            first.close(); second.close()
        }
    }

    @Test
    fun visiblePagePublishesWithoutWaitingForOffscreenPage() {
        val worker = QueueWorker()
        val provider = AppWidgetCatalogProvider {
            (0..5).map { entry("p$it", "App$it", "Widget") }
        }
        val rendered = mutableListOf<String>()
        val published = mutableListOf<String>()
        val loader = AppWidgetPreviewLoader { info, _, _, _ ->
            AppWidgetPreviewPlan {
                rendered += info.provider.packageName; AppWidgetPreview(
                null,
                "none"
            )
            }
        }
        val offscreen = AppWidgetCatalogDataSource(context, provider, loader, worker)
        val visible = AppWidgetCatalogDataSource(context, provider, loader, worker)
        try {
            offscreen.query(request("limit" to 5.0, "priority" to 2.0)) { published += "offscreen" }
            visible.query(
                request(
                    "offset" to 5.0,
                    "limit" to 1.0,
                    "priority" to 0.0
                )
            ) { published += "visible" }
            pump(worker)
            assertEquals("p5", rendered.first())
            assertEquals(listOf("visible", "offscreen"), published)
            assertEquals(6, rendered.size)
        } finally {
            offscreen.close(); visible.close()
        }
    }

    @Test
    fun invalidatedPreparedResultNeverRendersCachesOrPublishes() {
        val worker = QueueWorker()
        var preparations = 0
        var renders = 0
        var callbacks = 0
        val source = AppWidgetCatalogDataSource(
            context,
            AppWidgetCatalogProvider { listOf(entry("stale", "Stale", "Widget")) },
            AppWidgetPreviewLoader { _, _, _, _ ->
                preparations++
                AppWidgetPreviewPlan { renders++; AppWidgetPreview(null, "none") }
            },
            worker
        )
        try {
            source.query(request()) { callbacks++ }
            worker.drain(); main.idle() // Enumerate, subscribe and dispatch preparation.
            worker.drain() // Prepare; leave its owner-thread completion queued.
            // main.idle() may deliver a Choreographer callback at the current timestamp. Invalidate
            // before consuming the prepared completion, not after a potentially rendered frame.
            assertEquals(1, preparations)
            assertEquals(0, renders)
            AppWidgetCatalogCache.invalidate()
            pump(worker)
            assertEquals(0, renders)
            assertEquals(0, callbacks)
            source.query(request()) { callbacks++ }
            pump(worker)
            assertEquals(2, preparations) // The stale plan/result must not have warmed the cache.
            assertEquals(1, renders)
            assertEquals(1, callbacks)
        } finally {
            source.close()
        }
    }

    @Test
    fun fullPreviewKeySeparatesProfileConfigurationSizeCategoryAndLoaderIdentity() {
        val info = entry("same", "Same", "Widget").info
        fun profile(user: Int) {
            org.robolectric.util.ReflectionHelpers.setField(
                info,
                "providerInfo",
                android.content.pm.ActivityInfo().apply {
                    applicationInfo =
                        android.content.pm.ApplicationInfo().apply { uid = user * 100000 + 1000 }
                })
        }
        profile(0)
        val original = AppWidgetCatalogCache.previewKey(context, info, 64, 32, 1)
        profile(10)
        assertNotEquals(original, AppWidgetCatalogCache.previewKey(context, info, 64, 32, 1))
        profile(0)
        assertNotEquals(original, AppWidgetCatalogCache.previewKey(context, info, 32, 32, 1))
        assertNotEquals(original, AppWidgetCatalogCache.previewKey(context, info, 64, 32, 2))
        assertNotEquals(original, AppWidgetCatalogCache.previewKey(context, info, 64, 32, 1, Any()))
        val changed = android.content.res.Configuration(context.resources.configuration)
            .apply { fontScale += .1f }
        assertNotEquals(
            original,
            AppWidgetCatalogCache.previewKey(
                context.createConfigurationContext(changed),
                info,
                64,
                32,
                1
            )
        )
        assertEquals(original, AppWidgetCatalogCache.previewKey(context, info, 64, 32, 1))
    }

    @Test
    fun arbitraryDrawableAndRemoteViewDrawingRemainMainThreadOnly() {
        val loader = AppWidgetSystemPreviewLoader(context)
        val failures = mutableListOf<Throwable>()
        Thread {
            try {
                loader.renderDrawable(android.graphics.drawable.ColorDrawable(), 16, 16)
            } catch (e: Throwable) {
                failures += e
            }
        }.apply { start(); join() }
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IllegalStateException)
    }

    @Test
    fun missingProviderFallsThroughToNoneWithoutBinding() {
        val info = entry("not.installed", "Missing", "Missing").info.apply {
            previewLayout = 999; icon = 999
        }
        val loader = AppWidgetSystemPreviewLoader(context)
        lateinit var plan: AppWidgetPreviewPlan
        Thread { plan = loader.prepare(info, 32, 24, 1) }.apply { start(); join() }
        val result = renderWithFallback(plan)
        assertNull(result.bitmap)
        assertEquals("none", result.kind)
        assertTrue(result.error.isNotEmpty())
    }
}
