/** Attaches the CSRF header Spring Security expects for state-changing requests. */
function csrfFetch(url, options) {
    options = options || {};
    const method = (options.method || "GET").toUpperCase();
    if (method !== "GET" && method !== "HEAD") {
        const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
        if (match) {
            options.headers = Object.assign({}, options.headers, {"X-XSRF-TOKEN": decodeURIComponent(match[1])});
        }
    }
    return fetch(url, options);
}

document.addEventListener("DOMContentLoaded", () => {
    const lookupBtn = document.getElementById("lookup-btn");
    const symbolInput = document.getElementById("symbol-input");
    const marketSelect = document.getElementById("market-select");
    const resultEl = document.getElementById("lookup-result");

    if (!lookupBtn) {
        return;
    }

    lookupBtn.addEventListener("click", async () => {
        const symbol = symbolInput.value.trim();
        const market = marketSelect.value;
        if (!symbol) {
            resultEl.textContent = "Enter a symbol first.";
            return;
        }

        resultEl.textContent = "Looking up " + symbol + "...";
        lookupBtn.disabled = true;

        try {
            const response = await fetch(`/api/stocks/lookup?symbol=${encodeURIComponent(symbol)}&market=${encodeURIComponent(market)}`);
            if (!response.ok) {
                const message = await response.text();
                resultEl.textContent = message || "Symbol not found.";
                return;
            }
            const quote = await response.json();
            resultEl.textContent = `${quote.symbol} — ${quote.companyName} — ${quote.price.toFixed(2)} ${quote.currency}`;
        } catch (err) {
            resultEl.textContent = "Lookup failed: " + err.message;
        } finally {
            lookupBtn.disabled = false;
        }
    });
});

// Spreadsheet-style share editing in the portfolio table.
document.addEventListener("DOMContentLoaded", () => {
    const table = document.querySelector(".portfolio-table");
    if (!table) {
        return;
    }

    async function refreshTotals() {
        const html = await (await fetch("/", {headers: {"Accept": "text/html"}})).text();
        const doc = new DOMParser().parseFromString(html, "text/html");
        if (!doc.querySelector(".portfolio-table")) {
            location.reload();
            return;
        }
        // Drop stock rows whose holdings are all zero (the server no longer returns them).
        const liveStockIds = new Set(Array.from(doc.querySelectorAll(".portfolio-table .share-cell"))
            .map(c => c.dataset.stockId));
        table.querySelectorAll("tbody tr").forEach(tr => {
            const cell = tr.querySelector(".share-cell");
            if (cell && !liveStockIds.has(cell.dataset.stockId)) {
                tr.remove();
            }
        });
        const fresh = doc.querySelectorAll(".portfolio-table [data-live]");
        const current = table.querySelectorAll("[data-live]");
        if (fresh.length !== current.length) {
            location.reload();
            return;
        }
        current.forEach((el, i) => {
            el.innerHTML = fresh[i].innerHTML;
        });
        // Cost boxes are inputs, so sync them separately and never clobber one being edited.
        doc.querySelectorAll(".portfolio-table .cost-cell").forEach(f => {
            const cur = table.querySelector(`.cost-cell[data-account-id="${f.dataset.accountId}"][data-stock-id="${f.dataset.stockId}"]`);
            if (cur && cur !== document.activeElement) {
                cur.value = f.value;
                cur.dataset.original = f.dataset.original;
            }
        });
    }

    // Both editable cell types post to their own endpoint; empty means "none" for either.
    const CELLS = {
        "share-cell": {url: "/holdings/set", field: "shares"},
        "cost-cell": {url: "/holdings/cost", field: "cost"}
    };
    const cellType = el => Object.keys(CELLS).find(c => el.classList.contains(c));
    const COST_TITLE = "Average purchase price per share in this account";

    async function save(input) {
        const type = cellType(input);
        const value = input.value.trim().replace(/,/g, "");
        if (value === input.dataset.original) {
            return;
        }
        input.classList.remove("cell-error");
        input.classList.add("cell-saving");
        try {
            const body = new URLSearchParams({
                accountId: input.dataset.accountId,
                stockId: input.dataset.stockId,
                [CELLS[type].field]: value
            });
            const response = await csrfFetch(CELLS[type].url, {method: "POST", body});
            if (!response.ok) {
                input.classList.add("cell-error");
                input.title = await response.text();
                return;
            }
            const n = value === "" || value === "-" ? 0 : Number(value);
            input.dataset.original = n === 0 ? "" : String(n);
            input.value = input.dataset.original;
            input.title = input.dataset.defaultTitle;
            await refreshTotals();
        } catch (e) {
            input.classList.add("cell-error");
            input.title = "Save failed";
        } finally {
            input.classList.remove("cell-saving");
        }
    }

    table.addEventListener("change", e => {
        if (cellType(e.target)) {
            save(e.target);
        }
    });

    // Enter moves down the column like a spreadsheet.
    table.addEventListener("keydown", e => {
        if (e.key !== "Enter" || !cellType(e.target)) {
            return;
        }
        e.preventDefault();
        const cells = Array.from(table.querySelectorAll(`.${cellType(e.target)}[data-account-id="${e.target.dataset.accountId}"]`));
        const next = cells[cells.indexOf(e.target) + (e.shiftKey ? -1 : 1)];
        e.target.dispatchEvent(new Event("change", {bubbles: true}));
        (next || e.target).focus();
        if (next) {
            next.select();
        }
    });

    table.addEventListener("focusin", e => {
        if (cellType(e.target)) {
            e.target.select();
        }
    });
});

