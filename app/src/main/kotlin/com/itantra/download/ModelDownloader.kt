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
     * Checks whether a file exists and meets minimum valid size/format constraints.
     */
    fun isValidFile(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val name = file.name.lowercase()
        return when {
            name.endsWith(".onnx") -> file.length() >= 1_000_000L // ONNX models must be at least 1MB
            name.endsWith(".txt") -> {
                if (file.length() < 50L) return false
                try {
                    val prefix = file.inputStream().use { input ->
                        val buf = ByteArray(minOf(200, file.length().toInt()))
                        input.read(buf)
                        String(buf)
                    }
                    !prefix.contains("<!DOCTYPE", ignoreCase = true) &&
                    !prefix.contains("<html", ignoreCase = true) &&
                    !prefix.startsWith("version https://git-lfs")
                } catch (e: Exception) {
                    true
                }
            }
            else -> file.length() > 0L
        }
    }

    private fun validateDownloadedFile(file: File) {
        val name = file.name.lowercase()
        if (name.endsWith(".onnx") && file.length() < 1_000_000L) {
            val preview = runCatching {
                file.inputStream().use { input ->
                    val buf = ByteArray(minOf(300, file.length().toInt()))
                    input.read(buf)
                    String(buf)
                }
            }.getOrDefault("")
            throw IllegalStateException("Downloaded ONNX model ${file.name} is too small (${file.length()} bytes). Content preview: $preview")
        }
        if (name.endsWith(".txt")) {
            if (file.length() < 50L) {
                throw IllegalStateException("Downloaded tokens file ${file.name} is too small (${file.length()} bytes)")
            }
            val preview = runCatching {
                file.inputStream().use { input ->
                    val buf = ByteArray(minOf(300, file.length().toInt()))
                    input.read(buf)
                    String(buf)
                }
            }.getOrDefault("")
            if (preview.contains("<!DOCTYPE", ignoreCase = true) || preview.contains("<html", ignoreCase = true)) {
                throw IllegalStateException("Downloaded tokens file ${file.name} is an HTML page instead of tokens text")
            }
            if (preview.startsWith("version https://git-lfs")) {
                throw IllegalStateException("Downloaded tokens file ${file.name} is a Git LFS pointer instead of actual tokens")
            }
        }
    }

    /**
     * Synchronously downloads [url] to [destFile] using atomic write (.tmp -> rename),
     * automatically following HTTP/HTTPS redirects and validating downloaded binary integrity.
     */
    fun downloadSync(
        url: String,
        destFile: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ) {
        if (isValidFile(destFile)) return

        val tmpFile = File("${destFile.absolutePath}.tmp")
        tmpFile.delete()
        destFile.parentFile?.mkdirs()

        var currentUrl = if (url.contains("huggingface.co") && url.contains("/blob/")) {
            url.replace("/blob/", "/resolve/")
        } else {
            url
        }

        var redirects = 0
        val maxRedirects = 10
        var connection: HttpURLConnection? = null

        try {
            while (redirects < maxRedirects) {
                val targetUrl = URL(currentUrl)
                val conn = targetUrl.openConnection() as HttpURLConnection
                connection = conn

                if (conn is javax.net.ssl.HttpsURLConnection && android.os.Build.VERSION.SDK_INT < 24) {
                    try {
                        val sc = javax.net.ssl.SSLContext.getInstance("TLSv1.2")
                        sc.init(null, null, null)
                        conn.sslSocketFactory = sc.socketFactory
                    } catch (e: Exception) {
                        android.util.Log.w("ModelDownloader", "TLS 1.2 init failed: ${e.message}")
                    }
                }

                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = false // Follow manually across domains and protocol switches

                conn.connect()
                val responseCode = conn.responseCode

                // Handle 301, 302, 303, 307, 308 redirects (e.g. Hugging Face -> CDN)
                if (responseCode in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw Exception("HTTP $responseCode redirect without Location header for ${destFile.name}")
                    currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URL(targetUrl, location).toString()
                    }
                    conn.disconnect()
                    redirects++
                    continue
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP $responseCode for ${destFile.name}")
                }

                val totalBytes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    conn.contentLengthLong
                } else {
                    conn.contentLength.toLong()
                }
                var downloadedBytes = 0L
                var maxEmittedProgress = 0

                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(65_536)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0L) {
                                val currentProgress = ((downloadedBytes.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 100)
                                if (currentProgress >= maxEmittedProgress) {
                                    maxEmittedProgress = currentProgress
                                    onProgress?.invoke(downloadedBytes, totalBytes)
                                }
                            } else {
                                onProgress?.invoke(downloadedBytes, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }

                if (totalBytes > 0L && downloadedBytes < totalBytes) {
                    throw Exception("Incomplete download for ${destFile.name}: got $downloadedBytes of $totalBytes bytes")
                }

                // Validate downloaded file format & minimum size
                validateDownloadedFile(tmpFile)

                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
                break
            }

            if (redirects >= maxRedirects) {
                throw Exception("Too many redirects ($redirects) downloading ${destFile.name}")
            }
        } finally {
            connection?.disconnect()
            if (tmpFile.exists()) tmpFile.delete()
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
