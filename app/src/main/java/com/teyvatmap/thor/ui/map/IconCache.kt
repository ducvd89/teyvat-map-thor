package com.teyvatmap.thor.ui.map

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teyvatmap.thor.data.store.PackPaths
import com.teyvatmap.thor.ui.theme.Gold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Decodes category icons (full-size PNGs on disk) down to marker size and keeps them in memory. */
@Singleton
open class IconCache @Inject constructor(private val paths: PackPaths) {
    private val cache = object : LinkedHashMap<Int, ImageBitmap?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap?>?) = size > MAX
    }

    @Synchronized private fun peek(id: Int): Pair<Boolean, ImageBitmap?> =
        if (cache.containsKey(id)) true to cache[id] else false to null

    /** Returns the icon of a label or null if its file is missing (callers draw a placeholder). */
    open suspend fun get(labelId: Int): ImageBitmap? {
        val (hit, v) = peek(labelId)
        if (hit) return v
        val decoded = withContext(Dispatchers.IO) { decode(labelId) }
        synchronized(this) { cache[labelId] = decoded }
        return decoded
    }

    private fun decode(labelId: Int): ImageBitmap? {
        val f = paths.iconFile(labelId)
        if (f.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bmp.asImageBitmap()
    }

    private companion object {
        const val TARGET = 72
        const val MAX = 900
    }
}

/** Category icon, or a gold dot placeholder when the icon file is missing (e.g. labels 48 and 213). */
@Composable
fun LabelIcon(icons: IconCache, labelId: Int, size: Dp, modifier: Modifier = Modifier) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, labelId) { value = icons.get(labelId) }
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, modifier = modifier.size(size), contentScale = ContentScale.Fit)
    } else {
        Box(modifier.size(size)) {
            Box(Modifier.size(size * 0.45f).clip(CircleShape).background(Gold.copy(alpha = 0.8f)).align(androidx.compose.ui.Alignment.Center))
        }
    }
}

