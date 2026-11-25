# VOLTIR CALL

تطبيق أندرويد (Kotlin) للمكالمات الصوتية/المرئية والدردشة، يعمل على نفس مشروع Firebase الخاص بـ KIDS.

## التشغيل
1. من Firebase Console فعّل **Authentication ← Anonymous**.
2. الصق محتوى `firestore.volti.rules` في **Firestore ← Rules** و`storage.volti.rules` في **Storage ← Rules**.
3. ابنِ APK عبر GitHub Actions: workflow `Build VOLTIR CALL APK` (artifact: `voltir-call-debug`).

## الميزات
- 4 تبويبات: الدردشات، التحديثات، المجتمعات، المكالمات.
- حالات دائرية بنقطة خضراء للمتصلين، علامات ✓✓ وعدّاد غير مقروء.
- دردشة: صور، مستندات، صوت، موقع، جهة اتصال، اختفاء ذاتي، قفل دردشة.
- مكالمات WebRTC عبر Firestore (offer/answer/ICE) مع كتم، تبديل كاميرا، إنهاء.
- **عداد إنهاء تلقائي**: 1/3/5/10 دقائق أو مدة مخصصة، اهتزاز وشريط عدّ تنازلي للطرفين ثم إغلاق تلقائي.
- خدمة خلفية `NotifyService` لإشعارات الرسائل والمكالمات + خوادم TURN/STUN مخصصة من الإعدادات.
