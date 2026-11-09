"""Command line:  python -m ta TICKER [TICKER ...] [options]

Examples
  python -m ta AAPL --benchmark ^GSPC
  python -m ta RELIANCE.NS TCS.NS --benchmark ^NSEI --thesis "breakout, going to the moon"
  python -m ta data/mystock.csv --benchmark data/nifty.csv --chart
  python -m ta INFY.NS --entry 1520 --side long        # roast a position you already hold
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from .analysis import analyze
from .data import load
from .report import chart, render
from .roast import roast, verdict

ROOT = Path(__file__).resolve().parent.parent


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m ta", description="Technical analysis + roast.")
    ap.add_argument("tickers", nargs="+", help="Ticker symbols (Yahoo style, e.g. AAPL, RELIANCE.NS) or CSV paths")
    ap.add_argument("--source", default="auto", choices=["auto", "yahoo", "stooq", "csv"])
    ap.add_argument("--benchmark", "-b", help="Index to compare against, e.g. ^GSPC, ^NSEI, or a CSV path")
    ap.add_argument("--years", type=int, default=3, help="Years of daily history to pull (default 3)")
    ap.add_argument("--thesis", "-t", help="Your reason for the trade — it will be roasted too")
    ap.add_argument("--entry", type=float, help="Your entry price, if you already hold a position")
    ap.add_argument("--side", choices=["long", "short"], default="long")
    ap.add_argument("--account", type=float, default=100_000, help="Account size for position sizing")
    ap.add_argument("--risk", type=float, default=1.0, help="Percent of account risked per trade (default 1)")
    ap.add_argument("--chart", action="store_true", help="Save a PNG chart next to the report (needs matplotlib)")
    ap.add_argument("--out", default=str(ROOT / "reports"), help="Directory for reports")
    ap.add_argument("--no-cache", action="store_true")
    ap.add_argument("--print", dest="print_", action="store_true", help="Print the full report to stdout")
    args = ap.parse_args(argv)

    bench = None
    bench_name = Path(args.benchmark).stem if args.benchmark and args.benchmark.endswith(".csv") else args.benchmark
    if args.benchmark:
        try:
            bench = load(args.benchmark, args.source if args.source != "csv" else "auto", args.years, not args.no_cache)
        except Exception as e:  # noqa: BLE001
            print(f"[warn] benchmark {args.benchmark} unavailable: {e}", file=sys.stderr)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    rc = 0
    summary = []
    for tk in args.tickers:
        try:
            df = load(tk, args.source, args.years, not args.no_cache)
            pos = {"entry": args.entry, "side": args.side} if args.entry else None
            a = analyze(Path(tk).stem if tk.endswith(".csv") else tk, df, bench, bench_name,
                        args.account, args.risk, pos)
            burns = roast(a, args.thesis)
            name = "".join(ch if ch.isalnum() or ch in "-_." else "_" for ch in a.ticker)
            stamp = time.strftime("%Y-%m-%d")
            cp = chart(a, str(out / f"{name}_{stamp}.png")) if args.chart else None
            md = render(a, burns, args.thesis, cp)
            rp = out / f"{name}_{stamp}.md"
            rp.write_text(md, encoding="utf-8")
            v, _ = verdict(a, burns)
            summary.append((a.ticker, a.composite, a.stance, v, len(burns), rp))
            if args.print_:
                print(md)
                print("\n" + "=" * 80 + "\n")
        except Exception as e:  # noqa: BLE001
            print(f"[error] {tk}: {e}", file=sys.stderr)
            rc = 1
    if summary:
        print(f"{'Ticker':<16}{'Score':>7}  {'Stance':<20}{'Verdict':<16}{'Burns':>6}  Report")
        for tk, sc, stn, v, nb, rp in summary:
            print(f"{tk:<16}{sc:>+7.0f}  {stn:<20}{v:<16}{nb:>6}  {rp}")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
