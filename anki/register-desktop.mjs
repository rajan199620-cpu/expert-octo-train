#!/usr/bin/env node
// register-desktop.mjs — register the Anki MCP server with the Claude Desktop
// app by editing its claude_desktop_config.json. Cross-platform (Windows,
// macOS, Linux). Safe to re-run: it merges into any existing config and only
// touches the "anki" entry.
//
//   node anki/register-desktop.mjs
//
// Override the config location for testing with CLAUDE_DESKTOP_CONFIG=/path.

import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const MCP_PATH = resolve(HERE, 'mcp-server.mjs');

function configPath() {
  if (process.env.CLAUDE_DESKTOP_CONFIG) return process.env.CLAUDE_DESKTOP_CONFIG;
  const file = 'claude_desktop_config.json';
  if (process.platform === 'win32') {
    const appdata = process.env.APPDATA || join(process.env.USERPROFILE || '', 'AppData', 'Roaming');
    return join(appdata, 'Claude', file);
  }
  if (process.platform === 'darwin') {
    return join(process.env.HOME || '', 'Library', 'Application Support', 'Claude', file);
  }
  return join(process.env.HOME || '', '.config', 'Claude', file);
}

async function main() {
  const path = configPath();

  let config = {};
  if (existsSync(path)) {
    const raw = await readFile(path, 'utf8');
    if (raw.trim()) {
      try {
        config = JSON.parse(raw);
      } catch (e) {
        console.error(`Existing config at ${path} is not valid JSON; not overwriting.`);
        console.error(`Fix or remove it, then re-run. (${e.message})`);
        process.exit(1);
      }
    }
  }

  if (!config.mcpServers || typeof config.mcpServers !== 'object') config.mcpServers = {};

  const existed = !!config.mcpServers.anki;
  config.mcpServers.anki = { command: 'node', args: [MCP_PATH] };

  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, JSON.stringify(config, null, 2) + '\n', 'utf8');

  console.log(`${existed ? 'Updated' : 'Added'} "anki" MCP server in Claude Desktop config:`);
  console.log(`  config: ${path}`);
  console.log(`  command: node ${MCP_PATH}`);
  console.log('\nNow FULLY restart the Claude Desktop app (quit from the tray/menu,');
  console.log('not just close the window), keep Anki open, and ask: "List my Anki decks."');
}

main().catch((e) => {
  console.error(`Error: ${e.message || e}`);
  process.exit(1);
});