// Company-name search: pick a result to fill in the symbol.
document.addEventListener("DOMContentLoaded", () => {
    const input = document.getElementById("name-search-input");
    const btn = document.getElementById("name-search-btn");
    if (!input || !btn) {
        return;
    }
    const statusEl = document.getElementById("name-search-status");
    const listEl = document.getElementById("name-search-results");
    const marketSelect = document.getElementById("market-select");
    const symbolInput = document.getElementById("symbol-input");

    async function search() {
        const query = input.value.trim();
        listEl.innerHTML = "";
        if (query.length < 2) {
            statusEl.textContent = "Enter at least 2 characters.";
            return;
        }
        statusEl.textContent = "Searching...";
        btn.disabled = true;
        try {
            const response = await fetch(`/api/stocks/search?query=${encodeURIComponent(query)}&market=${encodeURIComponent(marketSelect.value)}`);
            if (!response.ok) {
                statusEl.textContent = (await response.text()) || "Search failed.";
                return;
            }
            const matches = await response.json();
            const label = marketSelect.options[marketSelect.selectedIndex].text;
            statusEl.textContent = matches.length
                ? "Select a stock:"
                : `No matches on ${label}. Try another market or spelling.`;
            matches.forEach(m => {
                const li = document.createElement("li");
                const b = document.createElement("button");
                b.type = "button";
                b.className = "search-result";
                b.textContent = `${m.symbol} — ${m.name}` + (m.exchange ? ` (${m.exchange})` : "");
                b.addEventListener("click", () => {
                    symbolInput.value = m.symbol;
                    listEl.innerHTML = "";
                    statusEl.textContent = "";
                    document.getElementById("lookup-btn").click();
                });
                li.appendChild(b);
                listEl.appendChild(li);
            });
        } catch (err) {
            statusEl.textContent = "Search failed: " + err.message;
        } finally {
            btn.disabled = false;
        }
    }

    btn.addEventListener("click", search);
    input.addEventListener("keydown", e => {
        if (e.key === "Enter") {
            e.preventDefault();
            search();
        }
    });
});

// Quick-add row above the portfolio table; remembers the last used exchange/account.
document.addEventListener("DOMContentLoaded", () => {
    const toggle = document.getElementById("quick-add-toggle");
    const form = document.getElementById("quick-add-form");
    if (!toggle || !form) {
        return;
    }
    const market = document.getElementById("quick-market");
    const account = document.getElementById("quick-account");
    const symbol = document.getElementById("quick-symbol");
    const addSharesMarket = document.getElementById("market-select");

    const store = {
        get(key) {
            try { return localStorage.getItem(key); } catch (e) { return null; }
        },
        set(key, value) {
            try { localStorage.setItem(key, value); } catch (e) { /* ignore */ }
        }
    };
    function restore(select, key) {
        const saved = store.get(key);
        if (saved && Array.from(select.options).some(o => o.value === saved)) {
            select.value = saved;
        }
    }
    restore(market, "lastMarket");
    restore(account, "lastAccount");
    if (addSharesMarket) {
        restore(addSharesMarket, "lastMarket");
        addSharesMarket.addEventListener("change", () => store.set("lastMarket", addSharesMarket.value));
    }

    toggle.addEventListener("click", () => {
        form.hidden = !form.hidden;
        toggle.setAttribute("aria-expanded", String(!form.hidden));
        toggle.textContent = form.hidden ? "+" : "−";
        if (!form.hidden) {
            symbol.focus();
        }
    });
    form.addEventListener("keydown", e => {
        if (e.key === "Escape") {
            toggle.click();
        }
    });
    form.addEventListener("submit", () => {
        store.set("lastMarket", market.value);
        store.set("lastAccount", account.value);
    });
});

// Tabs (remembered across reloads) and the Add Shares popup.
document.addEventListener("DOMContentLoaded", () => {
    const tabs = document.querySelectorAll(".tab");
    function show(name) {
        tabs.forEach(t => t.classList.toggle("active", t.dataset.tab === name));
        document.querySelectorAll(".tab-panel").forEach(p => {
            p.hidden = p.id !== "tab-" + name;
        });
        try { localStorage.setItem("activeTab", name); } catch (e) { /* ignore */ }
    }
    tabs.forEach(t => t.addEventListener("click", () => show(t.dataset.tab)));
    let saved = null;
    try { saved = localStorage.getItem("activeTab"); } catch (e) { /* ignore */ }
    if (saved && document.getElementById("tab-" + saved)) {
        show(saved);
    }

    const dialog = document.getElementById("add-shares-dialog");
    const openBtn = document.getElementById("add-shares-open");
    if (dialog && openBtn) {
        openBtn.addEventListener("click", () => {
            dialog.showModal();
            document.getElementById("symbol-input").focus();
        });
        document.getElementById("add-shares-close").addEventListener("click", () => dialog.close());
        dialog.addEventListener("click", e => {
            if (e.target === dialog) {
                dialog.close();
            }
        });
    }
});

