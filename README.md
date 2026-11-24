> Busy with studies lately, no time to update; back in a few months (2026/01/30)
>
> The newest build turned into spaghetti again — too scattered and hard to maintain. Next version adds PIN attacks and keeps refactoring! (2026/03/12)

A new version is in development on the `master` branch. Join the chat at [https://t.me/bszapp](https://t.me/s/bszapp). Handshake capture + offline cracking, dictionary attacks and PIN attacks are already implemented; new features are announced there. **Feedback for 3.0.0 is no longer accepted**, but suggestions in discussions and issues are still collected (2026/10/24).

# WiFi Toolbox

[![Stars](https://img.shields.io/github/stars/bszapp/android-wifi-pojie?style=flat-square&logo=github&color=blue)](https://github.com/bszapp/android-wifi-pojie/stargazers) [![Total Downloads](https://img.shields.io/github/downloads/bszapp/android-wifi-pojie/total?style=flat-square&logo=github&color=orange)](https://github.com/bszapp/android-wifi-pojie/releases)

Download the latest release:

[![Latest Release](https://img.shields.io/github/v/release/bszapp/android-wifi-pojie?style=flat-square&logo=github&color=green)](https://github.com/bszapp/android-wifi-pojie/releases/latest) [![Latest Downloads](https://img.shields.io/github/downloads/bszapp/android-wifi-pojie/latest/total?style=flat-square&logo=github&color=green&label=downloads)](https://github.com/bszapp/android-wifi-pojie/releases/latest)

Screenshots:

![screenshot.png](screenshot.png)

~~Spaghetti rating:~~
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=bszapp_android-wifi-pojie&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=bszapp_android-wifi-pojie)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=bszapp_android-wifi-pojie&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=bszapp_android-wifi-pojie)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=bszapp_android-wifi-pojie&metric=bugs)](https://sonarcloud.io/summary/new_code?id=bszapp_android-wifi-pojie)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=bszapp_android-wifi-pojie&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=bszapp_android-wifi-pojie)

(AI tools are ridiculously useful, you know?)

---

v3.x is a Kotlin rewrite; the package name is now `com.wifi.toolbox`.

Some features are still incomplete. Suggestions for improvements or new features are welcome here: [Feedback](https://github.com/bszapp/android-wifi-pojie/discussions/18)

Pure-root setup: in the run-settings page select "Command line" and "AIDL" for every mode, then enable "Start AIDL service" in global settings and restart the app.

The old version (the last one before the Bilibili account ban, using MDC design) is no longer maintained: [go to the v2 repo](https://github.com/bszapp/android-wifi-pojie/tree/v1.x-v2.x)

> Use this app only on networks you own or have explicit permission to test.

## Features

### Dictionary attack
#### How to use

##### Choose a mode

On first use, pick the execution modes. Shizuku is recommended everywhere, with "Command line" for reading network logs.

If that is not available you can use plain system APIs for everything, but results may produce false negatives, it is less stable than Shizuku, and the handshake-limit mode is unavailable.

~~On the list: a setup wizard that configures the modes automatically~~

##### Start a run

The "Run" page shows the Wi-Fi list in the middle. Tap "Start" next to a network, pick a password list and the attack begins.

You can run several tasks at once; they are queued automatically (see the workflow diagram).

##### Managing password lists

On the "Resources" page you can manage lists. Tap the bottom-right "+" → Import → choose a `.txt` file (or a `.json` / `.js` file) to use it directly.

You can also create plain or script resources.

###### Plain lists
Same format as Phantom WiFi: one password per line, tried in turn.

This app adds extra fields: ID, description, author and version.

The ID must be unique and match `/^[a-zA-Z][a-zA-Z0-9._-]+$/`.

Stored in the app's internal directory as `<ID>.json`.

###### Script lists
The script runs every time a password list is generated. It can read the target SSID and produce results.

Scripts run inside a WebView, so WebView features are available (`fetch` works, subject to CORS).

Example showing every supported feature:
```javascript
// ==ToolboxScript==
// @id {ID}
// @name Script name
// @description Script description
// @author Author
// @version 1.0.0
// ==/ToolboxScript==

console.log("Adding a password...") // log output with console.log
task.addItem("12345678") // add a candidate with task.addItem
console.log("Waiting 1 second...")
await wait(1000); // code runs inside an async function, so await works
console.log("One more!");
task.addItem(task.ssid + "888") // task.ssid is the target network name

try {
	console.log("Testing network...");
	const response = await fetch('https://uapis.cn/api/v1/network/myip'); // WebView fetch is available, but CORS applies
	const data = await response.json();
	console.log("IP: " + data.ip);
	console.log("Region: " + data.region);
} catch (err) {
	console.log("Request failed: " + err.message);
}
console.log("Done");
```

Stored in the app's internal directory as `<ID>.js`.

### Offline audit
Import a `.pcap` / `.pcapng` capture, pick a handshake or PMKID, and crack it locally with dictionaries and hashcat-style masks. No traffic is sent to the network.

#### Masks
`?l` lowercase, `?u` uppercase, `?d` digits, `?s` symbols, `?a` printable, `?b` binary, `?1`..`?4` custom sets, `??` literal question mark. The right-most position changes fastest, candidates are generated lazily.

### Program flow
![run_lc.svg](run_lc.svg)

[![Star History](https://star-history.dera.page/svg?repos=bszapp/android-wifi-pojie&type=Date)](https://star-history.dera.page/#bszapp/android-wifi-pojie&Date)
