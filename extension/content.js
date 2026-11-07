
(() => {
    if (window.__snomChatBridge) return;
    window.__snomChatBridge = true;

    let busy = false;

    const pause = ms =>
        new Promise(resolve => setTimeout(resolve, ms));

    const notify = (kind, jobId, message = "") =>
        chrome.runtime.sendMessage({
            type: "event",
            kind,
            jobId,
            message
        }).catch(() => {});

    function visible(element) {
        return !!element &&
            element.getClientRects().length > 0;
    }

    function composer() {
        const selectors = [
            "#prompt-textarea[contenteditable='true']",
            "[data-testid='prompt-textarea'][contenteditable='true']",
            "form [contenteditable='true']",
            "textarea#prompt-textarea"
        ];

        for (const selector of selectors) {
            const element = [
                ...document.querySelectorAll(selector)
            ].find(visible);

            if (element) return element;
        }

        return null;
    }

    function sendButton() {
        const selectors = [
            "[data-testid='send-button']",
            "[data-testid='composer-submit-button']",
            "button[aria-label='Send prompt']",
            "button[aria-label='Send message']"
        ];

        for (const selector of selectors) {
            const button = [
                ...document.querySelectorAll(selector)
            ].find(visible);

            if (button && !button.disabled) {
                return button;
            }
        }

        return [
            ...document.querySelectorAll(
                "form button[type='submit']"
            )
        ].find(button =>
            visible(button) &&
            !button.disabled &&
            !button.matches(
                "[data-testid='stop-button']"
            )
        );
    }

    function assistants() {
        return [
            ...document.querySelectorAll(
                "[data-message-author-role='assistant']"
            )
        ];
    }

    function stopVisible() {
        return [
            ...document.querySelectorAll(
                "[data-testid='stop-button'], " +
                "button[aria-label='Stop generating'], " +
                "button[aria-label='Stop streaming']"
            )
        ].some(visible);
    }

    async function perform(job) {
        const input = composer();

        if (!input) {
            throw Error(
                "Не найдено поле ввода ChatGPT"
            );
        }

        if (
            (input.innerText || input.value || "").trim()
        ) {
            throw Error(
                "В поле ChatGPT уже есть текст"
            );
        }

        const before = assistants().length;

        const previousText =
            assistants().at(-1)?.innerText || "";

        input.focus();

        let ok = false;

        if (input.tagName === "TEXTAREA") {
            const setter =
                Object.getOwnPropertyDescriptor(
                    HTMLTextAreaElement.prototype,
                    "value"
                ).set;

            setter.call(input, job.text);

            input.dispatchEvent(
                new Event("input", { bubbles: true })
            );

            ok = input.value.includes(
                job.text.slice(0, 20)
            );

        } else {
            ok = !!document.execCommand(
                "insertText",
                false,
                job.text
            );

            if (!ok) {
                input.textContent = job.text;

                input.dispatchEvent(
                    new InputEvent("input", {
                        bubbles: true,
                        inputType: "insertText",
                        data: job.text
                    })
                );

                ok = (input.innerText || "")
                    .includes(job.text.slice(0, 20));
            }
        }

        if (!ok) {
            throw Error(
                "Не удалось ввести текст в ChatGPT"
            );
        }

        let send = null;

        for (let i = 0; i < 25 && !send; i++) {
            send = sendButton();

            if (!send) {
                await pause(200);
            }
        }

        if (!send) {
            throw Error(
                "Не найдена кнопка отправки"
            );
        }

        send.click();

        await notify("sent", job.id);

        const deadline = Date.now() + 600000;

        let last = "";
        let changedAt = Date.now();

        let sawStop = false;
        let sawNewReply = false;

        while (Date.now() < deadline) {
            await pause(650);

            const messages = assistants();
            const newest = messages.at(-1);

            const text =
                newest?.innerText?.trim() || "";

            const stop = stopVisible();

            if (stop) {
                sawStop = true;
            }

            if (
                (messages.length > before && text) ||
                (
                    messages.length === before &&
                    text !== previousText &&
                    text
                )
            ) {
                sawNewReply = true;
            }

            if (!sawNewReply) continue;

            if (text !== last) {
                last = text;
                changedAt = Date.now();
            }

            const stableMs =
                Date.now() - changedAt;

            const safeWindow = sawStop
                ? 3000
                : 9500;

            if (
                !stop &&
                stableMs >= safeWindow &&
                text.length > 0
            ) {
                await notify("done", job.id);
                return;
            }
        }

        throw Error(
            "Ожидание ответа превысило 10 минут"
        );
    }

    async function tick() {
        if (busy) return;

        try {
            const result =
                await chrome.runtime.sendMessage({
                    type: "poll"
                });

            if (!result?.job) return;

            busy = true;

            try {
                await perform(result.job);

            } catch (error) {
                await notify(
                    "error",
                    result.job.id,
                    String(error.message || error)
                );

            } finally {
                busy = false;
            }

        } catch (_) {
            // Java пока не запущена
        }
    }

    setInterval(tick, 1300);
    tick();
})();