// Which saved analysis the user has already looked at (kept in this browser), so the page can tell
// them when a newer one is ready even if they closed the popup or the page while it ran.
const SEEN_KEY = "lastSeenAnalysis";
function getSeenAnalysis() {
    try {
        const v = localStorage.getItem(SEEN_KEY);
        return v === null ? null : Number(v);
    } catch (e) {
        return null;
    }
}
function setSeenAnalysis(id) {
    try { localStorage.setItem(SEEN_KEY, String(id)); } catch (e) { /* ignore */ }
    document.dispatchEvent(new CustomEvent("analysis-state-changed"));
}
async function newestAnalysisId() {
    const r = await fetch("/api/ai/analyses");
    const items = r.ok ? await r.json() : [];
    return items.length ? items[0].id : 0;
}
async function markLatestAnalysisSeen() {
    try {
        setSeenAnalysis(await newestAnalysisId());
    } catch (e) { /* ignore */ }
}

// Renders a model response as Markdown (escaped, so safe) with a small footer line.
function showMarkdown(el, markdown, footer) {
    el.classList.add("md");
    el.innerHTML = window.renderMarkdown(markdown) + `<p class="ai-meta"></p>`;
    el.querySelector(".ai-meta").textContent = footer;
}

// AI setup tab: Ollama endpoint, model selection and a test prompt.
document.addEventListener("DOMContentLoaded", () => {
    const form = document.getElementById("ai-config-form");
    if (!form) {
        return;
    }
    const endpointEl = document.getElementById("ai-endpoint");
    const modelEl = document.getElementById("ai-model");
    const statusEl = document.getElementById("ai-status");
    const testBtn = document.getElementById("ai-test-btn");
    const chatForm = document.getElementById("ai-chat-form");
    const sendBtn = document.getElementById("ai-send-btn");
    const responseEl = document.getElementById("ai-response");

    function setStatus(text, cls) {
        statusEl.textContent = text;
        statusEl.className = "ai-status " + (cls || "muted");
    }

    function fillModels(models, selected) {
        modelEl.innerHTML = "";
        if (!models.length) {
            modelEl.add(new Option("(no models available - install or load one in your provider)", ""));
            return;
        }
        const list = selected && !models.includes(selected) ? [selected, ...models] : models;
        list.forEach(m => modelEl.add(new Option(m, m)));
        modelEl.value = selected && list.includes(selected) ? selected : models[0];
    }

    const DEFAULTS = {ollama: "http://localhost:11434", lmstudio: "http://localhost:1234"};
    const NAMES = {ollama: "Ollama", lmstudio: "LM Studio"};
    const providerRadios = document.querySelectorAll('input[name="ai-provider"]');
    const getProvider = () => document.querySelector('input[name="ai-provider"]:checked').value;
    const setProvider = value => providerRadios.forEach(r => {
        r.checked = r.value === value;
    });

    // Picking a provider swaps the endpoint if it is still the other provider's default.
    providerRadios.forEach(r => r.addEventListener("change", () => {
        const p = getProvider();
        const current = endpointEl.value.trim().replace(/\/+$/, "");
        if (p !== "auto" && Object.values(DEFAULTS).includes(current)) {
            endpointEl.value = DEFAULTS[p];
        }
        modelEl.innerHTML = "";
        testConnection("");
    }));

    async function testConnection(selected) {
        setStatus("Testing...", "muted");
        testBtn.disabled = true;
        try {
            const params = new URLSearchParams({endpoint: endpointEl.value.trim(), provider: getProvider()});
            const r = await fetch("/api/ai/status?" + params);
            const s = await r.json();
            if (s.connected) {
                if (getProvider() === "auto") {
                    endpointEl.value = s.endpoint;
                }
                const detected = getProvider() === "auto" ? " (detected)" : "";
                const ver = s.version ? ` ${s.version}` : "";
                setStatus(`Connected - ${NAMES[s.provider]}${ver}${detected}, ${s.models.length} model(s)`, "ok");
                fillModels(s.models, selected !== undefined ? selected : modelEl.value);
            } else {
                setStatus(s.error || "Not connected", "bad");
            }
        } catch (e) {
            setStatus("Test failed: " + e.message, "bad");
        } finally {
            testBtn.disabled = false;
        }
    }

    testBtn.addEventListener("click", () => testConnection());

    form.addEventListener("submit", async e => {
        e.preventDefault();
        const body = new URLSearchParams({endpoint: endpointEl.value.trim(), provider: getProvider(), model: modelEl.value});
        const r = await csrfFetch("/api/ai/config", {method: "POST", body});
        if (!r.ok) {
            setStatus(await r.text(), "bad");
            return;
        }
        const saved = await r.json();
        endpointEl.value = saved.endpoint;
        setStatus("Saved.", "ok");
    });

    // The test prompt runs as a server-side job (like portfolio analysis) so a long prompt
    // on a slow local model can't hit an HTTP timeout; we poll for the result.
    const cancelChatBtn = document.getElementById("ai-cancel-btn");
    let chatJobId = null;
    cancelChatBtn.addEventListener("click", async () => {
        if (!chatJobId) {
            return;
        }
        cancelChatBtn.disabled = true;
        const r = await csrfFetch(`/api/ai/analyze/${chatJobId}/cancel`, {method: "POST"});
        if (!r.ok && r.status !== 409) {
            cancelChatBtn.disabled = false;
        }
    });

    function chatFail(message) {
        responseEl.classList.add("bad");
        responseEl.textContent = message;
    }

    chatForm.addEventListener("submit", async e => {
        e.preventDefault();
        responseEl.hidden = false;
        responseEl.classList.remove("bad", "md");
        responseEl.textContent = "Starting...";
        sendBtn.disabled = true;
        try {
            const start = await csrfFetch("/api/ai/chat", {
                method: "POST",
                body: new URLSearchParams({prompt: document.getElementById("ai-prompt").value})
            });
            if (!start.ok) {
                chatFail(await start.text());
                return;
            }
            const {id} = await start.json();
            chatJobId = id;
            cancelChatBtn.hidden = false;
            cancelChatBtn.disabled = false;
            while (true) {
                const r = await fetch("/api/ai/analyze/" + id);
                if (!r.ok) {
                    chatFail(await r.text());
                    return;
                }
                const job = await r.json();
                if (job.state === "DONE") {
                    showMarkdown(responseEl, job.response, `${job.model}, ${(job.elapsedMs / 1000).toFixed(1)}s`);
                    return;
                }
                if (job.state === "CANCELLED") {
                    responseEl.textContent = "Cancelled.";
                    return;
                }
                if (job.state === "FAILED") {
                    chatFail(job.error || "Request failed.");
                    return;
                }
                responseEl.textContent = `Thinking... ${Math.round(job.elapsedMs / 1000)}s elapsed. Local models can take several minutes.`;
                await new Promise(res => setTimeout(res, 2000));
            }
        } catch (err) {
            chatFail("Request failed: " + err.message);
        } finally {
            sendBtn.disabled = false;
            cancelChatBtn.hidden = true;
        }
    });

    const customEl = document.getElementById("ai-custom-prompt");
    const promptStatus = document.getElementById("ai-prompt-status");
    document.getElementById("ai-prompt-form").addEventListener("submit", async e => {
        e.preventDefault();
        const r = await csrfFetch("/api/ai/prompt", {
            method: "POST",
            body: new URLSearchParams({customPrompt: customEl.value})
        });
        promptStatus.className = "ai-status " + (r.ok ? "ok" : "bad");
        if (r.ok) {
            customEl.value = (await r.json()).customPrompt;
            promptStatus.textContent = "Saved.";
        } else {
            promptStatus.textContent = await r.text();
        }
    });

    fetch("/api/ai/config").then(r => r.json()).then(c => {
        endpointEl.value = c.endpoint;
        setProvider(c.provider || "auto");
        customEl.value = c.customPrompt || "";
        testConnection(c.model);
    });
});

