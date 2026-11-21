package ir.anten.yab

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.*
import android.view.ViewGroup
import android.widget.*
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private val requestCode = 41
    private val headerCount = 4

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
        addText("تحلیل جداگانه سیم‌کارت‌ها و مقایسه کیفیت شبکه", 15, false)
        content.addView(Button(this).apply {
            text = "به‌روزرسانی اطلاعات"
            setOnClickListener { refreshData() }
        }, match())
        addText("برای دریافت اطلاعات شبکه، مجوز تلفن و مکان را بدهید و Location را روشن کنید.", 13, false)

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

    private fun addCard(value: String) {
        val card = TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1C2A40.toInt())
            setPadding(22, 22, 22, 22)
        }
        val params = match()
        params.topMargin = 18
        content.addView(card, params)
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

    private fun refreshData() {
        if (!hasPermissions()) { askPermissions(); return }
        while (content.childCount > headerCount) content.removeViewAt(headerCount)

        val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val subs = try { sm.activeSubscriptionInfoList ?: emptyList() }
                   catch (_: SecurityException) { emptyList() }

        if (subs.isEmpty()) {
            addText("سیم‌کارت فعالی شناسایی نشد یا مجوز کافی نیست.", 15, true)
            return
        }

        val reports = mutableListOf<SimReport>()
        for (sub in subs.sortedBy { it.simSlotIndex }) {
            val tm = (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .createForSubscriptionId(sub.subscriptionId)

            val strength = if (Build.VERSION.SDK_INT >= 29) {
                try { tm.signalStrength } catch (_: Exception) { null }
            } else null

            val strongest = strength?.cellSignalStrengths?.maxByOrNull { it.dbm }
            val dbm = strongest?.dbm?.takeIf { it in -150..-1 }
            val network = networkName(try { tm.dataNetworkType } catch (_: Exception) { 0 })
            val carrier = sub.carrierName?.toString()?.ifBlank { null } ?: "اپراتور نامشخص"
            val report = SimReport(
                slot = sub.simSlotIndex + 1,
                subscriptionId = sub.subscriptionId,
                carrier = carrier,
                network = network,
                dbm = dbm,
                rsrp = (strongest as? CellSignalStrengthLte)?.rsrp,
                rsrq = (strongest as? CellSignalStrengthLte)?.rsrq,
                sinr = (strongest as? CellSignalStrengthLte)?.rssnr
            )
            reports.add(report)
            saveHistory(report)
            addCard(buildString {
                append("سیم‌کارت ${report.slot} — ${report.carrier}\n")
                append("شبکه داده: ${report.network}\n")
                append("قدرت سیگنال اختصاصی این اشتراک: ${report.dbm?.let { "$it dBm" } ?: "اندروید مقدار جداگانه‌ای ارائه نکرد"}\n")
                report.rsrp?.let { append("RSRP: $it dBm\n") }
                report.rsrq?.let { append("RSRQ: $it dB\n") }
                report.sinr?.let { append("SINR: $it dB\n") }
                append("شناسه اشتراک: ${report.subscriptionId}\n")
                append("تاریخچه قدرت سیگنال: ${historyText(report.subscriptionId)}")
            })
        }

        if (reports.size >= 2) {
            val a = reports[0]
            val b = reports[1]
            addText("مقایسه دو سیم‌کارت", 18, true)
            addCard(buildString {
                append("${a.carrier}: ${a.dbm?.let { "$it dBm" } ?: "نامشخص"}\n")
                append("${b.carrier}: ${b.dbm?.let { "$it dBm" } ?: "نامشخص"}\n")
                if (a.dbm != null && b.dbm != null) {
                    val better = if (a.dbm > b.dbm) a.carrier else if (b.dbm > a.dbm) b.carrier else "هر دو برابر"
                    append("بر اساس قدرت سیگنال گزارش‌شده: $better")
                } else {
                    append("مقایسه دقیق ممکن نیست؛ گوشی برای یکی از اشتراک‌ها قدرت سیگنال جداگانه نداده است.")
                }
            })
        }

        // Android's allCellInfo is device-level on many dual-SIM phones. Do not
        // query it inside the subscription loop or copy its registered cell to both SIM cards.
        val baseTm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val registeredCell = try {
            baseTm.allCellInfo?.firstOrNull { it.isRegistered }
        } catch (_: Exception) { null }

        addText("اطلاعات سلول ثبت‌شده دستگاه", 18, true)
        if (registeredCell == null) {
            addCard("اندروید در حال حاضر شناسه سلول ثبت‌شده را در اختیار برنامه نگذاشته است. این اطلاعات به‌زور به سیم‌کارت‌ها نسبت داده نمی‌شود.")
        } else {
            addCard(cellDescription(registeredCell) +
                "\n\nتوجه: این اطلاعات از فهرست سلول‌های سطح دستگاه دریافت شده و در گوشی‌های دو سیم‌کارته الزاماً قابل انتساب مستقل به هر سیم‌کارت نیست.")
        }

        addText("نکته درباره نقشه دکل", 18, true)
        addCard("Cell ID و قدرت سیگنال به‌تنهایی مختصات دکل را مشخص نمی‌کنند. برای نمایش محل دکل، فاصله و جهت، باید مختصات همان سلول از یک پایگاه داده معتبر سلول‌های مخابراتی دریافت شود. تا وقتی مختصات معتبر پیدا نشود، برنامه محل حدسی نمایش نمی‌دهد.")
    }

    private data class SimReport(
        val slot: Int, val subscriptionId: Int, val carrier: String,
        val network: String, val dbm: Int?, val rsrp: Int?, val rsrq: Int?, val sinr: Int?
    )

    private fun saveHistory(report: SimReport) {
        val prefs = getSharedPreferences("signal_history", Context.MODE_PRIVATE)
        val key = "sub_${report.subscriptionId}"
        val old = prefs.getString(key, "") ?: ""
        val entry = "${System.currentTimeMillis()},${report.dbm ?: 0}"
        val values = (old.split(";").filter { it.isNotBlank() } + entry).takeLast(12)
        prefs.edit().putString(key, values.joinToString(";")).apply()
    }

    private fun historyText(subscriptionId: Int): String {
        val prefs = getSharedPreferences("signal_history", Context.MODE_PRIVATE)
        val values = (prefs.getString("sub_$subscriptionId", "") ?: "")
            .split(";").filter { it.isNotBlank() }
        if (values.isEmpty()) return "هنوز داده‌ای ثبت نشده"
        return values.mapNotNull { item ->
            item.substringAfter(",", "").toIntOrNull()?.takeIf { it != 0 }?.let { "$it dBm" }
        }.joinToString(" ← ").ifBlank { "هنوز داده معتبر ثبت نشده" }
    }

    private fun cellDescription(cell: CellInfo): String = buildString {
        append("قدرت سیگنال سلول: ${cell.cellSignalStrength.dbm} dBm\n")
        append("ثبت‌شده: ${if (cell.isRegistered) "بله" else "خیر"}\n")
        when (cell) {
            is CellInfoLte -> {
                val c = cell.cellIdentity
                append("فناوری: 4G LTE\nCell ID: ${safe(c.ci)}\nTAC: ${safe(c.tac)}\nPCI: ${safe(c.pci)}\nEARFCN: ${safe(c.earfcn)}\n")
                if (Build.VERSION.SDK_INT >= 30) append("Band: ${c.bands.joinToString()}\n")
            }
            is CellInfoNr -> if (Build.VERSION.SDK_INT >= 29) {
                val c = cell.cellIdentity as CellIdentityNr
                append("فناوری: 5G NR\nNCI: ${safe(c.nci)}\nTAC: ${safe(c.tac)}\nPCI: ${safe(c.pci)}\nNR-ARFCN: ${safe(c.nrarfcn)}\n")
            }
            is CellInfoWcdma -> {
                val c = cell.cellIdentity
                append("فناوری: 3G WCDMA\nCell ID: ${safe(c.cid)}\nLAC: ${safe(c.lac)}\nPSC: ${safe(c.psc)}\n")
            }
            is CellInfoGsm -> {
                val c = cell.cellIdentity
                append("فناوری: 2G GSM\nCell ID: ${safe(c.cid)}\nLAC: ${safe(c.lac)}\n")
            }
            else -> append("فناوری سلول: نامشخص/سایر\n")
        }
    }

    private fun safe(v: Int): String = if (v == Int.MAX_VALUE || v < 0) "نامشخص/محدود" else v.toString()
    private fun safe(v: Long): String = if (v == Long.MAX_VALUE || v < 0) "نامشخص/محدود" else v.toString()

    private fun networkName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        else -> "نامشخص (کد $type)"
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
}
