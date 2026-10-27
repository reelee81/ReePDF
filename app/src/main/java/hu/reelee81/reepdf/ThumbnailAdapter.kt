package hu.reelee81.reepdf

import android.content.ContentResolver
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Bitmap.createBitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max
import kotlin.math.min

class ThumbnailAdapter(
    private val items: MutableList<PageItem>,
    private val deleteListener: (Int) -> Unit,
    private val rotateLeftListener: (Int) -> Unit,
    private val positionListener: (Int) -> Unit,
    private val positionIconFor: (Int) -> Int,
    private val cropListener: (Int) -> Unit,
    private val isCroppedAt: (Int) -> Boolean,
    private val clickListener: (Int) -> Unit,
    private val selectionChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<ThumbnailAdapter.ViewHolder>() {

    @Volatile private var docKey: String = "init"

    private val bindExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        2,
        3,
        3L, TimeUnit.SECONDS,
        LinkedBlockingDeque(32)
    ) { task, executor ->
        if (!executor.isShutdown) {
            executor.queue.poll()?.let { dropped ->
                (dropped as? KeyedFutureTask)?.let { keyed ->
                    keyed.cancel(true)
                    inFlight.remove(keyed.key, keyed)
                }
            }
            executor.execute(task)
        }
    }

    private val prefetchExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        2,
        2,
        0L, TimeUnit.MILLISECONDS,
        LinkedBlockingDeque(16),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val inFlight = ConcurrentHashMap<String, Future<*>>()

    private val waiters = ConcurrentHashMap<String, MutableList<WeakReference<ImageView>>>()

    private val cacheMaxBytes = 32 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(cacheMaxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            try { value.allocationByteCount } catch (_: Throwable) { value.byteCount }
    }

    private class RendererEntry(val renderer: PdfRenderer) {
        val lock = ReentrantLock(true)
    }

    private val renderers = ConcurrentHashMap<String, RendererEntry>()
    private val closeExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        0,
        1,
        3L, TimeUnit.SECONDS,
        LinkedBlockingQueue()
    )
    private val prefetchLimiter = Semaphore(2, true)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var onThumbnailFinishedListener: ((String) -> Unit)? = null
    @Volatile private var prefetchPaused = false
    @Volatile private var lastScrollNanos: Long = 0L

    private class KeyedFutureTask(
        val key: String,
        r: Runnable
    ) : FutureTask<Unit>(r, Unit)

    init { setHasStableIds(true) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_page_thumbnail, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        if (position < 0 || position >= items.size) return RecyclerView.NO_ID
        return ("$docKey|${items[position].pageIndex}").hashCode().toLong()
    }

    fun setOnThumbnailFinishedListener(listener: ((String) -> Unit)?) {
        onThumbnailFinishedListener = listener
    }

    fun getCacheKeyForPosition(position: Int): String? {
        if (position < 0 || position >= items.size) return null
        return "$docKey|${items[position].pageIndex}"
    }

    fun isThumbnailCachedByKey(key: String): Boolean {
        val bmp = cache.get(key)
        return bmp != null && !bmp.isRecycled
    }

    private fun notifyThumbnailFinished(key: String) {
        mainHandler.post {
            onThumbnailFinishedListener?.invoke(key)
        }
    }

    private fun closeRendererAsync(entry: RendererEntry) {
        try {
            closeExecutor.execute {
                while (true) {
                    if (Thread.currentThread().isInterrupted) return@execute
                    val ok = try { entry.lock.tryLock(200, TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
                    if (ok) {
                        try {
                            runCatching { entry.renderer.close() }
                        } finally {
                            try { entry.lock.unlock() } catch (_: Exception) {}
                        }
                        return@execute
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            runCatching { entry.renderer.close() }
        }
    }

    fun pausePrefetchAndCancel() {
        prefetchPaused = true
        for ((_, fut) in inFlight) {
            fut.cancel(true)
        }
        inFlight.clear()
        prefetchExecutor.queue.clear()
    }

    fun resumePrefetch() {
        prefetchPaused = false
    }

    private fun getOrCreateRenderer(context: Context, filePath: String): RendererEntry {
        return renderers.computeIfAbsent(filePath) {
            val uri: Uri = if (filePath.startsWith("content://") || filePath.startsWith("file://")) {
                filePath.toUri()
            } else {
                File(filePath).toUri()
            }
            val pfd: ParcelFileDescriptor =
                if (uri.scheme == ContentResolver.SCHEME_FILE) {
                    ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
                } else {
                    context.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IllegalArgumentException(context.getString(R.string.cannot_open_uri, uri))
                }
            RendererEntry(PdfRenderer(pfd))
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val safePos = holder.bindingAdapterPosition
        if (safePos == RecyclerView.NO_POSITION || safePos < 0 || safePos >= items.size) return
        val item = items[safePos]

        holder.tvPageNumber.text = item.pageNumberText
        holder.iv.safeSetBitmap(null)

        val wCm = item.widthPts / 72f * 2.54f
        val hCm = item.heightPts / 72f * 2.54f
        holder.tvSize.text = String.format(
            Locale.getDefault(),
            holder.itemView.context.getString(R.string.page_size_cm_by_cm),
            wCm, hCm
        )

        holder.cbSel.setOnCheckedChangeListener(null)
        holder.cbSel.isChecked = item.isSelected
        holder.cbSel.setOnCheckedChangeListener { _, checked ->
            val posNow = holder.bindingAdapterPosition
            if (posNow != RecyclerView.NO_POSITION && posNow in items.indices) {
                items[posNow].isSelected = checked
                selectionChanged(posNow, checked)
            }
        }
        holder.btnDelete.setOnClickListener {
            val posNow = holder.bindingAdapterPosition
            if (posNow != RecyclerView.NO_POSITION && posNow in items.indices) {
                deleteListener(posNow)
            }
        }
        holder.btnRotateLeft.setOnClickListener {
            val posNow = holder.bindingAdapterPosition
            if (posNow != RecyclerView.NO_POSITION && posNow in items.indices) {
                rotateLeftListener(posNow)
            }
        }
        holder.btnPosition.setImageResource(positionIconFor(safePos))
        holder.btnPosition.setOnClickListener {
            val posNow = holder.bindingAdapterPosition
            if (posNow != RecyclerView.NO_POSITION && posNow in items.indices) {
                positionListener(posNow)
            }
        }
        holder.btnCrop.setOnClickListener {
            val posNow = holder.bindingAdapterPosition
            if (posNow != RecyclerView.NO_POSITION && posNow in items.indices) {
                cropListener(posNow)
            }
        }
        holder.itemView.setOnClickListener {
            val posNow = holder.bindingAdapterPosition
            if (posNow != RecyclerView.NO_POSITION && posNow in items.indices) {
                clickListener(posNow)
            }
        }

        run {
            val ctx = holder.itemView.context
            val isLight =
                (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_NO

            val isLandscapeChecked = try {
                (holder.itemView.rootView
                    ?.findViewById<CompoundButton>(R.id.landscape)
                    ?.isChecked) == true
            } catch (_: Exception) { false }

            if (isLight && isLandscapeChecked) {
                val disabled = ContextCompat.getColor(ctx, R.color.dark_light_065)
                val normal   = ContextCompat.getColor(ctx, R.color.dark_light_100)
                val states   = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
                val colors   = intArrayOf(disabled, normal)
                val csl      = ColorStateList(states, colors)

                CompoundButtonCompat.setButtonTintList(holder.cbSel, csl)
                ImageViewCompat.setImageTintList(holder.btnDelete, csl)
                ImageViewCompat.setImageTintList(holder.btnRotateLeft, csl)
                ImageViewCompat.setImageTintList(holder.btnPosition, csl)
                ImageViewCompat.setImageTintList(holder.btnCrop, croppedTint(ctx, csl, isCroppedAt(safePos)))
            } else {
                val cbCsl  = AppCompatResources.getColorStateList(ctx, R.color.ic_checkbox_page_color)
                val delCsl = AppCompatResources.getColorStateList(ctx, R.color.ic_delete_rotate_position_page_crop_color)
                val rotCsl = AppCompatResources.getColorStateList(ctx, R.color.ic_delete_rotate_position_page_crop_color)

                if (cbCsl != null) {
                    CompoundButtonCompat.setButtonTintList(holder.cbSel, cbCsl)
                }
                if (delCsl != null) {
                    ImageViewCompat.setImageTintList(holder.btnDelete, delCsl)
                }
                if (rotCsl != null) {
                    ImageViewCompat.setImageTintList(holder.btnRotateLeft, rotCsl)
                    ImageViewCompat.setImageTintList(holder.btnPosition, rotCsl)
                    ImageViewCompat.setImageTintList(holder.btnCrop, croppedTint(ctx, rotCsl, isCroppedAt(safePos)))
                }
            }
        }

        val filePath = item.filePath ?: return
        val key = "$docKey|${item.pageIndex}"
        holder.iv.tag = key

        val cached = cache.get(key)
        if (cached != null && !cached.isRecycled) {
            holder.iv.safeSetBitmap(cached)
            return
        } else if (cached != null) {
            cache.remove(key)
        }

        inFlight[key]?.let { existing ->
            if (existing.isDone || existing.isCancelled) {
                inFlight.remove(key)
            } else {
                registerWaiter(key, holder.iv)
                holder.iv.postDelayed({
                    val f = inFlight[key]
                    if (f != null && !f.isDone && !f.isCancelled && holder.iv.tag == key) {
                        inFlight.remove(key)
                        val pNow = holder.bindingAdapterPosition
                        if (pNow != RecyclerView.NO_POSITION && pNow in 0 until itemCount) {
                            runCatching { notifyItemChanged(pNow, "retry") }
                        }
                    }
                }, 1000L)
                return
            }
        }

        val context = holder.itemView.context
        val density = holder.iv.resources.displayMetrics.density
        val targetW = (112f * density).toInt().coerceAtLeast(1)

        inFlight[key] = CompletableFuture.completedFuture(Unit)

        val task = KeyedFutureTask(key) {
            try {
                val entry = getOrCreateRenderer(context, filePath)

                var produced: Bitmap?
                entry.lock.lock()
                try {
                    var page: PdfRenderer.Page? = null
                    try {
                        page = entry.renderer.openPage(item.pageIndex)
                        val pageW = page.width.toFloat()
                        val pageH = page.height.toFloat()
                        val targetH = max(1, (targetW * (pageH / pageW)).toInt())

                        val b = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        Canvas(b).apply { drawColor(Color.WHITE) }
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        produced = b
                    } finally {
                        runCatching { page?.close() }
                    }
                } finally {
                    entry.lock.unlock()
                }

                produced.let { bmp ->
                    cache.put(key, bmp)
                    dispatchToWaiters(key, bmp)

                    holder.iv.post {
                        val posNow = holder.bindingAdapterPosition
                        if (posNow != RecyclerView.NO_POSITION &&
                            posNow in items.indices &&
                            items[posNow] === item &&
                            holder.iv.tag == key &&
                            !bmp.isRecycled
                        ) {
                            holder.iv.safeSetBitmap(bmp)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e("ThumbnailAdapter", "Bind render (112dp) error: ${t.message}")
            } finally {
                inFlight.remove(key)
                notifyThumbnailFinished(key)
            }
        }
        bindExecutor.execute(task)
        inFlight[key] = task
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.iv.tag = null
        holder.iv.safeSetBitmap(null)
        holder.iv.setImageBitmap(null)
        holder.cbSel.setOnCheckedChangeListener(null)
        holder.itemView.removeCallbacks(null)
    }

    fun resetForNewDocument(newDocKey: String) {
        prefetchPaused = true

        runCatching { inFlight.values.forEach { f -> runCatching { f.cancel(true) } } }
        inFlight.clear()

        runCatching { bindExecutor.queue.clear() }
        runCatching { prefetchExecutor.queue.clear() }

        waiters.clear()
        cache.evictAll()

        val toClose = renderers.values.toList()
        renderers.clear()
        toClose.forEach(::closeRendererAsync)

        docKey = newDocKey
        prefetchPaused = false
    }

    fun close() {
        prefetchPaused = true
        runCatching { inFlight.values.forEach { f -> runCatching { f.cancel(true) } } }
        inFlight.clear()

        runCatching { bindExecutor.queue.clear() }
        runCatching { prefetchExecutor.queue.clear() }

        renderers.values.forEach { entry ->
            val locked = try { entry.lock.tryLock(500, TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
            if (locked) {
                try { runCatching { entry.renderer.close() } } finally { runCatching { entry.lock.unlock() } }
            } else {
                runCatching { entry.renderer.close() }
            }
        }
        renderers.clear()

        bindExecutor.shutdownNow()
        prefetchExecutor.shutdownNow()
        closeExecutor.shutdown()
    }

    fun clearAllSelections() {
        val changed = mutableSetOf<Int>()
        items.forEachIndexed { idx, it ->
            if (it.isSelected) { it.isSelected = false; changed.add(idx) }
        }
        notifySelectionChanged(changed)
    }

    fun selectAll() {
        val changed = mutableSetOf<Int>()
        items.forEachIndexed { idx, it ->
            if (!it.isSelected) { it.isSelected = true; changed.add(idx) }
        }
        notifySelectionChanged(changed)
    }

    fun notifySelectionChanged(indices: Set<Int>) {
        if (indices.isEmpty()) return
        val sorted = indices.sorted()
        var start = sorted.first()
        var prev = start
        for (i in sorted.drop(1)) {
            if (i == prev + 1) prev = i
            else { notifyItemRangeChanged(start, prev - start + 1); start = i; prev = i }
        }
        notifyItemRangeChanged(start, prev - start + 1)
    }

    fun prefetchAroundVisible(
        context: Context,
        visibleWithPad: IntRange
    ) {
        if (prefetchPaused) return

        lastScrollNanos = System.nanoTime()

        val start = visibleWithPad.first.coerceAtLeast(0)
        val end = visibleWithPad.last.coerceAtMost(itemCount - 1)
        if (start > end) return

        val extraPad = 6

        val allowedStart = (start - extraPad).coerceAtLeast(0)
        val allowedEnd = (end + extraPad).coerceAtMost(itemCount - 1)
        val allowedKeys = HashSet<String>()
        for (i in allowedStart..allowedEnd) {
            val it = items.getOrNull(i) ?: continue
            allowedKeys += "$docKey|${it.pageIndex}"
        }

        try {
            (bindExecutor.queue as? LinkedBlockingDeque<Runnable>)?.let { dq ->
                val it = dq.iterator()
                while (it.hasNext()) {
                    val r = it.next()
                    val keyed = r as? KeyedFutureTask
                    if (keyed != null && keyed.key !in allowedKeys) {
                        keyed.cancel(true)
                        it.remove()
                    }
                }
            }
            (prefetchExecutor.queue as? LinkedBlockingDeque<Runnable>)?.let { dq ->
                val it = dq.iterator()
                while (it.hasNext()) {
                    val r = it.next()
                    val keyed = r as? KeyedFutureTask
                    if (keyed != null && keyed.key !in allowedKeys) {
                        keyed.cancel(true)
                        it.remove()
                    }
                }
            }
            val itIn = inFlight.entries.iterator()
            while (itIn.hasNext()) {
                val e = itIn.next()
                if (e.key !in allowedKeys) {
                    e.value.cancel(true)
                    itIn.remove()
                }
            }
        } catch (_: Exception) {}

        val toSchedule = mutableListOf<Pair<String, PrefetchInfo>>()
        for (i in start..end) {
            val it = items.getOrNull(i) ?: continue
            val fp = it.filePath ?: continue
            val key = "$docKey|${it.pageIndex}"
            val inCache = cache.get(key)?.takeIf { b -> !b.isRecycled } != null
            if (inCache || inFlight.containsKey(key)) continue
            toSchedule += key to PrefetchInfo(filePath = fp, pageIndex = it.pageIndex)
        }

        val density = context.resources.displayMetrics.density
        val targetWpx = (112f * density).toInt().coerceAtLeast(1)
        val targetHpx = (158f * density).toInt().coerceAtLeast(1)

        val remainingCap = (prefetchExecutor.queue as? LinkedBlockingDeque<Runnable>)
            ?.remainingCapacity()?.coerceAtLeast(0)
            ?: prefetchExecutor.queue.remainingCapacity().coerceAtLeast(0)
        val budget = min(remainingCap, toSchedule.size)

        for ((key, info) in toSchedule.take(budget)) {
            if (prefetchPaused) break

            inFlight[key] = CompletableFuture.completedFuture(Unit)

            try {
                val fut = KeyedFutureTask(key, Runnable {
                    prefetchLimiter.acquire()
                    try {
                        if (prefetchPaused || Thread.currentThread().isInterrupted) return@Runnable

                        val entry = getOrCreateRenderer(context, info.filePath)

                        var producedBmp: Bitmap?
                        entry.lock.lock()
                        try {
                            var pg: PdfRenderer.Page? = null
                            try {
                                pg = entry.renderer.openPage(info.pageIndex)

                                val pageW = pg.width.toFloat()
                                val pageH = pg.height.toFloat()
                                val aspect = pageH / pageW

                                var bmpW = targetWpx
                                var bmpH = (bmpW * aspect).toInt()

                                if (bmpH > targetHpx) {
                                    bmpH = targetHpx
                                    bmpW = (bmpH / aspect).toInt()
                                }

                                val b = createBitmap(
                                    bmpW.coerceAtLeast(1),
                                    bmpH.coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888
                                )
                                Canvas(b).apply { drawColor(Color.WHITE) }
                                pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                producedBmp = b
                            } finally {
                                try { pg?.close() } catch (_: Exception) {}
                            }
                        } finally {
                            entry.lock.unlock()
                        }

                        producedBmp.let { bmp ->
                            cache.put(key, bmp)
                            dispatchToWaiters(key, bmp)
                        }
                    } catch (_: InterruptedException) {
                    } catch (e: RejectedExecutionException) {
                        Log.w("ThumbnailAdapter", "Prefetch rejected: ${e.message}")
                    } catch (e: Exception) {
                        Log.e("ThumbnailAdapter", "Error while preloading: ${e.message}")
                    } finally {
                        prefetchLimiter.release()
                        inFlight.remove(key)
                        notifyThumbnailFinished(key)
                    }
                })
                prefetchExecutor.execute(fut)
                inFlight[key] = fut
            } catch (_: RejectedExecutionException) {
            }
        }
    }

    private data class PrefetchInfo(val filePath: String, val pageIndex: Int)

    private fun registerWaiter(key: String, iv: ImageView) {
        waiters.compute(key) { _, list ->
            val dst = (list ?: mutableListOf())
            val it = dst.iterator()
            while (it.hasNext()) if (it.next().get() == null) it.remove()
            dst.add(WeakReference(iv))
            dst
        }
    }

    private fun dispatchToWaiters(key: String, bmp: Bitmap) {
        waiters.remove(key)?.forEach { ref ->
            ref.get()?.let { iv ->
                iv.post {
                    if (iv.tag == key && !bmp.isRecycled) iv.safeSetBitmap(bmp)
                }
            }
        }
    }

    private fun croppedTint(context: Context, base: ColorStateList, cropped: Boolean): ColorStateList {
        if (!cropped) return base
        val croppedColor = ContextCompat.getColor(context, R.color.love_50)
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        return ColorStateList(
            arrayOf(disabled, intArrayOf()),
            intArrayOf(base.getColorForState(disabled, croppedColor), croppedColor)
        )
    }

    private fun ImageView.safeSetBitmap(bmp: Bitmap?) {
        if (bmp == null || bmp.isRecycled) setImageDrawable(null) else setImageBitmap(bmp)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val iv: ImageView = itemView.findViewById(R.id.iv_thumbnail)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete)
        val btnRotateLeft: ImageButton = itemView.findViewById(R.id.btn_rotate_left)
        val btnPosition: ImageButton = itemView.findViewById(R.id.btn_position)
        val btnCrop: ImageButton = itemView.findViewById(R.id.btn_crop)
        val tvSize: TextView = itemView.findViewById(R.id.tv_size_label)
        val cbSel: AppCompatCheckBox = itemView.findViewById(R.id.cb_select)
        val tvPageNumber: TextView = itemView.findViewById(R.id.page_number)
    }
}
