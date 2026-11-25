package ir.anten.yab

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.telephony.*
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private val requestCode = 41
    private val displayInfoBySub = mutableMapOf<Int, TelephonyDisplayInfo>()
    private val displayListeners = mutableMapOf<Int, PhoneStateListener>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 32)
            setBackgroundColor(0xFF101827.toInt())
        }
        scroll.addView(content)
        setContentView(scroll)
        addText("📡 آنتن‌یاب هوشمند", 25, true)
        addText("تحلیل شبکه و سلول مخابراتی دو سیم‌کارت", 15, false)
        content.addView(Button(this).apply {
            text = "به‌روزرسانی اطلاعات"
            setOnClickListener { refreshData() }
        }, match())
        addText("برای مشاهده جزئیات سلول، مجوز تلفن و مکان را بدهید و Location را روشن کنید.", 13, false)
        if (hasPermissions()) refreshData() else askPermissions()
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun addText(value: String, size: Int, bold: Boolean) {
        val v = TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(0xFFF2F5FA.toInt())
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 12, 0, 12)
        }
        content.addView(v, match())
    }

    private fun hasPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        requestPermissions(arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ), requestCode)
    }

    private fun registerDisplayInfoListener(tm: TelephonyManager, subId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || displayListeners.containsKey(subId)) return
        val listener = object : PhoneStateListener() {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                runOnUiThread {
                    displayInfoBySub[subId] = info
                    refreshData()
                }
            }
        }
        displayListeners[subId] = listener
        try {
            tm.listen(listener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
        } catch (_: Exception) {
            displayListeners.remove(subId)
        }
    }

    private fun refreshData() {
        if (!hasPermissions()) { askPermissions(); return }
        while (content.childCount > 4) content.removeViewAt(4)
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isLocationEnabled) addText("⚠️ لطفاً Location گوشی را روشن کنید.", 14, true)

        val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val subs = try { sm.activeSubscriptionInfoList ?: emptyList() }
                   catch (_: SecurityException) { emptyList() }
        if (subs.isEmpty()) {
            addText("سیم‌کارت فعالی شناسایی نشد یا مجوز کافی نیست.", 15, true)
            return
        }

        for (sub in subs.sortedBy { it.simSlotIndex }) {
            val tm = (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .createForSubscriptionId(sub.subscriptionId)
            registerDisplayInfoListener(tm, sub.subscriptionId)

            val carrier = sub.carrierName?.toString() ?: "اپراتور نامشخص"
            val report = StringBuilder()
            report.append("سیم‌کارت " + (sub.simSlotIndex + 1) + " — " + carrier + "\n")
            val displayInfo = displayInfoBySub[sub.subscriptionId]
            report.append("نسل شبکه فعلی: " + networkName(tm.dataNetworkType, displayInfo) + "\n")
            if (displayInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                report.append("نوع اتصال نمایشی: " + overrideNetworkName(displayInfo.overrideNetworkType) + "\n")
            }
            report.append("شبکه پایه دیتا: " + baseNetworkName(tm.dataNetworkType) + "\n")

            try {
                val cells = tm.allCellInfo ?: emptyList()
                val registered = cells.filter { it.isRegistered }
                val relevantCells = (registered + cells.filter { !it.isRegistered })
                    .distinctBy { cellKey(it) }
                val nrCells = relevantCells.filterIsInstance<CellInfoNr>()
                val lteCells = relevantCells.filterIsInstance<CellInfoLte>()

                if (nrCells.isEmpty() && lteCells.isEmpty()) {
                    report.append("جزئیات سلول 4G/5G توسط دستگاه ارائه نشد.\n")
                } else {
                    if (lteCells.isNotEmpty()) {
                        report.append("\n--- سلول‌های 4G LTE (لنگر/همسایه) ---\n")
                        lteCells.forEachIndexed { index, cell ->
                            val c = cell.cellIdentity
                            report.append("LTE " + (index + 1) + (if (cell.isRegistered) " [ثبت‌شده]" else " [همسایه]") + "\n")
                            report.append("قدرت سیگنال: " + cell.cellSignalStrength.dbm + " dBm\n")
                            report.append("Cell ID: " + safe(c.ci) + " | TAC: " + safe(c.tac) + "\n")
                            report.append("PCI: " + safe(c.pci) + " | EARFCN: " + safe(c.earfcn) + "\n")
                            if (Build.VERSION.SDK_INT >= 30) report.append("باند LTE: " + c.bands.joinToString() + "\n")
                        }
                    }
                    if (nrCells.isNotEmpty()) {
                        report.append("\n--- سلول‌های 5G NR ---\n")
                        nrCells.forEachIndexed { index, cell ->
                            val c = cell.cellIdentity as CellIdentityNr
                            report.append("NR " + (index + 1) + (if (cell.isRegistered) " [ثبت‌شده]" else " [همسایه]") + "\n")
                            report.append("قدرت سیگنال: " + cell.cellSignalStrength.dbm + " dBm\n")
                            report.append("NCI: " + safe(c.nci) + " | TAC: " + safe(c.tac) + "\n")
                            report.append("NR-PCI: " + safe(c.pci) + " | NR-ARFCN: " + safe(c.nrarfcn) + "\n")
                            report.append("باند NR: " + c.bands.joinToString() + "\n")
                        }
                    } else if (displayInfo?.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                        (Build.VERSION.SDK_INT >= 31 && displayInfo?.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)) {
                        report.append("\nوضعیت 5G: گوشی 5G NSA را اعلام می‌کند، اما اندروید جزئیات سلول NR را در این لحظه ارائه نکرده است.\n")
                    }
                }
            } catch (_: SecurityException) {
                report.append("اندروید دسترسی به جزئیات سلول را محدود کرده است.\n")
            } catch (e: Exception) {
                report.append("خطا در دریافت اطلاعات: " + (e.localizedMessage ?: "نامشخص") + "\n")
            }

            val card = TextView(this).apply {
                text = report.toString()
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF1C2A40.toInt())
                setPadding(22, 22, 22, 22)
            }
            val params = match()
            params.topMargin = 18
            content.addView(card, params)

            // Monitoring selection is local to the app; it does not force a radio handover.
            val watchableCells = (cells.filterIsInstance<CellInfoLte>() + cells.filterIsInstance<CellInfoNr>())
                .sortedByDescending { it.cellSignalStrength.dbm }
            val preferenceKey = "watch_${sub.subscriptionId}"
            val savedKey = getSharedPreferences("anten_yab", Context.MODE_PRIVATE)
                .getString(preferenceKey, null)
            addText("انتخاب سلول برای پایش (قوی‌ترین سیگنال در ابتدا)", 16, true)
            if (watchableCells.isEmpty()) {
                addText("سلول قابل انتخابی از طرف اندروید گزارش نشد.", 13, false)
            } else {
                watchableCells.forEach { cell ->
                    val key = cellKey(cell)
                    val selected = key == savedKey
                    val label = (if (selected) "✓ در حال پایش: " else "پایش این سلول: ") + cellTitle(cell)
                    content.addView(Button(this).apply {
                        text = label
                        isAllCaps = false
                        setOnClickListener {
                            getSharedPreferences("anten_yab", Context.MODE_PRIVATE).edit()
                                .putString(preferenceKey, key).apply()
                            refreshData()
                        }
                    }, match())
                }
                if (savedKey != null && watchableCells.none { cellKey(it) == savedKey }) {
                    addText("سلول انتخاب‌شده فعلاً در فهرست سلول‌های گزارش‌شده نیست.", 13, false)
                }
            }
        }
        addText("توجه: در 5G NSA ممکن است LTE به‌عنوان شبکه پایه و 5G به‌عنوان اتصال افزوده نمایش داده شود. نمایش NR-PCI و باند 5G به پشتیبانی مودم و نسخه اندروید/اپراتور بستگی دارد.", 13, false)
    }

    private fun cellTitle(cell: CellInfo): String = when (cell) {
        is CellInfoNr -> {
            val c = cell.cellIdentity as CellIdentityNr
            "5G NR | PCI ${safe(c.pci)} | NCI ${safe(c.nci)} | ${cell.cellSignalStrength.dbm} dBm"
        }
        is CellInfoLte -> {
            val c = cell.cellIdentity
            "4G LTE | PCI ${safe(c.pci)} | Cell ID ${safe(c.ci)} | ${cell.cellSignalStrength.dbm} dBm"
        }
        else -> "سلول ناشناخته"
    }

    private fun cellKey(cell: CellInfo): String = when (cell) {
        is CellInfoNr -> "NR:" + cell.cellIdentity.toString()
        is CellInfoLte -> "LTE:" + cell.cellIdentity.toString()
        else -> cell.toString()
    }

    private fun safe(v: Int): String = if (v == Int.MAX_VALUE || v < 0) "نامشخص/محدود" else v.toString()
    private fun safe(v: Long): String = if (v == Long.MAX_VALUE || v < 0) "نامشخص/محدود" else v.toString()

    private fun baseNetworkName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        else -> "نامشخص (کد " + type + ")"
    }

    private fun networkName(type: Int, info: TelephonyDisplayInfo?): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info != null) {
            when (info.overrideNetworkType) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> return "5G NSA"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> return "5G NSA (پیشرفته)"
            }
        }
        return if (type == TelephonyManager.NETWORK_TYPE_NR) "5G SA" else baseNetworkName(type)
    }

    private fun overrideNetworkName(type: Int): String = when (type) {
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G NSA"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G NSA پیشرفته"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE-Advanced"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE-Advanced Pro"
        else -> "بدون نشانگر افزوده"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode) {
            if (hasPermissions()) refreshData()
            else addText("بدون مجوزها، اندروید اطلاعات سلول مخابراتی را نمایش نمی‌دهد.", 14, true)
        }
    }

    override fun onDestroy() {
        displayListeners.values.forEach { listener ->
            try { (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .listen(listener, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) { }
        }
        displayListeners.clear()
        super.onDestroy()
    }
}
