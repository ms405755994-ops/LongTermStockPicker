const RESULT_URL = "./results/latest_score_top100.json";
const LOGIC_URL = "./results/strategy_logic.json";
const CHART_BASE_URL = "./results/charts/";
const STORE_RESULTS = "lsp_web_results_v1";
const STORE_LOGIC = "lsp_web_logic_v1";
const STORE_WATCHLIST = "lsp_web_watchlist_v1";

const state = {
  payload: null,
  logic: null,
  results: [],
  watchlist: new Set(),
  limit: 100,
  query: "",
  chart: null,
  chartPeriod: "monthly",
};

const el = (id) => document.getElementById(id);
const fmt = (value, digits = 1) => Number.isFinite(Number(value)) ? Number(value).toFixed(digits) : "-";
const text = (value) => value === null || value === undefined || value === "" ? "-" : String(value);

function loadLocal() {
  try {
    const saved = localStorage.getItem(STORE_RESULTS);
    if (saved) applyPayload(JSON.parse(saved), false);
  } catch (_) {
    localStorage.removeItem(STORE_RESULTS);
  }

  try {
    const savedLogic = localStorage.getItem(STORE_LOGIC);
    if (savedLogic) applyLogic(JSON.parse(savedLogic), false);
  } catch (_) {
    localStorage.removeItem(STORE_LOGIC);
  }

  try {
    state.watchlist = new Set(JSON.parse(localStorage.getItem(STORE_WATCHLIST) || "[]"));
  } catch (_) {
    state.watchlist = new Set();
  }
}

async function syncCloud() {
  setStatus("正在同步云端结果...");
  try {
    const cacheBust = `?t=${Date.now()}`;
    const [payload, logic] = await Promise.all([
      fetchJson(RESULT_URL + cacheBust),
      fetchJson(LOGIC_URL + cacheBust),
    ]);
    applyPayload(payload, true);
    applyLogic(logic, true);
    setStatus(`同步成功：${payload.trade_date || "-"}，共 ${payload.results?.length || 0} 只。`);
    showTab("ranking");
  } catch (error) {
    setStatus(`同步失败：${error.message || error}`);
  }
}

