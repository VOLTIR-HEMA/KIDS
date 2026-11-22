import { h, fmtTime } from "../util.js";

/**
 * تريمينال محاكاة: يكتب الأمر هنا، ينفّذ على الجهاز عبر أمر Firestore،
 * وتظهر المخرجات كما ظهرت في تريمينال الجهاز — بدون أي تفاعل مباشر مع الموبايل.
 */
const QUICK = [
  "getprop ro.build.version.release",
  "getprop ro.product.model",
  "pm list packages -3",
  "dumpsys battery",
  "wm size",
  "settings get global airplane_mode_on",
  "dumpsys activity services com.kids.monitor",
  "logcat -d -t 100",
];

export function render(container, ctx) {
  container.innerHTML = "";
  const root = h("div", { class: "terminal-section" });
  container.append(root);

  root.append(h("div", { class: "section-head" }, [
    h("span", { class: "section-icon", text: "⌨️" }),
    h("div", {}, [
      h("h3", { class: "panel-title", text: "تريمينال الجهاز" }),
      h("div", { class: "muted small", text: "أوامر محدودة (قائمة بيضاء). النتيجة تظهر هنا مباشرة." }),
    ]),
  ]));

  const log = h("pre", { class: "terminal-log" });
  const input = h("input", {
    class: "input terminal-input",
    type: "text",
    placeholder: "اكتب أمراً ثم Enter…",
    autocomplete: "off",
    spellcheck: "false",
    dir: "ltr",
  });
  const runBtn = h("button", { class: "btn primary", type: "button", text: "تنفيذ" });
  const clearBtn = h("button", { class: "btn ghost", type: "button", text: "مسح" });

  const quickBar = h("div", { class: "terminal-quick" });
  for (const q of QUICK) {
    const chip = h("button", { class: "chip", type: "button", text: q });
    chip.addEventListener("click", () => { input.value = q; input.focus(); });
    quickBar.append(chip);
  }

  root.append(log, quickBar, h("div", { class: "terminal-bar" }, [input, runBtn, clearBtn]));

  const history = [];
  let historyIndex = -1;
  let watcher = null;
  let busy = false;

  print("kids@device:~$ جاهز. اكتب أمراً مثل: getprop ro.build.version.release", "muted");

  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      run(input.value);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      if (!history.length) return;
      historyIndex = Math.min(history.length - 1, historyIndex + 1);
      input.value = history[history.length - 1 - historyIndex] || "";
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      historyIndex = Math.max(-1, historyIndex - 1);
      input.value = historyIndex < 0 ? "" : history[history.length - 1 - historyIndex] || "";
    }
  });
  runBtn.addEventListener("click", () => run(input.value));
  clearBtn.addEventListener("click", () => { log.textContent = ""; });

  async function run(raw) {
    const cmd = String(raw || "").trim();
    if (!cmd || busy) return;
    busy = true;
    input.value = "";
    history.push(cmd);
    historyIndex = -1;
    print("kids@device:~$ " + cmd, "cmd");
    print("… جارٍ التنفيذ على الجهاز", "muted");

    try {
      const ref = await ctx.data.sendCommand(ctx.uid, ctx.deviceId, "terminal", { cmd });
      const id = ref?.id || ref?.commandId;
      if (!id) {
        print("تعذر إنشاء الأمر (تحقق من الصلاحيات)", "err");
        busy = false;
        return;
      }
      if (watcher) { try { watcher(); } catch { /* تجاهل */ } }
      const started = Date.now();
      watcher = ctx.data.watchCommand(ctx.uid, ctx.deviceId, id, (doc) => {
        if (!doc) {
          if (Date.now() - started > 8000) {
            print("لا توجد استجابة — تأكد أن الجهاز متصل ومشاركة الحالة تعمل.", "err");
            busy = false;
          }
          return;
        }
        if (doc.status === "pending") return;
        const result = doc.result || {};
        if (result.stdout) print(String(result.stdout).trimEnd(), "out");
        if (result.message) print("→ " + result.message, result.ok ? "ok" : "err");
        if (result.exitCode !== undefined && result.exitCode !== 0) {
          print("[exit " + result.exitCode + "]", "muted");
        }
        if (watcher) { try { watcher(); } catch { /* تجاهل */ } }
        watcher = null;
        busy = false;
      });
      setTimeout(() => {
        if (busy) {
          print("انتهت مهلة الانتظار (30 ثانية).", "muted");
          busy = false;
        }
      }, 30000);
    } catch (e) {
      print("فشل إرسال الأمر: " + (e?.message || e), "err");
      busy = false;
    }
  }

  function print(text, kind) {
    const line = h("span", { class: "term-line " + (kind || ""), text: text + "\n" });
    log.append(line);
    log.scrollTop = log.scrollHeight;
  }

  input.focus();

  return () => {
    if (watcher) { try { watcher(); } catch { /* تجاهل */ } }
  };
}
