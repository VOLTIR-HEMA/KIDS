# KIDS

مشروع متابعة الأجهزة يتكون من:

- `dashboard/` — لوحة تحكم ويب (Firebase Auth + Firestore) لإدارة الأجهزة ومعاينة الحالة والموقع والاتصالات والوسائط وأوامر التحكم.
- `android-app/` — تطبيق أندرويد (Kotlin) يربط الجهاز بالحساب ويرسل الحالة ويستقبل الأوامر.
- `.github/workflows/build-apk.yml` — بناء الـ APK ورفعه كـ Artifact.

راجع `android-app/README.md` و `dashboard/README.md`.
