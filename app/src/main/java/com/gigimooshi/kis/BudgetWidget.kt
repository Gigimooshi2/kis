package com.gigimooshi.kis

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale

class BudgetWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Also credits the daily budget if the hour has passed (widget refreshes every 30 min).
        BudgetStore.get(context).catchUp()
        render(context, manager, ids)
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, BudgetWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val store = BudgetStore.get(context)
            val bal = store.balance
            val views = RemoteViews(context.packageName, R.layout.widget_budget)
            views.setTextViewText(R.id.widget_balance, Money.fmt(bal))
            views.setTextColor(R.id.widget_balance, if (bal >= 0) 0xFF81C784.toInt() else 0xFFE57373.toInt())
            views.setTextViewText(
                R.id.widget_sub,
                String.format(Locale.US, "+%s at %02d:00", Money.fmt(store.dailyAgorot), store.creditHour),
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(ids, views)
        }
    }
}
