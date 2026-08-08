package ir.factory.entryexit.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Shared helpers for turning a picked/captured photo [Uri] into a small, correctly-oriented
 * JPEG byte array (for sending to Gemini, and for local display), and for persisting a final
 * chosen photo permanently in the app's own storage (for [ir.factory.entryexit.data.PersonEntity.imageUri]).
 *
 * Everything funnels through JPEG at a capped resolution: camera photos can be several MB at
 * full resolution, both AI calls are faster/cheaper on a smaller image, and baking in the EXIF
 * rotation once here means Gemini always sees an upright photo regardless of whether it respects
 * EXIF metadata on its own.
 */
object ImagePrep {

    private const val MAX_DIMENSION = 1280
    private const val JPEG_QUALITY = 88

    /** Reads [uri], downsamples to at most [MAX_DIMENSION] on the long side, corrects EXIF
     *  rotation, and re-encodes as JPEG. Returns null if the image can't be decoded. */
    fun readAsJpeg(context: Context, uri: Uri): ByteArray? {
        val decoded = decodeSampledBitmap(context, uri) ?: return null
        val rotated = applyExifRotation(context, uri, decoded)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (rotated !== decoded) decoded.recycle()
        rotated.recycle()
        return out.toByteArray()
    }

    private fun decodeSampledBitmap(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DIMENSION || bounds.outHeight / (sample * 2) >= MAX_DIMENSION) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // inSampleSize only gives a power-of-two approximation; do one more precise scale pass.
        val longSide = maxOf(decoded.width, decoded.height)
        if (longSide <= MAX_DIMENSION) return decoded
        val scale = MAX_DIMENSION.toFloat() / longSide
        val scaled = Bitmap.createScaledBitmap(
            decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Persists [bytes] as a permanent JPEG in the app's private files dir and returns a
     *  `file://` Uri for it — same kind of Uri string [ir.factory.entryexit.data.Repository.updatePersonImage]
     *  already stores for gallery picks, so Glide loads it the same way with no other changes.
     *  If [previousImageUri] pointed at a file we previously wrote here, it's deleted first so
     *  re-processing a photo doesn't leak old files forever. */
    fun savePermanently(context: Context, bytes: ByteArray, previousImageUri: String?): Uri {
        cleanupIfOwnFile(context, previousImageUri)
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { it.write(bytes) }
        return Uri.fromFile(file)
    }

    private fun cleanupIfOwnFile(context: Context, imageUri: String?) {
        if (imageUri.isNullOrBlank()) return
        try {
            val uri = Uri.parse(imageUri)
            if (uri.scheme != "file") return
            val path = uri.path ?: return
            val photosDir = File(context.filesDir, "photos").canonicalPath
            val file = File(path)
            if (file.canonicalPath.startsWith(photosDir)) file.delete()
        } catch (e: Exception) {
            // Best-effort cleanup only — a leftover file is harmless.
        }
    }

    /** Creates a brand-new empty file under the app's cache dir and a FileProvider content://
     *  Uri for it, for the system camera app to write a full-resolution capture into. */
    fun createCameraCaptureUri(context: Context): Uri {
        val dir = File(context.cacheDir, "camera_temp").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
