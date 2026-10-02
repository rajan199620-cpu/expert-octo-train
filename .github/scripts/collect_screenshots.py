#!/usr/bin/env python3
"""Rebuilds the watch screenshots logged by the UI tests (logcat tag ANKIWATCH_SHOT).

Usage: collect_screenshots.py <logcat dump> <output dir>

Each image arrives as "SHOT|<name>|<index>|<total>|<base64 chunk>" lines. The JPEGs are
written to the output dir and also echoed as base64 between markers, so they can be read
back from the job log when artifact downloads are unavailable.
"""
import base64
import os
import re
import sys

LINE = re.compile(r"SHOT\|([^|]+)\|(\d+)\|(\d+)\|(\S*)")


def main() -> int:
    dump, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    chunks = {}
    totals = {}
    if os.path.exists(dump):
        with open(dump, encoding="utf-8", errors="replace") as f:
            for line in f:
                m = LINE.search(line)
                if not m:
                    continue
                name, index, total, data = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
                chunks.setdefault(name, {})[index] = data
                totals[name] = total
    if not chunks:
        print("No screenshots found in", dump)
        return 0
    for name in sorted(chunks):
        parts = chunks[name]
        if len(parts) != totals[name]:
            print(f"{name}: incomplete ({len(parts)}/{totals[name]} chunks)")
            continue
        data = "".join(parts[i] for i in range(totals[name]))
        path = os.path.join(out_dir, name + ".jpg")
        with open(path, "wb") as f:
            f.write(base64.b64decode(data))
        print(f"{name}: {os.path.getsize(path)} bytes")
    for name in sorted(chunks):
        if len(chunks[name]) != totals[name]:
            continue
        data = "".join(chunks[name][i] for i in range(totals[name]))
        print(f"=== SHOT {name} BEGIN ===")
        for i in range(0, len(data), 4000):
            print(data[i:i + 4000])
        print(f"=== SHOT {name} END ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
