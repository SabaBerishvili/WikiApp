package com.wikiapp

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.pow
import kotlin.math.sqrt

class BrowserActivity : AppCompatActivity() {
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var hiddenHandle: View
    private lateinit var hiddenHandleIcon: ImageView
    private lateinit var gestureDetector: GestureDetector
    private lateinit var menuContainer: LinearLayout
    private lateinit var webView: WebView
    private lateinit var sharedPrefs: SharedPreferences
    private val hostAddress: String = "localhost:4080"
    private var menuBusy: Boolean = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_browser)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(Type.systemBars())
            val imeInsets = insets.getInsets(Type.ime())
            val bottomPadding = maxOf(systemBars.bottom, imeInsets.bottom)
            v.updatePadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding)
            insets
        }
        swipeRefresh = findViewById(R.id.swipe_refresh)
        progressBar = findViewById(R.id.progress_bar)
        hiddenHandle = findViewById(R.id.hidden_handle)
        hiddenHandleIcon = findViewById(R.id.hidden_handle_icon)
        menuContainer = findViewById(R.id.menuContainer)
        webView = findViewById(R.id.webview)
        sharedPrefs = getSharedPreferences("state", MODE_PRIVATE)
        prepWebView()
        prepGesture()
        prepMenu()
        webView.loadUrl(hostAddress)
    }
    private fun prepMenu() {
        updateMenuIcon()
        findViewById<ImageButton>(R.id.menuItemHome).setOnClickListener {
            webView.loadUrl(hostAddress)
            closeMenu()
        }
        findViewById<ImageButton>(R.id.menuItemBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
            closeMenu()
        }
        findViewById<ImageButton>(R.id.menuItemForward).setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
            closeMenu()
        }
        findViewById<ImageButton>(R.id.menuItemRefresh).setOnClickListener {
            webView.reload()
            closeMenu()
        }
        findViewById<Button>(R.id.menuItemPage).setOnClickListener {
            if (webView.getUrl() != null) showPageDialog()
            closeMenu()
        }
        findViewById<Button>(R.id.menuItemExternal).setOnClickListener {
            if (webView.getUrl() != null) startActivity(Intent(Intent.ACTION_VIEW, webView.getUrl()?.toUri()))
            closeMenu()
        }
        findViewById<Button>(R.id.menuItemConfiguration).setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
            closeMenu()
        }
        findViewById<Button>(R.id.menuItemGrabber).setOnClickListener {
            startActivity(Intent(this, GrabberActivity::class.java))
            closeMenu()
        }
        val menuItemHideButton = findViewById<CheckBox>(R.id.menuItemHideButton)
        menuItemHideButton.isChecked = !sharedPrefs.getBoolean("menu_hidden", true)
        menuItemHideButton.setOnCheckedChangeListener { buttonView, isChecked ->
            sharedPrefs.edit { putBoolean("menu_hidden", !isChecked) }
            updateMenuIcon()
            closeMenu()
        }
    }
    private fun prepGesture() {
        hiddenHandle.visibility = View.VISIBLE
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 400
            private val SWIPE_VELOCITY_THRESHOLD = 4000
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val distance = sqrt((e2.x - e1.x).pow(2) + (e2.y - e1.y).pow(2))
                val velocity = sqrt(velocityX.pow(2) + velocityY.pow(2))
                if (distance > SWIPE_THRESHOLD && velocity > SWIPE_VELOCITY_THRESHOLD) {
                    openMenu()
                    return true
                }
                return false
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                openMenu()
                return true
            }
        })
        hiddenHandle.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            hiddenHandle.performClick()
            val handleLocation = IntArray(2)
            val globalLocation = IntArray(2)
            v?.getLocationOnScreen(handleLocation)
            webView.getLocationOnScreen(globalLocation)
            val forwarded = MotionEvent.obtain(
                event.downTime,
                event.eventTime,
                event.action,
                event.x + handleLocation[0] - globalLocation[0],
                event.y + handleLocation[1] - globalLocation[1],
                event.metaState
            )
            webView.dispatchTouchEvent(forwarded)
            forwarded.recycle()
            true
        }
    }
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun prepWebView(){
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                val host = request?.url?.host ?: return false
                val port = request.url?.port ?: return false
                return if (!("$host:$port").endsWith(hostAddress)) {
                    showExternalLinkDialog(url)
                    true
                } else false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    if (progressBar.visibility != View.VISIBLE) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = 0
                    }
                    progressBar.progress = newProgress
                } else {
                    progressBar.progress = 100
                    progressBar.postDelayed({
                        progressBar.animate()
                            .alpha(0f)
                            .setDuration(200)
                            .withEndAction {
                                progressBar.visibility = View.GONE
                                progressBar.alpha = 1f
                                progressBar.progress = 0
                            }.start()
                    }, 100)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (!menuBusy && menuContainer.isVisible) closeMenu()
            else if (webView.canGoBack()) webView.goBack()
        }
        webView.setOnTouchListener { v, event ->
            webView.performClick()
            closeMenu()
            false
        }
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ -> swipeRefresh.isEnabled = scrollY == 0 }
        swipeRefresh.setOnRefreshListener { webView.reload() }
        swipeRefresh.setColorSchemeResources(R.color.general_11, R.color.general_21, R.color.general_22)
        webView.settings.javaScriptEnabled = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
    }
    private fun openMenu() {
        if (!menuBusy && menuContainer.isGone) {
            menuBusy = true
            menuContainer.apply {
                post {
                    pivotX = 0f
                    pivotY = height.toFloat()
                    scaleX = 0.6f
                    scaleY = 0.6f
                    alpha = 0f
                    visibility = View.VISIBLE
                    animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator()).withEndAction {
                            menuBusy = false
                        }
                        .start()
                }
            }
        }
    }
    private fun closeMenu() {
        if (!menuBusy && menuContainer.isVisible) {
            menuBusy = true
            menuContainer.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    menuContainer.visibility = View.GONE
                    menuBusy = false
                }
                .start()
        }
    }
    private fun showPageDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.go_to_page))
        val container = FrameLayout(this)
        val margin = (16 * resources.displayMetrics.density).toInt()
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(margin, 0, margin, 0)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = params
            setText(webView.url
                ?.substringAfter(hostAddress, "")
                ?.removePrefix("/"))
        }
        container.addView(input)
        builder.setView(container)
        builder.setPositiveButton(getString(R.string.load)) { dialog, _ ->
            webView.loadUrl("$hostAddress/${input.text.toString().trim()}")
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }
    private fun showExternalLinkDialog(url: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.external_link))
        builder.setMessage("\n$url\n")
        builder.setPositiveButton(getString(R.string.open_browser)) { _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
        builder.setNegativeButton(getString(R.string.ignore)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.setNeutralButton(getString(R.string.copy_link)) { _, _ ->
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.copy_label), url)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
        }
        builder.show()
    }
    private fun updateMenuIcon() {
        hiddenHandleIcon.visibility = if (sharedPrefs.getBoolean("menu_hidden", true)) View.VISIBLE else View.GONE
    }
}