async function fetchJson(url) {
  const resp = await fetch(url, {
    cache: "no-store",
    headers: {
      "Cache-Control": "no-cache",
      "Pragma": "no-cache",
    },
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

function applyPayload(payload, persist) {
  state.payload = payload;
  state.results = Array.isArray(payload.results)
    ? [...payload.results].sort((a, b) => Number(b.total_score || 0) - Number(a.total_score || 0))
    : [];
  if (persist) localStorage.setItem(STORE_RESULTS, JSON.stringify(payload));
  renderStatus();
  renderRanking();
  renderWatchlist();
}

function applyLogic(logic, persist) {
  state.logic = logic;
  if (persist) localStorage.setItem(STORE_LOGIC, JSON.stringify(logic));
  renderLogic();
}

function renderStatus() {
  const payload = state.payload || {};
  el("generatedAt").textContent = text(payload.generated_at);
  el("tradeDate").textContent = text(payload.trade_date);
  el("totalCount").textContent = String(state.results.length || 0);
  el("modelVersion").textContent = text(payload.model_version);
}

function renderRanking() {
  const body = el("rankingBody");
  const q = state.query.trim().toLowerCase();
  const limit = state.limit === "all" ? Infinity : Number(state.limit);
  const filtered = state.results.filter((row) => {
    if (!q) return true;
    return [
      row.ts_code,
      row.name,
      row.industry,
      row.signal_level,
    ].some((value) => String(value || "").toLowerCase().includes(q));
  }).slice(0, limit);

  if (!filtered.length) {
    body.innerHTML = `<tr><td colspan="7" class="muted">暂无结果，请先同步云端 Top100。</td></tr>`;
    return;
  }

  body.innerHTML = filtered.map((row, index) => {
    const code = escapeHtml(row.ts_code);
    const watched = state.watchlist.has(row.ts_code);
    return `
      <tr data-code="${code}">
        <td>${index + 1}</td>
        <td><strong>${code}</strong></td>
        <td>${escapeHtml(text(row.name))}</td>
        <td>${escapeHtml(text(row.industry))}</td>
        <td class="score">${fmt(row.total_score, 1)}</td>
        <td><span class="tag">${escapeHtml(text(row.signal_level))}</span></td>
        <td>
          <button class="watch-btn ${watched ? "active" : ""}" data-watch="${code}" type="button">
            ${watched ? "已自选" : "加入"}
          </button>
        </td>
      </tr>
    `;
  }).join("");
}

function renderWatchlist() {
  const wrap = el("watchlistBody");
  const rows = [...state.watchlist]
    .map((code) => state.results.find((row) => row.ts_code === code) || { ts_code: code })
    .sort((a, b) => Number(b.total_score || 0) - Number(a.total_score || 0));

  if (!rows.length) {
    wrap.innerHTML = `<section class="panel muted">暂无自选股。可在排行榜中点击“加入”。</section>`;
    return;
  }

  wrap.innerHTML = rows.map((row) => `
    <article class="stock-card" data-code="${escapeHtml(row.ts_code)}">
      <h3>${escapeHtml(row.ts_code)} ${escapeHtml(text(row.name))}</h3>
      <p class="muted">${escapeHtml(text(row.industry))} ｜ ${escapeHtml(text(row.trade_date))}</p>
      <p>总分 <strong class="score">${fmt(row.total_score, 1)}</strong> ｜ 信号等级 ${escapeHtml(text(row.signal_level))}</p>
      <button class="watch-btn active" data-watch="${escapeHtml(row.ts_code)}" type="button">移除自选</button>
    </article>
  `).join("");
}

function renderLogic() {
  const meta = el("logicMeta");
  const body = el("logicBody");
  const logic = state.logic || {};
  const sections = Array.isArray(logic.sections) ? logic.sections : [];

  meta.textContent = `模型版本：${text(logic.model_version)} ｜ 逻辑更新时间：${text(logic.generated_at)} ｜ 评分交易日：${text(logic.trade_date)}`;

  if (!sections.length) {
    body.innerHTML = `<section class="logic-card emphasized"><h3>暂无云端选股逻辑</h3><p>请先同步云端 Top100。</p></section>`;
    return;
  }

  body.innerHTML = sections.map((section) => {
    const cls = [
      "logic-card",
      section.emphasized ? "emphasized" : "",
      section.kind === "formula" ? "formula" : "",
    ].filter(Boolean).join(" ");
    const content = section.kind === "formula"
      ? `<pre>${escapeHtml(text(section.body))}</pre>`
      : `<p>${escapeHtml(text(section.body)).replaceAll("\n", "<br>")}</p>`;
    return `<article class="${cls}"><h3>${escapeHtml(text(section.title))}</h3>${content}</article>`;
  }).join("");
}

async function openDetail(code) {
  const row = state.results.find((item) => item.ts_code === code);
  if (!row) return;
  el("detailTitle").textContent = `${row.ts_code} ${text(row.name)}`;
  el("detailBody").innerHTML = `
    <section class="chart-panel">
      <div class="chart-head">
        <h3>10年K线图</h3>
        <span id="chartStatus" class="muted">正在加载前复权月线图表...</span>
      </div>
      <div class="chart-tabs" aria-label="图表周期">
        <button class="chart-tab active" data-period="monthly" type="button">月线</button>
        <button class="chart-tab" data-period="weekly" type="button">周线</button>
        <button class="chart-tab" data-period="daily" type="button">日线</button>
      </div>
      <canvas id="klineCanvas" class="stock-chart" data-chart-height="260" height="260"></canvas>
      <canvas id="volumeCanvas" class="stock-chart small" data-chart-height="120" height="120"></canvas>
      <canvas id="macdCanvas" class="stock-chart small" data-chart-height="150" height="150"></canvas>
    </section>
    <div class="detail-grid">
      ${metric("行业", row.industry)}
      ${metric("交易日", row.trade_date)}
      ${metric("总分", fmt(row.total_score, 2))}
      ${metric("信号等级", row.signal_level)}
      ${metric("价格低位分", fmt(row.price_position_score, 1))}
      ${metric("MACD 多周期分", fmt(row.macd_multi_period_score, 1))}
      ${metric("财务安全分", fmt(row.financial_safety_score, 1))}
      ${metric("企业性质分", fmt(row.ownership_score, 1))}
      ${metric("10年价格分位", row.price_percentile === null || row.price_percentile === undefined ? "-" : `${fmt(Number(row.price_percentile) * 100, 2)}%`)}
      ${metric("距离10年最低价", row.distance_to_low === null || row.distance_to_low === undefined ? "-" : `${fmt(Number(row.distance_to_low) * 100, 2)}%`)}
      ${metric("当前收盘价", fmt(row.current_close, 3))}
      ${metric("10年最低价", fmt(row.ten_year_low, 3))}
      ${metric("日/周/月数量", `${text(row.daily_count)} / ${text(row.weekly_count)} / ${text(row.monthly_count)}`)}
      ${metric("月线 MACD", row.monthly_macd_status)}
      ${metric("周线 MACD", row.weekly_macd_status)}
      ${metric("日线 MACD", row.daily_macd_status)}
      ${metric("财务报告期", row.financial_report_period)}
      ${metric("企业性质", row.company_type)}
      ${metric("是否满足10年", row.has_ten_year_data ? "是" : "否")}
      ${metric("数据提示", row.data_warning)}
    </div>
    <section class="panel">
      <h3>模型说明</h3>
      <p>${escapeHtml(text(row.reason)).replaceAll("\n", "<br>")}</p>
      <p class="muted">${escapeHtml(text(row.financial_risk_note))}</p>
    </section>
  `;
  el("detailDialog").showModal();
  state.chart = null;
  state.chartPeriod = "monthly";
  bindChartTabs();
  await loadAndRenderChart(code);
}

async function loadAndRenderChart(code) {
  const status = el("chartStatus");
  try {
    const chart = await fetchJson(`${CHART_BASE_URL}${code}.json?t=${Date.now()}`);
    state.chart = normalizeChart(chart);
    renderChartPeriod(state.chartPeriod);
  } catch (error) {
    status.textContent = `图表加载失败：${error.message || error}`;
  }
}

function normalizeChart(chart) {
  if (chart.periods) return chart;
  return {
    ...chart,
    periods: {
      monthly: { label: "月线", points: chart.points || [] },
    },
  };
}

function bindChartTabs() {
  document.querySelectorAll(".chart-tab").forEach((btn) => {
    btn.addEventListener("click", () => {
      state.chartPeriod = btn.dataset.period;
      document.querySelectorAll(".chart-tab").forEach((item) => item.classList.toggle("active", item === btn));
      renderChartPeriod(state.chartPeriod);
    });
  });
}

function renderChartPeriod(period) {
  const chart = state.chart;
  const status = el("chartStatus");
  const data = chart?.periods?.[period];
  const points = data?.points || [];
  if (!points.length) {
    status.textContent = `${periodLabel(period)}图表数据为空`;
    clearCharts();
    return;
  }
  status.textContent = `前复权${periodLabel(period)}，${points.length} 根`;
  drawKlineChart(el("klineCanvas"), points, periodLabel(period));
  drawVolumeChart(el("volumeCanvas"), points, periodLabel(period));
  drawMacdChart(el("macdCanvas"), points, periodLabel(period));
}

function clearCharts() {
  ["klineCanvas", "volumeCanvas", "macdCanvas"].forEach((id) => {
    const canvas = el(id);
    if (!canvas) return;
    const { ctx, width, height } = setupCanvas(canvas);
    drawFrame(ctx, width, height, "暂无数据");
  });
}

function periodLabel(period) {
  return { monthly: "月线", weekly: "周线", daily: "日线" }[period] || "月线";
}

function metric(label, value) {
  return `<div class="metric"><span>${escapeHtml(label)}</span><strong>${escapeHtml(text(value))}</strong></div>`;
}

function setupCanvas(canvas) {
  const rect = canvas.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  const width = Math.max(320, Math.floor(rect.width || canvas.parentElement.clientWidth));
  const height = Number(canvas.dataset.chartHeight || 220);
  canvas.width = Math.floor(width * ratio);
  canvas.height = Math.floor(height * ratio);
  canvas.style.width = "100%";
  canvas.style.height = `${height}px`;
  const ctx = canvas.getContext("2d");
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, width, height);
  return { ctx, width, height };
}

function drawFrame(ctx, width, height, title) {
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, width, height);
  ctx.strokeStyle = "#d8e0e4";
  ctx.lineWidth = 1;
  ctx.strokeRect(0.5, 0.5, width - 1, height - 1);
  ctx.fillStyle = "#34434c";
  ctx.font = "13px system-ui, sans-serif";
  ctx.fillText(title, 12, 20);
}

function drawKlineChart(canvas, points, label) {
  const { ctx, width, height } = setupCanvas(canvas);
  drawFrame(ctx, width, height, `10年${label}K线（前复权）`);
  const pad = { left: 44, right: 14, top: 34, bottom: 24 };
  const areaW = width - pad.left - pad.right;
  const areaH = height - pad.top - pad.bottom;
  const highs = points.map((p) => Number(p.high)).filter(Number.isFinite);
  const lows = points.map((p) => Number(p.low)).filter(Number.isFinite);
  const max = Math.max(...highs);
  const min = Math.min(...lows);
  const scale = (v) => pad.top + (max - v) / Math.max(max - min, 0.0001) * areaH;
  const step = areaW / Math.max(points.length, 1);
  const bodyW = Math.max(3, Math.min(9, step * 0.58));
  drawYAxis(ctx, pad, width, height, min, max);
  points.forEach((p, i) => {
    const x = pad.left + i * step + step / 2;
    const open = Number(p.open);
    const close = Number(p.close);
    const high = Number(p.high);
    const low = Number(p.low);
    const up = close >= open;
    ctx.strokeStyle = up ? "#d93025" : "#138a5b";
    ctx.fillStyle = ctx.strokeStyle;
    ctx.beginPath();
    ctx.moveTo(x, scale(high));
    ctx.lineTo(x, scale(low));
    ctx.stroke();
    const y = Math.min(scale(open), scale(close));
    const h = Math.max(1, Math.abs(scale(open) - scale(close)));
    ctx.fillRect(x - bodyW / 2, y, bodyW, h);
  });
  drawPriceAnnotations(ctx, points, pad, areaW, areaH, scale, step, bodyW);
  drawXAxis(ctx, points, pad, width, height);
}

function drawVolumeChart(canvas, points, label) {
  const { ctx, width, height } = setupCanvas(canvas);
  drawFrame(ctx, width, height, `${label}成交量红绿柱`);
  const pad = { left: 44, right: 14, top: 30, bottom: 24 };
  const areaW = width - pad.left - pad.right;
  const areaH = height - pad.top - pad.bottom;
  const max = Math.max(...points.map((p) => Number(p.volume) || 0), 1);
  const step = areaW / Math.max(points.length, 1);
  const barW = Math.max(2, Math.min(8, step * 0.62));
  points.forEach((p, i) => {
    const open = Number(p.open);
    const close = Number(p.close);
    const h = (Number(p.volume) || 0) / max * areaH;
    const x = pad.left + i * step + step / 2 - barW / 2;
    ctx.fillStyle = close >= open ? "#d93025" : "#138a5b";
    ctx.fillRect(x, pad.top + areaH - h, barW, h);
  });
  drawXAxis(ctx, points, pad, width, height);
}

function drawMacdChart(canvas, points, label) {
  const { ctx, width, height } = setupCanvas(canvas);
  drawFrame(ctx, width, height, `${label}MACD红绿柱`);
  const pad = { left: 44, right: 14, top: 30, bottom: 24 };
  const areaW = width - pad.left - pad.right;
  const areaH = height - pad.top - pad.bottom;
  const values = points.flatMap((p) => [Number(p.macd), Number(p.dif), Number(p.dea)]).filter(Number.isFinite);
  const maxAbs = Math.max(...values.map((v) => Math.abs(v)), 0.01);
  const mid = pad.top + areaH / 2;
  const y = (v) => mid - v / maxAbs * (areaH / 2 - 6);
  const step = areaW / Math.max(points.length, 1);
  const barW = Math.max(2, Math.min(8, step * 0.62));
  ctx.strokeStyle = "#8a98a3";
  ctx.beginPath();
  ctx.moveTo(pad.left, mid);
  ctx.lineTo(width - pad.right, mid);
  ctx.stroke();
  points.forEach((p, i) => {
    const value = Number(p.macd) || 0;
    const x = pad.left + i * step + step / 2 - barW / 2;
    ctx.fillStyle = value >= 0 ? "#d93025" : "#138a5b";
    ctx.fillRect(x, Math.min(mid, y(value)), barW, Math.max(1, Math.abs(y(value) - mid)));
  });
  drawLine(ctx, points.map((p) => Number(p.dif)), pad, step, y, "#0d47a1");
  drawLine(ctx, points.map((p) => Number(p.dea)), pad, step, y, "#b45f06");
  ctx.fillStyle = "#0d47a1";
  ctx.fillText("DIF", width - 72, 20);
  ctx.fillStyle = "#b45f06";
  ctx.fillText("DEA", width - 40, 20);
  drawXAxis(ctx, points, pad, width, height);
}

function drawPriceAnnotations(ctx, points, pad, areaW, areaH, scale, step, bodyW) {
  let lowIndex = 0;
  points.forEach((p, i) => {
    if (Number(p.low) < Number(points[lowIndex].low)) lowIndex = i;
  });
  const latestIndex = points.length - 1;
  const lowCallout = makeCalloutBox(
    ctx,
    pad.left + lowIndex * step + step / 2,
    scale(Number(points[lowIndex].low)),
    `最低 ${fmt(points[lowIndex].low, 2)}`,
    "#0f6b63",
    pad,
    areaW,
    areaH,
    "below",
  );
  const latestCallout = makeCalloutBox(
    ctx,
    pad.left + latestIndex * step + step / 2,
    scale(Number(points[latestIndex].close)),
    `最新 ${fmt(points[latestIndex].close, 2)}`,
    "#b45f06",
    pad,
    areaW,
    areaH,
    "above",
  );
  avoidCalloutOverlap(lowCallout, latestCallout, pad, areaH);
  drawCalloutBox(ctx, lowCallout);
  drawCalloutBox(ctx, latestCallout);
}

function makeCalloutBox(ctx, x, y, label, color, pad, areaW, areaH, placement) {
  const textWidth = ctx.measureText(label).width;
  const boxW = textWidth + 12;
  const boxH = 22;
  const minX = pad.left;
  const maxX = pad.left + areaW - boxW;
  const minY = pad.top;
  const maxY = pad.top + areaH - boxH;
  const boxX = Math.max(minX, Math.min(maxX, x - boxW / 2));
  const rawY = placement === "below" ? y + 8 : y - boxH - 8;
  const boxY = Math.max(minY, Math.min(maxY, rawY));
  return { x, y, label, color, boxX, boxY, boxW, boxH, placement };
}

function avoidCalloutOverlap(a, b, pad, areaH) {
  if (!boxesOverlap(a, b)) return;
  const gap = 6;
  const minY = pad.top;
  const maxY = pad.top + areaH - Math.max(a.boxH, b.boxH);
  if (a.y >= b.y) {
    a.boxY = b.boxY + b.boxH + gap;
  } else {
    b.boxY = a.boxY + a.boxH + gap;
  }
  a.boxY = Math.max(minY, Math.min(maxY, a.boxY));
  b.boxY = Math.max(minY, Math.min(maxY, b.boxY));
  if (boxesOverlap(a, b)) {
    a.boxY = Math.max(minY, b.boxY - a.boxH - gap);
  }
}

function boxesOverlap(a, b) {
  return !(
    a.boxX + a.boxW < b.boxX ||
    b.boxX + b.boxW < a.boxX ||
    a.boxY + a.boxH < b.boxY ||
    b.boxY + b.boxH < a.boxY
  );
}

function drawCalloutBox(ctx, box) {
  ctx.strokeStyle = box.color;
  ctx.fillStyle = box.color;
  ctx.beginPath();
  ctx.moveTo(box.x, box.y);
  const targetY = box.placement === "below" ? box.boxY : box.boxY + box.boxH;
  ctx.lineTo(Math.max(box.boxX, Math.min(box.boxX + box.boxW, box.x)), targetY);
  ctx.stroke();
  ctx.fillRect(box.boxX, box.boxY, box.boxW, box.boxH);
  ctx.fillStyle = "#ffffff";
  ctx.font = "12px system-ui, sans-serif";
  ctx.fillText(box.label, box.boxX + 6, box.boxY + 15);
}

function drawLine(ctx, values, pad, step, y, color) {
  ctx.strokeStyle = color;
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  values.forEach((value, i) => {
    if (!Number.isFinite(value)) return;
    const x = pad.left + i * step + step / 2;
    if (i === 0) ctx.moveTo(x, y(value));
    else ctx.lineTo(x, y(value));
  });
  ctx.stroke();
}

function drawYAxis(ctx, pad, width, height, min, max) {
  ctx.strokeStyle = "#eef1f3";
  ctx.fillStyle = "#60717c";
  ctx.font = "12px system-ui, sans-serif";
  for (let i = 0; i <= 4; i += 1) {
    const y = pad.top + (height - pad.top - pad.bottom) * i / 4;
    const value = max - (max - min) * i / 4;
    ctx.beginPath();
    ctx.moveTo(pad.left, y);
    ctx.lineTo(width - pad.right, y);
    ctx.stroke();
    ctx.fillText(value.toFixed(2), 6, y + 4);
  }
}

function drawXAxis(ctx, points, pad, width, height) {
  ctx.fillStyle = "#60717c";
  ctx.font = "12px system-ui, sans-serif";
  const labels = 5;
  for (let i = 0; i < labels; i += 1) {
    const idx = Math.round((points.length - 1) * i / (labels - 1));
    const label = String(points[idx]?.date || "").slice(0, 6);
    const x = pad.left + (width - pad.left - pad.right) * i / (labels - 1);
    ctx.fillText(label, Math.min(width - 54, Math.max(4, x - 18)), height - 7);
  }
}

function toggleWatch(code) {
  if (state.watchlist.has(code)) {
    state.watchlist.delete(code);
  } else {
    state.watchlist.add(code);
  }
  localStorage.setItem(STORE_WATCHLIST, JSON.stringify([...state.watchlist]));
  renderRanking();
  renderWatchlist();
}

function showTab(id) {
  document.querySelectorAll(".page").forEach((page) => page.classList.toggle("active", page.id === id));
  document.querySelectorAll(".tab").forEach((tab) => tab.classList.toggle("active", tab.dataset.tab === id));
}

function setStatus(message) {
  el("syncStatus").textContent = message;
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (ch) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  }[ch]));
}

