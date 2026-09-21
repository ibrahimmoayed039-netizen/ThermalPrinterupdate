package com.mustfa.printer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mustfa.printer.databinding.ActivityAboutBinding
import kotlinx.coroutines.launch

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private lateinit var updateManager: UpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "حول البرنامج"
            setDisplayHomeAsUpEnabled(true)
        }

        updateManager = UpdateManager(this)

        // عرض رقم الإصدار الحالي
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "غير معروف" }

        // ✅ زر التحقق من التحديثات يدوياً
        binding.btnCheckUpdate.setOnClickListener {
            lifecycleScope.launch {
                updateManager.checkForUpdate(lifecycleScope, silent = false)
            }
        }

        binding.tvAppVersion.text = "الإصدار: $versionName"
        binding.tvPhone.setOnClickListener { callDeveloper() }
        binding.btnCallDev.setOnClickListener { callDeveloper() }
    }

    private fun callDeveloper() {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:07736970504")))
        } catch (_: Exception) {
            Toast.makeText(this, "تعذّر فتح تطبيق الاتصال", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
