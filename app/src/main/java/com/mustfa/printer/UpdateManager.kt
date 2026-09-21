package com.mustfa.printer

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * نظام التحديث التلقائي عبر رابط خارجي (GitHub أو سيرفر خاص)
 *
 * طريقة العمل:
 * 1. عند فتح التطبيق يقرأ ملف version.json من رابطك
 * 2. يقارن versionCode الحالي بالجديد
 * 3. إذا وجد تحديث يعرض dialog للمستخدم
 * 4. عند الموافقة يحمّل APK ويثبته مباشرة بدون حذف البيانات
 */
class UpdateManager(private val context: Context) {

    companion object {
        // ============================================================
        //  ⚙️ عدّل هذا الرابط برابطك الخاص
        //
        //  GitHub مثال:
        //  https://raw.githubusercontent.com/اسمك/اسم-المشروع/main/version.json
        //
        //  سيرفر خاص مثال:
        //  https://myserver.com/printer-app/version.json
        // ============================================================
        const val VERSION_CHECK_URL = "https://raw.githubusercontent.com/YOUR_USERNAME/YOUR_REPO/main/version.json"

        // اسم الملف المحلي عند التحميل
        private const val APK_FILE_NAME = "ThermalPrinter_update.apk"
    }

    /**
     * تحقق من التحديث وأظهر Dialog إذا وجد
     * استدعاء: UpdateManager(this).checkForUpdate(lifecycleScope)
     */
    suspend fun checkForUpdate(scope: LifecycleCoroutineScope, silent: Boolean = true) {
        try {
            val info = fetchVersionInfo() ?: return

            val currentCode = getCurrentVersionCode()
            val latestCode  = info.optInt("versionCode", 0)

            if (latestCode <= currentCode) {
                if (!silent) showNoUpdateDialog()
                return
            }

            // وجد تحديث — أظهر الـ dialog في Main thread
            withContext(Dispatchers.Main) {
                showUpdateDialog(info)
            }

        } catch (_: Exception) {
            // صمت — لا تزعج المستخدم إذا لم يكن هناك إنترنت
            if (!silent) {
                withContext(Dispatchers.Main) {
                    showErrorDialog("تعذّر الاتصال بالخادم للتحقق من التحديثات")
                }
            }
        }
    }

    // ============================================================
    //  قراءة ملف version.json من الرابط
    // ============================================================
    private suspend fun fetchVersionInfo(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val text = URL(VERSION_CHECK_URL).readText(Charsets.UTF_8)
            JSONObject(text)
        } catch (_: Exception) { null }
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            else
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (_: Exception) { 0 }
    }

    private fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) { "غير معروف" }
    }

    // ============================================================
    //  Dialog التحديث
    // ============================================================
    private fun showUpdateDialog(info: JSONObject) {
        val latestName  = info.optString("versionName", "جديدة")
        val apkUrl      = info.optString("apkUrl", "")
        val changeLog   = info.optString("changeLog", "تحسينات وإصلاح أخطاء")
        val isForced    = info.optBoolean("forced", false)

        val currentName = getCurrentVersionName()

        val builder = AlertDialog.Builder(context)
            .setTitle("🎉 تحديث جديد متاح!")
            .setMessage(
                "الإصدار الحالي: $currentName\n" +
                "الإصدار الجديد: $latestName\n\n" +
                "📋 ما الجديد:\n$changeLog\n\n" +
                "✅ بياناتك لن تُحذف عند التحديث"
            )
            .setPositiveButton("تحديث الآن") { _, _ ->
                if (apkUrl.isNotEmpty()) startDownload(apkUrl, latestName)
                else showErrorDialog("رابط التحديث غير متاح")
            }

        // إذا التحديث إجباري لا يُظهر زر التخطي
        if (!isForced) {
            builder.setNegativeButton("لاحقاً", null)
        }

        builder.setCancelable(!isForced)
        builder.show()
    }

    private fun showNoUpdateDialog() {
        AlertDialog.Builder(context)
            .setTitle("✅ التطبيق محدّث")
            .setMessage("أنت تستخدم آخر إصدار: ${getCurrentVersionName()}")
            .setPositiveButton("حسناً", null)
            .show()
    }

    private fun showErrorDialog(msg: String) {
        AlertDialog.Builder(context)
            .setTitle("⚠️ خطأ")
            .setMessage(msg)
            .setPositiveButton("حسناً", null)
            .show()
    }

    // ============================================================
    //  تحميل الـ APK وتثبيته
    // ============================================================
    private fun startDownload(apkUrl: String, versionName: String) {
        // حذف أي APK قديم
        val oldFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
        if (oldFile.exists()) oldFile.delete()

        // إظهار progress dialog
        val progressDialog = AlertDialog.Builder(context)
            .setTitle("⬇️ جاري التحميل...")
            .setMessage("يُحمَّل الإصدار $versionName\nيرجى الانتظار...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        // DownloadManager للتحميل في الخلفية
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("تحديث التطبيق - الإصدار $versionName")
            setDescription("جاري تحميل التحديث...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
            setMimeType("application/vnd.android.package-archive")
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        // استقبال إشعار اكتمال التحميل
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                context.unregisterReceiver(this)
                progressDialog.dismiss()

                // التحقق من نجاح التحميل
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        installApk()
                    } else {
                        showErrorDialog("فشل تحميل التحديث. تحقق من اتصالك بالإنترنت وحاول مرة أخرى.")
                    }
                }
                cursor.close()
            }
        }

        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun installApk() {
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
        if (!apkFile.exists()) {
            showErrorDialog("ملف التحديث غير موجود")
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7+ يحتاج FileProvider
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
            }
        }
        context.startActivity(intent)
    }
}
