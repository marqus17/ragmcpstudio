(() => {
    "use strict";

    // ----- Theme -----
    const themeToggleBtn = document.getElementById("theme-toggle");
    const themeToggleIcon = document.getElementById("theme-toggle-icon");

    function applyTheme(theme) {
        document.documentElement.setAttribute("data-theme", theme);
        themeToggleIcon.innerHTML = theme === "dark" ? "&#9728;" : "&#9789;";
        try {
            localStorage.setItem("rag-mcp-theme", theme);
        } catch (_) {
            // ignore storage errors
        }
    }

    const savedTheme = (() => {
        try {
            return localStorage.getItem("rag-mcp-theme");
        } catch (_) {
            return null;
        }
    })();
    const prefersDark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
    applyTheme(savedTheme || (prefersDark ? "dark" : "light"));

    themeToggleBtn.addEventListener("click", () => {
        const current = document.documentElement.getAttribute("data-theme");
        applyTheme(current === "dark" ? "light" : "dark");
    });

    // ----- Toast -----
    const toastContainer = document.getElementById("toast-container");
    function showToast(message, type = "info") {
        const el = document.createElement("div");
        el.className = `toast ${type === "success" ? "toast-success" : type === "error" ? "toast-error" : ""}`;
        el.textContent = message;
        toastContainer.appendChild(el);
        setTimeout(() => {
            el.remove();
        }, 3200);
    }

    // ----- HTTP Helper -----
    async function apiFetch(url, options = {}) {
        const response = await fetch(url, {
            headers: {
                "Content-Type": "application/json",
                ...(options.headers || {})
            },
            ...options
        });

        let payload = null;
        const contentType = response.headers.get("content-type") || "";
        if (contentType.includes("application/json")) {
            payload = await response.json();
        } else {
            const text = await response.text();
            payload = text ? { error: text } : null;
        }

        if (!response.ok) {
            const message = (payload && payload.error) ? payload.error : `Erreur HTTP ${response.status}`;
            throw new Error(message);
        }
        return payload;
    }

    // ----- Tabs -----
    const tabButtons = document.querySelectorAll(".tab-btn");
    const tabPanels = {
        ingestion: document.getElementById("panel-ingestion"),
        mcp: document.getElementById("panel-mcp")
    };

    function switchTab(tabName) {
        tabButtons.forEach((btn) => {
            const active = btn.dataset.tab === tabName;
            btn.classList.toggle("active", active);
            btn.setAttribute("aria-selected", String(active));
        });
        Object.entries(tabPanels).forEach(([key, panel]) => {
            panel.classList.toggle("active", key === tabName);
        });
    }

    tabButtons.forEach((btn) => {
        btn.addEventListener("click", () => switchTab(btn.dataset.tab));
    });

    // ----- Ingestion Controls -----
    const ingestionForm = document.getElementById("ingestion-form");
    const ingSourceDir = document.getElementById("ing-source-dir");
    const ingDbPath = document.getElementById("ing-db-path");
    const ingChunkSize = document.getElementById("ing-chunk-size");
    const ingChunkOverlap = document.getElementById("ing-chunk-overlap");
    const btnStartIngestion = document.getElementById("btn-start-ingestion");
    const btnCancelIngestion = document.getElementById("btn-cancel-ingestion");
    const btnUseDbInMcp = document.getElementById("btn-use-db-in-mcp");

    const ingStateBadge = document.getElementById("ing-state-badge");
    const ingProgressWrap = document.getElementById("ing-progress-wrap");
    const ingProgressFill = document.getElementById("ing-progress-fill");
    const ingPercent = document.getElementById("ing-percent");
    const ingCurrentFile = document.getElementById("ing-current-file");
    const statFiles = document.getElementById("stat-files");
    const statChunks = document.getElementById("stat-chunks");
    const statDuration = document.getElementById("stat-duration");
    const ingErrorBanner = document.getElementById("ing-error-banner");
    const ingLogConsole = document.getElementById("ing-log-console");
    const sseIndicator = document.getElementById("sse-indicator");
    const btnClearLogScroll = document.getElementById("btn-clear-log-scroll");

    btnClearLogScroll.addEventListener("click", () => {
        ingLogConsole.scrollTop = ingLogConsole.scrollHeight;
    });

    btnUseDbInMcp.addEventListener("click", () => {
        const dbPath = ingDbPath.value.trim();
        if (dbPath) {
            mcpDbPath.value = dbPath;
            switchTab("mcp");
            inspectDatabase();
        }
    });

    function formatDuration(ms) {
        if (!ms || ms <= 0) return "\u2014";
        if (ms < 1000) return `${ms} ms`;
        const seconds = (ms / 1000).toFixed(1);
        return `${seconds} s`;
    }

    function renderIngestionProgress(data) {
        if (!data) return;
        const state = (data.state || "IDLE").toUpperCase();
        const processed = Number(data.processedFiles || 0);
        const total = Number(data.totalFiles || 0);
        const chunks = Number(data.totalChunks || 0);

        const stateLabels = {
            IDLE: { text: "EN ATTENTE", cls: "state-idle" },
            RUNNING: { text: "EN COURS", cls: "state-running" },
            COMPLETED: { text: "TERMIN\u00c9", cls: "state-completed" },
            CANCELLED: { text: "ANNUL\u00c9", cls: "state-cancelled" },
            FAILED: { text: "\u00c9CHEC", cls: "state-failed" }
        };

        const badge = stateLabels[state] || stateLabels.IDLE;
        ingStateBadge.textContent = badge.text;
        ingStateBadge.className = `state-pill ${badge.cls}`;

        const isRunning = state === "RUNNING";
        btnStartIngestion.disabled = isRunning;
        btnCancelIngestion.disabled = !isRunning;

        if (state === "COMPLETED" && ingDbPath.value.trim()) {
            btnUseDbInMcp.classList.remove("hidden");
        }

        let percent = 0;
        if (state === "COMPLETED") {
            percent = 100;
        } else if (total > 0) {
            percent = Math.min(100, Math.round((processed / total) * 100));
        }

        ingProgressFill.style.width = `${percent}%`;
        ingProgressFill.classList.toggle("completed", state === "COMPLETED");
        ingProgressFill.classList.toggle("failed", state === "FAILED");
        ingProgressWrap.setAttribute("aria-valuenow", String(percent));
        ingPercent.textContent = `${percent} %`;

        if (data.currentFile) {
            ingCurrentFile.textContent = data.currentFile;
        } else if (state === "COMPLETED") {
            ingCurrentFile.textContent = "Indexation termin\u00e9e";
        } else if (state === "RUNNING") {
            ingCurrentFile.textContent = "Pr\u00e9paration...";
        } else {
            ingCurrentFile.textContent = "Aucun fichier en cours";
        }

        statFiles.textContent = `${processed} / ${total}`;
        statChunks.textContent = String(chunks);
        statDuration.textContent = formatDuration(data.durationMs);

        if (state === "FAILED" && data.error) {
            ingErrorBanner.textContent = data.error;
            ingErrorBanner.classList.remove("hidden");
        } else {
            ingErrorBanner.classList.add("hidden");
        }

        if (Array.isArray(data.logs) && data.logs.length > 0) {
            const wasAtBottom = ingLogConsole.scrollTop + ingLogConsole.clientHeight >= ingLogConsole.scrollHeight - 24;
            ingLogConsole.textContent = data.logs.join("\n");
            if (wasAtBottom) {
                ingLogConsole.scrollTop = ingLogConsole.scrollHeight;
            }
        } else if (state === "IDLE") {
            ingLogConsole.textContent = "En attente d'une t\u00e2che de vectorisation...";
        }
    }

    ingestionForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        ingErrorBanner.classList.add("hidden");

        const payload = {
            sourceDir: ingSourceDir.value.trim(),
            dbPath: ingDbPath.value.trim(),
            chunkSize: parseInt(ingChunkSize.value, 10) || 1000,
            chunkOverlap: parseInt(ingChunkOverlap.value, 10) || 0
        };

        try {
            const progress = await apiFetch("/api/ingestion/start", {
                method: "POST",
                body: JSON.stringify(payload)
            });
            renderIngestionProgress(progress);
            showToast("Vectorisation d\u00e9marr\u00e9e", "success");
        } catch (err) {
            ingErrorBanner.textContent = err.message;
            ingErrorBanner.classList.remove("hidden");
            showToast(err.message, "error");
        }
    });

    btnCancelIngestion.addEventListener("click", async () => {
        try {
            const progress = await apiFetch("/api/ingestion/cancel", { method: "POST" });
            renderIngestionProgress(progress);
            showToast("Annulation demand\u00e9e");
        } catch (err) {
            showToast(err.message, "error");
        }
    });

    // ----- SSE with Polling Fallback -----
    let eventSource = null;
    let pollTimer = null;

    function startPollingFallback() {
        if (pollTimer) return;
        sseIndicator.textContent = "Polling";
        pollTimer = setInterval(async () => {
            try {
                const status = await apiFetch("/api/ingestion/status");
                renderIngestionProgress(status);
            } catch (_) {
                // silent retry
            }
        }, 1500);
    }

    function stopPollingFallback() {
        if (pollTimer) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    function connectIngestionEvents() {
        if (typeof EventSource === "undefined") {
            startPollingFallback();
            return;
        }
        try {
            eventSource = new EventSource("/api/ingestion/events");
            eventSource.onopen = () => {
                sseIndicator.textContent = "SSE";
                stopPollingFallback();
            };
            eventSource.addEventListener("progress", (evt) => {
                try {
                    const data = JSON.parse(evt.data);
                    renderIngestionProgress(data);
                } catch (_) {
                    // ignore malformed event
                }
            });
            eventSource.onerror = () => {
                startPollingFallback();
            };
        } catch (_) {
            startPollingFallback();
        }
    }

    // ----- MCP Server & DB Inspection -----
    const headerMcpBadge = document.getElementById("header-mcp-badge");
    const headerMcpBadgeText = document.getElementById("header-mcp-badge-text");
    const mcpDbPath = document.getElementById("mcp-db-path");
    const btnInspectDb = document.getElementById("btn-inspect-db");
    const dbInspectError = document.getElementById("db-inspect-error");
    const dbMetadataCard = document.getElementById("db-metadata-card");
    const metaEmbedModel = document.getElementById("meta-embed-model");
    const metaDimensions = document.getElementById("meta-dimensions");
    const metaDocCount = document.getElementById("meta-doc-count");
    const metaChunkCount = document.getElementById("meta-chunk-count");
    const metaCreatedAt = document.getElementById("meta-created-at");
    const dbSourcesCount = document.getElementById("db-sources-count");
    const dbSourcesList = document.getElementById("db-sources-list");

    const mcpStatusPill = document.getElementById("mcp-status-pill");
    const mcpPort = document.getElementById("mcp-port");
    const mcpEndpointUrl = document.getElementById("mcp-endpoint-url");
    const mcpActiveDbLine = document.getElementById("mcp-active-db-line");
    const mcpActiveDb = document.getElementById("mcp-active-db");
    const mcpErrorBanner = document.getElementById("mcp-error-banner");
    const btnMcpStart = document.getElementById("btn-mcp-start");
    const btnMcpRestart = document.getElementById("btn-mcp-restart");
    const btnMcpStop = document.getElementById("btn-mcp-stop");
    const btnCopyEndpoint = document.getElementById("btn-copy-endpoint");
    const btnCopyOpencode = document.getElementById("btn-copy-opencode");
    const opencodeSnippet = document.getElementById("opencode-snippet");

    function buildLocalOpencodeSnippet(url) {
        return JSON.stringify({
            $schema: "https://opencode.ai/config.json",
            mcp: {
                "local-rag": {
                    type: "remote",
                    url: url || "http://localhost:8081/mcp",
                    enabled: true
                }
            }
        }, null, 2);
    }

    function renderMetadata(metadata) {
        if (!metadata) return;
        metaEmbedModel.textContent = metadata.embedModel || "\u2014";
        metaDimensions.textContent = metadata.dimensions != null ? String(metadata.dimensions) : "\u2014";
        metaDocCount.textContent = metadata.documentCount != null ? String(metadata.documentCount) : "0";
        metaChunkCount.textContent = metadata.chunkCount != null ? String(metadata.chunkCount) : "0";
        metaCreatedAt.textContent = metadata.createdAt || "\u2014";
        dbMetadataCard.classList.remove("hidden");
    }

    async function inspectDatabase() {
        const path = mcpDbPath.value.trim();
        dbInspectError.classList.add("hidden");
        if (!path) {
            dbInspectError.textContent = "Veuillez indiquer le chemin d'un fichier .db.";
            dbInspectError.classList.remove("hidden");
            return;
        }

        btnInspectDb.disabled = true;
        try {
            const data = await apiFetch(`/api/db/inspect?path=${encodeURIComponent(path)}`);
            renderMetadata(data.metadata);

            const sources = Array.isArray(data.sources) ? data.sources : [];
            dbSourcesCount.textContent = String(sources.length);
            dbSourcesList.innerHTML = "";

            if (sources.length === 0) {
                const li = document.createElement("li");
                li.textContent = "Aucune source index\u00e9e.";
                dbSourcesList.appendChild(li);
            } else {
                sources.forEach((src) => {
                    const li = document.createElement("li");
                    const nameSpan = document.createElement("span");
                    nameSpan.className = "mono";
                    nameSpan.textContent = src.source;
                    const countSpan = document.createElement("span");
                    countSpan.className = "muted";
                    countSpan.textContent = `${src.chunkCount} chunk(s)`;
                    li.appendChild(nameSpan);
                    li.appendChild(countSpan);
                    dbSourcesList.appendChild(li);
                });
            }
            showToast("Base SQLite inspect\u00e9e", "success");
        } catch (err) {
            dbMetadataCard.classList.add("hidden");
            dbInspectError.textContent = err.message;
            dbInspectError.classList.remove("hidden");
        } finally {
            btnInspectDb.disabled = false;
        }
    }

    btnInspectDb.addEventListener("click", inspectDatabase);

    function renderMcpStatus(status) {
        if (!status) return;
        const running = Boolean(status.running);
        const portVal = running && status.port ? status.port : (parseInt(mcpPort.value, 10) || 8081);
        const endpoint = (running && status.endpointUrl)
            ? status.endpointUrl
            : `http://localhost:${portVal}/mcp`;

        headerMcpBadge.classList.toggle("status-running", running);
        headerMcpBadge.classList.toggle("status-stopped", !running);
        headerMcpBadgeText.textContent = running ? `MCP Actif :${status.port}` : "MCP Arr\u00eat\u00e9";

        mcpStatusPill.textContent = running ? "EN LIGNE" : "ARR\u00caT\u00c9";
        mcpStatusPill.className = `state-pill ${running ? "state-completed" : "state-idle"}`;

        btnMcpStart.disabled = running;
        btnMcpStop.disabled = !running;

        mcpEndpointUrl.textContent = endpoint;
        opencodeSnippet.textContent = status.opencodeConfig || buildLocalOpencodeSnippet(endpoint);

        if (running && status.activeDbPath) {
            mcpActiveDb.textContent = status.activeDbPath;
            mcpActiveDbLine.classList.remove("hidden");
            if (!mcpDbPath.value.trim()) {
                mcpDbPath.value = status.activeDbPath;
            }
            if (status.metadata) {
                renderMetadata(status.metadata);
            }
        } else {
            mcpActiveDbLine.classList.add("hidden");
        }

        if (status.lastError) {
            mcpErrorBanner.textContent = status.lastError;
            mcpErrorBanner.classList.remove("hidden");
        } else {
            mcpErrorBanner.classList.add("hidden");
        }
    }

    mcpPort.addEventListener("input", () => {
        const portVal = parseInt(mcpPort.value, 10) || 8081;
        if (btnMcpStop.disabled) {
            const previewUrl = `http://localhost:${portVal}/mcp`;
            mcpEndpointUrl.textContent = previewUrl;
            opencodeSnippet.textContent = buildLocalOpencodeSnippet(previewUrl);
        }
    });

    async function refreshMcpStatus() {
        try {
            const status = await apiFetch("/api/mcp/status");
            if (status.running && status.port) {
                mcpPort.value = String(status.port);
            }
            renderMcpStatus(status);
        } catch (_) {
            // ignore initial fetch errors
        }
    }

    async function invokeMcpAction(endpoint, requirePayload, successMsg) {
        mcpErrorBanner.classList.add("hidden");
        const options = { method: "POST" };
        if (requirePayload) {
            const dbPath = mcpDbPath.value.trim();
            if (!dbPath) {
                mcpErrorBanner.textContent = "Veuillez s\u00e9lectionner un fichier .db avant de d\u00e9marrer le serveur MCP.";
                mcpErrorBanner.classList.remove("hidden");
                return;
            }
            options.body = JSON.stringify({
                port: parseInt(mcpPort.value, 10) || 8081,
                dbPath
            });
        }

        try {
            const status = await apiFetch(endpoint, options);
            renderMcpStatus(status);
            showToast(successMsg, "success");
        } catch (err) {
            mcpErrorBanner.textContent = err.message;
            mcpErrorBanner.classList.remove("hidden");
            showToast(err.message, "error");
        }
    }

    btnMcpStart.addEventListener("click", () => invokeMcpAction("/api/mcp/start", true, "Serveur MCP d\u00e9marr\u00e9"));
    btnMcpRestart.addEventListener("click", () => invokeMcpAction("/api/mcp/restart", true, "Serveur MCP red\u00e9marr\u00e9"));
    btnMcpStop.addEventListener("click", () => invokeMcpAction("/api/mcp/stop", false, "Serveur MCP arr\u00eat\u00e9"));

    async function copyToClipboard(text, label) {
        try {
            await navigator.clipboard.writeText(text);
            showToast(`${label} copi\u00e9`, "success");
        } catch (_) {
            const ta = document.createElement("textarea");
            ta.value = text;
            document.body.appendChild(ta);
            ta.select();
            document.execCommand("copy");
            ta.remove();
            showToast(`${label} copi\u00e9`, "success");
        }
    }

    btnCopyEndpoint.addEventListener("click", () => copyToClipboard(mcpEndpointUrl.textContent, "URL MCP"));
    btnCopyOpencode.addEventListener("click", () => copyToClipboard(opencodeSnippet.textContent, "opencode.json"));

    // ----- Search Tester -----
    const searchForm = document.getElementById("search-test-form");
    const searchQuery = document.getElementById("search-query");
    const searchTopK = document.getElementById("search-topk");
    const btnRunSearch = document.getElementById("btn-run-search");
    const searchErrorBanner = document.getElementById("search-error-banner");
    const searchResultsMeta = document.getElementById("search-results-meta");
    const searchResultsList = document.getElementById("search-results-list");

    searchForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        searchErrorBanner.classList.add("hidden");
        searchResultsMeta.classList.add("hidden");
        searchResultsList.innerHTML = "";

        const dbPath = mcpDbPath.value.trim();
        const query = searchQuery.value.trim();
        if (!dbPath) {
            searchErrorBanner.textContent = "Veuillez indiquer un fichier .db ci-dessus pour tester la recherche.";
            searchErrorBanner.classList.remove("hidden");
            return;
        }
        if (!query) {
            return;
        }

        btnRunSearch.disabled = true;
        const startTime = performance.now();
        try {
            const results = await apiFetch("/api/search/test", {
                method: "POST",
                body: JSON.stringify({
                    dbPath,
                    query,
                    topK: parseInt(searchTopK.value, 10) || 5
                })
            });

            const elapsed = Math.round(performance.now() - startTime);
            searchResultsMeta.textContent = `${results.length} r\u00e9sultat(s) en ${elapsed} ms`;
            searchResultsMeta.classList.remove("hidden");

            if (results.length === 0) {
                const empty = document.createElement("p");
                empty.className = "hint";
                empty.textContent = "Aucun chunk pertinent trouv\u00e9 pour cette requ\u00eate.";
                searchResultsList.appendChild(empty);
                return;
            }

            results.forEach((item, idx) => {
                const card = document.createElement("article");
                card.className = "result-card";

                const header = document.createElement("div");
                header.className = "result-header";

                const left = document.createElement("div");
                const title = document.createElement("div");
                title.className = "result-title";
                title.textContent = `#${idx + 1} \u2014 ${item.title || "Sans titre"}`;
                const src = document.createElement("div");
                src.className = "result-source";
                src.textContent = item.source || "";
                left.appendChild(title);
                left.appendChild(src);

                const badges = document.createElement("div");
                badges.className = "score-badges";

                const rrfPill = document.createElement("span");
                rrfPill.className = "score-pill primary";
                rrfPill.textContent = `RRF ${Number(item.score || 0).toFixed(4)}`;

                const vecPill = document.createElement("span");
                vecPill.className = "score-pill";
                vecPill.textContent = `Cosinus ${Number(item.vectorScore || 0).toFixed(4)}`;

                const ftsPill = document.createElement("span");
                ftsPill.className = "score-pill";
                ftsPill.textContent = `FTS5 ${Number(item.ftsScore || 0).toFixed(4)}`;

                badges.appendChild(rrfPill);
                badges.appendChild(vecPill);
                badges.appendChild(ftsPill);

                header.appendChild(left);
                header.appendChild(badges);

                const content = document.createElement("pre");
                content.className = "result-content";
                content.textContent = item.content || "";

                card.appendChild(header);
                card.appendChild(content);
                searchResultsList.appendChild(card);
            });
        } catch (err) {
            searchErrorBanner.textContent = err.message;
            searchErrorBanner.classList.remove("hidden");
        } finally {
            btnRunSearch.disabled = false;
        }
    });

    // ----- Filesystem Picker Modal -----
    const fsModal = document.getElementById("fs-modal");
    const fsModalTitle = document.getElementById("fs-modal-title");
    const fsModalClose = document.getElementById("fs-modal-close");
    const fsBtnUp = document.getElementById("fs-btn-up");
    const fsCurrentPath = document.getElementById("fs-current-path");
    const fsBtnGo = document.getElementById("fs-btn-go");
    const fsModalError = document.getElementById("fs-modal-error");
    const fsEntries = document.getElementById("fs-entries");
    const fsSaveFilenameRow = document.getElementById("fs-save-filename-row");
    const fsSaveFilename = document.getElementById("fs-save-filename");
    const fsBtnCancel = document.getElementById("fs-btn-cancel");
    const fsBtnSelect = document.getElementById("fs-btn-select");

    const fsState = {
        targetInput: null,
        mode: "dir", // "dir" | "db" | "save-db"
        currentPath: "",
        parentPath: null,
        selectedFilePath: null
    };

    function formatBytes(bytes) {
        if (!bytes || bytes <= 0) return "";
        const units = ["o", "Ko", "Mo", "Go"];
        let value = bytes;
        let idx = 0;
        while (value >= 1024 && idx < units.length - 1) {
            value /= 1024;
            idx++;
        }
        return `${value.toFixed(idx === 0 ? 0 : 1)} ${units[idx]}`;
    }

    async function loadFsDirectory(path) {
        fsModalError.classList.add("hidden");
        fsState.selectedFilePath = null;
        const apiMode = fsState.mode === "dir" ? "dir" : "db";
        const query = path ? `?path=${encodeURIComponent(path)}&mode=${apiMode}` : `?mode=${apiMode}`;

        try {
            const data = await apiFetch(`/api/fs/list${query}`);
            fsState.currentPath = data.currentPath;
            fsState.parentPath = data.parentPath;
            fsCurrentPath.value = data.currentPath;
            fsBtnUp.disabled = !data.parentPath;

            fsEntries.innerHTML = "";
            if (!data.entries || data.entries.length === 0) {
                const li = document.createElement("li");
                li.className = "fs-entry muted";
                li.textContent = "Aucun \u00e9l\u00e9ment affichable dans ce dossier.";
                fsEntries.appendChild(li);
                return;
            }

            data.entries.forEach((entry) => {
                const li = document.createElement("li");
                li.className = "fs-entry";

                const nameWrap = document.createElement("span");
                nameWrap.className = "fs-entry-name";
                const icon = document.createElement("span");
                icon.textContent = entry.directory ? "\uD83D\uDCC1" : "\uD83D\uDDC4\uFE0F";
                const nameText = document.createElement("span");
                nameText.textContent = entry.name;
                nameWrap.appendChild(icon);
                nameWrap.appendChild(nameText);

                const sizeWrap = document.createElement("span");
                sizeWrap.className = "fs-entry-size";
                sizeWrap.textContent = entry.directory ? "" : formatBytes(entry.sizeBytes);

                li.appendChild(nameWrap);
                li.appendChild(sizeWrap);

                li.addEventListener("click", () => {
                    if (entry.directory) {
                        loadFsDirectory(entry.path);
                    } else {
                        fsEntries.querySelectorAll(".fs-entry.selected").forEach((n) => n.classList.remove("selected"));
                        li.classList.add("selected");
                        fsState.selectedFilePath = entry.path;
                        if (fsState.mode === "save-db") {
                            fsSaveFilename.value = entry.name;
                        }
                    }
                });

                li.addEventListener("dblclick", () => {
                    if (!entry.directory) {
                        fsState.selectedFilePath = entry.path;
                        confirmFsSelection();
                    }
                });

                fsEntries.appendChild(li);
            });
        } catch (err) {
            fsModalError.textContent = err.message;
            fsModalError.classList.remove("hidden");
        }
    }

    function openFsModal(targetId, mode) {
        const input = document.getElementById(targetId);
        if (!input) return;

        fsState.targetInput = input;
        fsState.mode = mode;
        fsState.selectedFilePath = null;

        if (mode === "dir") {
            fsModalTitle.textContent = "S\u00e9lectionner un dossier source";
            fsSaveFilenameRow.classList.add("hidden");
        } else if (mode === "save-db") {
            fsModalTitle.textContent = "Choisir le dossier et le fichier .db de sortie";
            fsSaveFilenameRow.classList.remove("hidden");
            const currentVal = input.value.trim();
            if (currentVal && currentVal.endsWith(".db")) {
                const parts = currentVal.split(/[/\\]/);
                const last = parts[parts.length - 1];
                if (last) fsSaveFilename.value = last;
            }
        } else {
            fsModalTitle.textContent = "S\u00e9lectionner une base SQLite (.db)";
            fsSaveFilenameRow.classList.add("hidden");
        }

        fsModal.classList.remove("hidden");

        let startPath = input.value.trim();
        if (startPath && (mode === "save-db" || mode === "db")) {
            const lastSep = Math.max(startPath.lastIndexOf("/"), startPath.lastIndexOf("\\"));
            if (lastSep > 0 && !startPath.endsWith("/") && !startPath.endsWith("\\")) {
                startPath = startPath.slice(0, lastSep);
            }
        }
        loadFsDirectory(startPath);
    }

    function closeFsModal() {
        fsModal.classList.add("hidden");
    }

    function confirmFsSelection() {
        if (!fsState.targetInput) {
            closeFsModal();
            return;
        }

        if (fsState.mode === "dir") {
            fsState.targetInput.value = fsState.currentPath;
            closeFsModal();
            return;
        }

        if (fsState.mode === "save-db") {
            let fileName = fsSaveFilename.value.trim() || "knowledge.db";
            if (!fileName.toLowerCase().endsWith(".db")) {
                fileName += ".db";
            }
            const sep = fsState.currentPath.includes("\\") ? "\\" : "/";
            const base = fsState.currentPath.endsWith(sep) ? fsState.currentPath : fsState.currentPath + sep;
            fsState.targetInput.value = base + fileName;
            closeFsModal();
            return;
        }

        // mode === "db"
        if (!fsState.selectedFilePath) {
            fsModalError.textContent = "Veuillez cliquer sur un fichier .db dans la liste.";
            fsModalError.classList.remove("hidden");
            return;
        }
        fsState.targetInput.value = fsState.selectedFilePath;
        closeFsModal();
        if (fsState.targetInput === mcpDbPath) {
            inspectDatabase();
        }
    }

    document.querySelectorAll("[data-browse-target]").forEach((btn) => {
        btn.addEventListener("click", () => {
            openFsModal(btn.dataset.browseTarget, btn.dataset.browseMode);
        });
    });

    fsBtnUp.addEventListener("click", () => {
        if (fsState.parentPath) {
            loadFsDirectory(fsState.parentPath);
        }
    });

    fsBtnGo.addEventListener("click", () => loadFsDirectory(fsCurrentPath.value.trim()));
    fsCurrentPath.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
            e.preventDefault();
            loadFsDirectory(fsCurrentPath.value.trim());
        }
    });

    fsModalClose.addEventListener("click", closeFsModal);
    fsBtnCancel.addEventListener("click", closeFsModal);
    fsBtnSelect.addEventListener("click", confirmFsSelection);
    fsModal.addEventListener("click", (e) => {
        if (e.target === fsModal) closeFsModal();
    });
    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape" && !fsModal.classList.contains("hidden")) {
            closeFsModal();
        }
    });

    // ----- Initial Load -----
    opencodeSnippet.textContent = buildLocalOpencodeSnippet("http://localhost:8081/mcp");
    connectIngestionEvents();
    refreshMcpStatus();
})();
