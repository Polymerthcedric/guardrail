# guardrail

Self-hosted deep-work fence for the ThinkPad T440 (Arch) and a Realme phone.

Blocks the distracting pile (YouTube, video, social, news) between 07:00 and 19:00,
with the honest constraint stated up front: **any device you use daily can be reset or
have its guardrails removed by the person holding it**. On the laptop that is
actually hard. On an Android phone it is a factory reset away — the app below makes
bypassing annoying, not impossible.

## Architecture

```
07:00                              19:00
  ├── hosts + local-DNS sinkhole ON   sinkhole lifted
  └── browser policies                (always on — incognito + DoH off at all hours)
```

### Laptop (linux/)

| Layer | Mechanism | Bypass resistance |
|---|---|---|
| DNS | local `dnsmasq` sinkhole: `address=/domain/0.0.0.0` **and** `address=/domain/::` cover the domain and *every subdomain*, IPv4 and IPv6; `/etc/hosts` also swapped as defence in depth | trivial — user edits `/etc/resolv.conf` back (root) |
| Upstream | `guardrail-upstream` (run by a `NetworkManager` dispatcher) keeps dnsmasq pointed at the real network DNS, so it never forwards back into `systemd-resolved` in a loop | automatic |
| DoH | Chromium/Firefox policies disable browser DNS-over-HTTPS, so encrypted DNS can't dodge the sinkhole | cannot be flipped from the UI |
| Browser | Chromium `IncognitoModeAvailability:1` + no guest; Firefox `DisablePrivateBrowsing:true` + `NetworkPrediction:false` | cannot be flipped from the UI |
| Policy | policies and `/etc/resolv.conf` are root-writable only | fine for self-commitment |

### Android (android/)

| Layer | Mechanism | Bypass resistance |
|---|---|---|
| Screen | `AccessibilityService` — on `TYPE_WINDOW_STATE_CHANGED`, blocked apps get a full-screen focus overlay drawn as a `TYPE_ACCESSIBILITY_OVERLAY` window (realme UI aborts background `startActivity`, so the barrier is a window, not an activity) | user can disable the service in Settings |
| Friction | Overlay has **3 emergency unlocks/day** (2-min bypass), **strict mode** (available after Device Owner setup), rotating affirmations, and **presets** (Focus/Deep work/Family/Wind-down/Off) | self-commitment; resets at local midnight and does not refill when the local date moves backwards |
| Clock | Wall-clock vs. `elapsedRealtime()` drift > 90 s triggers **lockdown** — every app blocked with no bypass until the clock is fixed and unlocked in the app | reboot does not clear it |
| Settings | Opening the app's own App Info/Settings page kicks straight home before Force Stop can be pressed | only reaches the activity when the user resets the phone |
| Network | split-tunnel `VpnService` — routes every resolver the carrier/network advertises (v4 + v6) plus a deny-list of public resolvers, returns NXDOMAIN for blocked domains, forwards the rest; non-DNS traffic to those IPs is dropped so DoT/DoH can't escape, no NAT/server | user can toggle VPN off |
| Lock | Device *Owner* (not plain admin) can block uninstall + disable auto time | factory reset beats it |
| Base | Digital Wellbeing app timers for the apps the above would miss | same caveat |

The Android app is a ~4-part skeleton (a11y + vpn + device-admin receiver +
focus overlay/UI). On realme UI T phones (e.g. realme Note 50) device-owner
provisioning is often rejected; see `android/README.md` before trying
`dpm set-device-owner`.

## Quick start (laptop only)

```sh
cd linux
./install.sh          # installs timers, the local DNS sinkhole, and browser policies (needs sudo)
systemctl start focus-block.service     # enter the window now
systemctl start focus-unblock.service   # leave early (self-discipline version)
```

The timers fire the one-shot services at 07:00 and 19:00. Because they are
`oneshot`, exiting early via `systemctl stop` does nothing — always *start* the
matching service to flip state (or run `/usr/local/bin/focus-guardrail on|off`).

Check what it will add to `/etc/hosts`, `/etc/resolv.conf` and the dnsmasq
drop-ins before running — it is idempotent and reversible (`./uninstall.sh`).

## Layout

- `linux/` — laptop fence: `install.sh`, `uninstall.sh`, `focus.sh`,
  `hosts/blocklist.txt`, `dns/`, `browsers/`, `systemd/`
- `android/` — Kotlin app skeleton (a11y + split-tunnel DNS + device-owner)
