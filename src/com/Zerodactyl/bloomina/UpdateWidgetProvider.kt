package com.Zerodactyl.bloomina

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.Zerodactyl.bloomina.data.OtaConfig
import com.Zerodactyl.bloomina.ui.MainActivity

/**
 * Home-screen widget that shows whether a bloomina update is available and opens the app on tap.
 * Refreshes on its own cadence and whenever [notifyUpdate] is called after a check.
 */
class UpdateWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
        val available = context.getSharedPreferences(OtaConfig.PREFS_NAME, 0)
            .getBoolean("cached_available", false)
        val rv = RemoteViews(context.packageName, R.layout.update_widget)
        rv.setImageViewResource(
            R.id.widgetIcon,
            if (available) R.drawable.ic_status_available else R.drawable.ic_status_uptodate,
        )
        rv.setTextViewText(R.id.widgetTitle, context.getString(R.string.app_name))
        rv.setTextViewText(
            R.id.widgetStatus,
            context.getString(if (available) R.string.widget_update_available else R.string.widget_up_to_date),
        )
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        rv.setOnClickPendingIntent(R.id.widgetRoot, pi)
        rv.setOnClickPendingIntent(R.id.widgetIcon, pi)
        rv.setOnClickPendingIntent(R.id.widgetStatus, pi)
        mgr.updateAppWidget(id, rv)
    }

    companion object {
        /** Push a fresh status to all live widget instances. */
        fun notifyUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, UpdateWidgetProvider::class.java))
            val provider = UpdateWidgetProvider()
            ids.forEach { provider.render(context, mgr, it) }
        }
    }
}
