# guardrail-android

Kotlin AppCompat-less skeleton (four surfaces, each independently removable):

1. `GuardrailAccessibilityService` — watches `TYPE_WINDOW_STATE_CHANGED` and puts
   a full-screen focus barrier in front of blocked packages. The barrier is a
   **`TYPE_ACCESSIBILITY_OVERLAY` window** (`BlockOverlay`), not a launched
   activity: realme UI T (RMX3834, Android 15) *aborts* background
   `startActivity()` from a service
   (logcat: `AOSP_SafeCenter ... START_ABORTED`), while accessibility overlay
   windows are exempt. It also kicks straight home if the user opens Guardrail's
   own App Info / Settings page (the Force-Stop loophole). A
   It rechecks the foreground restricted app every five seconds so schedule
   boundaries, expired emergency unlocks, and daily limits take effect without
   waiting for another app switch. If a daily limit is configured while Usage
   Access is off, the app shows a setup-required barrier instead of treating
   usage as zero. The barrier is removed when the restriction no longer applies.
   `TYPE_WINDOW_CONTENT_CHANGED` walk of Chrome's tree detects incognito (a
   view-id containing `incognito`, or the exact normalized "You've gone
   Incognito" heading — the ambiguous `New Incognito tab` content-desc is
   ignored, since it is present in normal mode too) and raises the same overlay
   with `reason=incognito`.
2. `DnsVpnService` + `DnsPacket` — **split-tunnel** local DNS firewall. Every
   resolver advertised by *any* connected network (v4 **and** v6, active network
   first) is routed into the VPN, plus a deny-list of well-known public resolvers
   (`PUBLIC_RESOLVERS`: Google/Cloudflare/Quad9/OpenDNS, v4+v6) so a client that
   hardcodes a resolver still lands in the tunnel. Blocked domains get an
   NXDOMAIN reply; everything else is forwarded to the active resolver over one
   shared, protected UDP socket. **Only UDP/53 is forwarded** — anything else
   hitting a routed resolver IP (DoT/853, DoH/QUIC/443, which share those IPs) is
   dropped, forcing encrypted-DNS clients back onto the filtered plaintext path.
   The tunnel also carries IPv6 (`fd00:1:2:3::1/128`). A non-VPN network callback
   re-establishes the tunnel when the underlay changes (Wi-Fi ⇄ mobile); grants
   are ignored for 2.5 s after registration so the callback's initial replay does
   not cause a spurious restart. No full-tunnel capture, no NAT, no server. MTU
   DNS parsing is bounded by the received packet length; malformed or
   multi-question requests are rejected. Allowed lookups are forwarded to the
   connected network's DNS resolver. The MTU is forced to 1360 (an MTU-0 tun busy-loops `read n=0` and spins CPU while DNS
   dies — the parser offsets must match: protocol at byte 9, UDP after the IP
   header, DNS after that).
3. `GuardrailAdminReceiver` — **device owner** hardening: blocks uninstall and
   disables automatic time. Useless as a plain admin; owner provisioning is
   required (below).
4. Hardening inside `Blocked`:
   - **Clock-tamper lockdown**: wall clock vs `SystemClock.elapsedRealtime()`
     drift > 90 s → lockdown = *every* app blocked, no emergency, survives
     reboot. Clears/self-heals when the clock is back in sync; manual
     "Clear lockdown" lives in the app. Detecting a time shift is pointless if
     recency is expressed in that same clock, hence the uptime anchor.
   - **Emergency budget**: 3 unlocks/day, reset at midnight (`LocalDate`-keyed),
     each granting a 2-minute bypass unless **strict mode** is on.
   - **Presets**: Focus / Deep work / Family time / Wind-down / Unblock-all,
     applied across all apps as per-app windows.
     Device owner mode additionally hard-disables `Settings.Global.AUTO_TIME`.

## Build

```sh
# JDK 21 + Gradle 8.10.2 (path-carrying tools, not on PATH)
JAVA_HOME=$(ls -d ~/.local/java/jdk-21*); export PATH="$JAVA_HOME/bin:$PATH"
~/.local/share/gradle/gradle-8.10.2/bin/gradle :app:assembleDebug --no-daemon

adb install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
```

Notes:
- Use `--no-streaming`; streaming install is flaky on this device.
- **Every reinstall resets the accessibility grant.** Re-enable after install:
  ```sh
  adb shell settings put secure enabled_accessibility_services \
      dev.fidelco.guardrail/.GuardrailAccessibilityService
  adb shell settings put secure accessibility_enabled 1
  ```
  If the service shows "connected" but receives no events, force a rebind by
  setting the same value to `null` first, then back.
- Verbose packet/decision logging: `adb shell setprop log.tag.Guardrail VERBOSE`.
- `adb shell run-as dev.fidelco.guardrail cat shared_prefs/guardrail.xml` reads
  live prefs (debug build) — useful for simulating clock tamper by rewriting
  `clock_anchor_wall/uptime` after a `force-stop` (in-memory prefs otherwise
  overwrite disk edits).

