import { h, timeAgo, fmtDateTime, emptyState, errorBox } from "../util.js";

function normalizeLoc(d) {
  const lat = Number(d.lat ?? d.latitude);
  const lng = Number(d.lng ?? d.lon ?? d.long ?? d.longitude);
  if (!isFinite(lat) || !isFinite(lng)) return null;
  return {
    lat,
    lng,
    accuracy: Number(d.accuracy) || 0,
    address: d.address || "",
    timestamp: d.timestamp ?? d.time ?? d.createdAt ?? d.date ?? null,
  };
}

export function render(container, ctx) {
  container.innerHTML = "";
  const root = h("div", { class: "location-section" });
  const layout = h("div", { class: "loc-layout" });
  const mapBox = h("div", { class: "map-box" + (ctx.compact ? " compact" : "") });
  const side = h("div", { class: "loc-side" });
  const currentCard = h("div", { class: "loc-current card" });
  const toolbar = h("div", { class: "loc-toolbar" });
  const historyTitle = h("h3", { class: "panel-title", text: "سجل المواقع" });
  const historyList = h("div", { class: "history-list" });

  layout.append(mapBox, side);
  side.append(currentCard, toolbar, historyTitle, historyList);
  root.append(layout);
  container.append(root);

  let map = null;
  let currentMarker = null;
  let accCircle = null;
  let historyLayer = null;
  let locations = [];
  let showPath = true;
  let lastLocTs = null;

  if (window.L) {
    map = L.map(mapBox).setView([24.7136, 46.6753], 12);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: "© OpenStreetMap contributors",
    }).addTo(map);
    historyLayer = L.layerGroup().addTo(map);
    setTimeout(() => { try { map.invalidateSize(); } catch { /* تجاهل */ } }, 250);
  } else {
    mapBox.append(h("div", { class: "map-fallback" }, [
      h("div", { class: "empty-icon", text: "🗺️" }),
      h("div", { class: "empty-title", text: "الخريطة غير متاحة" }),
      h("div", { class: "empty-hint", text: "تحقق من اتصال الإنترنت لتحميل مكتبة الخرائط" }),
    ]));
  }

  const pathBtn = h("button", { class: "btn ghost sm active", type: "button", text: "إظهار المسار" });
  pathBtn.addEventListener("click", () => {
    showPath = !showPath;
    pathBtn.textContent = showPath ? "إظهار المسار" : "إخفاء المسار";
    pathBtn.classList.toggle("active", showPath);
    drawHistory();
  });
  const fitBtn = h("button", { class: "btn ghost sm", type: "button", text: "ملاءمة العرض" });
  fitBtn.addEventListener("click", fitAll);
  const refreshBtn = h("button", { class: "btn primary sm", type: "button", text: "تحديث الموقع" });
  refreshBtn.addEventListener("click", () => {
    ctx.data
      .sendCommand(ctx.uid, ctx.deviceId, "get_location", {})
      .then(() => ctx.toast("تم إرسال أمر تحديث الموقع"))
      .catch(() => ctx.toast("تعذر إرسال الأمر", "error"));
  });
  toolbar.append(pathBtn, fitBtn, refreshBtn);

  const unsubDevice = ctx.data.subscribeDevice(ctx.uid, ctx.deviceId, (dev, err) => {
    if (err) {
      currentCard.innerHTML = "";
      currentCard.append(errorBox("تعذر تحميل بيانات الجهاز"));
      return;
    }
    if (!dev) {
      currentCard.innerHTML = "";
      currentCard.append(emptyState("📵", "لا يوجد جهاز محدد"));
      return;
    }
    const loc = dev.location;
    currentCard.innerHTML = "";
    if (!loc) {
      currentCard.append(emptyState("📍", "لا يوجد موقع حالي"));
      return;
    }
    currentCard.append(
      h("div", { class: "loc-current-head" }, [
        h("span", { class: "dot ok" }),
        h("span", { class: "panel-title", text: "الموقع الحالي" }),
      ]),
      h("div", { class: "loc-coords", text: `${loc.lat.toFixed(5)}، ${loc.lng.toFixed(5)}` }),
      h("div", { class: "loc-meta" }, [
        h("span", { text: loc.address || "بدون عنوان" }),
        h("span", { class: "muted", text: timeAgo(loc.timestamp) }),
      ]),
    );
    if (map) {
      const ll = [loc.lat, loc.lng];
      if (currentMarker) currentMarker.setLatLng(ll);
      else currentMarker = L.marker(ll).addTo(map);
      if (loc.accuracy && loc.accuracy > 0) {
        if (accCircle) {
          accCircle.setLatLng(ll);
          accCircle.setRadius(loc.accuracy);
        } else {
          accCircle = L.circle(ll, {
            radius: loc.accuracy, color: "#4f7cff", weight: 1,
            fillColor: "#4f7cff", fillOpacity: 0.08,
          }).addTo(map);
        }
      }
      const ts = loc.timestamp ? String(loc.timestamp) : null;
      if (ts !== lastLocTs) {
        lastLocTs = ts;
        map.setView(ll, Math.max(map.getZoom(), 15));
      }
    }
  });

  const unsubHistory = ctx.data.subscribeCollection(ctx.uid, ctx.deviceId, "locations", (list, err) => {
    if (err) {
      historyList.innerHTML = "";
      historyList.append(errorBox("تعذر تحميل سجل المواقع"));
      return;
    }
    locations = (list || []).map(normalizeLoc).filter(Boolean);
    renderHistoryList();
    drawHistory();
  });

  function renderHistoryList() {
    historyList.innerHTML = "";
    if (!locations.length) {
      historyList.append(emptyState("🗺️", "لا يوجد سجل مواقع"));
      return;
    }
    locations.forEach((l) => {
      const item = h("button", { class: "history-item", type: "button" }, [
        h("span", { class: "history-idx", text: String(locations.length - historyList.children.length) }),
        h("div", { class: "history-body" }, [
          h("div", { class: "history-coords", text: `${l.lat.toFixed(4)}، ${l.lng.toFixed(4)}` }),
          h("div", { class: "history-time muted", text: fmtDateTime(l.timestamp) }),
        ]),
      ]);
      item.addEventListener("click", () => {
        if (map) map.setView([l.lat, l.lng], 16);
        historyList.querySelectorAll(".history-item").forEach((x) => x.classList.remove("active"));
        item.classList.add("active");
      });
      historyList.append(item);
    });
  }

  function drawHistory() {
    if (!map || !historyLayer) return;
    historyLayer.clearLayers();
    if (!locations.length) return;
    const pts = locations.map((l) => [l.lat, l.lng]);
    if (showPath && pts.length > 1) {
      L.polyline(pts, { color: "#4f7cff", weight: 3, opacity: 0.65 }).addTo(historyLayer);
    }
    locations.forEach((l) => {
      L.circleMarker([l.lat, l.lng], {
        radius: 5, color: "#2dd4bf", weight: 2, fillColor: "#2dd4bf", fillOpacity: 0.9,
      }).addTo(historyLayer);
    });
  }

  function fitAll() {
    if (!map) return;
    if (locations.length) {
      map.fitBounds(L.latLngBounds(locations.map((l) => [l.lat, l.lng])).pad(0.15));
    } else if (currentMarker) {
      map.setView(currentMarker.getLatLng(), 15);
    }
  }

  return () => {
    try { unsubDevice(); } catch { /* تجاهل */ }
    try { unsubHistory(); } catch { /* تجاهل */ }
    if (map) {
      try { map.remove(); } catch { /* تجاهل */ }
      map = null;
    }
  };
}
