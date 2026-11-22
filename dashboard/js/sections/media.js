import { h, fmtDateTime, fmtBytes, emptyState, errorBox } from "../util.js";

const COMMANDS = [
  { type: "get_location", icon: "📍", title: "تحديث الموقع", desc: "إرسال طلب لتحديث موقع الجهاز فوراً" },
  { type: "take_screenshot", icon: "🖥️", title: "لقطة شاشة", desc: "التقاط لقطة شاشة فورية (يتطلب تفعيل مشاركة الشاشة)" },
  { type: "ring", icon: "🔔", title: "رنين الجهاز", desc: "تشغيل رنين الجهاز بحد أقصى للصوت" },
  { type: "take_photo", icon: "📷", title: "التقاط صورة", desc: "التقاط صورة من الكاميرا وإرسالها" },
  { type: "lock", icon: "🔒", title: "قفل الجهاز", desc: "قفل شاشة الجهاز عن بُعد" },
  { type: "clear_notifications", icon: "🧹", title: "مسح الإشعارات", desc: "حذف جميع الإشعارات من شريط الجهاز" },
  { type: "send_message", icon: "✉️", title: "إرسال رسالة", desc: "إرسال رسالة نصية إلى رقم محدد", fields: true },
  { type: "factory_reset", icon: "⚠️", title: "إعادة ضبط المصنع", desc: "تحذير: سيتم حذف جميع بيانات الجهاز", danger: true },
];

function labelFor(type) {
  const c = COMMANDS.find((x) => x.type === type);
  return c ? c.title : type;
}

function statusLabel(s) {
  if (s === "done") return "تم التنفيذ";
  if (s === "failed") return "فشل";
  return "قيد التنفيذ";
}

function statusClass(s) {
  if (s === "done") return "done";
  if (s === "failed") return "failed";
  return "pending";
}

function iconForFile(m) {
  const t = String(m.mimeType || m.type || "").toLowerCase();
  if (t.startsWith("audio")) return "🎵";
  if (t.startsWith("video")) return "🎬";
  if (t.includes("pdf")) return "📄";
  return "📁";
}

function classify(m) {
  const t = String(m.type || m.mimeType || "").toLowerCase();
  const url = String(m.url || m.downloadUrl || m.path || "").toLowerCase();
  if (t.startsWith("image") || /\.(jpe?g|png|gif|webp|bmp)$/.test(url)) return "image";
  return "file";
}

function isAudio(m) {
  return String(m.mimeType || m.type || "").toLowerCase().startsWith("audio");
}

export function render(container, ctx) {
  container.innerHTML = "";
  const root = h("div", { class: "media-section" });
  const tabsBar = h("div", { class: "tabs" });
  const body = h("div", { class: "media-body" });
  root.append(tabsBar, body);
  container.append(root);

  const tabMedia = h("button", { class: "tab active", type: "button" }, [
    h("span", { text: "🖼️" }), h("span", { text: "الصور والملفات" }),
  ]);
  const tabCmd = h("button", { class: "tab", type: "button" }, [
    h("span", { text: "🎮" }), h("span", { text: "أوامر التحكم عن بُعد" }),
  ]);
  tabsBar.append(tabMedia, tabCmd);

  let active = "media";
  let currentDestroy = null;

  function refresh() {
    if (currentDestroy) { try { currentDestroy(); } catch { /* تجاهل */ } currentDestroy = null; }
    tabMedia.classList.toggle("active", active === "media");
    tabCmd.classList.toggle("active", active === "commands");
    body.innerHTML = "";
    currentDestroy = active === "media" ? renderMedia(body, ctx) : renderCommands(body, ctx);
  }

  tabMedia.addEventListener("click", () => { active = "media"; refresh(); });
  tabCmd.addEventListener("click", () => { active = "commands"; refresh(); });

  refresh();
  return () => { if (currentDestroy) { try { currentDestroy(); } catch { /* تجاهل */ } } };
}

