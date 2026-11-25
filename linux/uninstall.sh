#!/usr/bin/env bash
set -euo pipefail

sudo systemctl disable --now focus-block.timer focus-unblock.timer 2>/dev/null || true
sudo systemctl stop focus-block.service focus-unblock.service 2>/dev/null || true

/usr/local/bin/focus-guardrail off 2>/dev/null || true

sudo rm -f /etc/systemd/system/focus-block.service \
           /etc/systemd/system/focus-unblock.service \
           /etc/systemd/system/focus-block.timer \
           /etc/systemd/system/focus-unblock.timer
sudo systemctl daemon-reload

# --- local DNS: drop the sinkhole and restore the original resolver ---
sudo rm -f /etc/dnsmasq.d/guardrail.conf /etc/dnsmasq.d/guardrail-block.conf \
           /etc/dnsmasq.d/guardrail-upstream.conf
sudo systemctl disable --now dnsmasq 2>/dev/null || true
sudo rm -f /usr/local/bin/guardrail-upstream \
           /etc/NetworkManager/dispatcher.d/90-guardrail-dns
if [ -e /etc/resolv.conf.guardrail-bak ]; then
  sudo rm -f /etc/resolv.conf
  sudo cp -a /etc/resolv.conf.guardrail-bak /etc/resolv.conf
  sudo rm -f /etc/resolv.conf.guardrail-bak
elif grep -q '^# Managed by Guardrail$' /etc/resolv.conf 2>/dev/null; then
  sudo rm -f /etc/resolv.conf
fi

# Restore a pre-existing Firefox policy file; remove only the file created by Guardrail.
if [ -e /etc/firefox/policies/policies.json.guardrail-bak ]; then
  sudo rm -f /etc/firefox/policies/policies.json
  sudo mv /etc/firefox/policies/policies.json.guardrail-bak /etc/firefox/policies/policies.json
elif [ -e /etc/guardrail/firefox-policy-created ]; then
  sudo rm -f /etc/firefox/policies/policies.json
fi

sudo rm -f /usr/local/bin/focus-guardrail
sudo rm -rf /etc/guardrail
sudo rm -f /etc/chromium/policies/managed/guardrail.json \
           /etc/opt/chrome/policies/managed/guardrail.json

echo "removed (dnsmasq package left installed; remove with: sudo pacman -Rns dnsmasq)."
