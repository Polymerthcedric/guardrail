package dev.fidelco.guardrail

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.NumberPicker
import android.app.NotificationManager
import android.widget.ProgressBar
import android.widget.SearchView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.LocalTime
import java.util.Locale

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("guardrail", MODE_PRIVATE) }
    private val ui by lazy { Ui(this) }

    private lateinit var focusDot: View
    private lateinit var focusState: TextView
    private lateinit var focusMeta: TextView
    private lateinit var gateCell: Cell
    private lateinit var vpnCell: Cell
    private lateinit var ownerCell: Cell
    private lateinit var dnsSwitch: Switch
    private var updatingDnsSwitch = false
    private lateinit var noticeCard: LinearLayout
    private lateinit var strictSwitch: Switch
    private lateinit var strictRow: View
    private lateinit var strictCard: View
    private lateinit var gateSetupTitle: TextView
    private lateinit var gateSetupSub: TextView
    private lateinit var notificationSetupTitle: TextView
    private lateinit var notificationSetupSub: TextView
    private lateinit var perAppMap: Map<String, Long>
    private lateinit var screenTimeBox: LinearLayout
    private lateinit var scheduleBox: LinearLayout

    private class Cell(val dot: View, val value: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Blocked.initializeRestrictedApps(
            prefs,
            AppCatalog.launchableApps(this).mapTo(mutableSetOf()) { it.packageName }
        )
        window.statusBarColor = Ui.C.BG
        window.navigationBarColor = Ui.C.BG

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(48))
            layoutParams = LinearLayout.LayoutParams(ui.contentWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // header
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(iconOfSelf())
                clipToOutline = true
                background = ui.shape(Ui.C.SURFACE2, ui.dp(11), Ui.C.BORDER)
                setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
                layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(12) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.heading("Guardrail", 20f))
                addView(ui.body("Focus windows \u00b7 screen time \u00b7 DNS", 12.5f).apply { setPadding(0, ui.dp(2), 0, 0) })
            })
        })

        // focus status
        val focusCard = ui.card()
        focusCard.addView(ui.eyebrow("Status"))
        focusDot = ui.dot(Ui.C.FAINT, 10)
        focusState = ui.heading("", 22f)
        focusCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(focusDot, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)).apply { marginEnd = ui.dp(10) })
            addView(focusState)
        }, lpTop(ui.dp(12)))
        focusMeta = ui.mono("", 12.5f, Ui.C.DIM)
        focusCard.addView(focusMeta, lpTop(ui.dp(6)))
        column.addView(focusCard, topMargin(ui.dp(24)))

        // capability status
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        gateCell = statusCell(statusRow, "App gate", first = true)
        vpnCell = statusCell(statusRow, "DNS filter", first = false)
        ownerCell = statusCell(statusRow, "Owner lock", first = false)
        column.addView(ui.card().apply {
            setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
            addView(statusRow)
        }, topMargin(ui.dp(12)))

        // dns filter
        dnsSwitch = toggle()
        dnsSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingDnsSwitch) return@setOnCheckedChangeListener
            if (checked) startDns() else stopDns()
        }
        column.addView(ui.card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(ui.heading("DNS filter", 15f))
                    addView(ui.body("Blocks listed domains via DNS. Custom DNS or DoH may bypass; allowed lookups go to your network resolver.", 12.5f).apply { setPadding(0, ui.dp(2), 0, 0) })
                })
                addView(dnsSwitch)
            })
        }, topMargin(ui.dp(12)))

        // lockdown notice
        noticeCard = ui.noticeCard(
            eyebrow = "Lockdown",
            title = "Clock tamper detected",
            bodyText = "The clock moved out of sync, so restricted apps stay locked. " +
                "Home, Settings, Phone, and Guardrail remain available for recovery.",
            action = "Clear lockdown"
        ) { Blocked.clearLockdown(prefs); refresh() }
        column.addView(noticeCard, topMargin(ui.dp(12)))

        // screen time
        column.addView(ui.eyebrow("Screen time"), topMargin(ui.dp(28)))
        screenTimeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(screenTimeBox, topMargin(ui.dp(11)))

        // timetable
        column.addView(ui.eyebrow("Timetable"), topMargin(ui.dp(28)))
        scheduleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(scheduleBox, topMargin(ui.dp(11)))

        // setup
        column.addView(ui.eyebrow("Protection setup"), topMargin(ui.dp(28)))
        val setupCard = ui.card().apply { setPadding(0, 0, 0, 0) }
        setupCard.addView(gateAccessRow())
        setupCard.addView(ui.hairline())
        setupCard.addView(notificationAccessRow())
        setupCard.addView(ui.hairline())
        setupCard.addView(navRow("03", "Optional device-owner lock", "Advanced setup; not required for app blocking") { hardMode() })
        setupCard.addView(ui.hairline())
        setupCard.addView(navRow("04", "Quick presets", "Focus, deep work, family, wind-down") { showPresets() })
        setupCard.addView(ui.hairline())
        setupCard.addView(navRow("05", "Focus preview", "See what a blocked app looks like") {
            startActivity(
                Intent(this, BlockedAppActivity::class.java)
                    .putExtra(BlockedAppActivity.EXTRA_PKG, "com.google.android.youtube")
            )
        })
        column.addView(setupCard, topMargin(ui.dp(11)))
        column.addView(ui.body(
            "Android 8.0+ (API 26). App blocking uses Accessibility access; DNS filtering is optional and uses Android's VPN consent. Background behavior can vary by manufacturer.",
            12.5f
        ), topMargin(ui.dp(9)))

        // strict mode
        strictSwitch = toggle()
        strictSwitch.setOnCheckedChangeListener { _, checked -> Blocked.setStrictBypass(prefs, checked) }
        strictRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(ui.heading("Strict mode", 15f))
                addView(ui.body("No emergency unlocks while this is on", 12.5f).apply { setPadding(0, ui.dp(2), 0, 0) })
            })
            addView(strictSwitch)
        }
        strictCard = ui.card().apply { addView(strictRow) }
        column.addView(strictCard, topMargin(ui.dp(12)))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.C.BG)
            isFillViewport = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(column)
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        FocusScheduler.schedule(this)
    }

    private fun refresh() {
        val now = LocalTime.now()
        val restrictedApps = Blocked.restrictedApps(prefs)
        val activeApps = restrictedApps.filter { Blocked.windowActive(it, prefs, now) }
        val active = activeApps.isNotEmpty()
        if (active) {
            focusState.text = getString(R.string.focus_active)
            focusState.setTextColor(Ui.C.ACCENT)
            focusMeta.text = resources.getQuantityString(
                R.plurals.restricted_apps_active,
                activeApps.size,
                activeApps.size
            )
            focusDot.background = ui.circle(Ui.C.ACCENT)
        } else {
            focusState.text = getString(R.string.focus_inactive)
            focusState.setTextColor(Ui.C.TEXT)
            focusMeta.text = getString(R.string.focus_schedules_below)
            focusDot.background = ui.circle(Ui.C.FAINT)
        }

        val gateEnabled = a11yEnabled()
        setCell(gateCell, gateEnabled)
        gateSetupTitle.text = getString(if (gateEnabled) R.string.gate_enabled else R.string.gate_enable)
        gateSetupSub.text = if (gateEnabled) {
            getString(R.string.gate_enabled_summary)
        } else {
            getString(R.string.gate_disabled_summary)
        }
        val notificationsEnabled = notificationsEnabled()
        notificationSetupTitle.text = getString(
            if (notificationsEnabled) R.string.notifications_enabled else R.string.notifications_enable
        )
        notificationSetupSub.text = if (notificationsEnabled) {
            getString(R.string.notifications_enabled_summary)
        } else {
            getString(R.string.notifications_disabled_summary)
        }
        setCell(vpnCell, vpnRunning())
        setCell(ownerCell, ownerEnabled())

        updatingDnsSwitch = true
        dnsSwitch.isChecked = vpnRunning()
        updatingDnsSwitch = false

        Blocked.checkClockTamper(prefs)
        noticeCard.visibility = if (Blocked.isLockdown(prefs)) View.VISIBLE else View.GONE

        strictCard.visibility = if (ownerEnabled()) View.VISIBLE else View.GONE
        strictSwitch.isEnabled = ownerEnabled()
        strictSwitch.isChecked = Blocked.strictBypass(prefs)

        perAppMap = Usage.todayPerApp(this)
        rebuildScreenTime()
        rebuildSchedule()
    }

    private fun setCell(cell: Cell, on: Boolean) {
        cell.dot.background = ui.circle(if (on) Ui.C.ACCENT else Ui.C.FAINT)
        cell.value.text = if (on) "on" else "off"
        cell.value.setTextColor(if (on) Ui.C.ACCENT else Ui.C.DIM)
    }

    private fun rebuildScreenTime() {
        screenTimeBox.removeAllViews()

        if (!Usage.hasAccess(this)) {
            screenTimeBox.addView(ui.body("Let the phone read its own usage. Nothing leaves the device.", 13f))
            screenTimeBox.addView(navRow(null, "Enable usage access", "Opens Settings \u2192 Usage access") {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); refresh()
            }, topMargin(ui.dp(10)))
            return
        }

        val perApp = perAppMap.toList().sortedByDescending { it.second }
        val total = perApp.sumOf { it.second }
        val restrictedApps = Blocked.restrictedApps(prefs)
        val blockedUse = perAppMap.filterKeys { it in restrictedApps }.values.sum()
        Log.i(TAG, "screenTime today=${total}ms apps=${perApp.size} access=true")

        val summary = "today \u00b7 ${perApp.size} apps" +
            if (blockedUse > 0) " \u00b7 ${Usage.mmss(blockedUse)} in restricted apps" else ""
        screenTimeBox.addView(ui.heading(Usage.mmss(total), 26f))
        screenTimeBox.addView(ui.body(summary, 12.5f).apply { setPadding(0, ui.dp(3), 0, 0) })

        val card = ui.card().apply { setPadding(0, 0, 0, 0) }
        perApp.take(7).forEachIndexed { i, (pkg, ms) ->
            if (i > 0) card.addView(ui.hairline())
            card.addView(metricRow(appIcon(pkg), appLabel(pkg), Usage.mmss(ms)))
        }
        screenTimeBox.addView(card, topMargin(ui.dp(14)))
    }

    private fun rebuildSchedule() {
        scheduleBox.removeAllViews()

        val card = ui.card().apply { setPadding(0, 0, 0, 0) }
        val usageAccess = Usage.hasAccess(this)
        val restrictedApps = Blocked.restrictedApps(prefs).sortedBy { appLabel(it).lowercase() }
        if (restrictedApps.isEmpty()) {
            card.addView(ui.body("No apps selected yet. Choose installed apps to start a focus schedule.", 13f))
        }
        restrictedApps.forEachIndexed { i, pkg ->
            val installed = isInstalled(pkg)
            val limit = Blocked.limitMinutes(pkg, prefs)
            val used = if (usageAccess) Usage.todayFor(this, pkg) else 0L
            val progress = if (limit > 0 && usageAccess) (used.toFloat() / (limit * 60_000f)).coerceIn(0f, 1f) else null
            val sub = if (!installed) "Not installed" else
                Blocked.describeWindow(pkg, prefs) + if (limit > 0) {
                    if (usageAccess) " \u00b7 ${limit}m/day \u00b7 ${Usage.mmss(used)} used"
                    else " \u00b7 ${limit}m/day \u00b7 Usage access needed"
                } else ""
            if (i > 0) card.addView(ui.hairline())
            card.addView(appRow(appIcon(pkg), appLabel(pkg), sub, installed, progress) {
                if (installed) editAppDialog(pkg)
            })
        }
        scheduleBox.addView(card)
        scheduleBox.addView(ui.body("Tap an installed app to set its window and daily limit.", 12.5f).apply {
            setPadding(0, ui.dp(10), 0, 0)
        })
        scheduleBox.addView(navRow(null, "Choose apps to restrict", "${restrictedApps.size} selected · add or remove installed apps") {
            showAppPicker()
        }, topMargin(ui.dp(10)))
    }

    private fun statusCell(parent: LinearLayout, label: String, first: Boolean): Cell {
        if (!first) parent.addView(vline())
        val dot = ui.dot(Ui.C.FAINT)
        val value = ui.mono("\u2014", 13f, Ui.C.DIM)
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(ui.eyebrow(label))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(8), 0, 0)
                addView(dot, LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply { marginEnd = ui.dp(8) })
                addView(value)
            })
        })
        return Cell(dot, value)
    }

    private fun metricRow(icon: Drawable, name: String, value: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(11), ui.dp(16), ui.dp(11))
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(icon)
                contentDescription = name
                layoutParams = LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)).apply { marginEnd = ui.dp(14) }
            })
            addView(ui.heading(name, 14.5f).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(ui.mono(value, 13f, Ui.C.DIM))
        }

    private fun navRow(index: String?, title: String, sub: String, onClick: () -> Unit): LinearLayout =
        ui.row().apply {
            if (index != null) addView(ui.mono(index, 12f, Ui.C.FAINT).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(ui.heading(title, 15f))
                addView(ui.body(sub, 12.5f).apply { setPadding(0, ui.dp(2), 0, 0) })
            })
            addView(ui.chevron())
            setOnClickListener { onClick() }
        }

    private fun gateAccessRow(): LinearLayout {
        gateSetupTitle = ui.heading(getString(R.string.gate_enable), 15f)
        gateSetupSub = ui.body(getString(R.string.gate_disabled_summary), 12.5f)
            .apply { setPadding(0, ui.dp(2), 0, 0) }

        return ui.row().apply {
            addView(ui.mono("01", 12f, Ui.C.FAINT).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(gateSetupTitle)
                addView(gateSetupSub)
            })
            addView(ui.chevron())
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.accessibility_disclosure_title)
                    .setMessage(
                        R.string.accessibility_disclosure_message
                    )
                    .setNegativeButton(R.string.not_now, null)
                    .setPositiveButton(R.string.continue_to_settings) { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .show()
            }
        }
    }

    private fun notificationAccessRow(): LinearLayout {
        notificationSetupTitle = ui.heading(getString(R.string.notifications_enable), 15f)
        notificationSetupSub = ui.body(getString(R.string.notifications_disabled_summary), 12.5f)
            .apply { setPadding(0, ui.dp(2), 0, 0) }

        return ui.row().apply {
            addView(ui.mono("02", 12f, Ui.C.FAINT).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(notificationSetupTitle)
                addView(notificationSetupSub)
            })
            addView(ui.chevron())
            setOnClickListener { manageNotifications() }
        }
    }

    private fun appRow(icon: Drawable, name: String, sub: String, enabled: Boolean, progress: Float?, onClick: () -> Unit): LinearLayout =
        ui.row().apply {
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(icon)
                alpha = if (enabled) 1f else 0.35f
                contentDescription = name
                layoutParams = LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply { marginEnd = ui.dp(14) }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(ui.heading(name, 15f).apply { setTextColor(if (enabled) Ui.C.TEXT else Ui.C.DIM) })
                addView(ui.body(sub, 12.5f).apply { setPadding(0, ui.dp(2), 0, 0) })
                if (progress != null) addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    this.progress = (progress * max).toInt()
                    isIndeterminate = false
                    contentDescription = "${(progress * 100).toInt()} percent of daily limit used"
                    progressTintList = ColorStateList.valueOf(Ui.C.ACCENT)
                    progressBackgroundTintList = ColorStateList.valueOf(Ui.C.SURFACE2)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4)).apply { topMargin = ui.dp(8) }
                })
            })
            if (enabled) addView(ui.chevron())
            isEnabled = enabled
            if (enabled) setOnClickListener { onClick() } else background = null
        }

    // ---- dialogs ----

    private fun showPresets() {
        val presets = listOf(
            "Focus" to listOf(Blocked.Window(Blocked.START_HOUR * 60, Blocked.END_HOUR * 60)),
            "Deep work" to listOf(Blocked.Window(8 * 60, 18 * 60)),
            "Family time" to listOf(Blocked.Window(18 * 60, 21 * 60)),
            "Wind-down" to listOf(Blocked.Window(22 * 60, 7 * 60)),
            "Unblock everything" to emptyList()
        )
        AlertDialog.Builder(this)
            .setTitle("Presets")
            .setItems(presets.map { it.first }.toTypedArray()) { _, which ->
                val windows = presets[which].second
                Blocked.restrictedApps(prefs).forEach { pkg ->
                    Blocked.setCustomWindows(pkg, prefs, windows)
                    if (windows.isEmpty()) Blocked.setLimitMinutes(pkg, prefs, 0)
                }

                FocusScheduler.schedule(this)
                Toast.makeText(this, "${presets[which].first} applied", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppPicker() {
        val apps = AppCatalog.launchableApps(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps were found", Toast.LENGTH_LONG).show()
            return
        }

        val selectablePackages = apps.mapTo(mutableSetOf()) { it.packageName }
        val selected = Blocked.restrictedApps(prefs)
            .filter { it in selectablePackages }
            .toMutableSet()
        val visibleApps = apps.toMutableList()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            visibleApps.map { it.label }
        )
        val appList = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ui.dp(360)
            )
        }
        val selectionCount = ui.body(
            resources.getQuantityString(R.plurals.selected_apps_count, selected.size, selected.size),
            12.5f
        )
        fun updateResults(query: String) {
            visibleApps.clear()
            visibleApps.addAll(apps.filter { it.label.contains(query, ignoreCase = true) })
            adapter.clear()
            adapter.addAll(visibleApps.map { it.label })
            appList.clearChoices()
            visibleApps.forEachIndexed { index, app ->
                appList.setItemChecked(index, app.packageName in selected)
            }
        }
        appList.setOnItemClickListener { _, _, position, _ ->
            val packageName = visibleApps[position].packageName
            if (appList.isItemChecked(position)) selected.add(packageName) else selected.remove(packageName)
            selectionCount.text = resources.getQuantityString(
                R.plurals.selected_apps_count,
                selected.size,
                selected.size
            )
        }

        val search = SearchView(this).apply {
            isIconifiedByDefault = false
            queryHint = "Search installed apps"
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    updateResults(newText.orEmpty())
                    return true
                }
            })
        }
        val pickerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), 0, ui.dp(20), 0)
            addView(search, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(ui.body(
                "Home, Settings, the default Phone app, and Guardrail remain available during lockdown for recovery.",
                12.5f
            ).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) })
            addView(selectionCount)
            addView(appList)
        }

        AlertDialog.Builder(this)
            .setTitle("Choose restricted apps")
            .setView(pickerContent)
            .setPositiveButton("Save") { _, _ ->
                Blocked.setRestrictedApps(prefs, selected)
                FocusScheduler.schedule(this)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editAppDialog(pkg: String) {
        data class EditableWindow(var start: Int, var end: Int)

        val windows = Blocked.windowsFor(pkg, prefs)
            .map { EditableWindow(it.start, it.end) }
            .toMutableList()
            .ifEmpty {
                mutableListOf(EditableWindow(Blocked.START_HOUR * 60, Blocked.END_HOUR * 60))
            }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0)
        }

        val windowContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderWindows() {
            windowContainer.removeAllViews()
            if (windows.isEmpty()) {
                windowContainer.addView(ui.body("No time windows · daily limit still applies", 12.5f))
            }
            windows.forEachIndexed { index, window ->
                val panel = ui.card().apply {
                    setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                }
                panel.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ui.heading("Window ${index + 1}", 14f), LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ))
                    addView(ui.ghost("Remove") {
                        windows.removeAt(index)
                        renderWindows()
                    }.apply { textSize = 12f }, LinearLayout.LayoutParams(
                        ui.dp(88),
                        ui.dp(42)
                    ))
                })

                val startButton = ui.ghost("Start ${hhmm(window.start)}") {
                    TimePickerDialog(this, { _, hour, minute ->
                        window.start = hour * 60 + minute
                        renderWindows()
                    }, window.start / 60, window.start % 60, true).show()
                }
                val endButton = ui.ghost("End ${hhmm(window.end)}") {
                    TimePickerDialog(this, { _, hour, minute ->
                        window.end = hour * 60 + minute
                        renderWindows()
                    }, window.end / 60, window.end % 60, true).show()
                }
                panel.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(startButton, LinearLayout.LayoutParams(
                        0,
                        ui.dp(44),
                        1f
                    ).apply { marginEnd = ui.dp(6) })
                    addView(endButton, LinearLayout.LayoutParams(
                        0,
                        ui.dp(44),
                        1f
                    ).apply { marginStart = ui.dp(6) })
                }, topMargin(ui.dp(8)))
                windowContainer.addView(panel, topMargin(ui.dp(8)))
            }
        }
        renderWindows()
        box.addView(windowContainer)
        box.addView(ui.ghost("Add time window") {
            val start = windows.lastOrNull()?.end ?: Blocked.START_HOUR * 60
            windows.add(EditableWindow(start, (start + 60) % (24 * 60)))
            renderWindows()
        }, topMargin(ui.dp(8)))

        box.addView(ui.body("Daily limit \u00b7 minutes, 0 for none", 12.5f).apply { setPadding(0, ui.dp(14), 0, ui.dp(6)) })
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 1440
            value = Blocked.limitMinutes(pkg, prefs)
        }
        box.addView(picker)

        AlertDialog.Builder(this)
            .setTitle(appLabel(pkg))
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Blocked.setCustomWindows(
                    pkg,
                    prefs,
                    windows.map { Blocked.Window(it.start, it.end) }
                )
                Blocked.setLimitMinutes(pkg, prefs, picker.value)
                FocusScheduler.schedule(this)
                refresh()
            }
            .setNeutralButton("Disable") { _, _ ->
                Blocked.setCustomWindows(pkg, prefs, emptyList())
                Blocked.setLimitMinutes(pkg, prefs, 0)
                FocusScheduler.schedule(this)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hhmm(m: Int): String {
        val t = LocalTime.MIN.plusMinutes(m.coerceAtLeast(0).toLong())
        return String.format(Locale.getDefault(), "%02d:%02d", t.hour, t.minute)
    }

    // ---- platform ----

    private fun hardMode() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        if (dpm.isDeviceOwnerApp(packageName)) {
            val cn = android.content.ComponentName(this, GuardrailAdminReceiver::class.java)
            dpm.setUninstallBlocked(cn, packageName, true)
            dpm.setGlobalSetting(cn, Settings.Global.AUTO_TIME, "0")
            Toast.makeText(this, "Device owner locked", Toast.LENGTH_SHORT).show()
        } else {
            val command = "adb shell dpm set-device-owner $packageName/.GuardrailAdminReceiver"
            AlertDialog.Builder(this)
                .setTitle("Optional device-owner lock")
                .setMessage(
                    "This advanced feature is not needed for app blocking. Android only allows device-owner " +
                        "provisioning on a newly set up or factory-reset device, and setup can erase its data. " +
                        "Do not reset a phone just to enable this. If you are setting up a managed test device, " +
                        "copy the ADB command:\n\n$command"
                )
                .setNegativeButton("Not now", null)
                .setPositiveButton("Copy ADB command") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Guardrail device-owner command", command))
                    Toast.makeText(this, "Command copied", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
        refresh()
    }

    private fun manageNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(PREFS_ASKED_NOTIF, false)
        ) {
            prefs.edit().putBoolean(PREFS_ASKED_NOTIF, true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        } else {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            }
            startActivity(intent)
        }
    }

    private fun notificationsEnabled(): Boolean =
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) startVpn()
    }

    private fun startVpn() {
        try {
            startForegroundService(Intent(this, DnsVpnService::class.java))
            Log.i(TAG, "vpn requested")
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
        postRefresh()
    }

    private fun startDns() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, REQ_VPN) else startVpn()
    }

    private fun stopDns() {
        startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
        postRefresh()
    }

    private fun postRefresh() {
        window.decorView.postDelayed({ refresh() }, 600)
    }

    private fun iconOfSelf(): Drawable = try {
        packageManager.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        ui.shape(Ui.C.ACCENT, ui.dp(11))
    }

    private fun a11yEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == GuardrailAccessibilityService::class.java.name
        }
    }

    private fun vpnRunning(): Boolean {
        return DnsVpnService.isRunning
    }

    private fun ownerEnabled(): Boolean =
        (getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager)
            .isDeviceOwnerApp(packageName)

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun appIcon(pkg: String): Drawable = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        packageManager.defaultActivityIcon
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            .takeUnless { it == pkg }
            ?: packageNameFallback(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        packageNameFallback(pkg)
    }

    private fun packageNameFallback(pkg: String): String = when (pkg) {
        "com.android.launcher3" -> "Home screen"
        "com.android.systemui" -> "System UI"
        "com.google.android.captiveportallogin" -> "Network sign-in"
        else -> pkg.substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    // ---- layout helpers ----

    private fun lpTop(margin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = margin }

    private fun topMargin(margin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = margin }

    private fun vline(): View =
        View(this).apply {
            setBackgroundColor(Ui.C.BORDER)
            layoutParams = LinearLayout.LayoutParams(ui.dp(1), ui.dp(34)).apply { gravity = Gravity.CENTER_VERTICAL }
        }

    private fun toggle(): Switch =
        Switch(this).apply {
            thumbTintList = tint(Ui.C.ACCENT, Ui.C.FAINT)
            trackTintList = tint(Ui.C.a(Ui.C.ACCENT, 0.45f), Ui.C.BORDER)
        }

    private fun tint(on: Int, off: Int): ColorStateList =
        ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(on, off))

    companion object {
        private const val TAG = "Guardrail"
        private const val REQ_VPN = 100
        private const val REQ_NOTIF = 200
        private const val PREFS_ASKED_NOTIF = "asked_notif"
    }
}
