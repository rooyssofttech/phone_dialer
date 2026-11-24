package com.amitbharat.phonedialer.utils

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val avatarColors = listOf(
    Pair(Color(0xFF3B82F6), Color(0xFF1D4ED8)),
    Pair(Color(0xFF10B981), Color(0xFF047857)),
    Pair(Color(0xFFF59E0B), Color(0xFFB45309)),
    Pair(Color(0xFF8B5CF6), Color(0xFF6D28D9)),
    Pair(Color(0xFFEC4899), Color(0xFFBE185D)),
    Pair(Color(0xFF06B6D4), Color(0xFF0E7490))
)

private val avatarBitmapCache = object : android.util.LruCache<String, android.graphics.Bitmap>(80) {}

@Composable
fun ContactAvatar(
    name: String,
    photoUri: String? = null,
    size: Dp = 48.dp,
    fontSize: TextUnit = 18.sp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cachedBitmap = if (!photoUri.isNullOrBlank()) avatarBitmapCache.get(photoUri) else null
    var bitmap by remember(photoUri) { mutableStateOf(cachedBitmap) }

    LaunchedEffect(photoUri) {
        if (!photoUri.isNullOrBlank() && bitmap == null) {
            withContext(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openInputStream(Uri.parse(photoUri))
                    stream?.use {
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 2 // downsample thumbnail for instant smooth scroll
                        }
                        val decoded = BitmapFactory.decodeStream(it, null, options)
                        if (decoded != null) {
                            avatarBitmapCache.put(photoUri, decoded)
                            bitmap = decoded
                        }
                    }
                } catch (e: Exception) {
                    bitmap = null
                }
            }
        } else if (photoUri.isNullOrBlank()) {
            bitmap = null
        }
    }

    val hash = kotlin.math.abs(name.hashCode())
    val gradientColors = avatarColors[hash % avatarColors.size]

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(shape)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(Brush.linearGradient(listOf(gradientColors.first, gradientColors.second))),
            contentAlignment = Alignment.Center
        ) {
            val initial = if (name.isNotBlank()) name.trim().take(1).uppercase() else "?"
            Text(
                text = initial,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
