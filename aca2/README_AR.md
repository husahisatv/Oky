# Android Control Agent — التطبيق + الجسر

## ما الذي بُني
- `bridge/aca_bridge.py`: جسر HTTP على `127.0.0.1:8787` داخل Termux. **لا يعدّل المحرك**، ويستدعي فقط
  `run_skill <اسم>` و`execute_intent "<نص>"` (بلا shell، الأسماء مُتحقَّق منها).
- `app/`: تطبيق Kotlin + Jetpack Compose (5 شاشات: الدردشة، لوحة التحكم، المهارات والسجل، مزودو الذكاء، الإعدادات).
- لا يستخدم Accessibility، ولا يستبدل آلية rish/Shizuku. التحكم يبقى في المحرك.

## التثبيت
### 1) في Termux (المحرك)
```bash
cd ~ && unzip /sdcard/Download/aca2.zip && cd aca2/bridge
bash start_bridge.sh          # يطبع التوكن
```
شرط: `~/.android_skills/run_skill` موجود، و`~/.android_control.sh` و`~/.rish/rish` سليمان.
تحقق: `curl -s -H "Authorization: Bearer $(cat ~/.android_memory/bridge_token)" http://127.0.0.1:8787/health`

### 2) بناء الـ APK (GitHub Actions)
ارفع **محتويات** مجلد `app/` إلى جذر المستودع (يحتوي `app` و`build.gradle.kts` و`settings.gradle.kts` و`.github`)،
ثم Actions ← Build APK ← Run workflow ← حمّل `AndroidControlAgent-debug`.

### 3) إعداد التطبيق
1. Settings ← الصق توكن الجسر ← **Save token and test**.
2. تبويب AI ← اختر Gemini أو OpenRouter ← الصق مفتاحك ← **Save key** ← **Test connection** ← اختر نموذجاً.
   المفاتيح تُخزَّن مشفّرة (Android Keystore) ولا تُضمَّن في الـ APK.
3. Dashboard ← **Probe gateway + foreground** لقياس صحة Shizuku.

## التشغيل الكامل بأمر واحد
1. **فتح التطبيق تلقائياً:** قبل كل مهارة يتحقق الجسر من التطبيق الأمامي ويفتح التطبيق (من `APP/ACTIVITY` في `skill.conf`)
   إن لم يكن مفتوحاً، ويسجّل الدليل في المخرجات: `APP_LAUNCHED` أو `APP_ALREADY_FOREGROUND` أو `APP_LAUNCH_SKIPPED`.
   (المحرك نفسه يفعل ذلك أيضاً عبر `am start`، وهذا ضمان إضافي بدليل.)
2. **أوامر لا تغطيها مهارة مسجّلة:** وضع اختياري يستدعي Hermes (`hermes chat -q ... -Q --max-turns 25`) ليكتشف ويكمل.
   لتفعيله: `bash start_bridge.sh --agent` + مفتاح **Hermes fallback** في Settings.
   تحذير: هذا وكيل LLM بصلاحية terminal، لذلك هو معطّل افتراضياً، ويطلب موافقتك في كل مرة، ولا يُعاد تلقائياً،
   وتُعدّ نتيجته `agent_reported` (ادعاء الوكيل) وليست `verified`.

## الصلاحيات
- `INTERNET`: (أ) الاتصال بالجسر على loopback، (ب) استدعاء Gemini/OpenRouter عبر HTTPS.
  نص HTTP الصريح مسموح لـ `127.0.0.1` فقط (`network_security_config.xml`).

## عقد الجسر (كلها تتطلب `Authorization: Bearer <token>`)
| الطريقة | المسار | الوظيفة |
|---|---|---|
| GET | `/health[?deep=1]` | وجود المحرك/rish، وبـ deep: التطبيق الأمامي وصحة البوابة |
| GET | `/skills` | المهارات المكتشفة (runnable / needs_confirmation / evidence) |
| POST | `/tasks` | `{"skill":"..."}` أو `{"intent":"..."}` + `timeout` + `confirmed` ← `202 {id}` |
| GET | `/tasks/{id}` | الحالة والدليل وآخر 120 سطراً من مخرجات المحرك |
| POST | `/tasks/{id}/cancel` | إيقاف المهمة (SIGTERM ثم SIGKILL) |
| GET | `/tasks` | آخر 30 مهمة |
الأكواد: 401 توكن، 400 مدخل غير صالح، 404 مهارة غير موجودة/غير قابلة للتشغيل، 409 المحرك مشغول،
428 يلزم `confirmed:true`، 503 المحرك غير موجود.