function bindEvents() {
  document.querySelectorAll(".tab").forEach((tab) => {
    tab.addEventListener("click", () => showTab(tab.dataset.tab));
  });

  document.querySelectorAll("#syncBtn, #syncBtn2").forEach((btn) => {
    btn.addEventListener("click", syncCloud);
  });

  el("goRankingBtn").addEventListener("click", () => showTab("ranking"));
  el("searchInput").addEventListener("input", (event) => {
    state.query = event.target.value;
    renderRanking();
  });

  document.querySelectorAll(".limit").forEach((btn) => {
    btn.addEventListener("click", () => {
      state.limit = btn.dataset.limit === "all" ? "all" : Number(btn.dataset.limit);
      document.querySelectorAll(".limit").forEach((item) => item.classList.toggle("active", item === btn));
      renderRanking();
    });
  });

  document.body.addEventListener("click", (event) => {
    const watch = event.target.closest("[data-watch]");
    if (watch) {
      event.stopPropagation();
      toggleWatch(watch.dataset.watch);
      return;
    }
    const row = event.target.closest("[data-code]");
    if (row && !event.target.closest("button")) openDetail(row.dataset.code);
  });

  el("closeDetailBtn").addEventListener("click", () => el("detailDialog").close());
}

bindEvents();
loadLocal();
renderStatus();
renderRanking();
renderWatchlist();
renderLogic();
syncCloud();
