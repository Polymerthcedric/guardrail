#!/usr/bin/env bash
set -euo pipefail

SRC="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ETC="/etc/guardrail"

sudo mkdir -p "$ETC"
sudo cp "$SRC/hosts/blocklist.txt" "$ETC/blocklist.txt"
sudo install -m 0755 "$SRC/focus.sh" /usr/local/bin/focus-guardrail

# --- browser policies: kill private browsing and DoH (the main bypass) ---
sudo mkdir -p /etc/chromium/policies/managed /etc/opt/chrome/policies/managed /etc/firefox/policies
sudo cp "$SRC/browsers/chromium-policy.json" /etc/chromium/policies/managed/guardrail.json
sudo cp "$SRC/browsers/chromium-policy.json" /etc/opt/chrome/policies/managed/guardrail.json
if [ -e /etc/firefox/policies/policies.json ] && [ ! -e /etc/firefox/policies/policies.json.guardrail-bak ]; then
  sudo cp -a /etc/firefox/policies/policies.json /etc/firefox/policies/policies.json.guardrail-bak
elif [ ! -e /etc/firefox/policies/policies.json ]; then
  sudo touch "$ETC/firefox-policy-created"
fi
sudo cp "$SRC/browsers/firefox-policies.json" /etc/firefox/policies/policies.json

# --- local wildcard DNS so subdomains are blocked too (hosts can't) ---
if ! command -v dnsmasq >/dev/null 2>&1; then
  echo "installing dnsmasq..."
  sudo pacman -S --noconfirm dnsmasq
fi
sudo mkdir -p /etc/dnsmasq.d
if ! grep -qE '^[[:space:]]*conf-dir=/etc/dnsmasq.d' /etc/dnsmasq.conf; then
  echo 'conf-dir=/etc/dnsmasq.d/,*.conf' | sudo tee -a /etc/dnsmasq.conf >/dev/null
fi
sudo cp "$SRC/dns/guardrail.conf" /etc/dnsmasq.d/guardrail.conf

# upstream sync helper + NetworkManager dispatcher (keeps the real upstream DNS
# in sync so dnsmasq never forwards back into systemd-resolved in a loop)
sudo install -m 0755 "$SRC/dns/guardrail-upstream" /usr/local/bin/guardrail-upstream
sudo mkdir -p /etc/NetworkManager/dispatcher.d
sudo install -m 0755 "$SRC/dns/90-guardrail-dns" /etc/NetworkManager/dispatcher.d/90-guardrail-dns

sudo systemctl enable --now dnsmasq
sudo /usr/local/bin/guardrail-upstream || true

# point the system resolver at the local sinkhole (keep a backup to restore)
if { [ -e /etc/resolv.conf ] || [ -L /etc/resolv.conf ]; } && [ ! -e /etc/resolv.conf.guardrail-bak ]; then
  sudo cp -a /etc/resolv.conf /etc/resolv.conf.guardrail-bak
fi
if ! grep -q '^nameserver 127\.0\.0\.1[[:space:]]*$' /etc/resolv.conf 2>/dev/null; then
  sudo rm -f /etc/resolv.conf
  printf '# Managed by Guardrail\nnameserver 127.0.0.1\noptions edns0 trust-ad\nsearch .\n' | sudo tee /etc/resolv.conf >/dev/null
fi

# --- systemd timers ---
sudo cp "$SRC/systemd/focus-block.service" /etc/systemd/system/
sudo cp "$SRC/systemd/focus-unblock.service" /etc/systemd/system/
sudo cp "$SRC/systemd/focus-block.timer" /etc/systemd/system/
sudo cp "$SRC/systemd/focus-unblock.timer" /etc/systemd/system/

sudo systemctl daemon-reload
sudo systemctl enable --now focus-block.timer focus-unblock.timer

echo "installed. timers:"
systemctl list-timers 'focus-*' --no-pager | head -6
