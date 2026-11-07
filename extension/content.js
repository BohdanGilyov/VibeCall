
(() => {
    if (window.__snomBridge44) return;
    window.__snomBridge44 = true;

    const sleep = ms =>
        new Promise(resolve => setTimeout(resolve, ms));

    const send = data =>
        chrome.runtime.sendMessage(data);

    const normalize = value =>
        String(value || "")
            .replace(/\u00a0/g, " ")
            .replace(/\s+/g, " ")
            .trim();

    let busy = false;
    let polling = false;

    const RESPONSE_COPY = [
        '[data-testid="copy-turn-action-button"]',
        '[data-test-id="copy-turn-action-button"]',
        'button[aria-label="Copy response"]',
        'button[aria-label="Copy message"]',
        'button[aria-label="Копировать ответ"]'
    ].join(",");

    function visible(el) {
        return !!el && el.getClientRects().length > 0;
    }

    function assistantMessages() {
        const markers = [
            '[data-message-author-role="assistant"]',
            '[data-conversation-role="assistant"]',
            '[data-turn="assistant"]',
            '[data-role="assistant"]',
            '[data-message-author="assistant"]',
            '[data-turn-key]:has([data-chatgpt-agent-turn-start])'
        ].join(",");

        let nodes = [
            ...new Set(document.querySelectorAll(markers))
        ].filter(visible);

        nodes = nodes.filter(node => {
            const own = normalize(node.innerText);

            if (!own) return false;

            return !nodes.some(other =>
                other !== node &&
                node.contains(other) &&
                normalize(other.innerText).length >= 2
            );
        });

        if (nodes.length === 0) {
            nodes = [
                ...document.querySelectorAll(
                    'main .markdown, main .prose, ' +
                    '[data-app-shell-main-content-layout] .markdown, ' +
                    '[data-app-shell-main-content-layout] .prose'
                )
            ].filter(el =>
                visible(el) && normalize(el.innerText)
            );

            nodes = nodes.filter(node =>
                !nodes.some(other =>
                    other !== node &&
                    node.contains(other)
                )
            );
        }

        return nodes
            .map(el => normalize(el.innerText))
            .filter(Boolean);
    }

    function copyCount() {
        return [
            ...document.querySelectorAll(RESPONSE_COPY)
        ].filter(visible).length;
    }

    function stopVisible() {
        const selectors = [
            '[data-testid="stop-button"]',
            '[data-test-id="stop-button"]',
            'button[aria-label="Stop generating"]',
            'button[aria-label="Stop streaming"]',
            'button[aria-label="Stop response"]',
            'button[aria-label="Остановить генерацию"]',
            'button[aria-label="Остановить ответ"]'
        ].join(",");

        return [
            ...document.querySelectorAll(selectors)
        ].some(visible);
    }

    function textAfterPrompt(prompt) {
        const root =
            document.querySelector("main") ||
            document.querySelector(
                "[data-app-shell-main-content-layout]"
            ) ||
            document.body;

        const page = normalize(root?.innerText);
        const normalizedPrompt = normalize(prompt);

        if (!page || !normalizedPrompt) return "";

        const anchors = [
            normalizedPrompt,
            normalizedPrompt.slice(0, 90),
            normalizedPrompt.slice(-70)
        ].filter(part => part.length >= 12);

        for (const anchor of anchors) {
            const at = page.lastIndexOf(anchor);

            if (at < 0) continue;

            let after = page
                .slice(at + anchor.length)
                .trim();

            const composerMarkers = [
                "Спросить ChatGPT",
                "Спросить что-нибудь",
                "Ask anything",
                "Ask ChatGPT",
                "Message ChatGPT"
            ];

            for (const marker of composerMarkers) {
                const i = after.lastIndexOf(marker);

                if (i >= 0 && i > 5) {
                    after = after.slice(0, i).trim();
                }
            }

            return after;
        }

        return "";
    }

    function composer() {
        const selectors = [
            '#prompt-textarea[contenteditable="true"]',
            '[data-testid="prompt-textarea"][contenteditable="true"]',
            'form[data-chatgpt-composer] [data-composer-markdown][contenteditable="true"]',
            'form [contenteditable="true"][role="textbox"]',
            'textarea#prompt-textarea'
        ];

        return selectors
            .map(selector =>
                [
                    ...document.querySelectorAll(selector)
                ].find(visible)
            )
            .find(Boolean) || null;
    }

    function submitButton() {
        const selectors = [
            '[data-testid="send-button"]',
            '[data-testid="composer-submit-button"]',
            'button[aria-label="Send prompt"]',
            'button[aria-label="Send message"]',
            'button[aria-label="Отправить сообщение"]',
            'form button[type="submit"]'
        ];

        return selectors
            .map(selector =>
                [
                    ...document.querySelectorAll(selector)
                ].find(el =>
                    visible(el) &&
                    !el.disabled &&
                    !el.matches(
                        '[data-testid="stop-button"]'
                    )
                )
            )
            .find(Boolean) || null;
    }

    async function notify(kind, jobId, detail = "") {
        const attempts =
            kind === "done" || kind === "error"
                ? 8
                : 2;

        for (let n = 0; n < attempts; n++) {
            try {
                const reply = await send({
                    type: "event",
                    kind,
                    jobId,
                    message: detail
                });

                if (reply?.ok === true) {
                    return true;
                }

            } catch (_) {}

            await sleep(400 + 200 * n);
        }

        return false;
    }

    async function monitor(job) {
        const start = Number(job.submittedAt);
        const deadline = start + 10 * 60 * 1000;

        let lastObserved = "";
        let changedAt = Date.now();
        let noStopSince = 0;

        let sawStop = false;
        let sawAnswer = false;

        let lastProgress = 0;
        let lastSource = "";

        while (Date.now() < deadline) {
            await sleep(500);

            const turns = assistantMessages();
            const lastTurn = turns.at(-1) || "";

            const turnIsNew =
                !!lastTurn && (
                    turns.length >
                    Number(job.beforeCount || 0) ||
                    lastTurn !== (job.beforeText || "")
                );

            const afterPrompt = textAfterPrompt(
                job.prompt || ""
            );

            const stop = stopVisible();
            const copies = copyCount();

            const newCopy =
                copies >
                Number(job.beforeCopies || 0);

            if (stop) {
                sawStop = true;
            }

            let candidate = "";
            let source = "";

            if (turnIsNew) {
                candidate = lastTurn;
                source = "assistant";

            } else if (
                afterPrompt.length >= 15 &&
                (
                    newCopy ||
                    sawStop ||
                    Date.now() - start > 6500
                )
            ) {
                candidate = afterPrompt;
                source = "page";
            }

            if (candidate) {
                sawAnswer = true;
            }

            if (candidate !== lastObserved) {
                lastObserved = candidate;
                changedAt = Date.now();
            }

            lastSource = source || lastSource;

            if (stop) {
                noStopSince = 0;

            } else if (!noStopSince) {
                noStopSince = Date.now();
            }

            if (Date.now() - lastProgress >= 3500) {
                let status;

                if (!sawAnswer) {
                    status =
                        "Ищу ответ (сообщений: " +
                        turns.length +
                        ", кнопок копирования: " +
                        copies + ")";

                } else if (stop) {
                    status = "Ответ генерируется...";

                } else {
                    status =
                        "Ответ найден (" +
                        lastSource +
                        "), проверяю завершение";
                }

                void notify(
                    "progress",
                    job.id,
                    status
                );

                lastProgress = Date.now();
            }

            if (
                !sawAnswer ||
                !candidate ||
                stop
            ) {
                continue;
            }

            if (Date.now() - start < 2500) {
                continue;
            }

            const stable =
                Date.now() - changedAt;

            const noStop =
                Date.now() - noStopSince;

            const done =
                (
                    source === "assistant" &&
                    newCopy &&
                    stable >= 2200 &&
                    noStop >= 1600
                ) ||
                (
                    sawStop &&
                    stable >= 3000 &&
                    noStop >= 2000
                ) ||
                (
                    stable >= 9000 &&
                    noStop >= 9000 &&
                    Date.now() - start >= 11000
                );

            if (done) {
                const confirmed = await notify(
                    "done",
                    job.id
                );

                if (!confirmed) {
                    throw new Error(
                        "Java не подтвердила завершение ответа"
                    );
                }

                await send({
                    type: "forget"
                });

                return;
            }
        }

        throw new Error(
            "Не удалось определить конец ответа за 10 минут"
        );
    }

    async function submit(job) {
        const field = composer();

        if (!field) {
            throw new Error(
                "Поле ввода ChatGPT не найдено"
            );
        }

        if (normalize(
            field.innerText || field.value
        )) {
            throw new Error(
                "В поле ChatGPT уже есть черновик"
            );
        }

        const old = assistantMessages();

        const state = {
            schema: 44,
            id: job.id,
            prompt: job.text,
            submittedAt: Date.now(),
            beforeCount: old.length,
            beforeText: old.at(-1) || "",
            beforeCopies: copyCount()
        };

        field.focus();

        if (field.tagName === "TEXTAREA") {
            const setter =
                Object.getOwnPropertyDescriptor(
                    HTMLTextAreaElement.prototype,
                    "value"
                )?.set;

            if (!setter) {
                throw new Error(
                    "Не найден setter поля ввода"
                );
            }

            setter.call(field, job.text);

            field.dispatchEvent(
                new Event("input", {
                    bubbles: true
                })
            );

        } else {
            const worked = document.execCommand(
                "insertText",
                false,
                job.text
            );

            if (!worked) {
                field.textContent = job.text;

                field.dispatchEvent(
                    new InputEvent("input", {
                        bubbles: true,
                        inputType: "insertText",
                        data: job.text
                    })
                );
            }
        }

        if (
            !normalize(
                field.innerText || field.value
            ).includes(
                normalize(job.text).slice(0, 12)
            )
        ) {
            throw new Error(
                "Не удалось вставить запрос"
            );
        }

        let button = null;

        for (
            let i = 0;
            i < 30 && !button;
            i++
        ) {
            button = submitButton();

            if (!button) {
                await sleep(150);
            }
        }

        if (!button) {
            throw new Error(
                "Кнопка отправки не найдена"
            );
        }

        await send({
            type: "remember",
            job: state
        });

        button.click();

        void notify("sent", job.id);

        await monitor(state);
    }

    async function work(
        job,
        restoring = false
    ) {
        if (busy) return;

        busy = true;

        try {
            if (restoring) {
                await monitor(job);
            } else {
                await submit(job);
            }

        } catch (error) {
            await notify(
                "error",
                job.id,
                String(error?.message || error)
            );

            await send({
                type: "forget"
            }).catch(() => {});

        } finally {
            busy = false;
        }
    }

    async function poll() {
        if (polling) return;

        polling = true;

        try {
            const result = await send({
                type: "poll",
                busy
            });

            if (result?.job && !busy) {
                void work(result.job);
            }

        } catch (_) {
        } finally {
            polling = false;
        }
    }

    (async () => {
        try {
            const saved = await send({
                type: "restore"
            });

            if (
                saved?.job?.schema === 44 &&
                Date.now() -
                saved.job.submittedAt < 600000
            ) {
                void work(
                    saved.job,
                    true
                );

            } else if (saved?.job) {
                await send({
                    type: "forget"
                });
            }

        } catch (_) {}

        setInterval(poll, 1100);
        poll();
    })();
})();
