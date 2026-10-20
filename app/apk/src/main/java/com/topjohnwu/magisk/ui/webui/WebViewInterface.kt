package com.topjohnwu.magisk.ui.webui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The `ksu` JavaScript object exposed to module web pages (KernelSU WebUI compatible API).
 */
class WebViewInterface(
    private val context: Context,
    private val webView: WebView,
    private val modDir: String,
) {

    private fun js(script: String) = webView.post { webView.evaluateJavascript(script, null) }

    private fun StringBuilder.appendOptions(options: String?) {
        val json = if (options == null) JSONObject() else JSONObject(options)
        val cwd = json.optString("cwd")
        if (!TextUtils.isEmpty(cwd)) append("cd $cwd;")
        json.optJSONObject("env")?.let { env ->
            env.keys().forEach { key -> append("export $key=${env.getString(key)};") }
        }
    }

    @JavascriptInterface
    fun exec(cmd: String): String = newRootShell().use { shell ->
        shell.newJob().add(cmd).to(ArrayList<String>()).exec().out.joinToString("\n")
    }

    @JavascriptInterface
    fun exec(cmd: String, callbackFunc: String) = exec(cmd, null, callbackFunc)

    @JavascriptInterface
    fun exec(cmd: String, options: String?, callbackFunc: String) {
        val script = StringBuilder().apply { appendOptions(options); append(cmd) }.toString()
        newRootShell().use { shell ->
            val out = ArrayList<String>()
            val err = ArrayList<String>()
            val result = shell.newJob().add(script).to(out, err).exec()
            val stdout = JSONObject.quote(out.joinToString("\n"))
            val stderr = JSONObject.quote(err.joinToString("\n"))
            js(
                "(function() { try { $callbackFunc(${result.code}, $stdout, $stderr); } " +
                    "catch(e) { console.error(e); } })();"
            )
        }
    }

    @JavascriptInterface
    fun spawn(command: String, args: String?, options: String?, callbackFunc: String) {
        val script = StringBuilder().apply {
            appendOptions(options)
            append(command)
            if (!TextUtils.isEmpty(args)) {
                append(" ")
                val array = JSONArray(args)
                for (i in 0 until array.length()) append(array.getString(i)).append(" ")
            }
        }.toString()

        fun emitData(stream: String, data: String) = js(
            "(function() { try { $callbackFunc.$stream.emit('data', ${JSONObject.quote(data)}); } " +
                "catch(e) { console.error('emitData', e); } })();"
        )

        val shell = newRootShell()
        val stdout = object : CallbackList<String>() {
            override fun onAddElement(e: String) = emitData("stdout", e)
        }
        val stderr = object : CallbackList<String>() {
            override fun onAddElement(e: String) = emitData("stderr", e)
        }
        shell.newJob().add(script).to(stdout, stderr).submit { result ->
            val code = result.code
            js(
                "(function() { try { $callbackFunc.emit('exit', $code); } " +
                    "catch(e) { console.error(`emitExit error: \${e}`); } })();"
            )
            if (code != 0) {
                val message = JSONObject.quote(result.err.joinToString("\n"))
                js(
                    "(function() { try { var err = new Error(); err.exitCode = $code; " +
                        "err.message = $message; $callbackFunc.emit('error', err); } " +
                        "catch(e) { console.error('emitErr', e); } })();"
                )
            }
            Thread { runCatching { shell.close() } }.start()
        }
    }

    @JavascriptInterface
    fun toast(msg: String) {
        webView.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun fullScreen(enable: Boolean) {
        val activity = context as? Activity ?: return
        Handler(Looper.getMainLooper()).post {
            if (enable) activity.window.hideSystemUI() else activity.window.showSystemUI()
        }
    }

    @JavascriptInterface
    fun enableInsets(enable: Boolean = true) {
        (context as? WebUIActivity)?.enableInsets(enable)
    }

    @JavascriptInterface
    fun exit() {
        (context as? Activity)?.finish()
    }

    @JavascriptInterface
    fun moduleInfo(): String = JSONObject().apply {
        put("moduleDir", modDir)
        put("id", File(modDir).name)
    }.toString()

    @JavascriptInterface
    fun listPackages(type: String): String {
        val wanted = type.lowercase(Locale.ROOT)
        val names = AppList.applist.filter {
            val isSystem = ((it.packageInfo.applicationInfo?.flags ?: 0) and 1) != 0
            when (wanted) {
                "system" -> isSystem
                "user" -> !isSystem
                else -> true
            }
        }.map { it.packageName }.sorted()
        return JSONArray(names).toString()
    }

    @JavascriptInterface
    fun getPackagesInfo(packageNamesJson: String): String {
        val requested = JSONArray(packageNamesJson)
        val byName = AppList.applist.associateBy { it.packageName }
        val result = JSONArray()
        for (i in 0 until requested.length()) {
            val name = requested.getString(i)
            val app = byName[name]
            result.put(
                if (app != null) {
                    val info = app.packageInfo
                    val appInfo = info.applicationInfo
                    JSONObject().apply {
                        put("packageName", info.packageName)
                        put("versionName", info.versionName ?: "")
                        put("versionCode", info.longVersionCode)
                        put("appLabel", app.label)
                        put("isSystem", appInfo?.let { (it.flags and 1) != 0 } ?: JSONObject.NULL)
                        put("uid", appInfo?.uid ?: JSONObject.NULL)
                    }
                } else {
                    JSONObject().apply {
                        put("packageName", name)
                        put("error", "Package not found or inaccessible")
                    }
                }
            )
        }
        return result.toString()
    }

    private companion object {
        fun newRootShell(): Shell =
            Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build()

        fun Window.hideSystemUI() {
            WindowCompat.getInsetsController(this, decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        fun Window.showSystemUI() {
            WindowCompat.getInsetsController(this, decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