// Portfolio analysis via the configured AI provider.
document.addEventListener("DOMContentLoaded", () => {
    const btn = document.getElementById("analyze-btn");
    const dialog = document.getElementById("analysis-dialog");
    if (!btn || !dialog) {
        return;
    }
    const out = document.getElementById("analysis-output");
    document.getElementById("analysis-close").addEventListener("click", () => dialog.close());
    dialog.addEventListener("click", e => {
        if (e.target === dialog) {
            dialog.close();
        }
    });

    // The analysis runs as a server-side job that can take many minutes with a local model.
    // We poll for it rather than holding one long HTTP request open. Closing the popup just
    // stops polling; clicking Analyze again rejoins the job that is still running.
    let pollToken = 0;
    dialog.addEventListener("close", () => {
        try { sessionStorage.removeItem("analysisPopupOpen"); } catch (e) { /* ignore */ }
        pollToken++;
        cancelBtn.hidden = true;
        document.dispatchEvent(new CustomEvent("analysis-state-changed"));
    });

    const cancelBtn = document.getElementById("analysis-cancel");
    let currentJobId = null;
    cancelBtn.addEventListener("click", async () => {
        if (!currentJobId) {
            return;
        }
        cancelBtn.disabled = true;
        const r = await csrfFetch(`/api/ai/analyze/${currentJobId}/cancel`, {method: "POST"});
        // The polling loop picks up the CANCELLED state; a 409 means it had just finished.
        if (!r.ok && r.status !== 409) {
            cancelBtn.disabled = false;
        }
    });

    function fail(message) {
        out.classList.add("bad");
        out.textContent = message;
    }

    // rejoinOnly: reattach to a running analysis (after a page reload) without ever starting a new one.
    async function run(rejoinOnly) {
        const token = ++pollToken;
        out.classList.remove("bad", "md");
        out.textContent = rejoinOnly ? "Reconnecting to the running analysis..." : "Starting analysis...";
        dialog.showModal();
        try { sessionStorage.setItem("analysisPopupOpen", "1"); } catch (e) { /* ignore */ }
        btn.hidden = true;
        try {
            const start = await csrfFetch("/api/ai/analyze" + (rejoinOnly ? "?rejoinOnly=true" : ""), {method: "POST"});
            if (!start.ok) {
                if (rejoinOnly) {
                    dialog.close(); // it finished in the meantime; the status pill / History show the result
                    document.dispatchEvent(new CustomEvent("analysis-state-changed"));
                    return;
                }
                fail(await start.text());
                return;
            }
            const {id} = await start.json();
            currentJobId = id;
            cancelBtn.hidden = false;
            cancelBtn.disabled = false;
            while (token === pollToken) {
                const r = await fetch("/api/ai/analyze/" + id);
                if (token !== pollToken) {
                    return;
                }
                if (!r.ok) {
                    fail(await r.text());
                    return;
                }
                const job = await r.json();
                if (job.state === "DONE") {
                    showMarkdown(out, job.response, `${job.model}, ${(job.elapsedMs / 1000).toFixed(1)}s`);
                    markLatestAnalysisSeen();
                    return;
                }
                if (job.state === "CANCELLED") {
                    out.textContent = "Analysis cancelled.";
                    return;
                }
                if (job.state === "FAILED") {
                    fail(job.error || "Analysis failed.");
                    return;
                }
                out.textContent = `${job.phase || "Analyzing your portfolio"}... ${Math.round(job.elapsedMs / 1000)}s elapsed. `
                    + "Local models can take several minutes; you can close this window and click Analyze again later to rejoin.";
                await new Promise(res => setTimeout(res, 2000));
            }
        } catch (err) {
            if (token === pollToken) {
                fail("Request failed: " + err.message);
            }
        } finally {
            if (token === pollToken) {
                cancelBtn.hidden = true;
                // The status watcher decides whether the Analyze button comes back (it stays hidden while a job runs).
                document.dispatchEvent(new CustomEvent("analysis-state-changed"));
            }
        }
    }

    btn.addEventListener("click", () => run(false));
    document.addEventListener("rejoin-analysis", () => run(true));
});

