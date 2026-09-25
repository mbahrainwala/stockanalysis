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
            const response = await fetch(CELLS[type].url, {method: "POST", body});
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
        const r = await fetch("/api/ai/config", {method: "POST", body});
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
        const r = await fetch(`/api/ai/analyze/${chatJobId}/cancel`, {method: "POST"});
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
            const start = await fetch("/api/ai/chat", {
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
        const r = await fetch("/api/ai/prompt", {
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
        pollToken++;
        btn.disabled = false;
        cancelBtn.hidden = true;
    });

    const cancelBtn = document.getElementById("analysis-cancel");
    let currentJobId = null;
    cancelBtn.addEventListener("click", async () => {
        if (!currentJobId) {
            return;
        }
        cancelBtn.disabled = true;
        const r = await fetch(`/api/ai/analyze/${currentJobId}/cancel`, {method: "POST"});
        // The polling loop picks up the CANCELLED state; a 409 means it had just finished.
        if (!r.ok && r.status !== 409) {
            cancelBtn.disabled = false;
        }
    });

    function fail(message) {
        out.classList.add("bad");
        out.textContent = message;
    }

    btn.addEventListener("click", async () => {
        const token = ++pollToken;
        out.classList.remove("bad", "md");
        out.textContent = "Starting analysis...";
        dialog.showModal();
        btn.disabled = true;
        try {
            const start = await fetch("/api/ai/analyze", {method: "POST"});
            if (!start.ok) {
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
                out.textContent = `Analyzing your portfolio... ${Math.round(job.elapsedMs / 1000)}s elapsed. `
                    + "Local models can take several minutes; you can close this window and click Analyze again later to rejoin.";
                await new Promise(res => setTimeout(res, 2000));
            }
        } catch (err) {
            if (token === pollToken) {
                fail("Request failed: " + err.message);
            }
        } finally {
            if (token === pollToken) {
                btn.disabled = false;
                cancelBtn.hidden = true;
            }
        }
    });
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
        const r = await fetch(`/api/ai/analyses/${selectedId}/delete`, {method: "POST"});
        if (r.ok || r.status === 404) {
            clearDetail();
            load(true);
        }
    });

    btn.addEventListener("click", () => {
        clearDetail();
        dialog.showModal();
        load(true).catch(err => {
            emptyEl.hidden = false;
            emptyEl.textContent = "Could not load saved analyses: " + err.message;
        });
    });
});
