package com.apkx.app

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class AppEntry(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val apkFiles: List<File>,   // base.apk first, then split_*.apk
    val updatedAt: Long,
) {
    val isSplit get() = apkFiles.size > 1
    val totalBytes get() = apkFiles.sumOf { it.length() }
    val extension get() = if (isSplit) "apks" else "apk"
    val mimeType get() = if (isSplit) "application/octet-stream" else "application/vnd.android.package-archive"
    val fileName: String
        get() = "${label}_$versionName".replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_") + ".$extension"
}

object Apps {
    @Suppress("DEPRECATION")
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33)
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        else pm.getInstalledPackages(0)
        return packages.mapNotNull { info ->
            val ai = info.applicationInfo ?: return@mapNotNull null
            val base = ai.sourceDir?.let(::File) ?: return@mapNotNull null
            val splits = ai.splitSourceDirs.orEmpty().map(::File)
            AppEntry(
                label = ai.loadLabel(pm).toString(),
                packageName = info.packageName,
                versionName = info.versionName ?: "unknown",
                versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
                isSystem = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                apkFiles = listOf(base) + splits,
                updatedAt = info.lastUpdateTime,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** Writes a plain .apk, or for split apps a .apks zip (base.apk + split_*.apk) that SAI etc. can install. */
    fun write(app: AppEntry, out: OutputStream, onProgress: (Float) -> Unit = {}) {
        val total = app.totalBytes.coerceAtLeast(1)
        var done = 0L
        val buf = ByteArray(256 * 1024)
        fun pump(src: File, dst: OutputStream) = src.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                done += n
                onProgress(done.toFloat() / total)
            }
        }
        out.buffered().use { bout ->
            if (!app.isSplit) {
                pump(app.apkFiles[0], bout)
            } else {
                ZipOutputStream(bout).use { zip ->
                    zip.setLevel(Deflater.NO_COMPRESSION) // APKs are already compressed
                    for (f in app.apkFiles) {
                        zip.putNextEntry(ZipEntry(f.name))
                        pump(f, zip)
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    fun writeTo(resolver: ContentResolver, uri: Uri, app: AppEntry, onProgress: (Float) -> Unit) {
        val out = resolver.openOutputStream(uri, "w") ?: error("無法開啟輸出檔")
        write(app, out, onProgress)
    }

    /** Creates a file in a folder picked with OpenDocumentTree and writes the app into it. */
    fun writeToTree(resolver: ContentResolver, tree: Uri, app: AppEntry, onProgress: (Float) -> Unit): Uri {
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, dir, app.mimeType, app.fileName)
            ?: error("無法在資料夾建立檔案")
        writeTo(resolver, doc, app, onProgress)
        return doc
    }

    /** Copies into cache/share/ for handing to other apps through FileProvider. */
    fun writeToCache(context: Context, app: AppEntry, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, app.fileName)
        write(app, f.outputStream(), onProgress)
        return f
    }
}