// Saved portfolio analyses: every completed analysis is stored server-side for later reference.
document.addEventListener("DOMContentLoaded", () => {
    const btn = document.getElementById("history-btn");
    const dialog = document.getElementById("history-dialog");
    if (!btn || !dialog) {
        return;
    }
    const list = document.getElementById("history-list");
    const out = document.getElementById("history-output");
    const emptyEl = document.getElementById("history-empty");
    const promptWrap = document.getElementById("history-prompt-wrap");
    const promptEl = document.getElementById("history-prompt");
    const delBtn = document.getElementById("history-delete");
    let selectedId = null;

    document.getElementById("history-close").addEventListener("click", () => dialog.close());
    dialog.addEventListener("click", e => {
        if (e.target === dialog) {
            dialog.close();
        }
    });

    function clearDetail() {
        selectedId = null;
        out.hidden = true;
        promptWrap.hidden = true;
        delBtn.hidden = true;
    }

    async function show(id) {
        const r = await fetch("/api/ai/analyses/" + id);
        if (!r.ok) {
            out.hidden = false;
            out.classList.add("bad");
            out.textContent = await r.text();
            return;
        }
        const a = await r.json();
        selectedId = a.id;
        out.classList.remove("bad");
        out.hidden = false;
        showMarkdown(out, a.response, `${a.model}, ${(a.durationMs / 1000).toFixed(1)}s`);
        promptEl.textContent = a.prompt;
        promptWrap.hidden = false;
        delBtn.hidden = false;
        list.querySelectorAll("button").forEach(b => b.classList.toggle("active", b.dataset.id === String(a.id)));
    }

    async function load(selectFirst) {
        const items = await (await fetch("/api/ai/analyses")).json();
        list.innerHTML = "";
        emptyEl.hidden = items.length > 0;
        items.forEach(a => {
            const li = document.createElement("li");
            const b = document.createElement("button");
            b.type = "button";
            b.dataset.id = a.id;
            b.textContent = new Date(a.createdAt).toLocaleString() + (a.model ? " - " + a.model : "");
            b.addEventListener("click", () => show(a.id));
            li.appendChild(b);
            list.appendChild(li);
        });
        if (!items.length) {
            clearDetail();
        } else if (selectFirst) {
            show(items[0].id);
        }
    }

    delBtn.addEventListener("click", async () => {
        if (selectedId === null || !confirm("Delete this saved analysis? This cannot be undone.")) {
            return;
        }
        const r = await csrfFetch(`/api/ai/analyses/${selectedId}/delete`, {method: "POST"});
        if (r.ok || r.status === 404) {
            clearDetail();
            load(true);
        }
    });

    function open(id) {
        clearDetail();
        if (!dialog.open) {
            dialog.showModal();
        }
        load(false).then(() => show(id)).catch(err => {
            emptyEl.hidden = false;
            emptyEl.textContent = "Could not load saved analyses: " + err.message;
        });
        markLatestAnalysisSeen();
    }

    btn.addEventListener("click", () => {
        clearDetail();
        dialog.showModal();
        load(true).catch(err => {
            emptyEl.hidden = false;
            emptyEl.textContent = "Could not load saved analyses: " + err.message;
        });
        markLatestAnalysisSeen();
    });
    document.addEventListener("open-history", e => open(e.detail.id));
});

