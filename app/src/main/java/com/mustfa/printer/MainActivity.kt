package com.mustfa.printer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.EscPosCharsetEncoding
import com.dantsu.escposprinter.connection.bluetooth.BluetoothConnection
import com.dantsu.escposprinter.connection.tcp.TcpConnection
import com.dantsu.escposprinter.textparser.PrinterTextParserImg
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.mustfa.printer.databinding.ActivityMainBinding
import android.text.Editable
import android.text.TextWatcher
import kotlinx.coroutines.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var updateManager: UpdateManager
    private var printer: EscPosPrinter? = null
    private var selectedDevice: BluetoothDevice? = null
    private var printerWidthMM = 58f
    private var printerDpi = 203
    private var printerCharsPerLine = 32
    private var selectedImageBitmap: Bitmap? = null
    private var selectedPdfUri: Uri? = null
    private var barcodeCopies = 1

    // WiFi Direct
    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var wifiDirectReceiver: BroadcastReceiver? = null
    private val wifiDirectDevices = mutableListOf<WifiP2pDevice>()

    companion object {
        private const val PREFS_NAME = "printer_prefs"
        private const val KEY_LAST_DEVICE_ADDRESS  = "last_device_address"
        private const val KEY_LAST_DEVICE_NAME     = "last_device_name"
        private const val KEY_LAST_CONN_TYPE       = "last_conn_type"   // "bt" | "wifi" | "wifidirect"
        private const val KEY_LAST_WIFI_IP         = "last_wifi_ip"
        private const val KEY_LAST_WIFI_PORT       = "last_wifi_port"
        private const val KEY_LAST_WIFI_NAME       = "last_wifi_name"
        private const val DEFAULT_WIFI_PORT        = 9100
    }

    // ========== التصاريح - بلوتوث ==========
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) showBluetoothDevices()
        else toast("⚠️ يرجى منح جميع الصلاحيات")
    }

    // ========== التصاريح - WiFi Direct ==========
    private val wifiDirectPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) startWifiDirectScan()
        else toast("⚠️ يرجى منح صلاحيات WiFi")
    }


    // ========== اختيار PDF ==========
    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedPdfUri = it
            val name = it.lastPathSegment?.substringAfterLast("/") ?: "ملف PDF"
            binding.tvPdfName.text = name
            binding.btnPrintPdf.isEnabled = printer != null
        }
    }

    // ========== اختيار صورة ==========
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val stream = contentResolver.openInputStream(it)
            selectedImageBitmap = BitmapFactory.decodeStream(stream)
            binding.imgPreview.setImageBitmap(selectedImageBitmap)
            binding.imgPreview.visibility = View.VISIBLE
            binding.btnRemoveImage.visibility = View.VISIBLE
        }
    }

    // ========== onCreate ==========
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPrinterSettings()
        setupUI()
        tryAutoConnect()

        // ✅ التحقق من التحديث عند فتح التطبيق (بصمت إذا لم يكن هناك تحديث)
        updateManager = UpdateManager(this)
        lifecycleScope.launch {
            updateManager.checkForUpdate(lifecycleScope, silent = true)
        }
    }

    override fun onResume() {
        super.onResume()
        wifiDirectReceiver?.let {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            registerReceiver(it, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        wifiDirectReceiver?.let { unregisterReceiver(it) }
    }


    // ========== قراءة إعدادات الترميز ==========
    private fun getCharsetEncoding(): EscPosCharsetEncoding {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val charset  = prefs.getString("charset", "windows-1256") ?: "windows-1256"
        val codePage = prefs.getInt("code_page", 6)
        return EscPosCharsetEncoding(charset, codePage)
    }

    // ========== تحميل إعدادات الطابعة ==========
    private fun loadPrinterSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        printerWidthMM = prefs.getFloat("printer_width", 58f)
        printerCharsPerLine = if (printerWidthMM == 80f) 48 else 32
    }

    // ========== إعداد الواجهة ==========
    private fun setupUI() {

        // --- حجم الخط ---
        val fontSizes = arrayOf("صغير جداً (1)", "صغير (2)", "عادي (3)", "كبير (4)", "كبير جداً (5)")
        binding.spinnerFontSize.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, fontSizes)
        binding.spinnerFontSize.setSelection(2)

        // --- نوع الباركود ---
        val barcodeTypes = arrayOf("CODE 128", "CODE 39", "EAN 13", "QR Code", "UPC-A")
        binding.spinnerBarcodeType.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, barcodeTypes)

        // --- أزرار الاتصال ---
        binding.btnConnect.setOnClickListener { requestBluetoothPermissions() }
        binding.btnConnectWifi.setOnClickListener { showWifiConnectDialog() }
        binding.btnConnectWifiDirect.setOnClickListener { requestWifiDirectPermissions() }
        binding.btnDisconnect.setOnClickListener { disconnectPrinter() }

        // --- طباعة ---
        binding.btnPrintText.setOnClickListener { printText() }
        binding.btnPrintBarcode.setOnClickListener { printBarcode() }

        // تحديث المعاينة عند تغيير نوع الباركود
        binding.spinnerBarcodeType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val text = binding.etBarcodeValue.text.toString().trim()
                if (text.length >= 2) generateBarcodePreview(text)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // --- معاينة الباركود الفورية ---
        binding.etBarcodeValue.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                if (text.length >= 2) generateBarcodePreview(text)
                else {
                    binding.imgBarcodePreview.visibility = android.view.View.GONE
                    binding.tvBarcodePreviewValue.visibility = android.view.View.GONE
                }
            }
        })

        // --- عدد النسخ ---
        binding.btnCopiesUp.setOnClickListener {
            if (barcodeCopies < 99) { barcodeCopies++; binding.tvCopiesCount.text = barcodeCopies.toString() }
        }
        binding.btnCopiesDown.setOnClickListener {
            if (barcodeCopies > 1) { barcodeCopies--; binding.tvCopiesCount.text = barcodeCopies.toString() }
        }

        // --- معاينة الفاتورة ---
        binding.btnPreviewReceipt.setOnClickListener { showReceiptPreview() }
        binding.btnPrintImage.setOnClickListener { printImage() }
        binding.btnPrintFull.setOnClickListener { printFullReceipt() }
        binding.btnPrintTest.setOnClickListener { printTestPage() }

        // --- PDF ---
        binding.btnPickPdf.setOnClickListener { pdfPicker.launch("application/pdf") }
        binding.btnPrintPdf.setOnClickListener { printPdf() }

        // --- اختيار صورة ---
        binding.btnPickImage.setOnClickListener { imagePicker.launch("image/*") }
        binding.btnRemoveImage.setOnClickListener {
            selectedImageBitmap = null
            binding.imgPreview.visibility = View.GONE
            binding.btnRemoveImage.visibility = View.GONE
        }

        // --- إعدادات الطابعة ---
        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, PrinterSettingsActivity::class.java))
        }

        // --- قطع الورق ---
        binding.btnCutPaper.setOnClickListener { cutPaper() }

        updateConnectionUI(false)
    }

    // ============================================================
    //  BLUETOOTH
    // ============================================================

    private fun requestBluetoothPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val notGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) showBluetoothDevices()
        else permissionsLauncher.launch(notGranted.toTypedArray())
    }

    private fun showBluetoothDevices() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            toast("⚠️ يرجى تشغيل البلوتوث"); return
        }
        val pairedDevices = if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            btAdapter.bondedDevices.toList()
        } else emptyList()

        if (pairedDevices.isEmpty()) {
            toast("لا توجد أجهزة مقترنة. يرجى إقران الطابعة في إعدادات البلوتوث"); return
        }
        val names = pairedDevices.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🖨️ اختر الطابعة (بلوتوث)")
            .setItems(names) { _, index -> connectBluetooth(pairedDevices[index]) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun connectBluetooth(device: BluetoothDevice, isAutoConnect: Boolean = false) {
        showProgress(true)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = BluetoothConnection(device)
                val p = EscPosPrinter(conn, printerDpi, printerWidthMM, printerCharsPerLine, getCharsetEncoding())
                withContext(Dispatchers.Main) {
                    printer = p
                    selectedDevice = device
                    val name = if (ActivityCompat.checkSelfPermission(this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                        device.name else device.address
                    saveLastDevice(address = device.address, name = name, type = "bt")
                    updateConnectionUI(true, "✅ $name (بلوتوث)")
                    toast(if (isAutoConnect) "🔄 اتصال تلقائي بـ $name" else "متصل بـ $name")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(if (isAutoConnect) "تعذّر الاتصال التلقائي، حاول يدوياً"
                          else "❌ فشل الاتصال: ${e.message}")
                    if (!isAutoConnect) updateConnectionUI(false)
                }
            } finally { withContext(Dispatchers.Main) { showProgress(false) } }
        }
    }

    // ============================================================
    //  WiFi TCP/IP
    // ============================================================

    private fun showWifiConnectDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedIp   = prefs.getString(KEY_LAST_WIFI_IP, "") ?: ""
        val savedPort = prefs.getInt(KEY_LAST_WIFI_PORT, DEFAULT_WIFI_PORT)
        val savedName = prefs.getString(KEY_LAST_WIFI_NAME, "") ?: ""

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }

        val etName = EditText(this).apply {
            hint = "اسم الطابعة (اختياري)"
            setText(savedName)
            setTextColor(0xFF000000.toInt())
        }
        val etIp = EditText(this).apply {
            hint = "عنوان IP  مثال: 192.168.1.100"
            setText(savedIp)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(0xFF000000.toInt())
        }
        val etPort = EditText(this).apply {
            hint = "Port  (افتراضي: 9100)"
            setText(savedPort.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(0xFF000000.toInt())
        }

        layout.addView(TextView(this).apply { text = "اسم الطابعة" })
        layout.addView(etName)
        layout.addView(TextView(this).apply { text = "عنوان IP"; setPadding(0,16,0,0) })
        layout.addView(etIp)
        layout.addView(TextView(this).apply { text = "رقم المنفذ (Port)"; setPadding(0,16,0,0) })
        layout.addView(etPort)

        AlertDialog.Builder(this)
            .setTitle("📶 اتصال WiFi (TCP/IP)")
            .setView(layout)
            .setPositiveButton("اتصال") { _, _ ->
                val ip   = etIp.text.toString().trim()
                val port = etPort.text.toString().toIntOrNull() ?: DEFAULT_WIFI_PORT
                val name = etName.text.toString().trim().ifEmpty { ip }
                if (ip.isEmpty()) { toast("⚠️ أدخل عنوان IP"); return@setPositiveButton }
                connectWifi(ip, port, name)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun connectWifi(ip: String, port: Int, name: String, isAutoConnect: Boolean = false) {
        showProgress(true)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = TcpConnection(ip, port, 8000)
                val p = EscPosPrinter(conn, printerDpi, printerWidthMM, printerCharsPerLine, getCharsetEncoding())
                withContext(Dispatchers.Main) {
                    printer = p
                    selectedDevice = null
                    saveLastDevice(address = ip, name = name, type = "wifi",
                        wifiIp = ip, wifiPort = port, wifiName = name)
                    updateConnectionUI(true, "✅ $name (WiFi)")
                    toast(if (isAutoConnect) "🔄 اتصال تلقائي بـ $name" else "✅ متصل بـ $name عبر WiFi")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(if (isAutoConnect) "تعذّر الاتصال التلقائي عبر WiFi"
                          else "❌ فشل الاتصال: ${e.message}")
                    if (!isAutoConnect) updateConnectionUI(false)
                }
            } finally { withContext(Dispatchers.Main) { showProgress(false) } }
        }
    }

    // ============================================================
    //  WiFi Direct (P2P)
    // ============================================================

    private fun requestWifiDirectPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val notGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) startWifiDirectScan()
        else wifiDirectPermLauncher.launch(notGranted.toTypedArray())
    }

    private fun startWifiDirectScan() {
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        val manager = wifiP2pManager ?: run { toast("⚠️ الجهاز لا يدعم WiFi Direct"); return }
        wifiP2pChannel = manager.initialize(this, mainLooper, null)

        wifiDirectReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (ActivityCompat.checkSelfPermission(this@MainActivity,
                                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            manager.requestPeers(wifiP2pChannel) { peers ->
                                wifiDirectDevices.clear()
                                wifiDirectDevices.addAll(peers.deviceList)
                                if (wifiDirectDevices.isNotEmpty()) showWifiDirectDevices()
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        registerReceiver(wifiDirectReceiver, filter)

        toast("🔍 جاري البحث عن أجهزة WiFi Direct...")
        binding.tvDeviceName.text = "🔍 يبحث عن أجهزة WiFi Direct..."

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            manager.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { /* الـ BroadcastReceiver سيستقبل النتائج */ }
                override fun onFailure(reason: Int) {
                    val msg = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "WiFi Direct غير مدعوم في هذا الجهاز"
                        WifiP2pManager.BUSY -> "WiFi Direct مشغول، حاول مرة أخرى"
                        else -> "فشل البحث (رمز: $reason)"
                    }
                    toast("❌ $msg")
                    binding.tvDeviceName.text = "غير متصل"
                }
            })
        }
    }

    private fun showWifiDirectDevices() {
        val names = wifiDirectDevices.map { "${it.deviceName}\n${it.deviceAddress}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("📡 أجهزة WiFi Direct")
            .setItems(names) { _, index ->
                connectWifiDirect(wifiDirectDevices[index])
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun connectWifiDirect(device: WifiP2pDevice) {
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return

        showProgress(true)
        binding.tvDeviceName.text = "🔄 يتصل بـ ${device.deviceName}..."

        val config = android.net.wifi.p2p.WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toast("⚠️ يلزم صلاحية الموقع"); showProgress(false); return
        }

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // بعد الاتصال نحتاج IP الجهاز — نطلبه من connectionInfo
                manager.requestConnectionInfo(channel) { info ->
                    val ownerIp = info?.groupOwnerAddress?.hostAddress
                    if (ownerIp != null) {
                        // الطابعة غالباً هي Group Owner والمنفذ الافتراضي 9100
                        CoroutineScope(Dispatchers.Main).launch {
                            showProgress(false)
                            // نسأل المستخدم عن Port في حال كانت مختلفة
                            showWifiDirectPortDialog(device.deviceName, ownerIp)
                        }
                    } else {
                        CoroutineScope(Dispatchers.Main).launch {
                            showProgress(false)
                            toast("❌ تعذّر الحصول على IP بعد الاتصال")
                        }
                    }
                }
            }
            override fun onFailure(reason: Int) {
                CoroutineScope(Dispatchers.Main).launch {
                    showProgress(false)
                    toast("❌ فشل الاتصال بـ WiFi Direct (رمز: $reason)")
                    binding.tvDeviceName.text = "غير متصل"
                }
            }
        })
    }

    private fun showWifiDirectPortDialog(deviceName: String, ip: String) {
        val etPort = EditText(this).apply {
            hint = "Port (افتراضي: 9100)"
            setText("9100")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(0xFF000000.toInt())
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(TextView(this@MainActivity).apply {
                text = "تم الاتصال بـ $deviceName\nIP: $ip\nحدد رقم المنفذ:"
            })
            addView(etPort)
        }
        AlertDialog.Builder(this)
            .setTitle("📡 WiFi Direct - اكتمل الاتصال")
            .setView(layout)
            .setPositiveButton("اتصال بالطابعة") { _, _ ->
                val port = etPort.text.toString().toIntOrNull() ?: 9100
                connectWifi(ip, port, "$deviceName (WiFi Direct)")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ============================================================
    //  الاتصال التلقائي
    // ============================================================

    private fun tryAutoConnect() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val type = prefs.getString(KEY_LAST_CONN_TYPE, null) ?: return

        when (type) {
            "wifi" -> {
                val ip   = prefs.getString(KEY_LAST_WIFI_IP, null) ?: return
                val port = prefs.getInt(KEY_LAST_WIFI_PORT, DEFAULT_WIFI_PORT)
                val name = prefs.getString(KEY_LAST_WIFI_NAME, ip) ?: ip
                binding.tvDeviceName.text = "🔄 جاري الاتصال بـ $name..."
                connectWifi(ip, port, name, isAutoConnect = true)
            }
            "bt" -> {
                val permNeeded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
                if (ContextCompat.checkSelfPermission(this, permNeeded) != PackageManager.PERMISSION_GRANTED) return
                val lastAddress = prefs.getString(KEY_LAST_DEVICE_ADDRESS, null) ?: return
                val lastName    = prefs.getString(KEY_LAST_DEVICE_NAME, lastAddress) ?: lastAddress
                val btManager   = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val btAdapter   = btManager.adapter
                if (btAdapter == null || !btAdapter.isEnabled) return
                val device = btAdapter.bondedDevices.firstOrNull { it.address == lastAddress }
                if (device == null) {
                    prefs.edit().remove(KEY_LAST_DEVICE_ADDRESS).remove(KEY_LAST_DEVICE_NAME)
                        .remove(KEY_LAST_CONN_TYPE).apply()
                    return
                }
                binding.tvDeviceName.text = "🔄 جاري الاتصال بـ $lastName..."
                connectBluetooth(device, isAutoConnect = true)
            }
        }
    }

    // ============================================================
    //  حفظ آخر جهاز
    // ============================================================

    private fun saveLastDevice(
        address: String, name: String, type: String,
        wifiIp: String? = null, wifiPort: Int? = null, wifiName: String? = null
    ) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putString(KEY_LAST_DEVICE_ADDRESS, address)
            putString(KEY_LAST_DEVICE_NAME, name)
            putString(KEY_LAST_CONN_TYPE, type)
            if (wifiIp != null)   putString(KEY_LAST_WIFI_IP, wifiIp)
            if (wifiPort != null) putInt(KEY_LAST_WIFI_PORT, wifiPort)
            if (wifiName != null) putString(KEY_LAST_WIFI_NAME, wifiName)
            apply()
        }
    }

    // ============================================================
    //  قطع الاتصال
    // ============================================================

    private fun disconnectPrinter() {
        printer = null
        selectedDevice = null
        wifiP2pManager?.removeGroup(wifiP2pChannel, null)
        updateConnectionUI(false)
        toast("تم قطع الاتصال")
    }

    // ============================================================
    //  واجهة الاتصال
    // ============================================================

    private fun updateConnectionUI(connected: Boolean, label: String = "غير متصل") {
        binding.btnConnect.visibility          = if (connected) View.GONE else View.VISIBLE
        binding.btnConnectWifi.visibility      = if (connected) View.GONE else View.VISIBLE
        binding.btnConnectWifiDirect.visibility = if (connected) View.GONE else View.VISIBLE
        binding.btnDisconnect.visibility       = if (connected) View.VISIBLE else View.GONE
        binding.tvDeviceName.text              = if (connected) label else "غير متصل"
        val printBtns = listOf(binding.btnPrintText, binding.btnPrintBarcode,
            binding.btnPrintImage, binding.btnPrintFull,
            binding.btnPrintTest, binding.btnCutPaper)
        binding.btnPrintPdf.isEnabled = connected && selectedPdfUri != null
        printBtns.forEach { it.isEnabled = connected }
    }

    private fun showProgress(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnConnect.isEnabled          = !show
        binding.btnConnectWifi.isEnabled      = !show
        binding.btnConnectWifiDirect.isEnabled = !show
    }

    // ============================================================
    //  الطباعة
    // ============================================================

    private fun buildTextCommand(text: String): String {
        val align = when (binding.rgAlign.checkedRadioButtonId) {
            R.id.rbCenter -> "[C]"
            R.id.rbLeft   -> "[L]"
            else          -> "[R]"
        }
        val fontSize = binding.spinnerFontSize.selectedItemPosition + 1
        val bold = binding.cbBold.isChecked
        val underline = binding.cbUnderline.isChecked
        val sizeTag = when (fontSize) {
            1 -> "<font size='small'>"
            4 -> "<font size='wide'>"
            5 -> "<font size='big'>"
            else -> "<font size='normal'>"
        }
        var formatted = text
        if (bold)      formatted = "<b>$formatted</b>"
        if (underline) formatted = "<u>$formatted</u>"
        return "$align$sizeTag$formatted</font>\n"
    }

    private fun printText() {
        val text = binding.etText.text.toString().trim()
        if (text.isEmpty())  { toast("⚠️ أدخل النص أولاً"); return }
        if (printer == null) { toast("⚠️ غير متصل بالطابعة"); return }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                printer!!.printFormattedTextAndCut(buildTextCommand(text))
                withContext(Dispatchers.Main) { toast("✅ تمت الطباعة") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("❌ ${e.message}") }
            }
        }
    }

    private fun printBarcode() {
        val value = binding.etBarcodeValue.text.toString().trim()
        if (value.isEmpty())  { toast("⚠️ أدخل قيمة الباركود"); return }
        if (printer == null)  { toast("⚠️ غير متصل بالطابعة"); return }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val format = when (binding.spinnerBarcodeType.selectedItemPosition) {
                    0 -> BarcodeFormat.CODE_128
                    1 -> BarcodeFormat.CODE_39
                    2 -> BarcodeFormat.EAN_13
                    3 -> BarcodeFormat.QR_CODE
                    4 -> BarcodeFormat.UPC_A
                    else -> BarcodeFormat.CODE_128
                }
                val w = if (format == BarcodeFormat.QR_CODE) 200 else 400
                val h = if (format == BarcodeFormat.QR_CODE) 200 else 100
                val matrix = MultiFormatWriter().encode(value, format, w, h)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                for (x in 0 until w) for (y in 0 until h)
                    bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                val imgHex = PrinterTextParserImg.bitmapToHexadecimalString(printer!!, bmp)
                printer!!.printFormattedTextAndCut("[C]$imgHex\n[C]$value\n")
                withContext(Dispatchers.Main) { toast("✅ تمت طباعة الباركود") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("❌ ${e.message}") }
            }
        }
    }

    private fun printImage() {
        val bmp = selectedImageBitmap ?: run { toast("⚠️ اختر صورة أولاً"); return }
        if (printer == null) { toast("⚠️ غير متصل بالطابعة"); return }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val maxWidth = if (printerWidthMM == 80f) 560 else 384
                val scaled = Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height * maxWidth / bmp.width), true)
                val imgHex = PrinterTextParserImg.bitmapToHexadecimalString(printer!!, scaled)
                printer!!.printFormattedTextAndCut("[C]$imgHex\n")
                withContext(Dispatchers.Main) { toast("✅ تمت طباعة الصورة") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("❌ ${e.message}") }
            }
        }
    }

    private fun printFullReceipt() {
        if (printer == null) { toast("⚠️ غير متصل بالطابعة"); return }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val receipt = buildString {
                    append("[C]<font size='big'><b>فاتورة ضريبية</b></font>\n")
                    append("[C]================================\n")
                    append("[R]التاريخ: ${java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())}\n")
                    append("[R]الوقت: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n")
                    append("[C]--------------------------------\n")
                    append("[L]الصنف[R]السعر\n")
                    append("[C]--------------------------------\n")
                    append("[L]منتج 1[R]10.00 ر.س\n")
                    append("[L]منتج 2[R]25.50 ر.س\n")
                    append("[L]منتج 3[R]8.75 ر.س\n")
                    append("[C]================================\n")
                    append("[L]<b>المجموع</b>[R]<b>44.25 ر.س</b>\n")
                    append("[L]الضريبة 15%[R]6.64 ر.س\n")
                    append("[L]<b>الإجمالي</b>[R]<b>50.89 ر.س</b>\n")
                    append("[C]================================\n")
                    append("[C]شكراً لزيارتكم\n")
                    append("[C]com.mustfa.printer\n")
                }
                val qrMatrix = MultiFormatWriter().encode("INV-${System.currentTimeMillis()}", BarcodeFormat.QR_CODE, 150, 150)
                val qrBmp = Bitmap.createBitmap(150, 150, Bitmap.Config.RGB_565)
                for (x in 0 until 150) for (y in 0 until 150)
                    qrBmp.setPixel(x, y, if (qrMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                val qrHex = PrinterTextParserImg.bitmapToHexadecimalString(printer!!, qrBmp)
                printer!!.printFormattedTextAndCut("$receipt[C]$qrHex\n")
                withContext(Dispatchers.Main) { toast("✅ تمت طباعة الفاتورة") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("❌ ${e.message}") }
            }
        }
    }

    private fun printTestPage() {
        if (printer == null) { toast("⚠️ غير متصل بالطابعة"); return }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                printer!!.printFormattedTextAndCut(
                    "[C]<font size='big'><b>صفحة اختبار</b></font>\n" +
                    "[C]================================\n" +
                    "[L]خط عادي يسار\n" +
                    "[C]خط عادي وسط\n" +
                    "[R]خط عادي يمين\n" +
                    "[C]<b>نص غامق</b>\n" +
                    "[C]<u>نص مسطر</u>\n" +
                    "[C]<font size='small'>خط صغير</font>\n" +
                    "[C]<font size='big'>خط كبير</font>\n" +
                    "[C]================================\n" +
                    "[C]Arabic: مرحبا بالعالم\n" +
                    "[C]English: Hello World\n" +
                    "[C]Numbers: 0123456789\n" +
                    "[C]================================\n" +
                    "[C]الطابعة تعمل بشكل صحيح ✓\n"
                )
                withContext(Dispatchers.Main) { toast("✅ تمت طباعة صفحة الاختبار") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("❌ ${e.message}") }
            }
        }
    }

    private fun cutPaper() {
        if (printer == null) { toast("⚠️ غير متصل بالطابعة"); return }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                printer!!.printFormattedTextAndCut("\n\n\n")
                withContext(Dispatchers.Main) { toast("✂️ تم قطع الورق") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("❌ ${e.message}") }
            }
        }
    }


    // ============================================================
    //  طباعة PDF
    // ============================================================

    private fun printPdf() {
        val uri = selectedPdfUri ?: run { toast("اختر ملف PDF أولاً"); return }
        if (printer == null) { toast("غير متصل بالطابعة"); return }

        binding.progressPdf.visibility = android.view.View.VISIBLE
        binding.btnPrintPdf.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // نسخ الملف مؤقتاً لأن PdfRenderer يحتاج File
                val tmpFile = File(cacheDir, "print_tmp.pdf")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
                }

                val fd = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fd)
                val pageCount = renderer.pageCount
                val printerWidth = if (printerWidthMM == 80f) 560 else 384

                for (i in 0 until pageCount) {
                    val page = renderer.openPage(i)
                    val scale = printerWidth.toFloat() / page.width
                    val bmpHeight = (page.height * scale).toInt()
                    val bmp = Bitmap.createBitmap(printerWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()

                    val imgHex = com.dantsu.escposprinter.textparser.PrinterTextParserImg
                        .bitmapToHexadecimalString(printer!!, bmp)
                    printer!!.printFormattedText("[C]$imgHex\n")

                    // فاصل بين الصفحات
                    if (i < pageCount - 1) printer!!.printFormattedText("\n")
                }

                renderer.close()
                fd.close()
                printer!!.printFormattedTextAndCut("\n")
                tmpFile.delete()

                withContext(Dispatchers.Main) {
                    toast("تمت طباعة PDF ($pageCount صفحة)")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("فشل طباعة PDF: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progressPdf.visibility = android.view.View.GONE
                    binding.btnPrintPdf.isEnabled = true
                }
            }
        }
    }


    // ============================================================
    //  معاينة الفاتورة
    // ============================================================

    private fun showReceiptPreview() {
        val now = java.util.Date()
        val dateFmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(now)
        val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(now)

        val receiptText = """
شركة المسار الذهبي
الموصل - المجموعة الثقافية
================================
التاريخ: $dateFmt
الوقت: $timeFmt
================================
الصنف                      السعر
--------------------------------
منتج 1                   10.00 ر.س
منتج 2                   25.50 ر.س
منتج 3                    8.75 ر.س
================================
المجموع:             44.25 ر.س
الضريبة 15%:          6.64 ر.س
الإجمالي:            50.89 ر.س
================================
        شكراً لزيارتكم
        """.trimIndent()

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        // شريط العنوان
        val titleBar = android.widget.TextView(this).apply {
            text = "معاينة الفاتورة"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFE94560.toInt())
            setPadding(32, 24, 32, 24)
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        // محتوى الفاتورة
        val scroll = android.widget.ScrollView(this)
        val tv = android.widget.TextView(this).apply {
            text = receiptText
            textSize = 13f
            setTextColor(0xFF1A1A2E.toInt())
            setBackgroundColor(0xFFFAFAFA.toInt())
            setPadding(32, 24, 32, 24)
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(4f, 1f)
        }
        scroll.addView(tv)

        // أزرار
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFFF0F2F5.toInt())
        }
        val btnClose = android.widget.Button(this).apply {
            text = "إغلاق"
            setTextColor(0xFF555555.toInt())
            setBackgroundColor(0xFFDDDDDD.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginEnd = 8
            }
        }
        val btnPrint = android.widget.Button(this).apply {
            text = "طباعة الآن"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFE94560.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow.addView(btnClose)
        btnRow.addView(btnPrint)

        layout.addView(titleBar)
        layout.addView(scroll, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        layout.addView(btnRow)

        val alertDialog = dialog.setView(layout).create()
        alertDialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            (resources.displayMetrics.heightPixels * 0.75).toInt()
        )

        btnClose.setOnClickListener { alertDialog.dismiss() }
        btnPrint.setOnClickListener {
            alertDialog.dismiss()
            printFullReceipt()
        }

        alertDialog.show()
        alertDialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            (resources.displayMetrics.heightPixels * 0.75).toInt()
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
