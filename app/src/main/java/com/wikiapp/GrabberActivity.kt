package com.wikiapp

import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.updatePadding
import org.w3c.dom.Document
import java.io.File
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

class GrabberActivity : AppCompatActivity() {
    private lateinit var scrollCont: ScrollView
    private lateinit var apiInput: EditText
    private lateinit var outputDisplay: TextView
    private lateinit var stopBtn: Button
    private lateinit var grabBtn: Button
    private lateinit var sharedPrefs: SharedPreferences
    private var cliThread: Thread? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_grabber)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(Type.systemBars())
            val imeInsets = insets.getInsets(Type.ime())
            val bottomPadding = maxOf(systemBars.bottom, imeInsets.bottom)
            v.updatePadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding)
            insets
        }
        scrollCont = findViewById(R.id.scrollCont)
        apiInput = findViewById(R.id.apiInput)
        outputDisplay = findViewById(R.id.outputDisplay)
        stopBtn = findViewById(R.id.stopBtn)
        grabBtn = findViewById(R.id.grabBtn)
        sharedPrefs = getSharedPreferences("state", MODE_PRIVATE)
        stopBtn.setOnClickListener {
            setRunningState(false)
        }
        grabBtn.setOnClickListener {
            grabFromUrl()
        }
        onBackPressedDispatcher.addCallback(this) {
            if (grabBtn.isEnabled) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
        StrictMode.setThreadPolicy(ThreadPolicy.Builder().permitAll().build())
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }
    private fun grabFromUrl() {
        setRunningState(true)
        outputDisplay.visibility = View.VISIBLE
        outputDisplay.text = ""
        val storageDir = sharedPrefs.getString("storage_dir", null)
        val runPhp = File(filesDir.absolutePath, "mediawiki/maintenance/run.php")
        val apiUrl = apiInput.text.toString().trim()
        val exportUrl = apiUrl.replace("api.php", "Special:Export/${Utils.randomSequence(12)}")
        var siteName = ""
        var dbName = ""
        try {
            val result: Document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
            }.newDocumentBuilder().parse(URL(exportUrl).openStream())
            val xPath = XPathFactory.newInstance().newXPath()
            siteName = xPath.evaluate("//sitename/text()", result, XPathConstants.STRING) as String
            dbName = xPath.evaluate("//dbname/text()", result, XPathConstants.STRING) as String
        } catch (_: Exception) {}
        if (siteName == "") {
            siteName = "wiki_grab"
            try {
                val urlName = URL(apiUrl).host.split(".").firstOrNull()
                if (!urlName.isNullOrBlank()) siteName = urlName
            } catch (_: Exception) {}
            appendToOutput("Failed to fetch wiki name, defaulting to \"$siteName\"\n")
        } else appendToOutput("Wiki name: $siteName\n")
        if (dbName == "") {
            dbName = "wiki_db"
            appendToOutput("Failed to fetch database name, defaulting to \"$dbName\"\n")
        } else appendToOutput("Database name: $dbName\n")
        val newTargetDir = File(storageDir, siteName)
        if (newTargetDir.exists()) {
            appendToOutput("Wiki with name \"$siteName\" already in storage. Stopping.\n")
            setRunningState(false)
            return
        }
        var dbUser = findViewById<EditText>(R.id.usernameInput)!!.text.toString().trim()
        if (dbUser == "") {
            dbUser = "admin"
            appendToOutput("No username given, defaulting to \"$dbUser\"\n")
        } else appendToOutput("Administrator username: $dbUser\n")
        var dbPass = findViewById<EditText>(R.id.passwordInput)!!.text.toString().trim()
        if (dbPass == "") {
            dbPass = "adminpassword"
            appendToOutput("No password given, defaulting to \"$dbPass\"\n")
        } else appendToOutput("Administrator password: $dbPass\n")
        newTargetDir.mkdirs()
        Utils.ensureDirs(newTargetDir.absolutePath)
        var startPrinting = false
        cliThread = Assets.phpCli(this, listOf(
            "${runPhp.absolutePath} install " +
                    "--pass=\"$dbPass\" " +
                    "--scriptpath=\"\" " +
                    "--server=\"http://localhost:4080\" " +
                    "--lang=\"en\" " +
                    "--dbtype=\"sqlite\" " +
                    "--dbname=\"$dbName\" " +
                    "--dbpath=\"${newTargetDir.absolutePath}\" " +
                    "\"$siteName\" " +
                    "\"$dbUser\"",
            "echo \"\\\$wgMessageCacheType = CACHE_NONE;\" >> ${filesDir.absolutePath}/mediawiki/LocalSettings.php",
            "echo \"\\\$wgUploadDirectory = \\\"${newTargetDir.absolutePath}/images\\\";\" >> ${filesDir.absolutePath}/mediawiki/LocalSettings.php",
            "echo \"\\\$wgScribuntoDefaultEngine = 'luasandbox';\" >> ${filesDir.absolutePath}/mediawiki/LocalSettings.php",
            "echo \"wfLoadExtension( 'Scribunto' );\" >> ${filesDir.absolutePath}/mediawiki/LocalSettings.php",
            "${runPhp.absolutePath} grabText -u \"$apiUrl\"",
            "${runPhp.absolutePath} grabFiles -u \"$apiUrl\"",
            "${runPhp.absolutePath} rebuildtextindex",
            "${runPhp.absolutePath} rebuildrecentchanges",
            "${runPhp.absolutePath} refreshLinks --redirects-only"),
            onLine = { line ->
                runOnUiThread {
                    if (startPrinting) appendToOutput("$line\n")
                    else if (line.contains("The environment has been checked.")) {
                        startPrinting = true
                        appendToOutput("$line\n")
                    }
                }
            },
            onComplete = {
                val extraFile = File(newTargetDir.absolutePath, "wikicache.sqlite")
                if (extraFile.exists()) extraFile.delete()
                runOnUiThread {
                    setRunningState(false)
                }
            })
        cliThread?.start()
    }
    private fun setRunningState(state: Boolean) {
        grabBtn.isEnabled = !state
        stopBtn.isEnabled = state
        val settingsFile = File(filesDir.absolutePath, "/mediawiki/LocalSettings.php")
        val settingsFileBak = File(filesDir.absolutePath, "/mediawiki/LocalSettingsBak.php")
        if (state) {
            if (settingsFile.exists()) {
                settingsFile.copyTo(settingsFileBak, overwrite = true)
                settingsFile.delete()
            }
        }
        else {
            if (settingsFileBak.exists()) {
                settingsFileBak.copyTo(settingsFile, overwrite = true)
                settingsFileBak.delete()
            }
        }
        if (!state && cliThread != null && cliThread!!.isAlive && !cliThread!!.isInterrupted) cliThread?.interrupt()
    }
    private fun appendToOutput(line: String) {
        val nearBottom = 160 >= scrollCont.getChildAt(0).bottom - (scrollCont.height + scrollCont.scrollY)
        outputDisplay.append(line)
        if (nearBottom) scrollCont.post {
            scrollCont.fullScroll(View.FOCUS_DOWN)
        }
    }
}