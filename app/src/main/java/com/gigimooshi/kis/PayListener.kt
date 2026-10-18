package com.gigimooshi.kis

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Reads payment notifications (Google Wallet by default) and subtracts them from the budget. */
class PayListener : NotificationListenerService() {

    companion object {
        @Volatile private var connected: PayListener? = null

        /** Ask Android to reconnect the listener if a battery manager cut it off. */
        fun rebind(ctx: Context) {
            runCatching { requestRebind(ComponentName(ctx, PayListener::class.java)) }
        }

        /**
         * Count any payment still sitting in the notification shade that was missed.
         * Called whenever Kis gets to run (app open, widget refresh, update job). If the listener is
         * connected it scans right away; otherwise Android is asked to reconnect, which also scans.
         */
        fun catchUp(ctx: Context) {
            val l = connected
            if (l != null) l.scanShade() else rebind(ctx)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        BudgetStore.get(this).noteListener(connected = true)
        scanShade()
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        BudgetStore.get(this).noteListener(connected = false)
        super.onListenerDisconnected()
        rebind(this)
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    fun scanShade() {
        try {
            activeNotifications?.forEach { sbn -> runCatching { handle(sbn, catchUp = true) } }
        } catch (t: Throwable) {
            Log.w("Kis", "catch-up scan failed", t)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn, catchUp = false)
        } catch (t: Throwable) {
            Log.w("Kis", "Failed to handle notification from ${sbn.packageName}", t)
        }
    }

    /** [catchUp] = found while rescanning the shade rather than delivered live. */
    private fun handle(sbn: StatusBarNotification, catchUp: Boolean) {
        val store = BudgetStore.get(this)
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val watched = pkg in store.watchedPackages()
        if (!watched && !store.discoveryMode) return

        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras ?: return

        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val parts = listOf(
            title,
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (parts.isEmpty()) return

        val full = parts.joinToString(" · ")
        val parsed = AmountParser.parse(full)

        if (!watched) {
            // Discovery mode only: show ₪ notifications from other apps so you can find the right package.
            if (parsed != null) store.logDiscovery(pkg, full, "not tracked")
            return
        }
        if (!catchUp) store.noteSeen(pkg)
        if (parsed == null) {
            if (store.discoveryMode) store.logDiscovery(pkg, full, "tracked app, but no ₪ amount found")
            return
        }
        if (parsed.kind == AmountParser.Kind.IGNORE) {
            if (store.discoveryMode) store.logDiscovery(pkg, full, "looks declined/failed, ignored")
            return
        }
        val window = "$pkg|${sbn.key}|${parsed.agorot}"
        val permanent = "$pkg|${sbn.key}|${n.`when`}|${parsed.agorot}"
        val time = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
        if (store.alreadyCounted(window, permanent)) return
        if (catchUp) {
            // Older than Kis itself, or already in the history (e.g. counted by a build before
            // notification signatures were kept): don't count it again.
            val installed = runCatching { packageManager.getPackageInfo(packageName, 0).firstInstallTime }.getOrDefault(0L)
            if (time < installed || store.hasMatchingPayment(parsed.agorot, time)) return
        }

        // Merchant name: first line that isn't just the amount.
        val label = (parts.firstOrNull { AmountParser.parse(it) == null } ?: "Payment").take(60)

        if (parsed.kind == AmountParser.Kind.REFUND) {
            store.addCredit(parsed.agorot, "$label (refund)", Tx.SRC_PAY, full, time)
        } else {
            store.addExpense(parsed.agorot, label, Tx.SRC_PAY, full, time)
        }
        if (store.discoveryMode) {
            store.logDiscovery(pkg, full, "counted ${Money.fmt(parsed.agorot)}${if (catchUp) " (caught up later)" else ""}")
        }
    }
}
