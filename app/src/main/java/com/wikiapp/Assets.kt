package com.wikiapp

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object Assets {
    fun prepConfigs(context: Context, storageDir: String?, targetDir: String?) {
        val baseDir = context.filesDir.absolutePath
        val cacheDir = context.cacheDir.absolutePath
        val fullTargetDir = "$storageDir/$targetDir"
        val targetName = Utils.getConfigProperty(fullTargetDir, "name") ?: "$targetDir"
        val logoDir = Utils.getConfigProperty(fullTargetDir, "logo")
        val logoDirFull = if (logoDir == null) "" else "\$wgLogos = [ 'icon' => \"$logoDir\" ];"
        val dbNames = Utils.getDbFiles(fullTargetDir)
        val configs = mapOf(
            "/configs/php/php-prep.ini" to "/php/etc/php.ini",
            "/configs/php/opcache-blacklist-prep" to "/php/etc/opcache-blacklist",
            "/configs/nginx/nginx-prep.conf" to "/nginx/conf/nginx.conf",
            "/configs/mediawiki/LocalSettings-prep.php" to "/mediawiki/LocalSettings.php"
        )
        val replacements = mapOf(
            "---PHP_DIR---" to "$baseDir/php",
            "---CACHE_DIR---" to "$cacheDir",
            "---HTML_DIR---" to "$baseDir/mediawiki",
            "---TARGET_DIR---" to fullTargetDir,
            "---TARGET_NAME---" to targetName,
            "---META_NAME---" to Utils.namespaceFormat(targetName),
            "---DB_NAME---" to (if (dbNames.count() == 1) dbNames[0] else ""),
            "---LOGO_VAR---" to logoDirFull
        )
        configs.forEach { file ->
            val fileIn = File(baseDir, file.key)
            val fileOut = File(baseDir, file.value)
            if (fileOut.exists()) FileOutputStream(fileOut).close() else fileOut.createNewFile()
            Utils.replaceAndWrite(fileIn, fileOut, replacements)
        }
    }

    fun makeDirs(context: Context) {
        val cacheDir = context.cacheDir
        val dirs = listOf(
            "$cacheDir/opcache",
            "$cacheDir/session",
            "$cacheDir/uploadtmp")
        dirs.forEach { dir ->
            val dirFile = File(dir)
            if (!dirFile.exists()) dirFile.mkdirs()
        }
    }

    fun runPhp(context: Context) {
        val phpDir = File(context.filesDir.absolutePath, "php")
        val php = File(phpDir, "php-fpm")
        val pid = File(phpDir, "log/php-fpm.pid")
        val phpIni = File(phpDir, "etc/php.ini")
        if (!php.canExecute()) php.setExecutable(true)
        if (pid.exists() && Utils.isPidRunning(pid.readText().trim())) Runtime.getRuntime().exec("kill -QUIT ${pid.readText().trim()}").waitFor()
        ProcessBuilder(php.absolutePath, "-p", phpDir.absolutePath, "-c", phpIni.absolutePath).start()
    }

    fun runNginx(context: Context) {
        val nginxDir = File(context.filesDir.absolutePath, "nginx")
        val nginx = File(nginxDir, "nginx")
        val pid = File(nginxDir, "logs/nginx.pid")
        val conf = File(nginxDir, "conf/nginx.conf")
        if (!nginx.canExecute()) nginx.setExecutable(true)
        if (pid.exists() && Utils.isPidRunning(pid.readText().trim())) Runtime.getRuntime().exec("kill -s QUIT ${pid.readText().trim()}").waitFor()
        ProcessBuilder(nginx.absolutePath, "-p", nginxDir.absolutePath, "-c", conf.absolutePath).start()
    }

    fun copyAssets(context: Context) {
        Utils.copyAssetsSkip(context, listOf("_placeholder", "php-cli", "mediawiki.zip"))
    }

    fun extractMW(context: Context) {
        val baseDir = context.filesDir
        val zipFile = File(baseDir, "mediawiki.zip")
        val outDir = File(baseDir, "mediawiki")
        Utils.zipExtractShell(context, zipFile, outDir)
    }

    fun phpCli(
        context: Context,
        commands: List<String>,
        onLine: (String) -> Unit,
        onComplete: (() -> Unit)? = null) : Thread {
        var currentProcess: Process? = null
        val phpDir = File(context.filesDir.absolutePath, "php")
        val php = File(phpDir, "php-cli")
        val phpIni = File(phpDir, "etc/php.ini")
        if (!php.exists()) {
            context.assets.open("php-cli").use { input ->
                FileOutputStream(php).use { output ->
                    input.copyTo(output)
                }
            }
        }
        if (!php.canExecute()) php.setExecutable(true)
        return Thread {
            try {
                for (cmd in commands) {
                    val process = ProcessBuilder(
                        "/system/bin/sh",
                        "-c",
                        if (cmd.contains("run.php")) "${php.absolutePath} -c ${phpIni.absolutePath} $cmd" else cmd)
                        .redirectErrorStream(true)
                        .start()
                    currentProcess = process
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (Thread.currentThread().isInterrupted) {
                                process.destroy()
                                php.delete()
                                return@Thread
                            }
                            onLine(line)
                        }
                    }
                    process.waitFor()
                    currentProcess = null
                }
            } catch (_: InterruptedException) {
                onLine(context.getString(R.string.interrupted))
                currentProcess?.destroy()
            } catch (e: Exception) {
                onLine("${context.getString(R.string.error)} ${e.message}")
            } finally {
                php.delete()
                onComplete?.invoke()
            }
        }
    }
}