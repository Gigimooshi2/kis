package com.gigimooshi.kis

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArraySet

/** One history entry. amount is signed: + adds to the budget, − spends from it. */
data class Tx(
    val id: Long,
    val time: Long,
    val amount: Long,
    val label: String,
    val source: String,
    val raw: String?,
) {
    /** Real spending (or a refund of it), as opposed to daily credits and balance corrections. */
    val isSpend: Boolean get() = source == SRC_PAY || source == SRC_MANUAL

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("t", time).put("a", amount)
        .put("l", label).put("s", source)
        .also { if (raw != null) it.put("r", raw) }

    companion object {
        const val SRC_DAILY = "daily"
        const val SRC_PAY = "pay"
        const val SRC_MANUAL = "manual"
        const val SRC_ADJUST = "adjust"

        fun fromJson(o: JSONObject) = Tx(
            o.getLong("id"), o.getLong("t"), o.getLong("a"),
            o.getString("l"), o.getString("s"),
            if (o.has("r")) o.getString("r") else null,
        )
    }
}

data class DiscoveryEntry(val time: Long, val pkg: String, val text: String, val note: String)

/**
 * Single source of truth. Everything lives in SharedPreferences.
 *
 * Daily credit is computed lazily: a "budget day" starts at [creditHour]. Whenever the app,
 * widget or notification listener touches the store, [catchUp] credits every budget day
 * that started since the last one credited. No alarms or background jobs needed.
 */
class BudgetStore private constructor(private val ctx: Context) {

