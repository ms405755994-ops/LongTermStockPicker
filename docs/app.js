const RESULT_URL = "./results/latest_score_top100.json";
const LOGIC_URL = "./results/strategy_logic.json";
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
  const resp = await fetch(url, { cache: "no-store" });
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

function openDetail(code) {
  const row = state.results.find((item) => item.ts_code === code);
  if (!row) return;
  el("detailTitle").textContent = `${row.ts_code} ${text(row.name)}`;
  el("detailBody").innerHTML = `
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
}

function metric(label, value) {
  return `<div class="metric"><span>${escapeHtml(label)}</span><strong>${escapeHtml(text(value))}</strong></div>`;
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
