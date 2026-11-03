package com.example.esp32agent.android.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.esp32agent.android.util.Pt

/**
 * Frames are JPEGs (what the capture hands out and what the trace stores); this decodes them and feeds
 * [VisionMath]. Port of Agent/Vision.swift, whose CGImage calls become BitmapFactory and createScaledBitmap.
 */
object Vision {
    private const val COARSE_W = 32
    private const val COARSE_H = 64

    fun image(jpeg: ByteArray?): Bitmap? =
        if (jpeg == null || jpeg.isEmpty()) null else BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

    /** The two scales [VisionMath.changed] compares: 32x64 for the coarse pass, half size for the fine one. */
    fun scales(jpeg: ByteArray?): VisionMath.Scales? {
        val img = image(jpeg) ?: return null
        return try {
            VisionMath.Scales(
                gray(img, COARSE_W, COARSE_H),
                gray(img, maxOf(1, img.width / 2), maxOf(1, img.height / 2)),
            )
        } finally {
            img.recycle()
        }
    }

    fun changed(before: ByteArray?, after: ByteArray?, ignore: List<Pt> = emptyList(), threshold: Double = 1.0): Boolean =
        VisionMath.changed(scales(before), scales(after), ignore, threshold)

    /** The pointer's position, from two frames that differ only by the pointer having moved `shift` right. */
    fun pointer(before: ByteArray?, after: ByteArray?, near: Pt, shift: Double, reach: Pt = Pt(0.12, 0.08)): Pt? {
        val a = image(before) ?: return null
        val b = image(after) ?: return null
        return try {
            if (a.width != b.width || a.height != b.height) return null
            VisionMath.pointer(gray(a, a.width, a.height), gray(b, b.width, b.height), near, shift, reach)
        } finally {
            a.recycle()
            b.recycle()
        }
    }

    /** The bitmap drawn at w x h, converted to gray like PIL's convert("L"). */
    private fun gray(img: Bitmap, w: Int, h: Int): VisionMath.Gray {
        val scaled = if (img.width == w && img.height == h) img else Bitmap.createScaledBitmap(img, w, h, true)
        val argb = IntArray(w * h)
        scaled.getPixels(argb, 0, w, 0, 0, w, h)
        val px = ByteArray(w * h)
        for (i in argb.indices) {
            val c = argb[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            px[i] = ((r * 299 + g * 587 + b * 114 + 500) / 1000).toByte()
        }
        if (scaled !== img) scaled.recycle()
        return VisionMath.Gray(w, h, px)
    }
}