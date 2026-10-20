package com.topjohnwu.magisk.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.model.module.LocalModule
import kotlinx.coroutines.runBlocking

class ModuleActionWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ModuleListFactory(applicationContext, intent)
}

private class ModuleListFactory(
    private val context: Context,
    intent: Intent,
) : RemoteViewsService.RemoteViewsFactory {

    private val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
    private var modules: List<LocalModule> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val actionTab = ModuleActionWidgetProvider.isActionTab(context, widgetId)
        modules = runBlocking {
            LocalModule.installed().filter { if (actionTab) it.hasAction else it.hasWebUI }
        }
    }

    override fun onDestroy() {
        modules = emptyList()
    }

    override fun getCount() = modules.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.module_action_widget_item)
        val module = modules.getOrNull(position) ?: return views
        val actionTab = ModuleActionWidgetProvider.isActionTab(context, widgetId)
        views.setTextViewText(R.id.module_name, module.name)
        views.setImageViewResource(
            R.id.type_icon,
            if (actionTab) R.drawable.ic_widget_action else R.drawable.ic_widget_webui
        )
        val fillIn = Intent().putExtras(Bundle().apply {
            putString(ModuleActionWidgetProvider.EXTRA_ID, module.id)
            putString(ModuleActionWidgetProvider.EXTRA_NAME, module.name)
            putBoolean(ModuleActionWidgetProvider.EXTRA_IS_ACTION, actionTab)
        })
        views.setOnClickFillInIntent(R.id.widget_item_container, fillIn)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = true
}
