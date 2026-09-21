# كيف تنشر التحديث

## 1. رفع ملف version.json

ارفع هذا الملف على GitHub أو سيرفرك:

```json
{
  "versionCode": 2,
  "versionName": "1.1",
  "apkUrl": "https://YOUR_SERVER/ThermalPrinter_v1.1.apk",
  "changeLog": "- إصلاح مشاكل الأداء\n- ميزة جديدة",
  "forced": false
}
```

## 2. تعديل الرابط في الكود

افتح UpdateManager.kt وعدّل هذا السطر:

```kotlin
const val VERSION_CHECK_URL = "https://raw.githubusercontent.com/اسمك/المشروع/main/version.json"
```

## 3. عند إصدار تحديث جديد — 3 خطوات فقط:

1. زِد versionCode في build.gradle (مثلاً من 1 إلى 2)
2. ابنِ APK جديد (Build → Generate Signed APK)
3. ارفع APK على سيرفرك وحدّث version.json

## GitHub مجاناً:

1. أنشئ repository باسم "printer-app-updates"
2. ارفع version.json
3. الرابط سيكون:
   https://raw.githubusercontent.com/اسمك/printer-app-updates/main/version.json

## التحديث الإجباري:

لإجبار المستخدم على التحديث بدون زر "لاحقاً":
```json
"forced": true
```