function renderMedia(body, ctx) {
  const toolbar = h("div", { class: "filter-row" });
  const select = h("select", { class: "input select sm" });
  for (const o of [["all", "الكل"], ["image", "الصور"], ["audio", "التسجيلات"], ["file", "الملفات"]]) {
    select.append(h("option", { value: o[0], text: o[1] }));
  }
  toolbar.append(h("span", { class: "panel-title", text: "الصور والتسجيلات والملفات" }), select);
  const grid = h("div", { class: "media-grid" });
  const audioList = h("div", { class: "list" });
  const filesList = h("div", { class: "list" });
  body.append(toolbar, grid, audioList, filesList);

  let media = [];
  let filter = "all";

  select.addEventListener("change", () => { filter = select.value; draw(); });

  const unsub = ctx.data.subscribeCollection(ctx.uid, ctx.deviceId, "media", (items, err) => {
    if (err) {
      body.innerHTML = "";
      body.append(errorBox("تعذر تحميل الوسائط"));
      return;
    }
    media = items || [];
    draw();
  });

  function draw() {
    grid.innerHTML = "";
    audioList.innerHTML = "";
    filesList.innerHTML = "";

    const images = media.filter((m) => classify(m) === "image" && filter !== "audio" && filter !== "file");
    const audioItems = media.filter((m) => isAudio(m) && filter !== "image" && filter !== "file");
    const files = media.filter((m) => classify(m) === "file" && !isAudio(m) && filter !== "image" && filter !== "audio");

    if (!media.length) {
      grid.append(emptyState("🖼️", "لا توجد وسائط", "ستظهر الصور والتسجيلات والملفات هنا عند توفرها"));
      return;
    }
    if (!images.length && !audioItems.length && !files.length) {
      grid.append(emptyState("🔍", "لا توجد عناصر مطابقة للتصفية"));
      return;
    }

    for (const m of images) {
      const url = m.url || m.downloadUrl || m.dataUrl || "";
      const card = h("div", { class: "media-item" }, [
        h("div", { class: "media-thumb placeholder", text: "🖼️" }),
        h("div", { class: "media-name", text: m.name || "صورة" }),
        h("div", { class: "media-meta muted", text: fmtDateTime(m.timestamp) }),
        url
          ? h("button", { class: "btn ghost sm media-open", type: "button", text: "عرض" })
          : h("div", { class: "media-meta muted", text: "لا يوجد رابط" }),
      ]);
      if (url) {
        const openBtn = card.querySelector(".media-open");
        openBtn.addEventListener("click", () => {
          const img = h("img", { class: "media-thumb", src: url, alt: m.name || "صورة", loading: "lazy", decoding: "async" });
          img.addEventListener("click", () => window.open(url, "_blank"));
          card.replaceChild(img, card.querySelector(".media-thumb"));
          openBtn.remove();
          card.append(h("a", { class: "media-meta", href: url, target: "_blank", rel: "noopener", text: "فتح / تنزيل ⬇" }));
        });
      }
      grid.append(card);
    }

    for (const m of audioItems) {
      const url = m.url || m.dataUrl || "";
      const card = h("div", { class: "list-row audio-card" }, [
        h("div", { class: "row-icon", text: "🎙️" }),
        h("div", { class: "row-body" }, [
          h("div", { class: "row-title", text: m.name || "تسجيل صوتي" }),
          h("div", { class: "row-meta muted", text: [fmtBytes(m.size), fmtDateTime(m.timestamp)].filter(Boolean).join(" · ") }),
          url
            ? h("audio", { class: "audio-player", controls: true, preload: "none", src: url, style: "width:100%;margin-top:8px" })
            : h("div", { class: "row-meta muted", text: "لا يوجد رابط تشغيل (لم يُرفع بعد)" }),
        ]),
      ]);
      audioList.append(card);
    }

    for (const m of files) {
      const url = m.url || m.downloadUrl || m.dataUrl || "";
      const inner = [
        h("div", { class: "row-icon", text: iconForFile(m) }),
        h("div", { class: "row-body" }, [
          h("div", { class: "row-title", text: m.name || "ملف" }),
          h("div", { class: "row-meta muted", text: [fmtBytes(m.size), fmtDateTime(m.timestamp)].filter(Boolean).join(" · ") }),
        ]),
        h("div", { class: "row-time muted", text: "⬇️" }),
      ];
      const item = url
        ? h("a", { class: "list-row file-row", href: url, target: "_blank", rel: "noopener" }, inner)
        : h("div", { class: "list-row file-row" }, inner);
      filesList.append(item);
    }
  }

  return () => { try { unsub(); } catch { /* تجاهل */ } };
}

