// أدوات مساعدة عامة: إنشاء عناصر، تنسيق التواريخ، تنبيهات، نوافذ منبثقة

export function h(tag, attrs = {}, children = []) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue;
    if (k === "class") node.className = v;
    else if (k === "text") node.textContent = v;
    else if (k === "html") node.innerHTML = v;
    else if (k.startsWith("on") && typeof v === "function") node.addEventListener(k.slice(2).toLowerCase(), v);
    else node.setAttribute(k, v);
  }
  const list = Array.isArray(children) ? children : [children];
  for (const c of list) {
    if (c === null || c === undefined || c === false) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return node;
}

export function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[c]));
}

export function toDate(ts) {
  if (ts === null || ts === undefined || ts === "") return null;
  if (typeof ts.toDate === "function") {
    const d = ts.toDate();
    return isNaN(d) ? null : d;
  }
  if (ts instanceof Date) return isNaN(ts) ? null : ts;
  if (typeof ts === "number") return new Date(ts < 1e12 ? ts * 1000 : ts);
  if (typeof ts === "string") {
    const d = new Date(ts);
    return isNaN(d) ? null : d;
  }
  return null;
}

const fmtDT = new Intl.DateTimeFormat("ar", { day: "numeric", month: "long", year: "numeric", hour: "2-digit", minute: "2-digit" });
const fmtT = new Intl.DateTimeFormat("ar", { hour: "2-digit", minute: "2-digit" });
const fmtD = new Intl.DateTimeFormat("ar", { day: "numeric", month: "long" });

export function fmtDateTime(ts) { const d = toDate(ts); return d ? fmtDT.format(d) : "—"; }
export function fmtTime(ts) { const d = toDate(ts); return d ? fmtT.format(d) : "—"; }
export function fmtDate(ts) { const d = toDate(ts); return d ? fmtD.format(d) : "—"; }

export function timeAgo(ts) {
  const d = toDate(ts);
  if (!d) return "—";
  const s = Math.floor((Date.now() - d.getTime()) / 1000);
  if (s < 60) return "الآن";
  const m = Math.floor(s / 60);
  if (m < 60) {
    if (m === 1) return "قبل دقيقة";
    if (m === 2) return "قبل دقيقتين";
    return m <= 10 ? `قبل ${m} دقائق` : `قبل ${m} دقيقة`;
  }
  const hr = Math.floor(m / 60);
  if (hr < 24) {
    if (hr === 1) return "قبل ساعة";
    if (hr === 2) return "قبل ساعتين";
    return hr <= 10 ? `قبل ${hr} ساعات` : `قبل ${hr} ساعة`;
  }
  const days = Math.floor(hr / 24);
  if (days === 1) return "أمس";
  if (days === 2) return "قبل يومين";
  if (days < 7) return `قبل ${days} أيام`;
  return fmtD.format(d);
}

export function fmtBytes(n) {
  if (n === null || n === undefined || isNaN(n)) return "—";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  let v = Number(n);
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${u[i]}`;
}

export function fmtDuration(sec) {
  sec = Number(sec) || 0;
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

export function toast(message, type = "info") {
  const root = document.getElementById("toasts");
  if (!root) return;
  const t = h("div", { class: `toast ${type}`, role: "status" }, [h("span", { text: message })]);
  root.append(t);
  setTimeout(() => t.classList.add("show"), 10);
  setTimeout(() => {
    t.classList.remove("show");
    setTimeout(() => t.remove(), 300);
  }, 3500);
}

export function modal({ title, message, fields = [], okText = "تأكيد", cancelText = "إلغاء", danger = false } = {}) {
  return new Promise((resolve) => {
    const root = document.getElementById("modalRoot");
    const overlay = h("div", { class: "modal-overlay" });
    const card = h("div", { class: "modal-card", role: "dialog", "aria-modal": "true" });
    card.append(h("h3", { text: title }));
    if (message) card.append(h("p", { class: "muted", text: message }));

    const inputs = [];
    for (const f of fields) {
      const input = h("input", {
        class: "input",
        type: f.type || "text",
        placeholder: f.placeholder || "",
        value: f.value || "",
      });
      card.append(h("label", { class: "field" }, [h("span", { text: f.label }), input]));
      inputs.push(input);
    }

    const close = (val) => {
      overlay.classList.remove("show");
      setTimeout(() => overlay.remove(), 200);
      resolve(val);
    };

    const ok = h("button", { class: `btn ${danger ? "danger" : "primary"}`, type: "button", text: okText });
    ok.addEventListener("click", () => {
      if (fields.length) {
        const values = {};
        for (let i = 0; i < fields.length; i++) {
          const v = inputs[i].value.trim();
          if (fields[i].required !== false && !v) { inputs[i].focus(); return; }
          values[fields[i].name] = v;
        }
        close(values);
      } else {
        close(true);
      }
    });

    const cancel = h("button", { class: "btn ghost", type: "button", text: cancelText });
    cancel.addEventListener("click", () => close(null));
    card.append(h("div", { class: "modal-actions" }, [ok, cancel]));

    overlay.append(card);
    overlay.addEventListener("click", (e) => { if (e.target === overlay) close(null); });
    document.addEventListener("keydown", function onKey(e) {
      if (e.key === "Escape") { document.removeEventListener("keydown", onKey); close(null); }
      else if (e.key === "Enter") { document.removeEventListener("keydown", onKey); ok.click(); }
    });

    root.append(overlay);
    requestAnimationFrame(() => overlay.classList.add("show"));
    if (inputs[0]) inputs[0].focus();
  });
}

export function emptyState(icon, title, hint = "") {
  return h("div", { class: "empty-state" }, [
    h("div", { class: "empty-icon", text: icon }),
    h("div", { class: "empty-title", text: title }),
    hint ? h("div", { class: "empty-hint", text: hint }) : null,
  ]);
}

export function errorBox(message) {
  return h("div", { class: "error-box", role: "alert" }, [
    h("span", { text: "⚠️" }),
    h("span", { text: message }),
  ]);
}
