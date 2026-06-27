# Lenovo Precision Pen 2 — stock & price watcher

Watches an Amazon.in product listing and is meant to alert only when the item is
**in stock AND priced at or under ₹5000** — once per distinct condition, never twice
for the same thing. It never purchases anything.

- Product: https://www.amazon.in/Lenovo-Precision-Pen-Laptop-Recognition/dp/B0BR4N1ZRW/
- Budget: ₹5000
- Intended cadence: hourly

## Files

- `check.mjs` — Playwright/Chromium scraper. Prints one JSON line describing
  `inStock`, `priceValue`, `availabilityText`, etc. Routes through the agent
  proxy (`HTTPS_PROXY`) and uses the pre-installed Chromium.
- `state.json` — last observed state, committed so each hourly run can compare
  against the previous one and avoid duplicate alerts. (Created on first
  successful check.)

## Run a single check

```bash
node monitor/check.mjs
```

## ⚠️ Network requirement

This watcher needs outbound HTTPS access to `www.amazon.in`. The current web
environment's network egress policy **blocks** that host (the agent proxy
answers `403` to the CONNECT), so the watcher cannot run here yet.

To enable it, recreate/configure this Claude Code on the web environment with a
network policy that allows `www.amazon.in` (or a less restrictive policy), then
re-run the hourly loop. See the network-policy section of
https://code.claude.com/docs/en/claude-code-on-the-web
