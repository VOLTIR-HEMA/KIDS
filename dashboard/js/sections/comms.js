import { h, timeAgo, fmtDateTime, fmtDuration, emptyState } from "../util.js";

const TABS = [
  { id: "calls", title: "المكالمات", icon: "📞" },
  { id: "messages", title: "الرسائل", icon: "💬" },
  { id: "notifications", title: "الإشعارات", icon: "🔔" },
  { id: "contacts", title: "جهات الاتصال", icon: "👤" },
];

function row(icon, title, sub, meta, ts) {
  return h("div", { class: "list-row" }, [
    h("div", { class: "row-icon", text: icon }),
    h("div", { class: "row-body" }, [
      h("div", { class: "row-title", text: title || "—" }),
      sub ? h("div", { class: "row-sub", text: sub }) : null,
      meta ? h("div", { class: "row-meta muted", text: meta }) : null,
    ]),
    ts ? h("div", { class: "row-time muted", text: timeAgo(ts) }) : null,
  ]);
}

function callRow(it) {
  const raw = String(it.type ?? it.callType ?? it.direction ?? "").toLowerCase();
  const dir = raw.includes("out") ? "out" : raw.includes("miss") ? "miss" : raw.includes("reject") ? "miss" : "in";
  const icon = dir === "out" ? "↗️" : dir === "miss" ? "✖️" : "↙️";
  const label = dir === "out" ? "صادرة" : dir === "miss" ? "فائتة" : "واردة";
  const name = it.name || it.contactName || it.number || "رقم غير معروف";
  const dur = it.duration ? fmtDuration(it.duration) : "";
  const rec = it.recording === "failed" ? "التسجيل غير متاح" : it.recording === "saved" ? "مسجلة" : "";
  return row(icon, name, it.number || "", [label, dur, rec, fmtDateTime(it.timestamp)].filter(Boolean).join(" · "), it.timestamp);
}

function messageRow(it) {
  const sent = String(it.type ?? "").toLowerCase() === "sent" || it.sent === true;
  const name = it.name || it.number || "رقم غير معروف";
  const body = it.body || it.text || it.message || "";
  const node = row("💬", name, body, sent ? "مرسلة" : "واردة", it.timestamp);
  node.classList.add("clickable");
  node.title = "اضغط لعرض الرسالة كاملة";
  node.addEventListener("click", () => openFull(name, body, it.timestamp));
  return node;
}

function notifRow(it) {
  return row("🔔", it.title || it.app || "إشعار", it.body || it.text || "", it.app || "", it.timestamp);
}

function contactRow(it) {
  return row("👤", it.name || "جهة اتصال", it.number || "", "جهة اتصال", null);
}

function openFull(title, body, ts) {
  const root = document.getElementById("modalRoot");
  const overlay = h("div", { class: "modal-overlay show" });
  const card = h("div", { class: "modal-card", role: "dialog" });
  card.append(h("h3", { text: title }));
  card.append(h("div", { class: "full-message", text: body }));
  card.append(h("div", { class: "muted", text: fmtDateTime(ts) }));
  const close = h("button", { class: "btn primary", type: "button", text: "إغلاق" });
  close.addEventListener("click", () => overlay.remove());
  card.append(h("div", { class: "modal-actions" }, [close]));
  overlay.append(card);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) overlay.remove(); });
  root.append(overlay);
}

export function render(container, ctx) {
  container.innerHTML = "";
  const fixed = ctx.filter || null;
  const root = h("div", { class: "comms" });
  const tabsBar = h("div", { class: "tabs" });
  const search = h("input", { class: "input search", type: "search", placeholder: "بحث في القائمة..." });
  const list = h("div", { class: "list" });
  root.append(tabsBar, h("div", { class: "comms-toolbar" }, [search]), list);
  container.append(root);

  const tabs = fixed ? TABS.filter((t) => t.id === fixed) : TABS;
  const state = {
    tab: fixed || "calls",
    q: "",
    data: { calls: [], messages: [], notifications: [], contacts: [] },
  };
  const tabBtns = {};

  for (const t of tabs) {
    const btn = h("button", { class: "tab" + (fixed ? " single" : ""), type: "button" }, [
      h("span", { class: "tab-icon", text: t.icon }),
      h("span", { class: "tab-title", text: t.title }),
      h("span", { class: "tab-count", text: "0" }),
    ]);
    btn.addEventListener("click", () => {
      state.tab = t.id;
      refreshTabs();
      renderList();
    });
    tabBtns[t.id] = btn;
    tabsBar.append(btn);
  }

  search.addEventListener("input", () => {
    state.q = search.value.trim();
    renderList();
  });

  const unsubs = tabs.map((t) =>
    ctx.data.subscribeCollection(ctx.uid, ctx.deviceId, t.id, (items, err) => {
      state.data[t.id] = err ? [] : items || [];
      refreshTabs();
      renderList();
    }),
  );

  function refreshTabs() {
    for (const t of tabs) {
      const active = state.tab === t.id;
      tabBtns[t.id].classList.toggle("active", active);
      tabBtns[t.id].querySelector(".tab-count").textContent = String(state.data[t.id].length);
    }
  }

  function renderList() {
    list.innerHTML = "";
    const items = state.data[state.tab] || [];
    const q = state.q.toLowerCase();
    const filtered = q ? items.filter((it) => JSON.stringify(it).toLowerCase().includes(q)) : items;

    if (!items.length) {
      list.append(emptyState("📭", "لا توجد بيانات", "ستظهر العناصر هنا عند توفرها من الجهاز."));
      return;
    }
    if (!filtered.length) {
      list.append(emptyState("🔍", "لا توجد نتائج مطابقة"));
      return;
    }
    for (const it of filtered) {
      if (state.tab === "calls") list.append(callRow(it));
      else if (state.tab === "messages") list.append(messageRow(it));
      else if (state.tab === "notifications") list.append(notifRow(it));
      else list.append(contactRow(it));
    }
  }

  refreshTabs();
  renderList();

  return () => unsubs.forEach((u) => { try { u(); } catch { /* تجاهل */ } });
}
