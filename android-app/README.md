# تطبيق  kids monitor (أندرويد)

تطبيق Kotlin كامل يربط الجهاز بحساب Firebase ويظهر في لوحة التحكم `dashboard/`.

## المزايا

- تسجيل دخول / إنشاء حساب عبر **Firebase Authentication (Email/Password)**.
- ربط الجهاز تلقائياً بحساب المستخدم: `users/{uid}/devices/{deviceId}` مع الاسم والطراز والبطارية والشحن والاتصال والتخزين وآخر موقع.
- شاشة أذونات واضحة: الإشعارات، الموقع، سجل المكالمات، الرسائل، جهات الاتصال، الصور/الملفات، الكاميرا، قراءة الإشعارات (NotificationListener)، إمكانية الوصول، مسؤول الجهاز (Device Admin).
- خدمة **Foreground** بإشعار دائم يوضح حالة المزامنة وزر إيقاف.
- حفظ محلي عند انقطاع الإنترنت (تخزين Firestore المؤقت + قائمة انتظار محلية) ورفع تلقائي عند عودة الاتصال.
- استقبال أوامر التحكم من لوحة التحكم وتنفيذها: `get_location`, `ring`, `lock`, `clear_notifications`, `send_message`, `take_photo`, `factory_reset`, `sync_now`.
- مشاركة الشاشة عبر **MediaProjection** (لقطات دورية تُرفع إلى Storage وتظهر في لوحة التحكم).

## البيانات في Firestore

```
users/{uid}/devices/{deviceId}                حالة الجهاز
users/{uid}/devices/{deviceId}/locations      سجل المواقع
users/{uid}/devices/{deviceId}/calls          المكالمات
users/{uid}/devices/{deviceId}/messages       الرسائل
users/{uid}/devices/{deviceId}/notifications  الإشعارات
users/{uid}/devices/{deviceId}/contacts       جهات الاتصال
users/{uid}/devices/{deviceId}/media          الصور والملفات
users/{uid}/devices/{deviceId}/commands       الأوامر الواردة وحالتها
```

الصور الملتقطة عبر الأمر ولقطات الشاشة تُرفع إلى `media/{uid}/...` في Firebase Storage ويُخزَّن رابطها في `media`.

## البناء المحلي

المتطلبات: JDK 17 و Android SDK (platform 35 و build-tools 35.0.0).

```sh
cd android-app
./gradlew assembleDebug
```

الناتج: `app/build/outputs/apk/debug/app-debug.apk`.

سير العمل `.github/workflows/build-apk.yml` ينفّذ البناء ويرفع الـ APK كـ Artifact باسم `kids-app-debug`.

## ملاحظات

- فعّل **Email/Password** في Firebase Authentication و **Cloud Firestore** و **Storage**.
- قواعد مقترحة: يسمح للمستخدم بالقراءة والكتابة فقط داخل `users/{uid}` و `media/{uid}`.
- صلاحيات قراءة الإشعارات وإمكانية الوصول ومسؤول الجهاز تُفعَّل من إعدادات النظام (لا يمكن منحها برمجياً).
- بيانات الوسائط في لوحة التحكم تعرض الاسم والنوع والحجم والتاريخ؛ الصور  taken عبر الأمر لها رابط مباشر.
