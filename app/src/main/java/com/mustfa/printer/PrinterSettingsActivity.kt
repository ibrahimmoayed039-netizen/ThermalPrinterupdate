package com.mustfa.printer

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class PrinterSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("printer_prefs", MODE_PRIVATE)
        val rgWidth   = findViewById<RadioGroup>(R.id.rgPrinterWidth)
        val rgCharset = findViewById<RadioGroup>(R.id.rgCharset)
        val etCodePage = findViewById<EditText>(R.id.etCodePage)
        val btnSave   = findViewById<Button>(R.id.btnSaveSettings)

        // تحميل الإعدادات المحفوظة
        val savedWidth   = prefs.getFloat("printer_width", 58f)
        val savedCharset = prefs.getString("charset", "windows-1256") ?: "windows-1256"
        val savedCodePage = prefs.getInt("code_page", 6)

        if (savedWidth == 80f) rgWidth.check(R.id.rb80mm)
        else rgWidth.check(R.id.rb58mm)

        when (savedCharset) {
            "ISO-8859-6" -> rgCharset.check(R.id.rbCharsetISO8859)
            "UTF-8"      -> rgCharset.check(R.id.rbCharsetUTF8)
            else         -> rgCharset.check(R.id.rbCharsetWindows1256)
        }
        etCodePage.setText(savedCodePage.toString())

        btnSave.setOnClickListener {
            val width = when (rgWidth.checkedRadioButtonId) {
                R.id.rb80mm -> 80f
                else -> 58f
            }
            val charset = when (rgCharset.checkedRadioButtonId) {
                R.id.rbCharsetISO8859 -> "ISO-8859-6"
                R.id.rbCharsetUTF8   -> "UTF-8"
                else                 -> "windows-1256"
            }
            val codePage = etCodePage.text.toString().toIntOrNull() ?: 6

            prefs.edit()
                .putFloat("printer_width", width)
                .putString("charset", charset)
                .putInt("code_page", codePage)
                .apply()

            Toast.makeText(this, "تم حفظ الإعدادات", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
