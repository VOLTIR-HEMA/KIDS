import { h, timeAgo, fmtBytes, emptyState, errorBox } from "../util.js";

const ICONS = {
  status: "📶",
  battery: "🔋",
  device: "📱",
  network: "🌐",
  storage: "💾",
  location: "📍",
};

function batteryLabel(b) {
  if (b < 20) return "البطارية منخفضة";
  if (b < 50) return "متوسطة";
  return "جيدة";
}

function statCard(kind, title) {
  const el = h("div", { class: "stat-card" });
  const head = h("div", { class: "stat-head" }, [
    h("span", { class: "stat-icon", text: ICONS[kind] }),
    h("span", { class: "stat-title", text: title }),
  ]);
  const valueEl = h("div", { class: "stat-value", text: "—" });
  const subEl = h("div", { class: "stat-sub", text: "" });
  const barWrap = h("div", { class: "bar hidden" });
  const fill = h("div", { class: "bar-fill" });
  barWrap.append(fill);
  el.append(head, valueEl, subEl, barWrap);

  let dotEl = null;
  return {
    el,
    setValue(v, cls) {
      valueEl.textContent = v;
      valueEl.className = "stat-value" + (cls ? " " + cls : "");
    },
    setSub(s) { subEl.textContent = s; },
    bar(pct, cls) {
      if (pct === null || pct === undefined) return;
      barWrap.classList.remove("hidden");
      fill.style.width = Math.max(0, Math.min(100, pct)) + "%";
      fill.className = "bar-fill" + (cls ? " " + cls : "");
    },
    dot(cls) {
      if (!dotEl) {
        dotEl = h("span", { class: "dot" });
        head.prepend(dotEl);
      }
      dotEl.className = "dot " + cls;
    },
  };
}

export function render(container, ctx) {
  container.innerHTML = "";
  const root = h("div", { class: "overview" });
  const errBox = h("div", { class: "section-note hidden" });
  const grid = h("div", { class: "stat-grid" });
  const quick = h("div", { class: "quick-actions card" }, [
    h("h3", { class: "panel-title", text: "إجراءات سريعة" }),
  ]);
  const actionsRow = h("div", { class: "actions-row" });
  quick.append(actionsRow);
  root.append(errBox, grid, quick);
  container.append(root);

  const cards = {
    status: statCard("status", "حالة الاتصال"),
    battery: statCard("battery", "البطارية"),
    device: statCard("device", "الجهاز"),
    network: statCard("network", "الشبكة"),
    storage: statCard("storage", "التخزين"),
    location: statCard("location", "آخر موقع"),
  };
  grid.append(...Object.values(cards).map((c) => c.el));

  const actions = [
    { icon: "📍", label: "تحديث الموقع", cmd: "get_location" },
    { icon: "✉️", label: "إرسال رسالة", cmd: "send_message", fields: true },
    { icon: "📷", label: "التقاط صورة", cmd: "take_photo" },
    { icon: "🔔", label: "رنين الجهاز", cmd: "ring" },
    { icon: "🔒", label: "قفل الجهاز", cmd: "lock" },
  ];
  for (const a of actions) {
    const btn = h("button", { class: "action-btn", type: "button" }, [
      h("span", { class: "action-icon", text: a.icon }),
      h("span", { text: a.label }),
    ]);
    btn.addEventListener("click", () => runCommand(a.cmd, a.fields));
    actionsRow.append(btn);
  }

  function runCommand(type, needsFields) {
    if (needsFields) {
      ctx.modal({
        title: "إرسال رسالة",
        message: "أدخل رقم المستلم ونص الرسالة",
        fields: [
          { name: "number", label: "الرقم", placeholder: "05xxxxxxxx", required: true },
          { name: "message", label: "نص الرسالة", placeholder: "اكتب الرسالة هنا", required: true },
        ],
        okText: "إرسال",
      }).then((res) => { if (res) send(type, res); });
      return;
    }
    send(type, {});
  }

  function send(type, payload) {
    ctx.data
      .sendCommand(ctx.uid, ctx.deviceId, type, payload)
      .then(() => ctx.toast("تم إرسال الأمر إلى الجهاز"))
      .catch(() => ctx.toast("تعذر إرسال الأمر", "error"));
  }

  function update(dev) {
    const online = !!dev.online;
    cards.status.setValue(online ? "متصل" : "غير متصل", online ? "ok" : "bad");
    cards.status.setSub(dev.lastSeen ? `آخر ظهور: ${timeAgo(dev.lastSeen)}` : "لا توجد بيانات مزامنة");
    cards.status.dot(online ? "ok" : "bad");

    cards.battery.setValue(`${Math.round(dev.battery)}٪`, dev.battery < 20 ? "bad" : "");
    cards.battery.setSub(dev.charging ? "يشحن الآن" : batteryLabel(dev.battery));
    cards.battery.bar(dev.battery, dev.charging ? "charging" : dev.battery < 20 ? "low" : "");

    cards.device.setValue(dev.name, "");
    cards.device.setSub(`${dev.model}${dev.version ? " · " + dev.version : ""} · ${dev.platform}`);

    cards.network.setValue(dev.connection.type || "غير متصلة", "");
    cards.network.setSub(dev.connection.ssid || "—");

    const st = dev.storage;
    cards.storage.setValue(st.total ? `${fmtBytes(st.used)} / ${fmtBytes(st.total)}` : "—", "");
    cards.storage.setSub(st.percent !== null && st.percent !== undefined ? `مستخدم ${st.percent}٪` : "لا توجد بيانات");
    cards.storage.bar(st.percent, st.percent > 85 ? "low" : "");

    const loc = dev.location;
    cards.location.setValue(loc ? `${loc.lat.toFixed(4)}، ${loc.lng.toFixed(4)}` : "غير متوفر", "");
    cards.location.setSub(loc?.timestamp ? timeAgo(loc.timestamp) : "—");
  }

  const unsub = ctx.data.subscribeDevice(ctx.uid, ctx.deviceId, (dev, err) => {
    if (err) {
      errBox.classList.remove("hidden");
      errBox.innerHTML = "";
      errBox.append(errorBox("تعذر تحميل بيانات الجهاز — تحقق من قواعد أمان Firestore أو الاتصال بالشبكة."));
      return;
    }
    errBox.classList.add("hidden");
    if (!dev) {
      root.innerHTML = "";
      root.append(emptyState("📵", "لا يوجد جهاز محدد", "اختر جهازاً من القائمة العلوية، أو فعّل الوضع التجريبي لاستعراض الواجهة."));
      return;
    }
    update(dev);
  });

  return () => {
    try { unsub(); } catch { /* تجاهل */ }
  };
}
