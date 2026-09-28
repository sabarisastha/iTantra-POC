package com.itantra.download

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class DownloadState {
    object Checking : DownloadState()
    data class Downloading(val progress: Int, val downloadedMb: Float, val totalMb: Float) : DownloadState()
    object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloader(private val context: Context) {

    val modelsDir: File = File(context.filesDir, "models")
    val espeakDataDir: File = File(context.filesDir, "espeak-ng-data")

    fun initEspeakData() {
        val marker = File(espeakDataDir, ".complete")
        if (!marker.exists()) {
            espeakDataDir.mkdirs()
            copyAssetFolder("espeak-ng-data", espeakDataDir.absolutePath)
            runCatching { marker.createNewFile() }
        }
    }

    private fun copyAssetFolder(assetPath: String, destPath: String) {
        val assets = context.assets
        try {
            val files = assets.list(assetPath) ?: return
            if (files.isEmpty()) {
                // It's a file
                assets.open(assetPath).use { input ->
                    java.io.FileOutputStream(File(destPath)).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                // It's a directory
                File(destPath).mkdirs()
                for (filename in files) {
                    copyAssetFolder("$assetPath/$filename", "$destPath/$filename")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cleanupStaleDownloads() {
        modelsDir.mkdirs()
        modelsDir.walkTopDown()
            .filter { it.extension == "tmp" }
            .forEach { it.delete() }
    }

    fun modelFile(type: String, lang: String, filename: String): File {
        return File(modelsDir, "$type/$lang/$filename").also { it.parentFile?.mkdirs() }
    }

    fun sharedFile(type: String, filename: String): File {
        return File(modelsDir, "$type/shared/$filename").also { it.parentFile?.mkdirs() }
    }

    fun sharedModelFile(sharedId: String, filename: String): File {
        return File(modelsDir, "tts/shared/$sharedId/$filename").also { it.parentFile?.mkdirs() }
    }

    /**
     * Synchronously downloads [url] to [destFile] using atomic write (.tmp -> rename).
     */
    fun downloadSync(
        url: String,
        destFile: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ) {
        if (destFile.exists() && destFile.length() > 0L) return

        val tmpFile = File("${destFile.absolutePath}.tmp")
        tmpFile.delete()
        destFile.parentFile?.mkdirs()

        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection is javax.net.ssl.HttpsURLConnection && android.os.Build.VERSION.SDK_INT < 24) {
            try {
                val sc = javax.net.ssl.SSLContext.getInstance("TLSv1.2")
                sc.init(null, null, null)
                connection.sslSocketFactory = sc.socketFactory
            } catch (e: Exception) {
                android.util.Log.w("ModelDownloader", "TLS 1.2 init failed: ${e.message}")
            }
        }
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true

        try {
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${connection.responseCode} for ${destFile.name}")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(65_536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress?.invoke(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }

            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
        } finally {
            connection.disconnect()
            if (tmpFile.exists() && !destFile.exists()) tmpFile.delete()
        }
    }

    fun downloadFile(url: String, destFile: File): Flow<DownloadState> = flow {
        emit(DownloadState.Checking)
        if (destFile.exists() && destFile.length() > 0) {
            emit(DownloadState.Done)
            return@flow
        }
        try {
            withContext(Dispatchers.IO) {
                downloadSync(url, destFile)
            }
            emit(DownloadState.Done)
        } catch (e: Exception) {
            emit(DownloadState.Error(e.message ?: "Download error"))
        }
    }
}