// Speculation list: draw a small trend chart (daily closes, day added marked) for each entry.
document.addEventListener("DOMContentLoaded", () => {
    const sparks = document.querySelectorAll(".spark");
    if (!sparks.length) {
        return;
    }
    const NS = "http://www.w3.org/2000/svg";
    const W = 140, H = 36, PAD = 3;

    function draw(el, points, added) {
        if (points.length < 2) {
            el.textContent = "n/a";
            el.classList.add("muted");
            return;
        }
        const closes = points.map(p => p.close);
        const min = Math.min(...closes), max = Math.max(...closes);
        const span = max - min || 1;
        const x = i => PAD + (i * (W - 2 * PAD)) / (points.length - 1);
        const y = v => H - PAD - ((v - min) / span) * (H - 2 * PAD);
        const svg = document.createElementNS(NS, "svg");
        svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
        svg.setAttribute("width", W);
        svg.setAttribute("height", H);
        const line = document.createElementNS(NS, "polyline");
        line.setAttribute("points", closes.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" "));
        line.setAttribute("fill", "none");
        line.setAttribute("stroke", "#64748b");
        line.setAttribute("stroke-width", "1.5");
        svg.appendChild(line);
        // Mark the first close on/after the day the stock was added, if it falls inside this range.
        const idx = added >= points[0].date ? points.findIndex(p => p.date >= added) : -1;
        if (idx >= 0) {
            const dot = document.createElementNS(NS, "circle");
            dot.setAttribute("cx", x(idx).toFixed(1));
            dot.setAttribute("cy", y(closes[idx]).toFixed(1));
            dot.setAttribute("r", "3");
            dot.setAttribute("fill", "#2563eb");
            svg.appendChild(dot);
        }
        const last = closes[closes.length - 1];
        const tip = `${points[0].date}: ${closes[0].toFixed(2)} -> ${points[points.length - 1].date}: ${last.toFixed(2)} (blue dot = day added)`;
        el.title = tip;
        el.textContent = "";
        el.appendChild(svg);
    }

    const buttons = document.querySelectorAll(".range-btn");
    const label = document.getElementById("spark-range-label");
    let current = "3mo";
    try {
        const saved = localStorage.getItem("sparkRange");
        if (saved && document.querySelector(`.range-btn[data-range="${saved}"]`)) {
            current = saved;
        }
    } catch (e) { /* ignore */ }

    let token = 0;
    function load() {
        const mine = ++token;
        buttons.forEach(b => b.classList.toggle("active", b.dataset.range === current));
        const active = document.querySelector(`.range-btn[data-range="${current}"]`);
        label.textContent = "(" + active.textContent + ")";
        sparks.forEach(el => {
            el.textContent = "";
            fetch(`/api/speculation/${el.dataset.id}/history?range=${current}`)
                .then(r => r.ok ? r.json() : [])
                .then(points => {
                    if (mine === token) {
                        draw(el, points, el.dataset.added);
                    }
                })
                .catch(() => {
                    if (mine === token) {
                        el.textContent = "n/a";
                    }
                });
        });
    }

    buttons.forEach(b => b.addEventListener("click", () => {
        current = b.dataset.range;
        try { localStorage.setItem("sparkRange", current); } catch (e) { /* ignore */ }
        load();
    }));
    load();
});

// Chat about the speculation stocks. The conversation lives in the page; each answer is a
// background job that we poll, so a slow local model can't hit an HTTP timeout.
document.addEventListener("DOMContentLoaded", () => {
    const form = document.getElementById("spec-chat-form");
    if (!form) {
        return;
    }
    const log = document.getElementById("spec-chat-log");
    const input = document.getElementById("spec-chat-input");
    const sendBtn = document.getElementById("spec-chat-send");
    const cancelBtn = document.getElementById("spec-chat-cancel");
    const conversation = [];
    let jobId = null;

    function bubble(role, text) {
        const div = document.createElement("div");
        div.className = "chat-msg chat-" + role;
        if (role === "assistant") {
            div.classList.add("md");
            div.innerHTML = window.renderMarkdown(text);
        } else {
            div.textContent = text;
        }
        log.appendChild(div);
        log.scrollTop = log.scrollHeight;
        return div;
    }

    function status(text, bad) {
        const div = document.createElement("div");
        div.className = "chat-msg chat-status" + (bad ? " bad" : "");
        div.textContent = text;
        log.appendChild(div);
        log.scrollTop = log.scrollHeight;
        return div;
    }

    document.getElementById("spec-chat-clear").addEventListener("click", () => {
        if (sendBtn.disabled) {
            return;
        }
        conversation.length = 0;
        log.innerHTML = "";
    });

    cancelBtn.addEventListener("click", async () => {
        if (!jobId) {
            return;
        }
        cancelBtn.disabled = true;
        const r = await csrfFetch(`/api/ai/analyze/${jobId}/cancel`, {method: "POST"});
        if (!r.ok && r.status !== 409) {
            cancelBtn.disabled = false;
        }
    });

    input.addEventListener("keydown", e => {
        if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            form.requestSubmit();
        }
    });

    form.addEventListener("submit", async e => {
        e.preventDefault();
        const question = input.value.trim();
        if (!question) {
            return;
        }
        bubble("user", question);
        conversation.push({role: "user", content: question});
        input.value = "";
        sendBtn.disabled = true;
        const wait = status("Starting...");
        let answered = false;
        try {
            const start = await csrfFetch("/api/speculation/chat", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify(conversation)
            });
            if (!start.ok) {
                wait.textContent = await start.text();
                wait.classList.add("bad");
                return;
            }
            const {id} = await start.json();
            jobId = id;
            cancelBtn.hidden = false;
            cancelBtn.disabled = false;
            while (true) {
                const r = await fetch("/api/ai/analyze/" + id);
                if (!r.ok) {
                    wait.textContent = await r.text();
                    wait.classList.add("bad");
                    return;
                }
                const job = await r.json();
                if (job.state === "DONE") {
                    wait.remove();
                    const div = bubble("assistant", job.response);
                    const meta = document.createElement("div");
                    meta.className = "ai-meta";
                    meta.textContent = `${job.model}, ${(job.elapsedMs / 1000).toFixed(1)}s`;
                    div.appendChild(meta);
                    conversation.push({role: "assistant", content: job.response});
                    answered = true;
                    return;
                }
                if (job.state === "CANCELLED") {
                    wait.textContent = "Cancelled.";
                    return;
                }
                if (job.state === "FAILED") {
                    wait.textContent = job.error || "Request failed.";
                    wait.classList.add("bad");
                    return;
                }
                wait.textContent = `${job.phase || "Thinking"}... ${Math.round(job.elapsedMs / 1000)}s elapsed. Local models can take several minutes.`;
                await new Promise(res => setTimeout(res, 2000));
            }
        } catch (err) {
            wait.textContent = "Request failed: " + err.message;
            wait.classList.add("bad");
        } finally {
            if (!answered) {
                // Drop the unanswered question so the next request still ends on a fresh user turn.
                conversation.pop();
            }
            sendBtn.disabled = false;
            cancelBtn.hidden = true;
            jobId = null;
        }
    });
});

