package com.peterlaurence.mapview.core

import android.graphics.Bitmap
import java.util.*

/**
 * A pool of [Bitmap] which has a limited size.
 */
class BitmapPool {
    private val pool = LinkedList<Bitmap>()
    private var size: Int = 0
    private val threshold = 100

    fun getBitmap(): Bitmap? {
        return if (pool.isNotEmpty()) {
            size--
            pool.removeFirst()
        } else {
            null
        }
    }

    fun putBitmap(bitmap: Bitmap) {
        if (size < threshold) {
            size++
            pool.add(bitmap)
        }
    }
}