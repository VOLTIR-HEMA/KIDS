import { h, emptyState } from "../util.js";
import { render as overview } from "./overview.js";
import { render as location } from "./location.js";
import { render as comms } from "./comms.js";
import { render as media } from "./media.js";

export const PANELS = [
  { id: "overview", title: "نظرة عامة", icon: "📊", render: overview },
  { id: "location", title: "الموقع الجغرافي", icon: "📍", render: location },
  { id: "comms", title: "المكالمات والاتصالات", icon: "💬", render: comms },
  { id: "media", title: "الوسائط والتحكم", icon: "🎮", render: media },
];

export function render(container, ctx, selected, onChange) {
  container.innerHTML = "";
  const root = h("div", { class: "combined" });
  const toolbar = h("div", { class: "combined-toolbar" }, [
    h("span", { class: "panel-title", text: "اختر الأقسام لعرضها معاً:" }),
  ]);
  const grid = h("div", { class: "combined-grid" });
  root.append(toolbar, grid);
  container.append(root);

  const checks = [];
  for (const p of PANELS) {
    const cb = h("input", { type: "checkbox" });
    cb.checked = selected.includes(p.id);
    cb.addEventListener("change", () => {
      let next = checks.filter((c) => c.cb.checked).map((c) => c.p.id);
      if (!next.length) { cb.checked = true; next = [p.id]; }
      onChange(next);
    });
    checks.push({ p, cb });
    toolbar.append(h("label", { class: "check-chip" }, [cb, h("span", { text: `${p.icon} ${p.title}` })]));
  }

  let destroys = [];

  function rebuild() {
    destroys.forEach((d) => { try { d(); } catch { /* تجاهل */ } });
    destroys = [];
    grid.innerHTML = "";
    const sel = checks.filter((c) => c.cb.checked).map((c) => c.p);
    if (!sel.length) {
      grid.append(emptyState("🧩", "اختر قسماً واحداً على الأقل"));
      return;
    }
    for (const p of sel) {
      const body = h("div", { class: "panel-body" });
      const panel = h("section", { class: "panel" }, [
        h("div", { class: "panel-head" }, [
          h("span", { text: p.icon }),
          h("h3", { class: "panel-title", text: p.title }),
        ]),
        body,
      ]);
      grid.append(panel);
      destroys.push(p.render(body, { ...ctx, compact: true }));
    }
  }

  rebuild();
  return () => { destroys.forEach((d) => { try { d(); } catch { /* تجاهل */ } }); };
}