// Analyst rating popup for speculation stocks: consensus, price targets, analyst actions and news.
document.addEventListener("DOMContentLoaded", () => {
    const dialog = document.getElementById("rating-dialog");
    const buttons = document.querySelectorAll(".info-btn");
    if (!dialog || !buttons.length) {
        return;
    }
    const body = document.getElementById("rating-body");
    const title = document.getElementById("rating-title");
    document.getElementById("rating-close").addEventListener("click", () => dialog.close());
    dialog.addEventListener("click", e => {
        if (e.target === dialog) {
            dialog.close();
        }
    });

    // Build DOM with textContent throughout: headlines and firm names come from outside.
    function el(tag, cls, text) {
        const e = document.createElement(tag);
        if (cls) {
            e.className = cls;
        }
        if (text !== undefined) {
            e.textContent = text;
        }
        return e;
    }
    const money = (sym, v) => v == null ? "-" : sym + Number(v).toLocaleString(undefined, {minimumFractionDigits: 2, maximumFractionDigits: 2});

    function section(heading) {
        const s = el("div", "rating-section");
        s.appendChild(el("h3", null, heading));
        body.appendChild(s);
        return s;
    }

    function render(d) {
        body.textContent = "";
        title.textContent = `${d.symbol} - ${d.companyName || ""}`;
        const r = d.rating;

        const head = section("Analyst consensus");
        if (r.consensusKey) {
            const row = el("div", "rating-head");
            row.appendChild(el("span", "rating-badge rating-" + r.consensusKey, r.consensus));
            const bits = [];
            if (r.mean != null) {
                bits.push(`score ${r.mean.toFixed(2)} (1 = strong buy, 5 = strong sell)`);
            }
            if (r.analystCount != null) {
                bits.push(`${r.analystCount} analysts`);
            }
            row.appendChild(el("span", "muted", bits.join(" - ")));
            head.appendChild(row);
            const c = r.counts;
            if (c) {
                const total = c.strongBuy + c.buy + c.hold + c.sell + c.strongSell;
                if (total > 0) {
                    const bar = el("div", "rating-bar");
                    const legend = el("div", "rating-legend");
                    [["strongBuy", "Strong Buy", "strong_buy"], ["buy", "Buy", "buy"], ["hold", "Hold", "hold"],
                        ["sell", "Sell", "sell"], ["strongSell", "Strong Sell", "strong_sell"]].forEach(([k, name, cls]) => {
                        if (c[k] > 0) {
                            const seg = el("span", "rating-seg rating-" + cls);
                            seg.style.flexGrow = c[k];
                            seg.title = `${name}: ${c[k]}`;
                            bar.appendChild(seg);
                        }
                        legend.appendChild(el("span", "rating-leg rating-" + cls, `${name} ${c[k]}`));
                    });
                    head.appendChild(bar);
                    head.appendChild(legend);
                }
            }
        } else {
            head.appendChild(el("p", "muted", "No analyst coverage found for this stock (common for smaller or non-US listings)."));
        }

        const t = r.targets;
        if (t && (t.mean != null || t.high != null || t.low != null)) {
            const s = section("Analyst price targets");
            const line = `Average ${money(d.currencySymbol, t.mean)}  |  High ${money(d.currencySymbol, t.high)}  |  Low ${money(d.currencySymbol, t.low)}`;
            s.appendChild(el("p", null, line));
            if (t.mean != null && d.currentPrice) {
                const up = (t.mean - d.currentPrice) / d.currentPrice * 100;
                s.appendChild(el("p", up >= 0 ? "gain" : "loss",
                    `${up >= 0 ? "+" : ""}${up.toFixed(1)}% vs the current price of ${money(d.currencySymbol, d.currentPrice)}`));
            }
        }

        if (d.ai || d.note) {
            const s = section(d.ai ? "Why the AI suggested it" : "Your note");
            s.appendChild(el("p", null, d.note || "No reason was recorded."));
        }

        if (r.actions && r.actions.length) {
            const s = section("Recent analyst actions");
            const table = el("table", "rating-table");
            const thead = el("thead");
            const hr = el("tr");
            ["Date", "Firm", "Action", "Rating", "Target"].forEach(h => hr.appendChild(el("th", null, h)));
            thead.appendChild(hr);
            table.appendChild(thead);
            const tb = el("tbody");
            r.actions.forEach(a => {
                const tr = el("tr");
                tr.appendChild(el("td", null, a.date));
                tr.appendChild(el("td", null, a.firm));
                tr.appendChild(el("td", null, a.action));
                tr.appendChild(el("td", null, a.fromGrade && a.fromGrade !== a.toGrade ? `${a.fromGrade} \u2192 ${a.toGrade}` : a.toGrade));
                tr.appendChild(el("td", null, a.priceTarget == null ? "-" : money(d.currencySymbol, a.priceTarget)));
                tb.appendChild(tr);
            });
            table.appendChild(tb);
            const wrap = el("div", "table-scroll");
            wrap.appendChild(table);
            s.appendChild(wrap);
        }

        const news = section("Recent news");
        if (r.news && r.news.length) {
            const ul = el("ul", "rating-news");
            r.news.forEach(n => {
                const li = el("li");
                const a = el("a", null, n.title);
                a.href = n.link;
                a.target = "_blank";
                a.rel = "noopener noreferrer";
                li.appendChild(a);
                li.appendChild(el("small", "muted", ` ${n.publisher}${n.date ? " - " + n.date : ""}`));
                ul.appendChild(li);
            });
            news.appendChild(ul);
        } else {
            news.appendChild(el("p", "muted", "No recent news found."));
        }
    }

    let token = 0;
    buttons.forEach(btn => btn.addEventListener("click", async () => {
        const mine = ++token;
        title.textContent = `${btn.dataset.symbol} - Analyst Rating`;
        body.textContent = "Loading analyst data...";
        dialog.showModal();
        try {
            const r = await fetch(`/api/speculation/${btn.dataset.id}/rating`);
            if (mine !== token) {
                return;
            }
            if (!r.ok) {
                body.textContent = await r.text();
                return;
            }
            render(await r.json());
        } catch (err) {
            if (mine === token) {
                body.textContent = "Could not load analyst data: " + err.message;
            }
        }
    }));
});

