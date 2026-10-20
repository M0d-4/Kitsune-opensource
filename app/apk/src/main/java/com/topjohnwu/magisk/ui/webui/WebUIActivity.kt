package com.topjohnwu.magisk.ui.webui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.core.R as CoreR
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder

/**
 * Shows a module's web UI (`/data/adb/modules/<id>/webroot`) in a WebView with a KernelSU
 * WebUI compatible JavaScript API (see [WebViewInterface]).
 */
@SuppressLint("SetJavaScriptEnabled")
class WebUIActivity : ComponentActivity() {

    private lateinit var container: FrameLayout
    private lateinit var webView: WebView
    private lateinit var webInterface: WebViewInterface
    private lateinit var moduleDir: String

    private var insets = Insets.ZERO
    private var insetsEnabled = false
    private var insetsReady: (() -> Unit)? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var downloadFilename: String? = null

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            var uris: Array<Uri>? = null
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                uris = when {
                    clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            }
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)

        setContentView(FrameLayout(this).apply {
            addView(
                ProgressBar(this@WebUIActivity),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        })

        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) {
                if (!Shell.getShell().isRoot) return@withContext null
                AppList.getApps(this@WebUIActivity)
                RootUtils.fs // blocks until the root file system service is connected
            }
            if (ready == null) {
                Toast.makeText(
                    this@WebUIActivity, getString(CoreR.string.webui_root_denied), Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            setupWebView()
        }
    }

    private suspend fun setupWebView() {
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME) ?: id
        if (name.isNotEmpty()) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                setTaskDescription(ActivityManager.TaskDescription(name))
            } else {
                setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(name).build())
            }
        }

        WebView.setWebContentsDebuggingEnabled(false)
        moduleDir = "/data/adb/modules/$id"

        container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        webView = WebView(this).apply {
            setBackgroundColor(0)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
        }
        val density = webView.resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            insets = Insets(
                top = (bars.top / density).toInt(),
                bottom = (bars.bottom / density).toInt(),
                left = (bars.left / density).toInt(),
                right = (bars.right / density).toInt(),
            )
            if (insetsEnabled) {
                view.setPadding(0, 0, 0, 0)
            } else {
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insetsReady?.invoke()
            insetsReady = null
            WindowInsetsCompat.CONSUMED
        }
        webInterface = WebViewInterface(this, webView, moduleDir)
        container.addView(webView)
        setContentView(container)

        // The page's CSS may depend on the insets, so wait until the first inset pass ran.
        if (insets == Insets.ZERO) {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                insetsReady = { cont.resumeWith(Result.success(Unit)) }
                cont.invokeOnCancellation { insetsReady = null }
            }
        }
        loadModulePage()
    }

    private fun loadModulePage() {
        val handler = RemoteFsPathHandler(
            directory = File("$moduleDir/webroot"),
            fs = RootUtils.fs,
            insetsSupplier = { insets },
            onInsetsRequested = { enableInsets(it) },
        )
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("mui.kernelsu.org")
            .addPathHandler("/", handler)
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                if (url.scheme.equals("ksu", true) && url.host.equals("icon", true)) {
                    val pkg = url.path?.removePrefix("/")
                    if (!pkg.isNullOrEmpty()) {
                        AppIconUtil.loadAppIconSync(this@WebUIActivity, pkg, 512)?.let { bitmap ->
                            val out = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            return WebResourceResponse(
                                "image/png", null, ByteArrayInputStream(out.toByteArray())
                            )
                        }
                    }
                }
                return assetLoader.shouldInterceptRequest(url)
            }
        }
        webView.addJavascriptInterface(webInterface, "ksu")
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun setDownloadFilename(filename: String) {
                downloadFilename = filename
            }
        }, "downloadInterface")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                if (params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    fileChooser.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    callback?.onReceiveValue(null)
                    filePathCallback = null
                    false
                }
            }
        }
        webView.setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
            onDownload(url, contentDisposition, mimeType)
        })
        webView.loadUrl("https://mui.kernelsu.org/index.html")
    }

    private fun onDownload(url: String, contentDisposition: String, mimeType: String) {
        if (!url.startsWith("data:")) {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (mimeType.isNotEmpty()) setMimeType(mimeType)
                if (contentDisposition.isNotEmpty()) setTitle(contentDisposition)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, WebUIFileHelper.getFilename(contentDisposition)
                )
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, CoreR.string.webui_download_started, Toast.LENGTH_SHORT).show()
            return
        }

        // data: URL -> "<mime>[;base64],<payload>"
        val parts = url.removePrefix("data:").split(",", limit = 2)
        if (parts.size != 2) return
        val (header, payload) = parts
        val mime = header.split(";").first()
        val isBase64 = "base64" in header
        var filename = downloadFilename ?: "download"
        if (contentDisposition.isNotEmpty()) filename = WebUIFileHelper.getFilename(contentDisposition)
        if ('.' !in filename) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { filename += ".$it" }
        }
        filename = WebUIFileHelper.sanitizeFilename(filename)
        try {
            if (isBase64) {
                WebUIFileHelper.saveContent(this, Base64.decode(payload, Base64.DEFAULT), filename)
            } else {
                WebUIFileHelper.saveContent(this, URLDecoder.decode(payload, "UTF-8"), filename)
            }
            downloadFilename = null
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to decode data: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun enableInsets(enable: Boolean = true) {
        runOnUiThread {
            if (insetsEnabled != enable) {
                insetsEnabled = enable
                ViewCompat.requestApplyInsets(container)
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"

        fun start(context: Context, moduleId: String, moduleName: String) {
            context.startActivity(
                Intent(context, WebUIActivity::class.java)
                    .putExtra(EXTRA_ID, moduleId)
                    .putExtra(EXTRA_NAME, moduleName)
            )
        }

        // Lets pages download blob: links (not supported by WebView's DownloadListener).
        private const val DOWNLOAD_HOOK_JS = """
            (function () {
                document.addEventListener("click", async (e) => {
                    if (e.target.tagName !== "A" || !e.target.hasAttribute("download")) return;
                    const filename = e.target.getAttribute("download") || "download";
                    if (window.downloadInterface) {
                        downloadInterface.setDownloadFilename(filename);
                    }
                    if (!e.target.href.startsWith("blob:")) return;
                    e.preventDefault();
                    const blob = await fetch(e.target.href).then((r) => r.blob());
                    const reader = new FileReader();
                    reader.onload = () => {
                        const temp = document.createElement("a");
                        temp.href = reader.result;
                        temp.download = filename;
                        temp.click();
                    };
                    reader.readAsDataURL(blob);
                });
            })();
        """
    }
}
