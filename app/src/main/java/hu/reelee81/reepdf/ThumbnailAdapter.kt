package hu.reelee81.reepdf

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Bitmap.createBitmap
import android.os.Debug
import android.os.Handler
import android.os.Looper
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
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import hu.reelee81.reepdf.pdf.MuPdfViewerEngine
import hu.reelee81.reepdf.pdf.PdfThumbnailScheduler
import hu.reelee81.reepdf.pdf.PdfViewerDocument
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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

    private val waiters = ConcurrentHashMap<String, MutableList<WeakReference<ImageView>>>()

    private val cacheMaxBytes = 32 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(cacheMaxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            try { value.allocationByteCount } catch (_: Throwable) { value.byteCount }
    }

    private val scheduler = PdfThumbnailScheduler(
        open = { path ->
            PdfViewerDocument.open({ Debug.getNativeHeapAllocatedSize() - cache.size() }) {
                MuPdfViewerEngine.open(path, pageTreeCache = false)
            }
        },
        newTarget = { width, height -> AndroidBitmapRasterTarget(createBitmap(width, height, Bitmap.Config.ARGB_8888)) },
        release = { target -> target.bitmap.recycle() },
        finished = ::onThumbnailRendered
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var onThumbnailFinishedListener: ((String) -> Unit)? = null
    @Volatile private var prefetchPaused = false
    @Volatile private var renderingStopped = false
    @Volatile private var lastScrollNanos: Long = 0L

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

    private fun onThumbnailRendered(key: String, result: Result<AndroidBitmapRasterTarget>) {
        result.onSuccess { target ->
            val bmp = target.bitmap
            cache.put(key, bmp)
            dispatchToWaiters(key, bmp)
        }.onFailure { t ->
            Log.e("ThumbnailAdapter", "Thumbnail render error: ${t.message}")
        }
        notifyThumbnailFinished(key)
    }

    fun pausePrefetchAndCancel() {
        prefetchPaused = true
        scheduler.cancelAll()
    }

    fun resumePrefetch() {
        prefetchPaused = false
    }

    fun stopRendering() {
        renderingStopped = true
        scheduler.closeDocuments()
    }

    fun resumeRendering() {
        renderingStopped = false
    }

    private fun request(key: String, filePath: String, pageIndex: Int, density: Float) {
        if (renderingStopped) return
        val boxW = (112f * density).toInt().coerceAtLeast(1)
        val boxH = (158f * density).toInt().coerceAtLeast(1)
        scheduler.request(key, filePath, pageIndex, boxW, boxH)
    }

    private fun showCached(iv: ImageView, key: String): Boolean {
        val cached = cache.get(key)
        if (cached == null || cached.isRecycled) return false
        iv.safeSetBitmap(cached)
        return true
    }

    private fun checkAgainLater(holder: ViewHolder, key: String) {
        holder.iv.postDelayed({
            if (holder.iv.tag != key) return@postDelayed
            when {
                showCached(holder.iv, key) -> Unit
                scheduler.isPending(key) -> checkAgainLater(holder, key)
                scheduler.hasFailed(key) -> Unit
                else -> {
                    val pNow = holder.bindingAdapterPosition
                    if (pNow != RecyclerView.NO_POSITION && pNow in 0 until itemCount) {
                        runCatching { notifyItemChanged(pNow, "retry") }
                    }
                }
            }
        }, 1000L)
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

        val pending = scheduler.isPending(key)
        val cached = cache.get(key)
        if (cached != null && !cached.isRecycled) {
            holder.iv.safeSetBitmap(cached)
            return
        } else if (cached != null) {
            cache.remove(key)
        }

        registerWaiter(key, holder.iv)
        if (pending) {
            if (!showCached(holder.iv, key)) checkAgainLater(holder, key)
            return
        }

        request(key, filePath, item.pageIndex, holder.iv.resources.displayMetrics.density)
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

        scheduler.closeDocuments()

        waiters.clear()
        cache.evictAll()

        docKey = newDocKey
        prefetchPaused = false
    }

    fun close() {
        prefetchPaused = true
        scheduler.shutdown()
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

        scheduler.retainOnly(allowedKeys)
        scheduler.focus((start..end).mapNotNull { i -> items.getOrNull(i)?.let { "$docKey|${it.pageIndex}" } })

        val density = context.resources.displayMetrics.density
        for (i in start..end) {
            if (prefetchPaused) break
            val it = items.getOrNull(i) ?: continue
            val fp = it.filePath ?: continue
            val key = "$docKey|${it.pageIndex}"
            if (scheduler.isPending(key) || scheduler.hasFailed(key)) continue
            val inCache = cache.get(key)?.takeIf { b -> !b.isRecycled } != null
            if (inCache) continue
            request(key, fp, it.pageIndex, density)
        }
    }

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
