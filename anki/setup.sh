#!/usr/bin/env bash
# Anki integration — one-shot local setup.
#
# Run this ON YOUR OWN COMPUTER (the one where the Anki desktop app runs).
# It installs deps, verifies it can reach Anki, and registers the MCP server
# with Claude Code if the `claude` CLI is available.
#
#   bash anki/setup.sh
#
# Re-running is safe (idempotent).

set -euo pipefail

# Resolve the directory this script lives in, so it works from anywhere.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m ✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m ! \033[0m%s\n' "$*"; }
die()  { printf '\033[1;31m ✗ %s\033[0m\n' "$*" >&2; exit 1; }

# --- 1. Node check ----------------------------------------------------------
command -v node >/dev/null 2>&1 || die "Node.js not found. Install Node 18+ from https://nodejs.org and re-run."
NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
[ "$NODE_MAJOR" -ge 18 ] || die "Node $(node -v) is too old; need 18+."
ok "Node $(node -v)"

# --- 2. Install deps (for the MCP server) -----------------------------------
say "Installing dependencies (@modelcontextprotocol/sdk, zod)…"
if command -v npm >/dev/null 2>&1; then
  npm install --silent
  ok "Dependencies installed"
else
  die "npm not found (it ships with Node). Reinstall Node and re-run."
fi

# --- 3. Verify Anki is reachable --------------------------------------------
say "Checking the connection to Anki/AnkiConnect…"
if node anki.mjs ping; then
  ok "Anki is reachable"
else
  warn "Could not reach Anki yet."
  warn "Make sure the Anki desktop app is OPEN and the AnkiConnect add-on is"
  warn "installed (Tools > Add-ons > Get Add-ons > code 2055492159, then restart)."
  warn "Setup will continue; re-run 'node anki/anki.mjs ping' once Anki is open."
fi

# --- 4. Register the MCP server with Claude ---------------------------------
MCP_PATH="$SCRIPT_DIR/mcp-server.mjs"
say "Registering the MCP server with Claude…"
if command -v claude >/dev/null 2>&1; then
  if claude mcp list 2>/dev/null | grep -q '^anki\b'; then
    ok "MCP server 'anki' already registered with Claude Code."
  else
    claude mcp add anki -- node "$MCP_PATH" \
      && ok "Registered 'anki' with Claude Code." \
      || warn "Could not auto-register; add it manually (see below)."
  fi
else
  warn "The 'claude' CLI was not found — registering with the Claude Desktop app instead."
  if node register-desktop.mjs; then
    ok "Claude Desktop config updated."
  else
    warn "Auto-registration failed. Add this to your claude_desktop_config.json"
    warn "(Settings > Developer > Edit Config) by hand, then restart Claude Desktop:"
    cat <<EOF

  {
    "mcpServers": {
      "anki": { "command": "node", "args": ["$MCP_PATH"] }
    }
  }
EOF
  fi
fi

cat <<EOF

$(ok "Setup complete.")

Next:
  • Keep the Anki desktop app open whenever you want Claude to reach it.
  • Restart Claude (or your Claude Code session) so it picks up the server.
  • Try asking Claude: "List my Anki decks" or
    "Add 5 cards about the water cycle to my Science deck."

  Terminal usage any time:
    node "$SCRIPT_DIR/anki.mjs" help
EOF