## Test order

1. Install, re-grant a11y (above), `setprop log.tag.Guardrail VERBOSE`.
2. Open `MainActivity` → toggle DNS filter → confirm the VPN request prompt.
   `logcat -s Guardrail`: `vpn prepare=null` → `vpn requested` → `vpn established`.
3. Verify DNS blocking: `adb shell ping -c1 youtube.com` → `unknown host`
   (`blocked youtube.com` in logcat), `ping -c1 wikipedia.org` → resolves
   (`forwarded wikipedia.org`). Confirm the tunnel picked up every resolver:
   `adb shell dumpsys connectivity | grep tun0` →
   `LinkAddresses: [ 10.1.10.1/32,fd00:1:2:3::1/128 ]` and all four carrier DNS
   servers in `DnsAddresses`. Open a blocked site in **normal Chrome** → the DNS
   queries log as `blocked`.
4. Incognito guard: `adb shell am force-stop com.android.chrome` then
   `am start -n com.android.chrome/org.chromium.chrome.browser.incognito.IncognitoTabLauncher -a
   org.chromium.chrome.browser.incognito.OPEN_PRIVATE_TAB` →
   `incognito detected in com.android.chrome (ntp)` → `overlay shown ... reason=incognito`.
   Normal Chrome must *not* log either line (regression check).
5. Open a blocked app (or "Focus preview" in the app) → overlay appears with
   emergency / home buttons. With the clock tampered (`clock_lockdown=true`)
   the overlay reads "Clock tamper detected" and the bypass is disabled.
6. May require HOME first so a fresh window arrives; the service only reacts to
   `TYPE_WINDOW_STATE_CHANGED`.

Note: while the accessibility service is enabled this ROM refuses `uiautomator
dump` (`null root node`). Disable the grant temporarily to dump the UI (the DNS
toggle sits at ~`(581,585)` on the 720×1600 layout). The VPN service is not
exported, so it cannot be started from the shell — toggle it in the UI.

## Realme reality check

Target device: **realme Note 50 (RMX3834)**. It ships with **realme UI T**
(realme's lite UI for the Note line) on **Android 13**, later updated to
**Android 15** — this unit reports `ro.build.version.release=15`,
`ro.build.display.id=RMX3834export_15_H.07`, fingerprint
`realme/RMX3834/RE5C9F:15/...`. Its settings app is `com.android.settings`.
Consequently the ROM-specific behaviours described here (background
`startActivity()` aborts, `uiautomator` refusal while an a11y service is active,
`RMX3834export_…` build strings) are realme UI T behaviours.

- `adb shell dpm set-device-owner dev.fidelco.guardrail/.GuardrailAdminReceiver`
  requires the app be installed with no account/user present, or it is rejected
  with `Not allowed to set the device owner`. **Test on a device you can afford
  to wipe.**
- The accessibility service and VPN can each be disabled by the user at any time
  from Settings. This app makes bypassing annoying, not impossible.
- The DNS filter covers listed domains and known resolver routes. Apps using an
  unlisted custom resolver or their own DoH endpoint may bypass it.
- Strict mode is available after Device Owner provisioning; ordinary Device
  Admin activation does not provide that control.

## Battery

Realme's battery manager will kill the VPN/a11y service in the background.
`dontkillmyapp.com` procedure: Settings → Battery → App management → Guardrail →
**don't auto-detect / allow background**, and under App battery usage select
*unrestricted*. The service is also low-footprint by design:
`canRetrieveWindowContent=true` is required to detect Chrome Incognito; events
are package-scoped to restricted apps and Chrome. Screen contents are inspected
locally and not sent to the DNS resolver. Usage cache is 5 s, with one pooled DNS socket,
packet-loop `sleep(10)` on empty reads, debug logs gated behind
`Log.isLoggable`.

## Files

```
android/
  settings.gradle.kts
  build.gradle.kts
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/res/xml/accessibility_service_config.xml
  app/src/main/java/dev/fidelco/guardrail/
    MainActivity.kt               settings UI: focus hero, per-app windows,
                                  presets, DNS toggle, strict mode, lockdown notice
    GuardrailAccessibilityService.kt  gate + settings-kick + tamper hook
    BlockOverlay.kt               TYPE_ACCESSIBILITY_OVERLAY barrier
    BlockedAppActivity.kt         overlay-as-activity (used by "Focus preview")
    DnsVpnService.kt              split-tunnel DNS forwarder
    DnsPacket.kt                  v4/v6 parser + NXDOMAIN/reply builders
    Blocked.kt                    domains, windows, limits, emergencies,
                                  strict mode, clock-tamper lockdown
    Usage.kt                      usage access + 5s cache
    BootReceiver.kt               resume VPN afer boot/update
    FocusScheduler.kt, WindowAlarmReceiver.kt, GuardrailAdminReceiver.kt
```
