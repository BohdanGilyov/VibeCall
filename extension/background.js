const BRIDGE = "http://127.0.0.1:47624";

chrome.runtime.onMessage.addListener(
    (message, sender, respond) => {

        if (!sender.tab ||
            !["poll", "event"].includes(message.type)) {
            return;
        }

        const tab = sender.tab;

        const path = message.type === "poll"
            ? "/poll"
            : "/event";

        const payload = {
            tabId: tab.id,
            windowId: tab.windowId,
            title: tab.title || "ChatGPT",
            url: tab.url || "",
            kind: message.kind,
            jobId: message.jobId,
            message: message.message
        };

        (async () => {
            const response = await fetch(BRIDGE + path, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "X-Snom-Bridge": "chrome-ext-v1"
                },
                body: JSON.stringify(payload)
            });

            if (!response.ok) {
                throw Error(
                    "Local bridge HTTP " + response.status
                );
            }

            const data = await response.json();

            if (data.job) {
                await chrome.windows.update(
                    tab.windowId,
                    { focused: true }
                ).catch(() => {});

                await chrome.tabs.update(
                    tab.id,
                    { active: true }
                ).catch(() => {});
            }

            respond(data);

        })().catch(() => respond({}));

        return true;
    }
);
