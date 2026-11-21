package ir.anten.yab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.*
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private val requestCode = 41
    private val headerCount = 6

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
        addText("تحلیل جداگانه سیم‌کارت‌ها و مکان‌یابی سلول", 15, false)
        addButton("به‌روزرسانی اطلاعات") { refreshData() }
        addText("برای دریافت اطلاعات شبکه، مجوز تلفن و مکان را بدهید و Location را روشن کنید.", 13, false)
        addButton("تنظیم کلید پایگاه داده آنلاین") { showApiKeyDialog() }
        addButton("مکان‌یابی سلول ثبت‌شده") { lookupRegisteredCell() }

        if (hasPermissions()) refreshData() else askPermissions()
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun addButton(label: String, action: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        }, match())
    }

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
                append("قدرت سیگنال گزارش‌شده برای این اشتراک: ${report.dbm?.let { "$it dBm" } ?: "اندروید مقدار جداگانه‌ای ارائه نکرد"}\n")
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

        // Android often exposes registered cell information at device level.
        // Never copy this one cell record into both SIM reports.
        val baseTm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val registeredCell = try {
            baseTm.allCellInfo?.firstOrNull { it.isRegistered }
        } catch (_: Exception) { null }

        addText("اطلاعات سلول ثبت‌شده دستگاه", 18, true)
        if (registeredCell == null) {
            addCard("اندروید در حال حاضر شناسه سلول ثبت‌شده را در اختیار برنامه نگذاشته است. اطلاعات حدسی نمایش داده نمی‌شود.")
        } else {
            addCard(cellDescription(registeredCell) +
                "\n\nاین اطلاعات از فهرست سلول‌های سطح دستگاه دریافت شده و در گوشی دو سیم‌کارته الزاماً قابل انتساب مستقل به هر سیم‌کارت نیست.")
        }

        addText("مکان‌یابی آنلاین", 18, true)
        addCard("برای مکان‌یابی، کلید OpenCelliD را در بخش تنظیمات وارد کنید. مختصات برگشتی تخمینی است و ممکن است برای بعضی سلول‌ها در پایگاه داده موجود نباشد.")
        addText("داده مکانی از OpenCelliD با رعایت مجوز CC BY-SA 4.0 نمایش داده می‌شود.", 12, false)
    }

    private data class SimReport(
        val slot: Int, val subscriptionId: Int, val carrier: String,
        val network: String, val dbm: Int?, val rsrp: Int?, val rsrq: Int?, val sinr: Int?
    )

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "کلید API"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(getSharedPreferences("tower_database", MODE_PRIVATE).getString("api_key", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("کلید OpenCelliD")
            .setMessage("کلید API را در همین گوشی وارد کنید. آن را در گفتگو یا جای عمومی نفرستید.")
            .setView(input)
            .setPositiveButton("ذخیره") { _, _ ->
                getSharedPreferences("tower_database", MODE_PRIVATE).edit()
                    .putString("api_key", input.text.toString().trim()).apply()
                Toast.makeText(this, "کلید روی گوشی ذخیره شد.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun lookupRegisteredCell() {
        if (!hasPermissions()) { askPermissions(); return }
        val key = getSharedPreferences("tower_database", MODE_PRIVATE)
            .getString("api_key", "")?.trim().orEmpty()
        if (key.isBlank()) {
            showApiKeyDialog()
            return
        }

        val baseTm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val cell = try { baseTm.allCellInfo?.firstOrNull { it.isRegistered } }
                   catch (_: Exception) { null }
        if (cell == null) {
            Toast.makeText(this, "اطلاعات سلول ثبت‌شده در دسترس نیست.", Toast.LENGTH_LONG).show()
            return
        }

        val identity = when (cell) {
            is CellInfoLte -> Triple("LTE", cell.cellIdentity.tac, cell.cellIdentity.ci.toLong())
            is CellInfoNr -> if (Build.VERSION.SDK_INT >= 29) {
                val id = cell.cellIdentity as CellIdentityNr
                Triple("NR", id.tac, id.nci)
            } else null
            else -> null
        }
        if (identity == null || identity.second < 0 || identity.third < 0) {
            Toast.makeText(this, "فعلاً مکان‌یابی این نوع سلول یا شناسه آن پشتیبانی نمی‌شود.", Toast.LENGTH_LONG).show()
            return
        }

        val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val subs = try { sm.activeSubscriptionInfoList ?: emptyList() }
                   catch (_: Exception) { emptyList() }
        val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        val sub = subs.firstOrNull { it.subscriptionId == dataSubId }
        if (sub == null) {
            Toast.makeText(this, "سیم‌کارت پیش‌فرض اینترنت شناسایی نشد؛ امکان تطبیق اپراتور با سلول وجود ندارد.", Toast.LENGTH_LONG).show()
            return
        }

        val mcc: String
        val mnc: String
        if (Build.VERSION.SDK_INT >= 29) {
            mcc = sub.mccString ?: ""
            mnc = sub.mncString ?: ""
        } else {
            mcc = sub.mcc.toString()
            mnc = sub.mnc.toString()
        }
        if (mcc.isBlank() || mnc.isBlank() || mcc == "0" || mnc == "0") {
            Toast.makeText(this, "کدهای شبکه اپراتور در دسترس نیستند.", Toast.LENGTH_LONG).show()
            return
        }

        addText("در حال جست‌وجوی موقعیت تخمینی سلول در پایگاه داده آنلاین…", 14, false)
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(
                    "https://opencellid.org/cell/get?key=${Uri.encode(key)}&mcc=$mcc&mnc=$mnc&lac=${identity.second}&cellid=${identity.third}&radio=${identity.first}&format=json"
                )
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 12000
                }
                val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = JSONObject(body)
                val lat = json.optDouble("lat", Double.NaN)
                val lon = json.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    val message = json.optString("message", "سلول در پایگاه داده پیدا نشد یا پاسخ معتبر نبود.")
                    runOnUiThread {
                        addCard("موقعیت پیدا نشد: $message\nممکن است این سلول هنوز در پایگاه داده ثبت نشده باشد.")
                    }
                } else {
                    val range = json.optInt("range", -1)
                    val samples = json.optInt("samples", -1)
                    runOnUiThread {
                        addCard(buildString {
                            append("مختصات تخمینی سلول:\nعرض جغرافیایی: $lat\nطول جغرافیایی: $lon\n")
                            if (range >= 0) append("محدوده تخمین پایگاه داده: $range متر\n")
                            if (samples >= 0) append("تعداد نمونه‌های ثبت‌شده: $samples\n")
                            append("این نقطه الزاماً محل دقیق دکل فیزیکی نیست.")
                        })
                        val mapUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, mapUri))
                        } catch (_: Exception) {
                            Toast.makeText(this, "نقشه روی این گوشی باز نشد.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    addCard("خطا در ارتباط با پایگاه داده: ${e.localizedMessage ?: "نامشخص"}")
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

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
