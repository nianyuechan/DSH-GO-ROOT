package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.min

internal data class PreviewPoint(val x: Float, val y: Float)

internal object PreviewMapping {
    /** Screen point → captured-image pixels → the ImageView's real FIT_CENTER content rect. */
    fun toView(
        screenX: Int,
        screenY: Int,
        geometry: ImageGeometry,
        viewWidth: Int,
        viewHeight: Int,
    ): PreviewPoint? {
        if (viewWidth <= 0 || viewHeight <= 0 || geometry.width <= 0 || geometry.height <= 0) return null
        val imageX = (screenX - geometry.originX) * geometry.scaleX
        val imageY = (screenY - geometry.originY) * geometry.scaleY
        if (imageX < 0f || imageY < 0f || imageX >= geometry.width || imageY >= geometry.height) return null
        val scale = min(viewWidth / geometry.width.toFloat(), viewHeight / geometry.height.toFloat())
        val offsetX = (viewWidth - geometry.width * scale) / 2f
        val offsetY = (viewHeight - geometry.height * scale) / 2f
        return PreviewPoint(offsetX + imageX * scale, offsetY + imageY * scale)
    }
}

internal class OverlayPreview(
    context: Context,
    private val density: Float,
    colors: OverlayPalette,
    unavailableText: String,
) : FrameLayout(context) {
    private data class Ripple(val screenX: Int, val screenY: Int, val startedAt: Long)

    private val image = ImageView(context)
    private val placeholder = TextView(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripples = mutableListOf<Ripple>()
    private var bitmap: Bitmap? = null
    private var geometry: ImageGeometry? = null
    private var sourceKey: String? = null

    init {
        setBackgroundColor(Color.rgb(20, 17, 22))
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        placeholder.text = unavailableText
        placeholder.textSize = 12f
        placeholder.gravity = Gravity.CENTER
        placeholder.setTextColor(colors.muted)
        addView(placeholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnClickListener { /* Preview is not an input surface. */ }
    }

    fun show(shot: Shot) {
        if (sourceKey != shot.sourceKey) {
            clearImage()
            sourceKey = shot.sourceKey
        }
        val bytes = shot.bytes
        val decoded = bytes?.takeIf { it.isNotEmpty() }?.let {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
        val capturedGeometry = shot.geometry
        if (decoded == null || capturedGeometry == null) {
            clearImage()
            placeholder.visibility = View.VISIBLE
            return
        }
        val old = bitmap
        bitmap = decoded
        geometry = capturedGeometry
        image.setImageBitmap(decoded)
        placeholder.visibility = View.GONE
        old?.recycle()
    }

    fun showTap(screenX: Int, screenY: Int) {
        if (geometry == null) return
        ripples.removeAll { SystemClock.uptimeMillis() - it.startedAt > RIPPLE_MS }
        ripples += Ripple(screenX, screenY, SystemClock.uptimeMillis())
        invalidate()
    }

    fun release() {
        ripples.clear()
        clearImage()
        sourceKey = null
        placeholder.visibility = View.VISIBLE
    }

    private fun clearImage() {
        image.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        geometry = null
        ripples.clear()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val currentGeometry = geometry ?: return
        val iterator = ripples.iterator()
        while (iterator.hasNext()) {
            val ripple = iterator.next()
            val age = now - ripple.startedAt
            if (age >= RIPPLE_MS) {
                iterator.remove()
                continue
            }
            val point = PreviewMapping.toView(
                ripple.screenX, ripple.screenY, currentGeometry, width, height,
            ) ?: continue
            val progress = age / RIPPLE_MS.toFloat()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = Color.argb((220 * (1f - progress)).toInt(), 255, 214, 64)
            canvas.drawCircle(point.x, point.y, (10f + 24f * progress) * density, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb((230 * (1f - progress)).toInt(), 255, 214, 64)
            canvas.drawCircle(point.x, point.y, 4f * density, paint)
        }
        if (ripples.isNotEmpty()) postInvalidateOnAnimation()
    }

    private companion object {
        const val RIPPLE_MS = 450L
    }
}
