package ir.anten.yab

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.telephony.*
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private val requestCode = 41

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
            val carrier = sub.carrierName?.toString() ?: "اپراتور نامشخص"
            val report = StringBuilder()
            report.append("سیم‌کارت " + (sub.simSlotIndex + 1) + " — " + carrier + "\n")
            report.append("شبکه داده: " + networkName(tm.dataNetworkType) + "\n")
            try {
                val cells = tm.allCellInfo ?: emptyList()
                val cell = cells.firstOrNull { it.isRegistered } ?: cells.firstOrNull()
                if (cell == null) {
                    report.append("جزئیات سلول توسط دستگاه ارائه نشد.\n")
                } else {
                    report.append("قدرت سیگنال: " + cell.cellSignalStrength.dbm + " dBm\n")
                    report.append("سلول ثبت‌شده: " + (if (cell.isRegistered) "بله" else "خیر") + "\n")
                    when (cell) {
                        is CellInfoLte -> {
                            val c = cell.cellIdentity
                            report.append("فناوری: 4G LTE\nCell ID: " + safe(c.ci) + "\nTAC: " + safe(c.tac) + "\nPCI: " + safe(c.pci) + "\nEARFCN: " + safe(c.earfcn) + "\n")
                            if (android.os.Build.VERSION.SDK_INT >= 30) report.append("باندها: " + c.bands.joinToString() + "\n")
                        }
                        is CellInfoNr -> {
                            val c = cell.cellIdentity as CellIdentityNr
                            report.append("فناوری: 5G NR\nNCI: " + safe(c.nci) + "\nTAC: " + safe(c.tac) + "\nPCI: " + safe(c.pci) + "\nNR-ARFCN: " + safe(c.nrarfcn) + "\n")
                        }
                        is CellInfoWcdma -> {
                            val c = cell.cellIdentity
                            report.append("فناوری: 3G WCDMA\nCell ID: " + safe(c.cid) + "\nLAC: " + safe(c.lac) + "\nPSC: " + safe(c.psc) + "\n")
                        }
                        is CellInfoGsm -> {
                            val c = cell.cellIdentity
                            report.append("فناوری: 2G GSM\nCell ID: " + safe(c.cid) + "\nLAC: " + safe(c.lac) + "\n")
                        }
                        else -> report.append("فناوری سلول: نامشخص/سایر\n")
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
        }
        addText("توجه: فاصله تا دکل فقط با مختصات معتبر همان Cell ID از پایگاه داده قابل محاسبه است؛ قدرت سیگنال به‌تنهایی فاصله را مشخص نمی‌کند.", 13, false)
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
        else -> "نامشخص (کد " + type + ")"
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
