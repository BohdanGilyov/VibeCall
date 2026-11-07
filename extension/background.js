
const BRIDGE_URL = "http://127.0.0.1:47624";

chrome.runtime.onMessage.addListener((msg, sender, respond) => {
    const tab = sender.tab;

    if (!tab || !tab.id) {
        return false;
    }

    const key = `snom-active-${tab.id}`;

    (async () => {
        if (msg.type === "remember") {
            await chrome.storage.session.set({
                [key]: msg.job
            });

            return {ok: true};
        }

        if (msg.type === "restore") {
            const data = await chrome.storage.session.get(key);

            return {
                job: data[key] || null
            };
        }

        if (msg.type === "forget") {
            await chrome.storage.session.remove(key);

            return {ok: true};
        }

        if (!["poll", "event"].includes(msg.type)) {
            return {};
        }

        const response = await fetch(
            BRIDGE_URL + (
                msg.type === "poll" ? "/poll" : "/event"
            ),
            {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "X-Snom-Bridge": "chrome-ext-v1"
                },
                body: JSON.stringify({
                    tabId: tab.id,
                    windowId: tab.windowId,
                    title: tab.title || "ChatGPT",
                    url: tab.url || "",
                    busy: !!msg.busy,
                    jobId: msg.jobId || -1,
                    kind: msg.kind || "",
                    message: msg.message || ""
                })
            }
        );

        if (!response.ok) {
            throw new Error(
                `Local bridge HTTP ${response.status}`
            );
        }

        const data = await response.json();

        if (data.job) {
            await chrome.windows.update(
                tab.windowId,
                {focused: true}
            ).catch(() => {});

            await chrome.tabs.update(
                tab.id,
                {active: true}
            ).catch(() => {});
        }

        return data;

    })().then(respond).catch(() => respond({}));

    return true;
});
