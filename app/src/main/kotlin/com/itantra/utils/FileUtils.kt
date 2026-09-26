package com.itantra.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object FileUtils {
    fun copyAssetToInternalStorage(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName.substringAfterLast("/"))
        if (file.exists() && file.length() > 0L) return file.absolutePath

        context.assets.open(assetName).use { inputStream ->
            FileOutputStream(file).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return file.absolutePath
    }
}
