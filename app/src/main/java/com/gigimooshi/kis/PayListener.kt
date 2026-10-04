package com.gigimooshi.kis

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Reads payment notifications (Google Wallet by default) and subtracts them from the budget. */
class PayListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn)
        } catch (t: Throwable) {
            Log.w("Kis", "Failed to handle notification from ${sbn.packageName}", t)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
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
        if (parsed == null) {
            if (store.discoveryMode) store.logDiscovery(pkg, full, "tracked app, but no ₪ amount found")
            return
        }
        if (parsed.kind == AmountParser.Kind.IGNORE) {
            if (store.discoveryMode) store.logDiscovery(pkg, full, "looks declined/failed, ignored")
            return
        }
        if (store.seenRecently("$pkg|${sbn.key}|${parsed.agorot}")) return

        // Merchant name: first line that isn't just the amount.
        val label = (parts.firstOrNull { AmountParser.parse(it) == null } ?: "Payment").take(60)

        if (parsed.kind == AmountParser.Kind.REFUND) {
            store.addCredit(parsed.agorot, "$label (refund)", Tx.SRC_PAY, full)
        } else {
            store.addExpense(parsed.agorot, label, Tx.SRC_PAY, full)
        }
        if (store.discoveryMode) store.logDiscovery(pkg, full, "counted ${Money.fmt(parsed.agorot)}")
    }
}