// Analysis status next to the Analyze button: shows whether an analysis is still running or has
// finished (and is waiting in History), even after the popup was closed or the page reloaded.
document.addEventListener("DOMContentLoaded", () => {
    const pill = document.getElementById("analysis-status");
    const dot = document.getElementById("history-dot");
    const analyzeBtn = document.getElementById("analyze-btn");
    const refreshForm = document.getElementById("refresh-prices-form");
    if (!pill) {
        return;
    }
    let timer = null;
    let action = null;
    let dismissedFailure = null;
    try { dismissedFailure = sessionStorage.getItem("dismissedAnalysisFailure"); } catch (e) { /* ignore */ }

    function show(text, cls, onClick) {
        pill.hidden = false;
        pill.className = "analysis-status " + cls;
        pill.textContent = text;
        action = onClick;
    }

    async function refresh() {
        let job = null;
        try {
            const r = await fetch("/api/ai/analyze/latest");
            job = r.status === 200 ? await r.json() : null;
        } catch (e) {
            return; // server unreachable: keep whatever is shown
        }
        clearTimeout(timer);
        // While an analysis runs the pill replaces the Analyze button: there is only ever one at a time.
        const running = !!(job && job.state === "RUNNING");
        if (analyzeBtn) {
            analyzeBtn.hidden = running;
        }
        // The analysis refreshes prices itself, so a separate refresh would only collide with it.
        if (refreshForm) {
            refreshForm.hidden = running;
        }
        if (job && job.state === "RUNNING") {
            const secs = Math.round(job.elapsedMs / 1000);
            const time = secs >= 60 ? `${Math.floor(secs / 60)}m ${secs % 60}s` : `${secs}s`;
            show(`Analysis running - ${job.phase || "working"} (${time})`, "running",
                () => document.dispatchEvent(new CustomEvent("rejoin-analysis")));
            timer = setTimeout(refresh, 3000);
            return;
        }
        try {
            const newest = await newestAnalysisId();
            let seen = getSeenAnalysis();
            if (seen === null) {
                // First visit from this browser: don't flag analyses that already existed.
                seen = newest;
                setSeenAnalysis(seen);
                return;
            }
            const unseen = newest > seen;
            dot.hidden = !unseen;
            if (unseen) {
                show("Analysis complete - view in History", "ready", () => {
                    document.dispatchEvent(new CustomEvent("open-history", {detail: {id: newest}}));
                });
                return;
            }
        } catch (e) { /* fall through */ }
        if (job && job.state === "FAILED" && job.id !== dismissedFailure) {
            show("Analysis failed: " + (job.error || "unknown error") + " (click to dismiss)", "failed", () => {
                dismissedFailure = job.id;
                try { sessionStorage.setItem("dismissedAnalysisFailure", job.id); } catch (e) { /* ignore */ }
                refresh();
            });
            return;
        }
        pill.hidden = true;
        action = null;
    }

    pill.addEventListener("click", () => {
        if (action) {
            action();
        }
    });

    // After a page reload, put the user back where they were: the popup on a still-running analysis,
    // or the finished result in History.
    async function restoreAfterReload() {
        let wasOpen = false;
        try { wasOpen = sessionStorage.getItem("analysisPopupOpen") === "1"; } catch (e) { /* ignore */ }
        if (!wasOpen) {
            return;
        }
        try { sessionStorage.removeItem("analysisPopupOpen"); } catch (e) { /* ignore */ }
        try {
            const r = await fetch("/api/ai/analyze/latest");
            const job = r.status === 200 ? await r.json() : null;
            if (job && job.state === "RUNNING") {
                document.dispatchEvent(new CustomEvent("rejoin-analysis"));
            } else if (job && job.state === "DONE") {
                document.dispatchEvent(new CustomEvent("open-history", {detail: {id: await newestAnalysisId()}}));
            }
        } catch (e) { /* ignore */ }
    }
    document.addEventListener("analysis-state-changed", refresh);
    // Starting an analysis from the popup: pick up the new running job right away.
    if (analyzeBtn) {
        analyzeBtn.addEventListener("click", () => setTimeout(refresh, 1500));
    }
    refresh();
    restoreAfterReload();
});
