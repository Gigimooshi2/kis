package com.gigimooshi.kis

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home-screen widget: balance, a bar of today's budget used, and the last N expenses.
 * Rows that wouldn't fit the widget's height are dropped, so it can be resized from a
 * one-line balance up to a full list.
 */
class BudgetWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runCatching { BudgetStore.get(context).catchUp() } // credits the day if the hour passed
        PayListener.rebind(context)
        refresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        safeRender(context, manager, id) // resized
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            runCatching { BudgetStore.get(context).catchUp() }
            refresh(context)
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.gigimooshi.kis.WIDGET_REFRESH"

        // Rough layout heights (dp) to decide how many rows fit; text parts scale with font size.
        private const val HEADER_FIXED_DP = 91f
        private const val HEADER_TEXT_DP = 28f
        private const val ROW_FIXED_DP = 6f
        private const val ROW_TEXT_DP = 16f
        private const val SAFETY_DP = 12f // some launchers report a bit more height than they draw

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, BudgetWidget::class.java))
            if (ids.isEmpty()) return
            ids.forEach { safeRender(context, manager, it) }
            scheduleNextRefresh(context)
        }

        /** Wake the widget right after the next daily credit so the bar resets on time. */
        private fun scheduleNextRefresh(context: Context) {
            runCatching {
                val am = context.getSystemService(AlarmManager::class.java) ?: return
                val at = BudgetStore.get(context).nextCredit().toInstant().toEpochMilli() + 60_000L
                val pi = PendingIntent.getBroadcast(
                    context, 7, Intent(context, BudgetWidget::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                am.set(AlarmManager.RTC, at, pi)
            }
        }

        private fun safeRender(context: Context, manager: AppWidgetManager, id: Int) {
            try {
                render(context, manager, id)
            } catch (e: Exception) {
                Log.w("Kis", "widget render failed", e)
            }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val store = BudgetStore.get(context)
            val bal = store.balance
            val spent = store.spentThisPeriod().coerceAtLeast(0L)
            val total = bal + spent
            val ratio = if (total <= 0L) 1f else (spent.toFloat() / total).coerceIn(0f, 1f)
            val over = bal < 0

            val v = RemoteViews(context.packageName, R.layout.widget_budget)
            v.setTextViewText(R.id.widget_balance, Money.fmt(bal))
            v.setTextColor(R.id.widget_balance, context.getColor(if (over) R.color.neg else R.color.text))
            v.setTextViewText(
                R.id.widget_next,
                String.format(Locale.US, "+%s at %02d:00", Money.fmt(store.dailyAgorot, compact = true), store.creditHour),
            )

            val bar = when {
                over -> R.id.widget_bar_over
                ratio >= 0.75f -> R.id.widget_bar_warn
                else -> R.id.widget_bar_ok
            }
            for (b in intArrayOf(R.id.widget_bar_ok, R.id.widget_bar_warn, R.id.widget_bar_over)) {
                v.setViewVisibility(b, if (b == bar) View.VISIBLE else View.GONE)
            }
            v.setProgressBar(bar, 1000, (ratio * 1000).toInt(), false)
            v.setTextViewText(
                R.id.widget_used,
                if (over) "Over budget by ${Money.fmt(-bal)}"
                else "${Money.fmt(spent, compact = true)} of ${Money.fmt(total, compact = true)} used today",
            )

            // How many rows fit: portrait height is OPTION_APPWIDGET_MAX_HEIGHT.
            val heightDp = manager.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
            val fs = context.resources.configuration.fontScale
            val fits = if (heightDp <= 0) {
                store.widgetCount
            } else {
                ((heightDp - HEADER_FIXED_DP - HEADER_TEXT_DP * fs - SAFETY_DP) / (ROW_FIXED_DP + ROW_TEXT_DP * fs))
                    .toInt().coerceAtLeast(0)
            }
            val n = minOf(store.widgetCount, fits)
            v.setViewVisibility(R.id.widget_used, if (heightDp in 1..89) View.GONE else View.VISIBLE)

            v.removeAllViews(R.id.widget_list)
            val items = if (n > 0) store.recentForWidget(n) else emptyList()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val time = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            val day = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            for (tx in items) {
                val at = Instant.ofEpochMilli(tx.time).atZone(zone)
                val row = RemoteViews(context.packageName, R.layout.widget_row)
                row.setTextViewText(R.id.row_label, tx.label)
                row.setTextViewText(
                    R.id.row_when,
                    when (at.toLocalDate()) {
                        today -> time.format(at)
                        today.minusDays(1) -> "Yest."
                        else -> day.format(at)
                    },
                )
                row.setTextViewText(R.id.row_amount, Money.fmt(tx.amount, showPlus = true, compact = true))
                if (tx.amount > 0) row.setTextColor(R.id.row_amount, context.getColor(R.color.accent))
                v.addView(R.id.widget_list, row)
            }
            v.setViewVisibility(R.id.widget_list, if (items.isEmpty()) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.widget_empty, if (n > 0 && items.isEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.widget_empty, if (store.widgetShowIncome) "No activity yet" else "No expenses yet")

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(id, v)
        }
    }
}
