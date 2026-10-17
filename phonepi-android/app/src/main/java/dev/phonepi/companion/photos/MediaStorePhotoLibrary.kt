package dev.phonepi.companion.photos

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.ByteArrayOutputStream

/**
 * Reads camera and gallery images from MediaStore. Query and openInputStream only.
 * No insert, update, delete, or upload.
 */
class MediaStorePhotoLibrary(private val context: Context) : PhotoLibrary {

    override fun scan(scanCap: Int, sinceMs: Long?): List<PhotoRecord> {
        val cap = scanCap.coerceIn(1, PhotoLimits.SCAN_CAP)
        val (selection, args) = selection(sinceMs, id = null)
        return query(imagesUri(), selection, args, cap)
    }

    override fun find(id: Long): PhotoRecord? {
        if (id <= 0L) return null
        val (selection, args) = selection(sinceMs = null, id = id)
        return query(imagesUri(), selection, args, limit = 1).firstOrNull()
    }

    override fun encode(id: Long, attempt: EncodeAttempt): EncodedPhoto? {
        if (id <= 0L) return null
        val uri = ContentUris.withAppendedId(imagesUri(), id)
        val bitmap = decodeOriented(uri, attempt.maxEdge) ?: return null
        return try {
            val bytes = ByteArrayOutputStream(bitmap.byteCount.coerceAtMost(512 * 1024))
            val format = compressFormat(attempt.format)
            val ok = bitmap.compress(format, attempt.quality.coerceIn(1, 100), bytes)
            if (!ok) return null
            EncodedPhoto(
                bytes = bytes.toByteArray(),
                width = bitmap.width,
                height = bitmap.height,
                mimeType = attempt.format.mimeType(),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun query(uri: Uri, selection: String?, args: Array<String>, limit: Int): List<PhotoRecord> {
        val queryArgs = Bundle().apply {
            if (selection != null) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            }
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media._ID),
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val cursor = context.contentResolver.query(uri, PROJECTION, queryArgs, null) ?: return emptyList()
        cursor.use { rows ->
            val idIdx = rows.getColumnIndex(MediaStore.Images.Media._ID)
            val nameIdx = rows.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val takenIdx = rows.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val addedIdx = rows.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val sizeIdx = rows.getColumnIndex(MediaStore.Images.Media.SIZE)
            val widthIdx = rows.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightIdx = rows.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val mimeIdx = rows.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val out = ArrayList<PhotoRecord>(rows.count.coerceAtLeast(0))
            while (rows.moveToNext()) {
                if (idIdx < 0) continue
                val record = photoFromColumns(
                    RawPhotoColumns(
                        id = rows.getLong(idIdx),
                        displayName = if (nameIdx >= 0 && !rows.isNull(nameIdx)) rows.getString(nameIdx) else null,
                        dateTakenRaw = if (takenIdx >= 0 && !rows.isNull(takenIdx)) rows.getLong(takenIdx) else 0L,
                        dateAddedRaw = if (addedIdx >= 0 && !rows.isNull(addedIdx)) rows.getLong(addedIdx) else 0L,
                        sizeBytes = if (sizeIdx >= 0 && !rows.isNull(sizeIdx)) rows.getLong(sizeIdx) else 0L,
                        width = if (widthIdx >= 0 && !rows.isNull(widthIdx)) rows.getInt(widthIdx) else 0,
                        height = if (heightIdx >= 0 && !rows.isNull(heightIdx)) rows.getInt(heightIdx) else 0,
                        mimeType = if (mimeIdx >= 0 && !rows.isNull(mimeIdx)) rows.getString(mimeIdx) else null,
                    ),
                )
                if (record != null) out.add(record)
            }
            return out
        }
    }

    private fun selection(sinceMs: Long?, id: Long?): Pair<String?, Array<String>> {
        val clauses = ArrayList<String>(3)
        val args = ArrayList<String>(4)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            clauses.add("${MediaStore.MediaColumns.IS_PENDING}=0")
        }
        if (id != null) {
            clauses.add("${MediaStore.Images.Media._ID}=?")
            args.add(id.toString())
        }
        if (sinceMs != null && sinceMs > 0L) {
            clauses.add(
                "(${MediaStore.Images.Media.DATE_TAKEN}>=? OR ${MediaStore.Images.Media.DATE_ADDED}>=?)",
            )
            args.add(sinceMs.toString())
            args.add((sinceMs / 1000L).toString())
        }
        if (clauses.isEmpty()) return null to emptyArray()
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    private fun imagesUri(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    /**
     * Decode, apply EXIF orientation to the pixels, then scale.
     * The compressed output is a new bitmap, so the original EXIF blob is not in the bytes.
     */
    private fun decodeOriented(uri: Uri, maxEdge: Int): Bitmap? {
        val resolver = context.contentResolver
        val orientation = readOrientation(resolver, uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = inSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        } ?: return null
        val oriented = applyOrientation(decoded, orientation)
        val (targetW, targetH) = scaledSize(oriented.width, oriented.height, maxEdge)
        if (targetW == oriented.width && targetH == oriented.height) return oriented
        val scaled = Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
        if (scaled != oriented) oriented.recycle()
        return scaled
    }

    private fun readOrientation(resolver: ContentResolver, uri: Uri): Int =
        try {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return src
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (out != src) src.recycle()
        return out
    }

    private fun compressFormat(format: PhotoFormat): Bitmap.CompressFormat = when (format) {
        PhotoFormat.JPEG -> Bitmap.CompressFormat.JPEG
        PhotoFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
    }

    companion object {
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
        )
    }
}
