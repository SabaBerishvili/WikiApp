package com.wikiapp

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

object Utils {
    fun zipExtractShell(context: Context, targetFile: File, outDir: File) {
        if (!outDir.exists()) outDir.mkdirs()
        context.assets.open(targetFile.name).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        ProcessBuilder()
            .command("unzip", "-o", targetFile.absolutePath, "-d", outDir.absolutePath)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
        targetFile.delete()
    }

    fun replaceAndWrite(inFile: File, outFile: File, reps: Map<String, String>) {
        inFile.bufferedReader().use { reader ->
            outFile.bufferedWriter().use { writer ->
                reader.forEachLine { line ->
                    var modifiedLine = line
                    reps.forEach { rep ->
                        modifiedLine = modifiedLine.replace(rep.key, rep.value)
                    }
                    writer.write(modifiedLine)
                    writer.newLine()
                }
            }
        }
    }

    fun randomSequence(n: Int): String {
        val chars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..n)
            .map { chars.random() }
            .joinToString("")
    }

    fun namespaceFormat(name: String): String {
        return name.replaceFirstChar {
            if (it.isLowerCase()) it.uppercase() else it.toString()
        }.replace(" ", "_")
    }

    fun isStorageValid(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val dir = File(path)
        if (!(dir.exists() && dir.canRead() && dir.canWrite())) return false
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) nomedia.createNewFile()
        return true
    }

    fun isTargetValid(path: String?, target: String?): Boolean {
        if (!isStorageValid(path)) return false
        if (target.isNullOrBlank()) return false
        val dir = File(path, target)
        return dir.exists() && dir.canRead() && dir.canWrite()
    }

    fun targetHasFiles(context: Context, path: String?, target: String?): String {
        if (!isTargetValid(path, target)) return context.getString(R.string.directory_unusable)
        val dbFilesName = getDbFiles("$path/$target")
        if (dbFilesName.count() == 0) return context.getString(R.string.no_db)
        if (dbFilesName.count() > 1) return context.getString(R.string.too_many_db)
        val dbFilesCheck = checkDbFiles("$path/$target", dbFilesName[0])
        if (dbFilesCheck != "") return "${context.getString(R.string.failed_find_1)} $dbFilesCheck ${context.getString(R.string.failed_find_2)}"
        ensureDirs("$path/$target")
        return "ok"
    }

    fun getDbFiles(path: String): List<String> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        val dbFiles = dir.listFiles { file ->
            file.isFile &&
                    file.name.endsWith(".sqlite") &&
                    !file.name.contains("_jobqueue.sqlite") &&
                    !file.name.contains("_l10n_cache.sqlite") &&
                    !file.name.contains("wikicache.sqlite")
        } ?: return emptyList()
        return dbFiles.mapNotNull { file -> file.name.removeSuffix(".sqlite") }
    }

    fun checkDbFiles(path: String, name: String): String {
        var res = ""
        val jobqueueFile = File(path, "${name}_jobqueue.sqlite")
        if (!jobqueueFile.exists()) res += "${name}_jobqueue.sqlite"
        val cacheFile = File(path, "${name}_l10n_cache.sqlite")
        if (!cacheFile.exists()) {
            if (res != "") res += ", "
            res += "${name}_l10n_cache.sqlite"
        }
        return res
    }

    fun ensureDirs(path: String) {
        val imgsDir = File(path, "images")
        val deletedDir = File(path, "images/deleted")
        val imgsReadme = File(path, "images/README")
        if (!imgsDir.exists()) imgsDir.mkdirs()
        if (!deletedDir.exists()) deletedDir.mkdirs()
        if (!imgsReadme.exists()) imgsReadme.createNewFile()
    }

    fun getConfigProperty(path: String?, property: String) : String? {
        if (path.isNullOrEmpty()) return null
        val configFile = File(path, "wiki.cfg")
        if (!configFile.exists()) return null
        val properties = Properties()
        configFile.inputStream().use { input -> properties.load(input) }
        return properties.getProperty(property)
    }

    fun setConfigProperty(path: String?, property: String, value: String) {
        if (path.isNullOrEmpty()) return
        val configFile = File(path, "wiki.cfg")
        val properties = Properties()
        if (configFile.exists()) configFile.inputStream().use { input -> properties.load(input) }
        properties.setProperty(property, value)
        configFile.outputStream().use { output -> properties.store(output, null) }
    }

    fun copyAssetsSkip(context: Context, skipFileNames: List<String>) {
        fun copyAssetDir(assetPath: String, destDir: File) {
            val assetManager = context.assets
            val files = assetManager.list(assetPath) ?: return
            if (files.isEmpty()) {
                val fileName = assetPath.substringAfterLast('/')
                if (fileName in skipFileNames) return
                val outFile = File(destDir, fileName)
                assetManager.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                val newDir = File(destDir, assetPath.substringAfterLast('/'))
                if (!newDir.exists()) newDir.mkdirs()
                for (file in files) {
                    val fullAssetPath = if (assetPath.isEmpty()) file else "$assetPath/$file"
                    copyAssetDir(fullAssetPath, newDir)
                }
            }
        }
        copyAssetDir("", context.filesDir)
    }

    fun treeUriToAbsolutePath(context: Context, treeUri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parts = docId.split(":")
        if (parts.isNotEmpty()) {
            val volume = parts[0]
            val relative = if (parts.size > 1) parts[1] else ""
            if (volume.equals("primary", ignoreCase = true)) {
                val ext = Environment.getExternalStorageDirectory().absolutePath
                return if (relative.isNotEmpty()) "$ext/$relative" else ext
            } else {
                try {
                    val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                    val volumeList = storageManager.storageVolumes
                    for (vol in volumeList) {
                        val uuid = vol.uuid
                        if (uuid != null && uuid.equals(volume, ignoreCase = true)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val path = vol.directory?.absolutePath
                                if (path != null) return if (relative.isNotEmpty()) "$path/$relative" else path
                            }
                            else {
                                val volDir = "/storage/$uuid"
                                val volCheck = File(volDir)
                                if (volCheck.exists() && volCheck.isDirectory) return if (relative.isNotEmpty()) "$volDir/$relative" else volDir
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return ""
    }

    fun isPidRunning(pid: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill -0 $pid"))
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}