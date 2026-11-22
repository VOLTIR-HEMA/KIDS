import { h, fmtDateTime, emptyState, errorBox } from "../util.js";

/**
 * قسم "مباشر": كاميرات أمامية/خلفية بمربعات تحديد، شاشة، مايك أحادي الاتجاه،
 * تسجيل لحظي، إرسال رسالة، اتصال، لقطة شاشة.
 * يعتمد على مجموعة live/{front,back,screen,mic} في Firestore.
 */
export function render(container, ctx) {
  container.innerHTML = "";
  const root = h("div", { class: "live-section" });
  container.append(root);

  const storeKey = `kids.live.${ctx.deviceId}`;
  const saved = readState(storeKey);

  // ---------- الكاميرات ----------
  root.append(sectionTitle("🎥", "الكاميرات", "حدّد المربع لتشغيل الكاميرا، وألغِه للإيقاف"));
  const cams = h("div", { class: "live-grid" });
  const front = cameraCard("front", "الكاميرا الأمامية", ctx, saved, storeKey);
  const back = cameraCard("back", "الكاميرا الخلفية", ctx, saved, storeKey);
  cams.append(front.card, back.card);
  root.append(cams);

  // ---------- الشاشة ----------
  root.append(sectionTitle("🖥️", "الشاشة", "تُعرض صورة محدثة كل 4 ثوانٍ بعد الموافقة على الجهاز مرة واحدة"));
  const screenImg = h("img", { class: "live-frame", alt: "الشاشة" });
  const screenInfo = h("div", { class: "muted small", text: "لا توجد لقطة بعد" });
  const screenCard = h("div", { class: "card live-card" }, [
    h("div", { class: "live-head" }, [h("span", { class: "live-dot" }), h("strong", { text: "شاشة الجهاز" })]),
    screenImg,
    screenInfo,
    h("div", { class: "actions-row" }, [
      actionButton("📸", "لقطة الآن", () => send("take_screenshot", {}, ctx)),
      actionButton("🔄", "فحص الحالة", () => send("screen_status", {}, ctx)),
    ]),
    h("div", { class: "muted small", text: "لبدء البث: افتح التطبيق على الجهاز ← «مشاركة الشاشة» ← الموافقة مرة واحدة." }),
  ]);
  root.append(screenCard);

  // ---------- المايك ----------
  root.append(sectionTitle("🎙️", "المايك", "استماع أحادي: تسمع ما حول الجهاز، والجهاز لا يسمعك"));
  const micBox = h("input", { type: "checkbox" });
  micBox.checked = !!saved.mic;
  const micStatus = h("div", { class: "muted small", text: micBox.checked ? "المايك مفتوح" : "المايك مغلق" });
  const audio = h("audio", { controls: true, class: "audio-player" });
  const micList = h("div", { class: "audio-queue" });
  const micCard = h("div", { class: "card live-card" }, [
    h("label", { class: "live-check" }, [micBox, h("strong", { text: "فتح مايك الجهاز (استماع فقط)" })]),
    micStatus,
    audio,
    h("div", { class: "muted small", text: "يُشغّل المقاطع تلقائياً بالترتيب (مقطع كل 10 ثوانٍ)." }),
    h("div", { class: "actions-row" }, [
      actionButton("⏺️", "تسجيل لحظي (30 ث)", () => send("record_mic", {}, ctx)),
    ]),
    micList,
  ]);
  root.append(micCard);

  micBox.addEventListener("change", () => {
    saved.mic = micBox.checked;
    writeState(storeKey, saved);
    micStatus.textContent = micBox.checked ? "جارٍ فتح المايك…" : "جارٍ الإغلاق…";
    send(micBox.checked ? "mic_start" : "mic_stop", {}, ctx);
  });

  // ---------- أوامر سريعة ----------
  root.append(sectionTitle("⚡", "أوامر سريعة"));
  const actions = h("div", { class: "actions-row" }, [
    actionButton("✉️", "إرسال رسالة", () => promptCommand(ctx, {
      title: "إرسال رسالة",
      fields: [
        { name: "number", label: "رقم المستلم", placeholder: "05xxxxxxxx", required: true },
        { name: "message", label: "نص الرسالة", required: true },
      ],
      type: "send_message",
      okText: "إرسال",
    })),
    actionButton("📞", "إجراء مكالمة", () => promptCommand(ctx, {
      title: "إجراء مكالمة",
      fields: [{ name: "number", label: "رقم الهاتف", placeholder: "05xxxxxxxx", required: true }],
      type: "make_call",
      okText: "اتصال",
    })),
    actionButton("📷", "صورة من الجهاز", () => send("take_photo", {}, ctx)),
    actionButton("🔔", "رنين الجهاز", () => send("ring", {}, ctx)),
    actionButton("🔒", "قفل الجهاز", () => send("lock", {}, ctx)),
    actionButton("🔄", "مزامنة الآن", () => send("sync_now", {}, ctx)),
  ]);
  root.append(actions);

  // ---------- اشتراك live ----------
  const unsubLive = ctx.data.subscribeLive(ctx.uid, ctx.deviceId, (data, err) => {
    if (err) {
      screenInfo.textContent = "تعذر قراءة بيانات البث (تحقق من الصلاحيات)";
      return;
    }
    const live = data || {};
    applyCamera(front, live.front);
    applyCamera(back, live.back);
    applyScreen(live.screen);
    applyMic(live.mic);
  });

  // ---------- تسجيلات المايك المحفوظة ----------
  const unsubMedia = ctx.data.subscribeCollection(ctx.uid, ctx.deviceId, "media", (items, err) => {
    micList.innerHTML = "";
    if (err || !items) return;
    const clips = items
      .filter((m) => m && (m.mic === true || m.recording === "saved") && (m.url || m.dataUrl))
      .slice(0, 8);
    if (!clips.length) {
      micList.append(h("div", { class: "muted small", text: "لا توجد تسجيلات محفوظة بعد." }));
      return;
    }
    for (const clip of clips) {
      micList.append(h("div", { class: "clip-row" }, [
        h("div", { class: "clip-name", text: clip.name || "تسجيل" }),
        h("audio", { controls: true, preload: "none", src: clip.url || clip.dataUrl }),
        h("div", { class: "muted small", text: fmtDateTime(clip.timestamp) }),
      ]));
    }
  });

  // ---------- طابور تشغيل المايك ----------
  const queue = [];
  let currentSrc = "";
  let playing = false;
  let lastMicTs = 0;

  function applyMic(mic) {
    if (!mic) return;
    const ts = Number(mic.timestamp) || 0;
    if (ts <= lastMicTs) return;
    lastMicTs = ts;
    const src = mic.url || mic.dataUrl;
    if (!src) {
      if (mic.error) micStatus.textContent = "خطأ: " + mic.error;
      return;
    }
    queue.push(src);
    if (!playing) playNext();
  }

  function playNext() {
    const src = queue.shift();
    if (!src) {
      playing = false;
      audio.pause();
      return;
    }
    playing = true;
    currentSrc = src;
    audio.src = src;
    audio.play().catch(() => {
      micStatus.textContent = "اضغط تشغيل على المشغّل لبدء الصوت";
      playing = false;
    });
  }

  audio.addEventListener("ended", () => { playing = false; playNext(); });
  audio.addEventListener("error", () => { playing = false; playNext(); });

  function applyScreen(screen) {
    if (!screen) return;
    const src = screen.url || screen.dataUrl;
    if (src && src !== screenImg.src) screenImg.src = src;
    screenImg.style.display = src ? "block" : "none";
    if (screen.error) {
      screenInfo.textContent = "خطأ: " + screen.error;
    } else if (src) {
      screenInfo.textContent = "آخر تحديث: " + fmtDateTime(screen.timestamp);
    }
  }

  return () => {
    try { unsubLive(); } catch { /* تجاهل */ }
    try { unsubMedia(); } catch { /* تجاهل */ }
    try { audio.pause(); } catch { /* تجاهل */ }
  };
}

