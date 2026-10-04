package com.gigimooshi.kis

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: BudgetStore
    private lateinit var banner: TextView
    private lateinit var updateBanner: TextView
    private lateinit var balanceView: TextView
    private lateinit var subView: TextView
    private lateinit var discoveryBox: LinearLayout
    private lateinit var historyBox: LinearLayout

    private val onChange: () -> Unit = { render() }
    private val timeFmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())

    private val green = 0xFF2E7D32.toInt()
    private val red = 0xFFC62828.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BudgetStore.get(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        Updater.visible = true
        store.addListener(onChange)
        store.catchUp()
        render()
        Updater.schedule(this)
        if (Updater.autoUpdate(this)) checkForUpdates(force = false)
    }

    override fun onPause() {
        Updater.visible = false
        store.removeListener(onChange)
        Updater.soon(this)
        super.onPause()
    }

    // ---- layout ------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun muted(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = 0.65f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        banner = TextView(this).apply {
            text = "⚠ Notification access is off, so payments aren't being tracked. Tap to fix."
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(red)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { showAccessHelp() }
        }
        root.addView(banner, matchWrap().apply { bottomMargin = dp(16) })

        updateBanner = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1565C0.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { onUpdateBannerTap() }
        }
        root.addView(updateBanner, matchWrap().apply { bottomMargin = dp(16) })

        root.addView(muted("Available"))
        balanceView = TextView(this).apply {
            textSize = 52f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(balanceView)
        subView = muted("")
        root.addView(subView, matchWrap().apply { bottomMargin = dp(16) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "− Expense"
            setOnClickListener { showExpenseDialog() }
        }, weighted())
        buttons.addView(Button(this).apply {
            text = "Settings"
            setOnClickListener { showSettingsDialog() }
        }, weighted().apply { marginStart = dp(8) })
        root.addView(buttons, matchWrap())

        discoveryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(discoveryBox, matchWrap())

        root.addView(header("History"))
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(historyBox, matchWrap())

        return ScrollView(this).apply { addView(root) }
    }

    // ---- render ------------------------------------------------------------

    private fun render() {
        banner.visibility = if (hasListenerAccess()) View.GONE else View.VISIBLE
        renderUpdate()

        val bal = store.balance
        balanceView.text = Money.fmt(bal)
        balanceView.setTextColor(if (bal >= 0) green else red)

        val now = ZonedDateTime.now()
        val next = store.nextCredit(now)
        val day = if (next.toLocalDate() == now.toLocalDate()) "today" else "tomorrow"
        val mode = if (store.rollover) "unused budget stacks" else "resets daily"
        subView.text = String.format(
            Locale.US, "Next +%s %s at %02d:00 · %s",
            Money.fmt(store.dailyAgorot), day, store.creditHour, mode,
        )

        renderDiscovery()

        historyBox.removeAllViews()
        val txs = store.transactions()
        if (txs.isEmpty()) historyBox.addView(muted("Nothing yet."))
        txs.forEach { historyBox.addView(txRow(it)) }
        if (txs.isNotEmpty()) historyBox.addView(muted("Long-press an entry to delete it."))
    }

    private fun txRow(tx: Tx): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = tx.label
            textSize = 16f
            maxLines = 2
        })
        val src = when (tx.source) {
            Tx.SRC_PAY -> " · auto"
            Tx.SRC_MANUAL -> " · manual"
            else -> ""
        }
        left.addView(TextView(this).apply {
            text = timeFmt.format(Instant.ofEpochMilli(tx.time).atZone(ZoneId.systemDefault())) + src
            textSize = 12f
            alpha = 0.6f
        })
        row.addView(left, weighted())
        row.addView(TextView(this).apply {
            text = Money.fmt(tx.amount, showPlus = true)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (tx.amount >= 0) green else red)
            setPadding(dp(12), 0, 0, 0)
        })
        row.setOnLongClickListener { confirmDelete(tx); true }
        return row
    }

    private fun renderDiscovery() {
        discoveryBox.removeAllViews()
        if (!store.discoveryMode) return
        discoveryBox.addView(header("Discovery log"))
        discoveryBox.addView(muted("Notifications containing ₪ amounts. Tap an entry to start tracking that app."))
        val log = store.discoveryLog()
        if (log.isEmpty()) discoveryBox.addView(muted("Nothing captured yet — make a payment."))
        log.forEach { e ->
            discoveryBox.addView(TextView(this).apply {
                text = "${e.pkg}\n${e.text}\n→ ${e.note}"
                textSize = 13f
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { offerWatch(e.pkg) }
            })
        }
        if (log.isNotEmpty()) {
            discoveryBox.addView(Button(this).apply {
                text = "Clear log"
                setOnClickListener { store.clearDiscovery() }
            }, matchWrap())
        }
    }

    // ---- dialogs -----------------------------------------------------------

    private fun form(vararg views: View): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        views.forEach { box.addView(it, matchWrap()) }
        return ScrollView(this).apply { addView(box) }
    }

    private fun decimalField(hint: String, signed: Boolean = false) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
            (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
    }

    private fun showExpenseDialog() {
        val amount = decimalField("Amount (₪)")
        val label = EditText(this).apply {
            hint = "What for (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this)
            .setTitle("Add expense")
            .setView(form(amount, label))
            .setPositiveButton("Subtract") { _, _ ->
                val a = Money.parseInput(amount.text.toString())
                if (a == null || a <= 0) {
                    toast("Invalid amount")
                } else {
                    store.addExpense(a, label.text.toString().trim().ifEmpty { "Manual expense" }, Tx.SRC_MANUAL, null)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            isChecked = store.rollover
            setPadding(0, dp(12), 0, dp(12))
        }
        val balance = decimalField("0", signed = true).apply { setText(Money.plain(shownBalance)) }
        val pkgs = EditText(this).apply {
            setText(store.watchedPackages().joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
            textSize = 13f
        }
        val autoUpd = Switch(this).apply {
            text = "Auto-update from GitHub"
            isChecked = Updater.autoUpdate(this@MainActivity)
            setPadding(0, dp(16), 0, dp(4))
        }
        val checkBtn = Button(this).apply {
            text = "Check for updates (installed: build ${Updater.installedVersion(this@MainActivity)})"
            setOnClickListener { checkForUpdates(force = true) }
        }
        val discovery = Switch(this).apply {
            text = "Discovery mode"
            isChecked = store.discoveryMode
            setPadding(0, dp(12), 0, dp(4))
        }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(
                form(
                    muted("Daily amount (₪)"), daily,
                    muted("Added every day at"), hour,
                    roll,
                    muted("Current balance (₪) — edit to correct it"), balance,
                    muted("Apps to read payments from (package names, one per line)"), pkgs,
                    discovery,
                    muted("Logs ₪ notifications from every app without counting them. Use it to check parsing or find the right app."),
                    autoUpd,
                    checkBtn,
                )
            )
            .setPositiveButton("Save") { _, _ ->
                val newDaily = Money.parseInput(daily.text.toString())
                if (newDaily == null || newDaily < 0) {
                    toast("Invalid daily amount — kept the old one")
                }
                store.updateSettings(
                    daily = newDaily?.takeIf { it >= 0 } ?: store.dailyAgorot,
                    hour = hour.value,
                    roll = roll.isChecked,
                    packages = pkgs.text.toString(),
                    discovery = discovery.isChecked,
                )
                Updater.setAutoUpdate(this, autoUpd.isChecked)
                val newBal = Money.parseInput(balance.text.toString())
                if (newBal != null && newBal != shownBalance) store.setBalance(newBal)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(tx: Tx) {
        val msg = buildString {
            append("${tx.label}\n${Money.fmt(tx.amount, showPlus = true)}\n\n")
            append("Deleting changes the balance by ${Money.fmt(-tx.amount, showPlus = true)}.")
            if (tx.raw != null) append("\n\nOriginal notification:\n${tx.raw}")
        }
        AlertDialog.Builder(this)
            .setTitle("Delete entry?")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ -> store.deleteTx(tx.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun offerWatch(pkg: String) {
        if (pkg in store.watchedPackages()) {
            toast("Already tracking $pkg")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Track this app?")
            .setMessage("Payments from $pkg will be subtracted from the budget.")
            .setPositiveButton("Track") { _, _ -> store.addWatchedPackage(pkg) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAccessHelp() {
        AlertDialog.Builder(this)
            .setTitle("Allow notification access")
            .setMessage(
                "Turn on \"${getString(R.string.app_name)}\" in the next screen.\n\n" +
                    "If it's greyed out / says \"Restricted setting\" (Android 13+ does this for apps " +
                    "installed from an APK): open App info → ⋮ menu (top right) → " +
                    "\"Allow restricted settings\", then try again."
            )
            .setPositiveButton("Notification access") { _, _ -> openListenerSettings() }
            .setNeutralButton("App info") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // ---- updates -----------------------------------------------------------

    private fun renderUpdate() {
        val file = Updater.pendingFile(this)
        val st = Updater.status
        val text = when {
            st != null && file != null -> "$st\nTap to retry."
            st != null -> st
            file != null -> "⬆ Kis update (build ${Updater.pendingVersion(this)}) is ready. Tap to install."
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
            AlertDialog.Builder(this)
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
        Thread {
            val msg = try {
                val rel = Updater.checkAndDownload(app, force)
                if (rel == null && force) "You're on the latest version (build ${Updater.installedVersion(app)})" else null
            } catch (e: Exception) {
                if (force) "Update check failed: ${e.message}" else null
            }
            runOnUiThread {
                if (msg != null) toast(msg)
                render()
            }
        }.start()
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
