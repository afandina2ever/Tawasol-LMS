package com.tawasol.lms

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var topProgress: ProgressBar
    private lateinit var errorLayout: View
    private lateinit var prefs: android.content.SharedPreferences

    private var domainUrl: String = ""
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var lastBackPressTime: Long = 0

    private val fileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode != Activity.RESULT_OK) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data
        val results: Array<Uri>? = when {
            data?.clipData != null -> {
                val count = data.clipData!!.itemCount
                Array(count) { i -> data.clipData!!.getItemAt(i).uri }
            }
            data?.data != null -> arrayOf(data.data!!)
            cameraImageUri != null -> arrayOf(cameraImageUri!!)
            else -> null
        }
        callback.onReceiveValue(results)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* no-op — the chooser still offers gallery/other apps even if denied */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        domainUrl = prefs.getString(Prefs.KEY_DOMAIN, null) ?: run {
            goToSetup()
            return
        }

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        topProgress = findViewById(R.id.topProgress)
        errorLayout = findViewById(R.id.errorLayout)

        setupWebView()

        // إصلاح "السايد بار لا يتحرك": الموقع نفسه (includes/header.php) بيبعت
        // إشارة عن طريق الجسر ده لما إصبع المستخدم يكون على قائمة السايد بار،
        // عشان نوقف الـ SwipeRefreshLayout مؤقتًا (وإلا فهو بيسحب أي لمسة على
        // الشاشة كلها على إنها محاولة "تحديث"، لأن فحصه الافتراضي بيتأكد فقط
        // من تمرير الصفحة الرئيسية، مش من تمرير عنصر ثابت زي السايد بار).
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        swipeRefresh.setOnRefreshListener { webView.reload() }
        findViewById<View>(R.id.retryButton).setOnClickListener {
            errorLayout.visibility = View.GONE
            webView.loadUrl(domainUrl)
        }
        findViewById<View>(R.id.changeDomainButton).setOnClickListener { clearDomainAndRestart() }

        webView.loadUrl(domainUrl)

        // نطلب صلاحية الكاميرا مبكرًا (تُستخدم لو المستخدم اختار "التقاط صورة" عند رفع ملف داخل الموقع)
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                webView.canGoBack() -> webView.goBack()
                System.currentTimeMillis() - lastBackPressTime < 2000 -> finish()
                else -> {
                    lastBackPressTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, R.string.exit_confirm, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = settings.userAgentString + " TawasolLMSApp/1.0"

        // ضروري لبقاء تسجيل الدخول (الجلسة/الكوكيز) بين مرات فتح التطبيق
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme ?: ""

                // روابط خارجية (اتصال، إيميل، واتساب، تيليجرام) تُفتح بتطبيقاتها الخاصة
                if (scheme == "tel" || scheme == "mailto" || scheme == "sms" ||
                    url.contains("wa.me") || url.contains("api.whatsapp.com") ||
                    url.contains("t.me") || scheme == "tg"
                ) {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                // كل الروابط العادية (http/https) تُفتح داخل التطبيق نفسه
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                errorLayout.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                topProgress.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    swipeRefresh.isRefreshing = false
                    topProgress.visibility = View.GONE
                    errorLayout.visibility = View.VISIBLE
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // الدومين مُدخل من المستخدم وقد لا يملك شهادة SSL صحيحة بعد — نُكمل بحذر
                // بدل ما نكسر التطبيق بالكامل. الأفضل لاحقًا تثبيت شهادة SSL صالحة على السيرفر.
                handler.proceed()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress in 1..99) {
                    topProgress.visibility = View.VISIBLE
                    topProgress.progress = newProgress
                } else {
                    topProgress.visibility = View.GONE
                }
            }

            // دعم رفع الملفات (صور، Excel، PDF...) من داخل نماذج الموقع
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intents = mutableListOf<Intent>()

                // خيار الكاميرا (لو التطبيق عنده صلاحية أو الجهاز يدعمها)
                if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                    try {
                        val photoFile = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                        cameraImageUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", photoFile)
                        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                        }
                        intents.add(cameraIntent)
                    } catch (e: Exception) { /* تجاهل، يبقى خيار المعرض متاح */ }
                }

                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }

                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "اختر ملفًا")
                    if (intents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
                    }
                }

                fileChooserLauncher.launch(chooserIntent)
                return true
            }
        }

        // دعم تنزيل الملفات (تصدير Excel، طباعة PDF...) عبر مدير التنزيلات في أندرويد
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                val cookie = CookieManager.getInstance().getCookie(url)
                request.addRequestHeader("cookie", cookie)
                request.addRequestHeader("User-Agent", userAgent)
                val fileName = URLUtilGuessFileName(url, contentDisposition, mimeType)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "جاري التنزيل...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "تعذّر بدء التنزيل", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun URLUtilGuessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        return android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                webView.reload()
                true
            }
            R.id.action_change_domain -> {
                clearDomainAndRestart()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun clearDomainAndRestart() {
        prefs.edit().remove(Prefs.KEY_DOMAIN).apply()
        goToSetup()
    }

    private fun goToSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    // جسر جافاسكريبت <-> أندرويد يستخدمه كود الموقع (includes/header.php) عشان
    // يوقف/يشغّل "اسحب لتحديث الصفحة" مؤقتًا وقت لمس قائمة السايد بار الداخلية،
    // بدل ما SwipeRefreshLayout يسرقها ظنًا منه إنها محاولة تحديث للصفحة كلها.
    private inner class AndroidBridge {
        @JavascriptInterface
        fun setSwipeEnabled(enabled: Boolean) {
            runOnUiThread { swipeRefresh.isEnabled = enabled }
        }
    }
}
