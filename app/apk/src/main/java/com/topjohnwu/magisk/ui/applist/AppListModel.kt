package com.topjohnwu.magisk.ui.applist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class HideMode { BLACKLIST, WHITELIST }

/** Categories maintained by the payload (names are the preset names of the service). */
enum class HideCategory(val presetName: String) {
    ROOT("root_apps"),
    XPOSED("xposed"),
    DETECTOR("detector_apps"),
    CUSTOM_ROM("custom_rom"),
    SUSPICIOUS("sus_apps"),
    ACCESSIBILITY("accessibility_apps"),
    SHIZUKU("shizuku_dhizuku");

    companion object {
        fun fromPreset(name: String) = entries.firstOrNull { it.presetName == name }
    }
}

/**
 * What the user configured in the UI. It is kept in a small private preference and converted into
 * the JSON config of the service by [AppListConfigBuilder].
 *
 * - [hidden]       BLACKLIST: apps hidden from other apps. WHITELIST: apps that stay visible.
 * - [connected]    app -> apps that stay mutually visible with it while it is hidden
 * - [whitelist]    apps that always see every installed app (launchers, stores, ...)
 * - [blind]        apps that see nothing except [whitelist] and their connected apps
 * - [categories]   categories hidden from everybody
 * - [appCategories] extra categories hidden from one specific app
 */
data class AppListModel(
    val mode: HideMode = HideMode.BLACKLIST,
    val hidden: Set<String> = emptySet(),
    val connected: Map<String, Set<String>> = emptyMap(),
    val whitelist: Set<String> = emptySet(),
    val blind: Set<String> = emptySet(),
    val categories: Set<HideCategory> = emptySet(),
    val appCategories: Map<String, Set<HideCategory>> = emptyMap(),
) {
    fun toJson() = JSONObject().apply {
        put("mode", mode.name)
        put("hidden", JSONArray(hidden.toList()))
        put("connected", JSONObject().also { o -> connected.forEach { (k, v) -> o.put(k, JSONArray(v.toList())) } })
        put("whitelist", JSONArray(whitelist.toList()))
        put("blind", JSONArray(blind.toList()))
        put("categories", JSONArray(categories.map { it.name }))
        put("appCategories", JSONObject().also { o ->
            appCategories.forEach { (k, v) -> o.put(k, JSONArray(v.map { it.name })) }
        })
    }

    companion object {
        private const val PREF = "applist_model"
        private const val KEY = "model"

        private fun JSONArray?.strings(): Set<String> =
            if (this == null) emptySet() else (0 until length()).map { getString(it) }.toSet()

        private fun JSONArray?.cats(): Set<HideCategory> =
            strings().mapNotNull { n -> HideCategory.entries.firstOrNull { it.name == n } }.toSet()

        fun fromJson(o: JSONObject): AppListModel {
            val conn = o.optJSONObject("connected")
            val appCat = o.optJSONObject("appCategories")
            return AppListModel(
                mode = runCatching { HideMode.valueOf(o.optString("mode")) }.getOrDefault(HideMode.BLACKLIST),
                hidden = o.optJSONArray("hidden").strings(),
                connected = conn?.keys()?.asSequence()?.associateWith { conn.optJSONArray(it).strings() }.orEmpty(),
                whitelist = o.optJSONArray("whitelist").strings(),
                blind = o.optJSONArray("blind").strings(),
                categories = o.optJSONArray("categories").cats(),
                appCategories = appCat?.keys()?.asSequence()?.associateWith { appCat.optJSONArray(it).cats() }.orEmpty(),
            )
        }

        fun load(context: Context): AppListModel = runCatching {
            val text = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            if (text == null) AppListModel() else fromJson(JSONObject(text))
        }.getOrDefault(AppListModel())

        fun save(context: Context, model: AppListModel) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY, model.toJson().toString()).apply()
        }
    }
}

/**
 * Translates an [AppListModel] into the service's JSON config.
 *
 * The service decides per *calling* app (see `HMAService.shouldHide`): every installed app that is
 * not in the always-sees-everything whitelist gets a scope entry, with:
 * - BLACKLIST: `extraAppList` = hidden apps (minus those connected with the caller)
 * - WHITELIST: `useWhitelist = true` and `extraAppList` = apps that stay visible (+ connected)
 * Blind apps always get an entry with `useWhitelist = true` that only lets the whitelist and their
 * connected apps through. Unknown keys of an existing config are preserved.
 */
object AppListConfigBuilder {

    private fun arr(items: Collection<String>) = JSONArray(items.sorted())

    private fun appConfig(
        useWhitelist: Boolean,
        excludeSystemApps: Boolean,
        extra: Collection<String>,
        presets: Collection<HideCategory>,
    ) = JSONObject().apply {
        put("useWhitelist", useWhitelist)
        put("excludeSystemApps", excludeSystemApps)
        put("extraAppList", arr(extra))
        put("applyPresets", arr(presets.map { it.presetName }))
    }

    private fun visibleBetween(model: AppListModel, a: String, b: String) =
        b in model.connected[a].orEmpty() || a in model.connected[b].orEmpty()

    fun build(existing: String?, model: AppListModel, installed: Collection<String>): String {
        val root = runCatching { JSONObject(existing ?: "{}") }.getOrDefault(JSONObject())
        root.put("configVersion", AppListClient.CONFIG_VERSION)

        val scope = JSONObject()
        val callers = installed.filter { it !in model.whitelist }

        for (caller in callers) {
            val cats = model.categories + model.appCategories[caller].orEmpty()
            val cfg = when {
                caller in model.blind -> {
                    val allowed = model.whitelist + installed.filter { visibleBetween(model, caller, it) }
                    appConfig(true, false, allowed, cats)
                }
                model.mode == HideMode.BLACKLIST -> {
                    val toHide = model.hidden.filter { it != caller && !visibleBetween(model, caller, it) }
                    appConfig(false, true, toHide, cats)
                }
                else -> {
                    val visible = model.hidden + model.whitelist +
                        installed.filter { visibleBetween(model, caller, it) }
                    appConfig(true, true, visible, cats)
                }
            }
            scope.put(caller, cfg)
        }
        root.put("scope", scope)

        // Newly installed apps follow the global rules
        if (model.mode == HideMode.BLACKLIST) {
            root.put("defaultConfig", appConfig(false, true, model.hidden, model.categories))
        } else {
            root.put("defaultConfig", appConfig(true, true, model.hidden + model.whitelist, model.categories))
        }
        return root.toString()
    }
}
