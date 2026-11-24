# v3.0.0_Alpha-08

Offline audit (new):
- Import `.pcap` / `.pcapng` captures and crack them locally: WPA/WPA2 4-way handshake (MIC) and PMKID
- Pure-Kotlin WPA engine (PBKDF2-HMAC-SHA1, 802.11i PRF, CCMP/TKIP key versions) with no external binaries
- Runs on every CPU core with live speed/progress, and remembers found passwords in the history
- Optional live capture through root/Shizuku/AIDL + tcpdump (EAPOL frames only)
- Supports Ethernet, raw 802.11, radiotap, PPI, AVS, Linux SLL/SLL2 captures

Mask generator (new):
- Hashcat-style masks (`?l ?u ?d ?s ?a ?b ?1..?4`, literal `?`) with lazy iteration
- Built-in presets derived from the SSID (8/10/11/12 digits, SSID + digits, word + digits)
- Combination counter with readable K/M/B estimates

Adaptive strategy (new):
- Attack profile chosen from `capabilities`: open / WEP / WPA / WPA2 / WPA3-only / WPA3 transition / Enterprise
- WPA3 transition and weak signals get longer timeouts; WPA2 uses the fast handshake-limit flag
- Candidate ranking adapts: WPA3 prefers long passwords, old WPA prefers numeric defaults
- The reasoning is written to the run log

Cleanup:
- The app is English by default; all Chinese UI strings, assets and code comments were translated or removed
- Chinese locale entries were removed, with a one-time migration of the saved language index
- Version bumped to v3.0.0_Alpha-08 (versionCode 7)

# v3.0.0_Alpha-07

Performance and stability:
- History is no longer rewritten after every attempt: one snapshot at task start, then progress-only updates (far less database IO)
- Force-stopping the system Settings app now happens once at start instead of every 250 ms (less battery and CPU use)
- Connect calls are wrapped in a timeout so a stuck binder cannot hang a task forever
- WakeLock / WifiLock are held during a run to prevent Doze/screen-off from sleeping Wi-Fi and dropping handshake logs
- logcat events now travel through an unbounded Channel, so success/failure events are never discarded
- Recognizes temporary SSID blocks (TEMP-DISABLED) and retries them as transient errors
- Fixed connections not being cleaned up after AIDL mode ends
- Adding a task no longer interrupts the attempt in progress
- The task queue is persisted: after the process is killed (START_STICKY restart) it resumes from the same point
- Huge dictionaries are only scored for the first 100k entries to avoid jank and OOM

Intelligence:
- Candidate scoring: SSID words, common weak passwords, years, keyboard patterns, length distribution, etc.
- Previously cracked passwords are moved to the front (password reuse is common)
- The network list shows the security type (WPA3 / WPA2 / WPA / WEP)
- The notification shows the discovered password immediately

Fixes:
- Run parameters (max attempt time, failure flag, timeout, etc.) are saved instead of reverting to defaults
- Restarting a task keeps the password that was already found

Tests:
- Added unit tests for candidate ranking and logcat parsing

# v3.0.0_Alpha-06

- Pure-root mode is supported (choose AIDLService for every mode and enable the AIDL service in global settings)
- Added multi-language support (AI tools are surprisingly good)
- Added an easter egg to the About page
- Half of the home page is done
- The running network list marks saved networks and passwords saved by system Settings
- Lab: viewing saved Wi-Fi passwords (carried over from the previous version)
- Reworked the run-history data format; old data is cleared
- Shows the average speed of the last 20 attempts and the estimated remaining time
- Fixed one known bug
- Added a billion unknown bugs
- Anything else? I forget, take a look yourself

# v3.0.0_Alpha-05

- UI polish
- Finished the About page
- Added history to the dictionary attack
- Fixed a crash when viewing a script resource right after editing it (seems to have fixed itself during refactoring?)

# v3.0.0_Alpha-04

- Added password-list management and script-type dictionaries (very incomplete)
- Fixed older Android versions not being able to use the app
- Added a few bugs

# v3.0.0_Alpha-03

- Every feature now works without Shizuku (pure system APIs)
- Improved the run output area layout
- Added success/failure markers for the last attempt
- Debug builds show a banner; fixed Shizuku detection

- All future builds use GitHub Actions; added discussions

# v3.0.0_Alpha-02

Features:
- Added retry on errors: each retry waits a while, and the wait doubles after repeated failures. Waiting does not block other tasks (all run parameters are now effective)
- Added ways to connect with system APIs (some bugs remain)
- Refactored parts of the logic
- Added a changelog

Other:
- This build is made with GitHub Actions

More features are on the way...

# v3.0.0_Alpha-01

Shizuku is required; root and no-Shizuku modes are not supported.
Open the menu at the top-left of the home page to use the dictionary attack.
First set every option in the attack settings to a "Shizuku" variant and set network-log reading to "Command line".

Currently supported:
- Running a manually entered password list
- Multiple queued tasks that can be added or removed at any time
- UI and service progress kept in sync
- Background operation (set battery optimization to unrestricted)
- Handling of wrong-password pop-ups (on some Xiaomi devices the app repeatedly kills Settings while running)
- Custom theme color and dark/light mode (in app settings)

Not supported / unfinished:
- Root mode / plain system mode
- Editing run parameters
- Pages other than the dictionary attack, Lab and Settings
- Password-list management