// ---------- مساعدات ----------

function sectionTitle(icon, title, hint) {
  return h("div", { class: "section-head" }, [
    h("span", { class: "section-icon", text: icon }),
    h("div", {}, [
      h("h3", { class: "panel-title", text: title }),
      hint ? h("div", { class: "muted small", text: hint }) : null,
    ]),
  ]);
}

function cameraCard(kind, label, ctx, saved, storeKey) {
  const box = h("input", { type: "checkbox" });
  box.checked = !!saved[kind];
  const img = h("img", { class: "live-frame", alt: label });
  img.style.display = "none";
  const info = h("div", { class: "muted small", text: box.checked ? "جارٍ التشغيل…" : "الكاميرا متوقفة" });
  const card = h("div", { class: "card live-card" }, [
    h("label", { class: "live-check" }, [box, h("strong", { text: label })]),
    img,
    info,
  ]);
  box.addEventListener("change", () => {
    saved[kind] = box.checked;
    writeState(storeKey, saved);
    info.textContent = box.checked ? "جارٍ التشغيل…" : "جارٍ الإيقاف…";
    send(box.checked ? "camera_start" : "camera_stop", { facing: kind }, ctx);
  });
  return {
    card,
    setFrame(data) {
      if (!data) return;
      const src = data.url || data.dataUrl;
      if (src && src !== img.src) img.src = src;
      img.style.display = src ? "block" : "none";
      if (data.error) info.textContent = "خطأ: " + data.error;
      else if (src) info.textContent = "آخر صورة: " + fmtDateTime(data.timestamp);
    },
  };
}

function applyCamera(camCard, data) {
  camCard.setFrame(data);
}

function actionButton(icon, label, onClick) {
  const btn = h("button", { class: "action-btn", type: "button" }, [
    h("span", { text: icon }),
    h("span", { text: label }),
  ]);
  btn.addEventListener("click", onClick);
  return btn;
}

async function promptCommand(ctx, { title, fields, type, okText }) {
  const values = await ctx.modal({ title, fields, okText });
  if (!values) return;
  send(type, values, ctx);
}

function send(type, payload, ctx) {
  ctx.data
    .sendCommand(ctx.uid, ctx.deviceId, type, payload)
    .then(() => ctx.toast("تم إرسال الأمر"))
    .catch(() => ctx.toast("تعذر إرسال الأمر", "error"));
}

function readState(key) {
  try { return JSON.parse(localStorage.getItem(key) || "{}") || {}; } catch { return {}; }
}

function writeState(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* تجاهل */ }
}
