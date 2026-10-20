package com.topjohnwu.magisk.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.R as CoreR
import com.topjohnwu.magisk.ui.MainActivity
import com.topjohnwu.magisk.ui.flash.FlashUtils
import com.topjohnwu.magisk.ui.webui.WebUIActivity

/**
 * Home-screen widget listing modules that have an Action (`action.sh`) or a WebUI, with a tab
 * switch between the two. Tapping a module runs its action or opens its WebUI.
 */
class ModuleActionWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SWITCH_TAB -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
                val isActionTab = intent.getBooleanExtra(EXTRA_IS_ACTION_TAB, true)
                prefs(context).edit().putBoolean(tabKey(widgetId), isActionTab).apply()
                val manager = AppWidgetManager.getInstance(context)
                updateWidget(context, manager, widgetId)
                manager.notifyAppWidgetViewDataChanged(widgetId, R.id.module_list)
            }
            ACTION_LAUNCH_ITEM -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return super.onReceive(context, intent)
                val name = intent.getStringExtra(EXTRA_NAME)
                if (intent.getBooleanExtra(EXTRA_IS_ACTION, true)) {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .setAction(FlashUtils.INTENT_MODULE_ACTION)
                            .putExtra(FlashUtils.EXTRA_MODULE_ID, id)
                            .putExtra(FlashUtils.EXTRA_MODULE_NAME, name ?: id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                } else if (name != null) {
                    context.startActivity(
                        Intent(context, WebUIActivity::class.java)
                            .putExtra("id", id)
                            .putExtra("name", name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
        super.onUpdate(context, manager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = prefs(context).edit()
        appWidgetIds.forEach { editor.remove(tabKey(it)) }
        editor.apply()
    }

    companion object {
        const val ACTION_SWITCH_TAB = "com.topjohnwu.magisk.action.WIDGET_SWITCH_TAB"
        const val ACTION_LAUNCH_ITEM = "com.topjohnwu.magisk.action.WIDGET_LAUNCH_ITEM"
        const val EXTRA_IS_ACTION_TAB = "is_action_tab"
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        const val EXTRA_IS_ACTION = "is_action"
        private const val PREFS = "com.topjohnwu.magisk.widget.ModuleActionWidget"

        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun tabKey(widgetId: Int) = "is_action_tab_$widgetId"
        fun isActionTab(context: Context, widgetId: Int) =
            prefs(context).getBoolean(tabKey(widgetId), true)

        private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val actionTab = isActionTab(context, widgetId)
            val views = RemoteViews(context.packageName, R.layout.module_action_widget)

            views.setInt(
                R.id.tab_action, "setBackgroundResource",
                if (actionTab) R.drawable.bg_widget_tab_selected else 0
            )
            views.setInt(
                R.id.tab_webui, "setBackgroundResource",
                if (actionTab) 0 else R.drawable.bg_widget_tab_selected
            )
            views.setTextViewText(
                R.id.empty_view,
                context.getString(
                    if (actionTab) CoreR.string.widget_no_action_modules
                    else CoreR.string.widget_no_webui_modules
                )
            )

            fun tabIntent(isAction: Boolean, requestCode: Int) = PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, ModuleActionWidgetProvider::class.java)
                    .setAction(ACTION_SWITCH_TAB)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra(EXTRA_IS_ACTION_TAB, isAction),
                PI_FLAGS
            )
            views.setOnClickPendingIntent(R.id.tab_action, tabIntent(true, widgetId * 10))
            views.setOnClickPendingIntent(R.id.tab_webui, tabIntent(false, widgetId * 10 + 1))

            val serviceIntent = Intent(context, ModuleActionWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            serviceIntent.data = Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.module_list, serviceIntent)
            views.setEmptyView(R.id.module_list, R.id.empty_view)

            views.setPendingIntentTemplate(
                R.id.module_list,
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, ModuleActionWidgetProvider::class.java).setAction(ACTION_LAUNCH_ITEM),
                    PI_FLAGS
                )
            )
            manager.updateAppWidget(widgetId, views)
        }
    }
}
