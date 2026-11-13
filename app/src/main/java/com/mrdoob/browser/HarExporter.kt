package com.mrdoob.browser

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object HarExporter {

    const val MIME_SAVE = "application/octet-stream"

    private const val NOTHING = "Nothing recorded yet"

    suspend fun share(context: Context) {
        val file = HarRecorder.flush() ?: return toast(context, NOTHING)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share HAR"))
    }

    suspend fun saveToDownloads(context: Context) {
        val file = HarRecorder.flush() ?: return toast(context, NOTHING)
        val where = withContext(Dispatchers.IO) {
            try { copyToDownloads(context, file) } catch (e: Exception) { null }
        }
        toast(context, if (where != null) "Saved to $where" else "Couldn't save the file")
    }

    suspend fun pickLocation(context: Context, launchPicker: (String) -> Unit) {
        val file = HarRecorder.flush() ?: return toast(context, NOTHING)
        launchPicker(file.name)
    }

    suspend fun saveTo(context: Context, uri: Uri) {
        val file = HarRecorder.flush() ?: return toast(context, NOTHING)
        val ok = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } != null
            } catch (e: Exception) { false }
        }
        toast(context, if (ok) "Saved" else "Couldn't save the file")
    }

    private fun copyToDownloads(context: Context, file: File): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) copyViaMediaStore(context, file)
        else copyToAppDownloads(context, file)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun copyViaMediaStore(context: Context, file: File): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, MIME_SAVE)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Netex")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("no stream")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/Netex"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun copyToAppDownloads(context: Context, file: File): String? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        dir.mkdirs()
        val dest = File(dir, file.name)
        file.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
