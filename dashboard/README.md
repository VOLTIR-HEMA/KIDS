# لوحة تحكم كيدز (Web Dashboard)

لوحة تحكم ويب مستقلة بدون خطوة بناء، تعمل مباشرة عبر خادم ملفات بسيط.

## التشغيل المحلي

```powershell
cd C:\Users\VOLTIR\Desktop\KIDS\dashboard
python -m http.server 8000
```

ثم افتح المتصفح على: http://localhost:8000

## المتطلبات في Firebase

- تفعيل **Authentication → Email/Password**.
- تفعيل **Cloud Firestore** (الوضع Test أو بقواعد تسمح للمستخدم بالقراءة/الكتابة على بياناته).

## هيكل البيانات المتوقع في Firestore

```
users/{uid}/devices/{deviceId}          -> وثيقة الجهاز (الاسم، الطراز، البطارية، الاتصال، آخر موقع)
users/{uid}/devices/{deviceId}/locations        -> سجل المواقع (lat, lng, accuracy, timestamp)
users/{uid}/devices/{deviceId}/calls            -> المكالمات (name, number, type, duration, timestamp)
users/{uid}/devices/{deviceId}/messages         -> الرسائل (name, number, body, type, timestamp)
users/{uid}/devices/{deviceId}/notifications    -> الإشعارات (app, title, body, timestamp)
users/{uid}/devices/{deviceId}/contacts         -> جهات الاتصال (name, number)
users/{uid}/devices/{deviceId}/media            -> الصور/الملفات (name, url, type, size, timestamp)
users/{uid}/devices/{deviceId}/commands         -> الأوامر المرسلة (type, payload, status, createdAt)
```

بديل مقبول للأجهزة: مجموعة علوية `devices/{deviceId}` مع الحقل `ownerId`.

## ميزات

- تسجيل دخول / إنشاء حساب عبر Email/Password.
- نظرة عامة: حالة الاتصال، البطارية، الشبكة، التخزين، آخر موقع.
- الموقع: خريطة Leaflet + سجل المواقع مع إظهار المسار.
- المكالمات والرسائل والإشعارات وجهات الاتصال مع بحث.
- الوسائط وأوامر التحكم عن بُعد مع حالة تنفيذ الأوامر.
- عرض مدمج: اختيار أي أقسام معاً في شبكة واحدة.
- وضع تجريبي (من الشريط العلوي) لاستعراض الواجهة ببيانات وهمية دون بيانات حقيقية.