## قواعد النجاح (لا ادعاء بلا دليل)
- `success/verified`: المحرك طبع `SUCCESS` أو `ALREADY_THERE` (تحقق أن العنصر `selected=true`).
- `success/executed_unverified`: مهارات `multi_step` — النقرات تمت لكن المحرك لا يتحقق من الحالة النهائية.
  يستمر التطبيق بها افتراضياً (استثناء موثّق) ويمكنك إيقاف ذلك من Settings.
- exit 0 بلا سطر نجاح صريح ← يُعدّ `failed (NO_SUCCESS_EVIDENCE)`.
- الإجراءات الحساسة (like/save/comment/post/share/follow/delete/send/story/upload) تتطلب تأكيدك،
  ولا تُعاد تلقائياً أبداً (غير idempotent). إعادة المحاولة محدودة بـ Retry limit للخطوات غير الحساسة فقط،
  وقبلها يجمع التطبيق دليلاً جديداً (التطبيق الأمامي وحالة البوابة).

## تقرير الاختبار (فعلي)
**اختُبر وانتجح (جسر Python، محرك وهمي في بيئة معزولة، 16 سيناريو):**
بلا توكن/توكن خاطئ → 401 · حقن في اسم المهارة → 400 · مهارة مجهولة/`REPLAY_VALID=no` → 404 · مهارة حساسة بلا تأكيد → 428 ·
نجاح موثّق `verified` · multi-step `executed_unverified` · فشل `VERIFICATION_FAILED` (exit 20) ·
exit 0 بلا دليل → failed · محرك مشغول → 409 · إلغاء → `cancelled` · مهلة 10 ث → `timeout` ·
محرك مفقود → 503 · صلاحيات ملف التوكن 600 · التوكن غير موجود في السجلات.

**اختُبر أيضاً (rish وhermes وهميان):** فتح التطبيق عند غيابه، وعدم إعادة فتحه إن كان أمامياً، وضع الوكيل معطّل افتراضياً (403)،
يتطلب تأكيداً (428)، نجاحه يُصنَّف `agent_reported`، ومدخلات غير صالحة (400)، وغياب hermes (503).

**لم يُختبر (لا يمكنني ذلك هنا):**
- استدعاء `hermes chat -q` الحقيقي (الصيغة مأخوذة من مرجع Hermes داخل الأرشيف، لا من تشغيل فعلي).
- ترجمة تطبيق Kotlin وبناء APK: لا SDK ولا إنترنت. فُحصت فقط صحة XML وتوازن الأقواس.
  احتمال وجود أخطاء ترجمة صغيرة قائم، أرسل لي سجل GitHub Actions إن ظهرت.
- واجهة Compose، حوار التأكيد، مزودو Gemini/OpenRouter (واجهاتهم REST مكتوبة من المعرفة العامة ولم تُستدعَ).
- الأتمتة الفعلية على جهاز، وعمل Shizuku/rish.

## حدود معروفة
1. **اكتشاف عناصر جديدة** (`discovery_*`) يحتاج وكيل Hermes (LLM) ولا يعمل عبر الجسر. التطبيق ينفّذ المهارات المسجّلة فقط،
   وأي عبارة لا يفهمها `execute_intent` تعود `NO_EXECUTION_PATH` (فشل صريح).
2. كل مهارات المحرك الحالية لـ Instagram. المعمارية عامة (`app` في كل `skill.conf`) لكن لا توجد مهارات لتطبيقات أخرى بعد.
3. `instagram.like_visible_reel` و`save_visible_reel` سكربتان مستقلان ليس لهما مدخل في `run_skill`، فتظهر «غير قابلة للتشغيل».
4. الأرشيف الأصلي لا يحوي `~/.android_control.sh` ولا `~/.rish`؛ لم أتحقق من صحتهما.
5. Android قد يقتل Termux في الخلفية: استخدم `termux-wake-lock` (يفعّله السكربت) واستثنِ Termux من توفير الطاقة.
6. المهام تعمل فقط والتطبيق مفتوح (حسب المتطلبات)، ولا توجد خدمة خلفية.
7. بوابة Shizuku متقطعة (مذكورة في ملاحظات المشروع): الفشل يظهر كما هو في Dashboard ولا يُخفى.