function renderCommands(body, ctx) {
  const grid = h("div", { class: "commands-grid" });
  const statusCard = h("div", { class: "card command-status" }, [
    h("h3", { class: "panel-title", text: "حالة الأوامر" }),
    h("div", { class: "list" }),
  ]);
  body.append(h("h3", { class: "panel-title", text: "أوامر التحكم عن بُعد" }), grid, statusCard);

  const statusList = statusCard.querySelector(".list");
  const unsub = ctx.data.subscribeCollection(ctx.uid, ctx.deviceId, "commands", (items, err) => {
    statusList.innerHTML = "";
    if (err) {
      statusList.append(errorBox("تعذر تحميل الأوامر"));
      return;
    }
    if (!items || !items.length) {
      statusList.append(emptyState("🧾", "لا توجد أوامر بعد"));
      return;
    }
    for (const c of items.slice(0, 10)) {
      statusList.append(h("div", { class: "list-row" }, [
        h("div", { class: "row-body" }, [
          h("div", { class: "row-title", text: labelFor(c.type) }),
          h("div", { class: "row-meta muted", text: fmtDateTime(c.createdAt) }),
        ]),
        h("span", { class: "chip " + statusClass(c.status), text: statusLabel(c.status) }),
      ]));
    }
  });

  for (const cmd of COMMANDS) {
    const btn = h("button", { class: "command-card" + (cmd.danger ? " danger" : ""), type: "button" }, [
      h("span", { class: "command-icon", text: cmd.icon }),
      h("div", { class: "command-info" }, [
        h("div", { class: "command-title", text: cmd.title }),
        h("div", { class: "command-desc", text: cmd.desc }),
      ]),
      h("span", { class: "command-send", text: "إرسال ↩" }),
    ]);
    btn.addEventListener("click", () => runCommand(cmd, ctx));
    grid.append(btn);
  }

  return () => { try { unsub(); } catch { /* تجاهل */ } };
}

async function runCommand(cmd, ctx) {
  try {
    let payload = {};
    if (cmd.fields) {
      const res = await ctx.modal({
        title: cmd.title,
        message: "أدخل بيانات الأمر",
        fields: [
          { name: "number", label: "رقم المستلم", placeholder: "05xxxxxxxx", required: true },
          { name: "message", label: "نص الرسالة", placeholder: "اكتب الرسالة هنا", required: true },
        ],
        okText: "إرسال",
      });
      if (!res) return;
      payload = res;
    } else if (cmd.danger) {
      const res = await ctx.modal({
        title: cmd.title,
        message: "⚠️ هذا إجراء خطير ولا يمكن التراجع عنه. هل أنت متأكد؟",
        okText: "نعم، تنفيذ",
        danger: true,
      });
      if (!res) return;
    }
    await ctx.data.sendCommand(ctx.uid, ctx.deviceId, cmd.type, payload);
    ctx.toast("تم إرسال الأمر إلى الجهاز");
  } catch {
    ctx.toast("تعذر إرسال الأمر", "error");
  }
}
