package com.iacamara.app.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale

/** Guarda una foto procesada en la galería con los datos EXIF de la toma original. */
object PhotoSaver {
    private val COPY_TAGS = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
    )

    /**
     * @param exifSource JPEG original del que copiar los datos (ISO, focal…), o null.
     * @param rotationDegrees rotación para enderezar la imagen (si el JPEG original no la trae).
     */
    fun save(context: Context, bmp: Bitmap, exifSource: ByteArray?, rotationDegrees: Int, suffix: String, software: String): Uri? {
        val resolver = context.contentResolver
        val name = "IACAM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis()) + suffix
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/IA Camara")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val dst = ExifInterface(pfd.fileDescriptor)
                var srcOrientation = ExifInterface.ORIENTATION_UNDEFINED
                exifSource?.let { bytes ->
                    val src = ExifInterface(ByteArrayInputStream(bytes))
                    for (tag in COPY_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                    srcOrientation = src.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                }
                val orientation = if (srcOrientation > ExifInterface.ORIENTATION_NORMAL) srcOrientation else when (rotationDegrees) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                dst.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                dst.setAttribute(ExifInterface.TAG_SOFTWARE, software)
                dst.saveAttributes()
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }
}