    companion object {
        // Google Wallet / Google Pay. Add more (e.g. a bank or SMS app) in Settings.
        const val DEFAULT_PACKAGES = "com.google.android.apps.walletnfcrel,com.google.android.gms"

        private const val K_DAILY = "daily"
        private const val K_HOUR = "hour"
        private const val K_ROLL = "rollover"
        private const val K_PKGS = "packages"
        private const val K_DISC = "discovery"
        private const val K_BAL = "balance"
        private const val K_LAST_DAY = "last_day"
        private const val K_TXS = "txs"
        private const val K_NEXT_ID = "next_id"
        private const val K_SEEN = "seen"
        private const val K_DISC_LOG = "disc_log"
        private const val K_WIDGET_N = "widget_n"

        private const val MAX_TX = 300
        private const val MAX_DISC = 40
        private const val DEDUP_MS = 5 * 60 * 1000L

        @Volatile private var instance: BudgetStore? = null

        fun get(ctx: Context): BudgetStore =
            instance ?: synchronized(this) {
                instance ?: BudgetStore(ctx.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences = ctx.getSharedPreferences("budget", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    val dailyAgorot: Long get() = prefs.getLong(K_DAILY, 7000L)
    val creditHour: Int get() = prefs.getInt(K_HOUR, 6)
    val rollover: Boolean get() = prefs.getBoolean(K_ROLL, true)
    val discoveryMode: Boolean get() = prefs.getBoolean(K_DISC, false)
    val balance: Long get() = prefs.getLong(K_BAL, 0L)

    /** How many recent expenses the widget lists (it also caps this to what fits). */
    val widgetCount: Int get() = prefs.getInt(K_WIDGET_N, 10)

    fun setWidgetCount(n: Int) {
        prefs.edit().putInt(K_WIDGET_N, n.coerceIn(1, 30)).apply()
        changed()
    }

    fun watchedPackages(): List<String> =
        (prefs.getString(K_PKGS, null) ?: DEFAULT_PACKAGES)
            .split(',', '\n', ' ', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun changed() {
        main.post {
            BudgetWidget.refresh(ctx)
            listeners.forEach { it() }
        }
    }

    // ---- daily credit ------------------------------------------------------

    private fun budgetDay(now: ZonedDateTime): LocalDate =
        if (now.hour >= creditHour) now.toLocalDate() else now.toLocalDate().minusDays(1)

    fun nextCredit(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val today = now.toLocalDate().atTime(creditHour, 0).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(creditHour, 0).atZone(now.zone)
    }

    /** When the current budget day started (today or yesterday at the credit hour). */
    fun periodStart(now: ZonedDateTime = ZonedDateTime.now()): Long =
        budgetDay(now).atTime(creditHour, 0).atZone(now.zone).toInstant().toEpochMilli()

    /** Net spending since the current budget day started (refunds subtract). */
    @Synchronized
    fun spentThisPeriod(now: ZonedDateTime = ZonedDateTime.now()): Long {
        val start = periodStart(now)
        return loadTxs().filter { it.time >= start && it.isSpend }.sumOf { -it.amount }
    }

    @Synchronized
    fun recentExpenses(n: Int): List<Tx> = loadTxs().filter { it.isSpend && it.amount < 0 }.take(n)

    @Synchronized
    fun catchUp(now: ZonedDateTime = ZonedDateTime.now()) {
        val current = budgetDay(now)
        val last = prefs.getString(K_LAST_DAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        if (last == null) {
            // First launch: start with one day's budget.
            commitTx(prefs.edit().putString(K_LAST_DAY, current.toString()), dailyAgorot, "Daily budget", Tx.SRC_DAILY, null)
            return
        }
        if (!last.isBefore(current)) return

        val days = ChronoUnit.DAYS.between(last, current).coerceAtMost(400).toInt()
        var bal = balance
        var total = 0L
        repeat(days) {
            // Rollover on: leftovers stack. Off: leftovers are dropped, but overspending still carries.
            val delta = if (rollover || bal <= 0) dailyAgorot else dailyAgorot - bal
            bal += delta
            total += delta
        }
        val label = if (days == 1) "Daily budget" else "Daily budget ×$days"
        commitTx(prefs.edit().putString(K_LAST_DAY, current.toString()), total, label, Tx.SRC_DAILY, null)
    }

    // ---- transactions ------------------------------------------------------

    @Synchronized
    fun transactions(): List<Tx> = loadTxs()

    private fun loadTxs(): MutableList<Tx> {
        val raw = prefs.getString(K_TXS, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { Tx.fromJson(arr.getJSONObject(it)) }
        }.getOrElse { mutableListOf() }
    }

    private fun txsJson(list: List<Tx>): String =
        JSONArray().also { arr -> list.forEach { arr.put(it.toJson()) } }.toString()

    /** Applies a signed amount to the balance, records it, and persists [e] along with it. */
    private fun commitTx(e: SharedPreferences.Editor, amount: Long, label: String, source: String, raw: String?) {
        if (amount != 0L) {
            val list = loadTxs()
            val id = prefs.getLong(K_NEXT_ID, 1L)
            list.add(0, Tx(id, System.currentTimeMillis(), amount, label, source, raw))
            while (list.size > MAX_TX) list.removeAt(list.size - 1)
            e.putLong(K_NEXT_ID, id + 1)
                .putLong(K_BAL, balance + amount)
                .putString(K_TXS, txsJson(list))
        }
        e.apply()
        changed()
    }

    @Synchronized
    fun addExpense(agorot: Long, label: String, source: String, raw: String?) {
        catchUp()
        commitTx(prefs.edit(), -agorot, label, source, raw)
    }

    @Synchronized
    fun addCredit(agorot: Long, label: String, source: String, raw: String?) {
        catchUp()
        commitTx(prefs.edit(), agorot, label, source, raw)
    }

    @Synchronized
    fun setBalance(target: Long) {
        catchUp()
        commitTx(prefs.edit(), target - balance, "Balance adjusted", Tx.SRC_ADJUST, null)
    }

    /** Removes an entry and reverses its effect on the balance. */
    @Synchronized
    fun deleteTx(id: Long) {
        val list = loadTxs()
        val tx = list.firstOrNull { it.id == id } ?: return
        list.remove(tx)
        prefs.edit()
            .putLong(K_BAL, balance - tx.amount)
            .putString(K_TXS, txsJson(list))
            .apply()
        changed()
    }

    // ---- settings ----------------------------------------------------------

    @Synchronized
    fun updateSettings(daily: Long, hour: Int, roll: Boolean, packages: String, discovery: Boolean) {
        catchUp() // settle any pending days under the old settings first
        prefs.edit()
            .putLong(K_DAILY, daily)
            .putInt(K_HOUR, hour.coerceIn(0, 23))
            .putBoolean(K_ROLL, roll)
            .putString(K_PKGS, packages)
            .putBoolean(K_DISC, discovery)
            .apply()
        changed()
    }

    @Synchronized
    fun addWatchedPackage(pkg: String) {
        val list = watchedPackages().toMutableList()
        if (pkg !in list) list.add(pkg)
        prefs.edit().putString(K_PKGS, list.joinToString(",")).apply()
        changed()
    }

    // ---- notification dedup ------------------------------------------------

    /** True if this signature was already counted in the last few minutes (notification updates repost). */
    @Synchronized
    fun seenRecently(sig: String, now: Long = System.currentTimeMillis()): Boolean {
        val obj = runCatching { JSONObject(prefs.getString(K_SEEN, "{}") ?: "{}") }.getOrElse { JSONObject() }
        val keys = obj.keys().asSequence().toList()
        for (k in keys) if (now - obj.optLong(k, 0L) > DEDUP_MS) obj.remove(k)
        val seen = obj.has(sig)
        obj.put(sig, now)
        prefs.edit().putString(K_SEEN, obj.toString()).apply()
        return seen
    }

    // ---- discovery log -----------------------------------------------------

    @Synchronized
    fun discoveryLog(): List<DiscoveryEntry> {
        val raw = prefs.getString(K_DISC_LOG, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                DiscoveryEntry(o.getLong("t"), o.getString("p"), o.getString("x"), o.getString("n"))
            }
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun logDiscovery(pkg: String, text: String, note: String) {
        val list = discoveryLog().toMutableList()
        list.add(0, DiscoveryEntry(System.currentTimeMillis(), pkg, text.take(300), note))
        while (list.size > MAX_DISC) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.time).put("p", it.pkg).put("x", it.text).put("n", it.note)) }
        prefs.edit().putString(K_DISC_LOG, arr.toString()).apply()
        changed()
    }

    @Synchronized
    fun clearDiscovery() {
        prefs.edit().remove(K_DISC_LOG).apply()
        changed()
    }
}
