# 🤖 ملف سياق المشروع - ThermalPrinter
# ارفع هذا الملف لـ Claude في بداية أي محادثة جديدة

## 📦 معلومات المشروع
- **اسم التطبيق:** طابعة حرارية
- **Package Name:** com.mustfa.printer
- **اللغة:** Kotlin
- **minSdk:** 23 (Android 6.0+)
- **targetSdk:** 34

## 📚 المكتبات المثبتة
- `ESCPOS-ThermalPrinter-Android:3.3.0` — طباعة حرارية
- `zxing:core:3.5.2` — باركود
- `zxing-android-embedded:4.3.0` — باركود
- `kotlinx-coroutines-android:1.7.3` — عمليات متزامنة

## ✅ الميزات المنجزة
- [x] اتصال بلوتوث بكل الطابعات
- [x] طباعة نصوص عربية وإنجليزية
- [x] محاذاة يمين / وسط / يسار
- [x] تكبير وتصغير الخط (5 أحجام)
- [x] غامق وتسطير
- [x] طباعة باركود (CODE128, CODE39, EAN13, QR, UPC)
- [x] طباعة صور من الجهاز
- [x] طباعة فاتورة كاملة مع QR
- [x] صفحة اختبار
- [x] قطع الورق
- [x] إعدادات عرض الطابعة 58mm / 80mm
- [x] اتصال تلقائي بآخر طابعة عند فتح التطبيق (SharedPreferences: last_device_address / last_device_name)
- [x] اتصال WiFi TCP/IP (IP + Port) للطابعات الشبكية
- [x] اتصال WiFi Direct (P2P) للطابعات التي تدعمه
- [x] حفظ آخر اتصال (بلوتوث أو WiFi) والاتصال التلقائي

## ❌ الميزات المطلوبة (اكتب هنا ما تريد إضافته)
- [ ] ...
- [ ] ...

## 📁 ملفات المشروع
```
ThermalPrinter/
├── app/src/main/
│   ├── java/com/mustfa/printer/
│   │   ├── MainActivity.kt
│   │   └── PrinterSettingsActivity.kt
│   ├── res/layout/
│   │   ├── activity_main.xml
│   │   └── activity_settings.xml
│   ├── res/values/
│   │   ├── strings.xml
│   │   └── themes.xml
│   └── AndroidManifest.xml
├── app/build.gradle
├── build.gradle
└── settings.gradle
```

## 🐛 مشاكل / ملاحظات
- اكتب هنا أي خطأ واجهته
- أو أي ملاحظة على التطبيق

## 💬 كيفية الاستخدام
1. ارفع هذا الملف في بداية المحادثة الجديدة
2. اكتب: "أكمل معي تطوير التطبيق"
3. Claude سيفهم كل شيء ويكمل معك فوراً
