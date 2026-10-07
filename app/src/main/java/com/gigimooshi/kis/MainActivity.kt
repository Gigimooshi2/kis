package com.gigimooshi.kis

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: BudgetStore
    private lateinit var c: Palette

    private lateinit var accessBanner: TextView
    private lateinit var updateBanner: TextView
    private lateinit var batteryBanner: TextView
    private lateinit var balanceView: TextView
    private lateinit var bar: BarView
    private lateinit var usedView: TextView
    private lateinit var nextView: TextView
    private lateinit var discoveryBox: LinearLayout
    private lateinit var historyBox: LinearLayout

    private val onChange: () -> Unit = { render() }
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())
    private val fullFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.getDefault())
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BudgetStore.get(this)
        c = Palette(this)
        setContentView(buildUi())
        KisApp.takeLastCrash(application)?.let { showCrash(it) }
    }

    override fun onResume() {
        super.onResume()
        Updater.visible = true
        store.addListener(onChange)
        store.catchUp()
        if (hasListenerAccess()) PayListener.rebind(this)
        render()
        // The updater must never crash the app: a crashing app can't update itself.
        runCatching { Updater.schedule(this) }.onFailure { Log.w("Kis", "schedule failed", it) }
        if (Updater.autoUpdate(this)) checkForUpdates(force = false)
    }

    override fun onPause() {
        Updater.visible = false
        store.removeListener(onChange)
        runCatching { Updater.soon(this) }.onFailure { Log.w("Kis", "soon failed", it) }
        super.onPause()
    }

    // ---- view helpers ------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp)
    }

    private fun ripple(content: Drawable?, radiusDp: Int) =
        RippleDrawable(ColorStateList.valueOf(c.ripple), content, rounded(Color.WHITE, radiusDp))

    private fun label(s: CharSequence, size: Float, color: Int, face: Typeface = Typeface.DEFAULT) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = face
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c.surface, 24)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun pill(text: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 16f
        typeface = medium
        setTextColor(if (filled) c.onAccent else c.accent)
        setPadding(dp(20), dp(15), dp(20), dp(15))
        background = ripple(rounded(if (filled) c.accent else c.accentSoft, 28), 28)
        setOnClickListener { onClick() }
    }

    private fun banner(bgColor: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        textSize = 14f
        typeface = medium
        setTextColor(fg)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = ripple(rounded(bgColor, 18), 18)
        setOnClickListener { onClick() }
    }

    private fun sectionTitle(text: String) = label(text, 13f, c.accent, medium).apply {
        setPadding(0, dp(18), 0, dp(4))
        isAllCaps = true
        letterSpacing = 0.06f
    }

    private val isNight: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun dialog() = AlertDialog.Builder(
        this,
        if (isNight) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
    )

    private fun balanceText(agorot: Long): CharSequence {
        val s = Money.fmt(agorot)
        val sp = SpannableString(s)
        val shekel = s.indexOf('₪')
        if (shekel >= 0) sp.setSpan(RelativeSizeSpan(0.62f), shekel, shekel + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val dot = s.lastIndexOf('.')
        if (dot >= 0) sp.setSpan(RelativeSizeSpan(0.5f), dot, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    // ---- layout ------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(36))
        }

        // top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), 0, dp(14))
        }
        top.addView(label("Kis", 30f, c.text, bold), weighted())
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(c.text2)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = ripple(null, 22)
            contentDescription = "Settings"
            setOnClickListener { showSettingsDialog() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(top, matchWrap())

        accessBanner = banner(c.negSoft, c.neg) { showAccessHelp() }
        root.addView(accessBanner, matchWrap().apply { bottomMargin = dp(12) })
        updateBanner = banner(c.infoSoft, c.info) { onUpdateBannerTap() }
        root.addView(updateBanner, matchWrap().apply { bottomMargin = dp(12) })
        batteryBanner = banner(c.negSoft, c.neg) { showBatteryHelp() }
        root.addView(batteryBanner, matchWrap().apply { bottomMargin = dp(12) })

        // hero card
        val hero = card().apply { setPadding(dp(22), dp(20), dp(22), dp(22)) }
        hero.addView(label("Available", 14f, c.text2, medium))
        balanceView = label("", 50f, c.text, bold).apply {
            includeFontPadding = false
            setPadding(0, dp(8), 0, dp(18))
        }
        hero.addView(balanceView)
        bar = BarView(this)
        hero.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        usedView = label("", 14f, c.text, medium).apply { setPadding(0, dp(12), 0, 0) }
        hero.addView(usedView)
        nextView = label("", 13f, c.text2).apply { setPadding(0, dp(4), 0, 0) }
        hero.addView(nextView)
        root.addView(hero, matchWrap())

        root.addView(pill("+  Add expense", filled = true) { showExpenseDialog() }, matchWrap().apply { topMargin = dp(14) })

        discoveryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(discoveryBox, matchWrap())

        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(historyBox, matchWrap().apply { topMargin = dp(10) })

        return ScrollView(this).apply {
            setBackgroundColor(c.bg)
            isFillViewport = true
            addView(root)
        }
    }

    // ---- render ------------------------------------------------------------

    private fun render() {
        accessBanner.text = "Notification access is off, so payments aren't tracked. Tap to fix."
        val access = hasListenerAccess()
        accessBanner.visibility = if (access) View.GONE else View.VISIBLE
        batteryBanner.text = "Android may stop Kis in the background, so payments only count while it's open. Tap to fix."
        batteryBanner.visibility = if (access && !isUnrestricted()) View.VISIBLE else View.GONE
        renderUpdate()

        val bal = store.balance
        val spent = store.spentThisPeriod().coerceAtLeast(0L)
        val total = bal + spent
        val ratio = if (total <= 0L) 1f else spent.toFloat() / total

        balanceView.text = balanceText(bal)
        balanceView.setTextColor(if (bal < 0) c.neg else c.text)
        bar.setColors(c.track, when {
            bal < 0 -> c.neg
            ratio >= 0.75f -> c.warn
            else -> c.accent
        })
        bar.setRatio(ratio)
        usedView.text = if (bal < 0) {
            "Over budget by ${Money.fmt(-bal)}"
        } else {
            "${Money.fmt(spent, compact = true)} of ${Money.fmt(total, compact = true)} used today"
        }

        val now = ZonedDateTime.now()
        val next = store.nextCredit(now)
        val day = if (next.toLocalDate() == now.toLocalDate()) "today" else "tomorrow"
        nextView.text = String.format(
            Locale.US, "+%s %s at %02d:00 · %s",
            Money.fmt(store.dailyAgorot, compact = true), day, store.creditHour,
            if (store.rollover) "leftovers stack" else "resets daily",
        )

        renderDiscovery()
        renderHistory()
    }

    private fun renderHistory() {
        historyBox.removeAllViews()
        val txs = store.transactions().take(150)
        if (txs.isEmpty()) {
            historyBox.addView(label("No activity yet.\nPayments show up here as you make them.", 14f, c.text2).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            }, matchWrap())
            return
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        txs.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }.forEach { (date, items) ->
            val name = when (date) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> dayFmt.format(date)
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(6), dp(18), dp(6), dp(8))
            }
            header.addView(label(name, 14f, c.text2, medium), weighted())
            val out = items.filter { it.isSpend }.sumOf { -it.amount }
            if (out > 0) header.addView(label("${Money.fmt(out)} spent", 13f, c.text2))
            historyBox.addView(header, matchWrap())

            val group = card().apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
            items.forEach { group.addView(txRow(it), matchWrap()) }
            historyBox.addView(group, matchWrap())
        }
        historyBox.addView(label("Tap an entry for details or to delete it.", 12f, c.text2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }, matchWrap())
    }

    private fun avatarFor(tx: Tx): Pair<String, Int> = when (tx.source) {
        Tx.SRC_DAILY -> "+" to c.accent
        Tx.SRC_ADJUST -> "±" to c.text2
        else -> {
            val ch = tx.label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "₪"
            ch to Palette.AVATARS[Math.floorMod(tx.label.hashCode(), Palette.AVATARS.size)]
        }
    }

    private fun sourceName(tx: Tx) = when (tx.source) {
        Tx.SRC_PAY -> "Auto"
        Tx.SRC_MANUAL -> "Manual"
        Tx.SRC_DAILY -> "Daily budget"
        else -> "Adjustment"
    }

    private fun txRow(tx: Tx): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(14), dp(10))
            background = ripple(null, 20)
        }
        val (letter, color) = avatarFor(tx)
        row.addView(label(letter, 16f, if (tx.source == Tx.SRC_DAILY) c.onAccent else Color.WHITE, bold).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
        }
        mid.addView(label(tx.label, 15f, c.text, medium).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        mid.addView(label(
            "${timeFmt.format(Instant.ofEpochMilli(tx.time).atZone(ZoneId.systemDefault()))} · ${sourceName(tx)}",
            12f, c.text2,
        ))
        row.addView(mid, weighted())
        row.addView(label(Money.fmt(tx.amount, showPlus = true), 15f, if (tx.amount > 0) c.accent else c.text, bold))
        row.setOnClickListener { showTxDetails(tx) }
        row.setOnLongClickListener { confirmDelete(tx); true }
        return row
    }

    private fun renderDiscovery() {
        discoveryBox.removeAllViews()
        if (!store.discoveryMode) return
        discoveryBox.addView(sectionTitle("Discovery log"))
        val box = card()
        box.addView(label("Notifications containing ₪ amounts. Tap one to start tracking that app.", 13f, c.text2))
        val log = store.discoveryLog()
        if (log.isEmpty()) box.addView(label("Nothing captured yet. Make a payment.", 14f, c.text).apply { setPadding(0, dp(12), 0, 0) })
        log.forEach { e ->
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = ripple(null, 14)
                addView(label(e.pkg, 12f, c.accent, medium))
                addView(label(e.text, 14f, c.text).apply { setPadding(0, dp(2), 0, dp(2)) })
                addView(label("→ ${e.note}", 12f, c.text2))
                setOnClickListener { offerWatch(e.pkg) }
            }, matchWrap().apply { topMargin = dp(6) })
        }
        if (log.isNotEmpty()) {
            box.addView(pill("Clear log", filled = false) { store.clearDiscovery() }, matchWrap().apply { topMargin = dp(10) })
        }
        discoveryBox.addView(box, matchWrap())
    }

    // ---- dialogs -----------------------------------------------------------

    private fun form(vararg views: View): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(12))
        }
        views.forEach { box.addView(it, matchWrap()) }
        return ScrollView(this).apply { addView(box) }
    }

    private fun fieldLabel(text: String) = label(text, 13f, c.text2).apply { setPadding(0, dp(12), 0, 0) }

    private fun decimalField(hint: String, signed: Boolean = false) = EditText(this).apply {
        this.hint = hint
        textSize = 18f
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
            (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
    }

    private fun showExpenseDialog() {
        val amount = decimalField("₪ 0.00").apply { textSize = 26f; typeface = bold }
        val what = EditText(this).apply {
            hint = "What for? (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val d = dialog()
            .setTitle("Add expense")
            .setView(form(amount, what))
            .setPositiveButton("Subtract") { _, _ ->
                val a = Money.parseInput(amount.text.toString())
                if (a == null || a <= 0) {
                    toast("Invalid amount")
                } else {
                    store.addExpense(a, what.text.toString().trim().ifEmpty { "Manual expense" }, Tx.SRC_MANUAL, null)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        amount.requestFocus()
    }

    private fun showSettingsDialog() {
        store.catchUp()
        val shownBalance = store.balance

        val daily = decimalField("70").apply { setText(Money.plain(store.dailyAgorot)) }
        val hour = NumberPicker(this).apply {
            minValue = 0
            maxValue = 23
            displayedValues = Array(24) { String.format(Locale.US, "%02d:00", it) }
            value = store.creditHour
            wrapSelectorWheel = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val roll = Switch(this).apply {
            text = "Stack unused budget to the next day"
            textSize = 15f
            isChecked = store.rollover
            setPadding(0, dp(14), 0, dp(6))
        }
        val balance = decimalField("0", signed = true).apply { setText(Money.plain(shownBalance)) }
        val widgetN = EditText(this).apply {
            setText(store.widgetCount.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 18f
        }
        val pkgs = EditText(this).apply {
            setText(store.watchedPackages().joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
            textSize = 13f
        }
        val discovery = Switch(this).apply {
            text = "Discovery mode"
            textSize = 15f
            isChecked = store.discoveryMode
            setPadding(0, dp(14), 0, dp(4))
        }
        val autoUpd = Switch(this).apply {
            text = "Auto-update from GitHub"
            textSize = 15f
            isChecked = Updater.autoUpdate(this@MainActivity)
            setPadding(0, dp(8), 0, dp(8))
        }
        val checkBtn = pill("Check for updates · build ${Updater.installedVersion(this)}", filled = false) {
            checkForUpdates(force = true)
        }

        dialog()
            .setTitle("Settings")
            .setView(
                form(
                    sectionTitle("Budget"),
                    fieldLabel("Daily amount (₪)"), daily,
                    fieldLabel("Added every day at"), hour,
                    roll,
                    fieldLabel("Current balance (₪), edit to correct it"), balance,
                    sectionTitle("Widget"),
                    fieldLabel("Recent expenses to show (1–30, fewer if the widget is small)"), widgetN,
                    sectionTitle("Tracking"),
                    fieldLabel("Apps to read payments from (package names, one per line)"), pkgs,
                    discovery,
                    label("Logs ₪ notifications from every app without counting them. Use it to check parsing or find the right app.", 12f, c.text2),
                    sectionTitle("Updates"),
                    autoUpd,
                    checkBtn,
                )
            )
            .setPositiveButton("Save") { _, _ ->
                val newDaily = Money.parseInput(daily.text.toString())
                if (newDaily == null || newDaily < 0) toast("Invalid daily amount, kept the old one")
                store.updateSettings(
                    daily = newDaily?.takeIf { it >= 0 } ?: store.dailyAgorot,
                    hour = hour.value,
                    roll = roll.isChecked,
                    packages = pkgs.text.toString(),
                    discovery = discovery.isChecked,
                )
                widgetN.text.toString().trim().toIntOrNull()?.let { store.setWidgetCount(it) }
                Updater.setAutoUpdate(this, autoUpd.isChecked)
                val newBal = Money.parseInput(balance.text.toString())
                if (newBal != null && newBal != shownBalance) store.setBalance(newBal)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTxDetails(tx: Tx) {
        val msg = buildString {
            append(Money.fmt(tx.amount, showPlus = true))
            append("\n")
            append(fullFmt.format(Instant.ofEpochMilli(tx.time).atZone(ZoneId.systemDefault())))
            append(" · ${sourceName(tx)}")
            if (tx.raw != null) append("\n\nOriginal notification:\n${tx.raw}")
        }
        dialog()
            .setTitle(tx.label)
            .setMessage(msg)
            .setNegativeButton("Delete") { _, _ -> confirmDelete(tx) }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmDelete(tx: Tx) {
        dialog()
            .setTitle("Delete entry?")
            .setMessage("${tx.label}  ${Money.fmt(tx.amount, showPlus = true)}\n\nThe balance changes by ${Money.fmt(-tx.amount, showPlus = true)}.")
            .setPositiveButton("Delete") { _, _ -> store.deleteTx(tx.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun offerWatch(pkg: String) {
        if (pkg in store.watchedPackages()) {
            toast("Already tracking $pkg")
            return
        }
        dialog()
            .setTitle("Track this app?")
            .setMessage("Payments from $pkg will be subtracted from the budget.")
            .setPositiveButton("Track") { _, _ -> store.addWatchedPackage(pkg) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAccessHelp() {
        dialog()
            .setTitle("Allow notification access")
            .setMessage(
                "Turn on \"${getString(R.string.app_name)}\" in the next screen.\n\n" +
                    "If it's greyed out or says \"Restricted setting\" (Android 13+ does this for apps " +
                    "installed from an APK): open App info → ⋮ menu (top right) → " +
                    "\"Allow restricted settings\", then try again."
            )
            .setPositiveButton("Notification access") { _, _ -> openListenerSettings() }
            .setNeutralButton("App info") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showCrash(trace: String) {
        val text = label(trace, 11f, c.text).apply {
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        dialog()
            .setTitle("Kis crashed last time")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Kis crash", trace))
                toast("Copied, paste it to Claude")
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    // ---- updates -----------------------------------------------------------

    private fun renderUpdate() {
        val file = Updater.pendingFile(this)
        val st = Updater.status
        val text = when {
            st != null && file != null -> "$st\nTap to retry."
            st != null -> st
            file != null -> "Kis update (build ${Updater.pendingVersion(this)}) is ready. Tap to install."
            else -> null
        }
        updateBanner.text = text ?: ""
        updateBanner.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun onUpdateBannerTap() {
        Updater.status = null
        val file = Updater.pendingFile(this)
        if (file == null) {
            render()
            return
        }
        if (!Updater.canInstall(this)) {
            dialog()
                .setTitle("Allow Kis to install updates")
                .setMessage(
                    "Android needs this once. Turn on \"Allow from this source\", come back, and tap the banner again.\n\n" +
                        "After the first update, new versions install on their own while Kis is closed."
                )
                .setPositiveButton("Open settings") { _, _ -> Updater.openInstallPermission(this) }
                .setNegativeButton("Later", null)
                .show()
            return
        }
        toast("Installing… Kis will close; open it again after.")
        Thread {
            try {
                Updater.install(applicationContext, file, silent = false)
            } catch (e: Exception) {
                Updater.status = "Update failed: ${e.message}"
                runOnUiThread { render() }
            }
        }.start()
    }

    private fun checkForUpdates(force: Boolean) {
        val app = applicationContext
        if (force) toast("Checking for updates…")
        Thread {
            val msg = try {
                val rel = Updater.checkAndDownload(app, force)
                when {
                    rel != null && force -> "Build ${rel.versionCode} downloaded. Close settings and tap the blue banner."
                    force -> "You're on the latest version (build ${Updater.installedVersion(app)})"
                    else -> null
                }
            } catch (e: Exception) {
                if (force) "Update check failed: ${e.message}" else null
            }
            runOnUiThread {
                if (msg != null) toast(msg)
                render()
            }
        }.start()
    }

    // ---- background ----------------------------------------------------------

    private fun isUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    private fun showBatteryHelp() {
        dialog()
            .setTitle("Let Kis run in the background")
            .setMessage(
                "Kis reads payment notifications as they arrive. If Android puts it to sleep, " +
                    "payments are only caught while the app is open.\n\n" +
                    "Tap Allow, then choose Allow / Unrestricted.\n\n" +
                    "Samsung, also: Settings → Battery → Background usage limits → make sure Kis isn't in " +
                    "Sleeping or Deep sleeping apps (add it to Never sleeping apps).\n\n" +
                    "Xiaomi/Redmi, also: App info → Autostart on, Battery saver → No restrictions."
            )
            .setPositiveButton("Allow") { _, _ -> requestUnrestricted() }
            .setNeutralButton("App info") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun requestUnrestricted() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    // ---- system ------------------------------------------------------------

    private fun hasListenerAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, PayListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openListenerSettings() {
        val me = ComponentName(this, PayListener::class.java)
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, me.flattenToString())
        } else null
        try {
            startActivity(detail ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
