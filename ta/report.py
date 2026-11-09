"""Markdown report + optional chart."""
from __future__ import annotations

from pathlib import Path

import numpy as np

from .analysis import WEIGHTS, Analysis
from .roast import SELF_ROAST, Burn, verdict

FIRE = {1: "🔥", 2: "🔥🔥", 3: "🔥🔥🔥"}


def _bar(score: float, width: int = 10) -> str:
    n = int(round(abs(score) * width))
    return ("+" if score >= 0 else "-") * n + "·" * (width - n)


def render(a: Analysis, burns: list[Burn], thesis: str | None = None, chart_path: str | None = None) -> str:
    st, plan = a.stats, a.plan
    v, why = verdict(a, burns)
    L = []
    L.append(f"# {a.ticker} — technical analysis & roast")
    L.append(f"_Data through {st['last_date']} · {st['bars']} bars · last close {st['price']:.2f}"
             + (f" · benchmark {a.bench_name}" if a.bench_name else "") + "_\n")
    L.append(f"## Verdict: **{v}**\n{why}\n")
    L.append(f"**Composite score: {a.composite:+.0f} / 100 → {a.stance}**\n")
    L.append("| Pillar | Weight | Score | |\n|---|---|---|---|")
    for k, p in a.pillars.items():
        L.append(f"| {k} | {int(WEIGHTS[k] * 100)}% | {100 * p.score:+.0f} | `{_bar(p.score)}` |")
    L.append("")
    if thesis:
        L.append(f"> **Your thesis:** {thesis}\n")
    if a.position:
        p = a.position
        L.append(f"> **Your position:** {p['side']} from {p['entry']:.2f} → {p['pnl_pct']:+.1f}% ({p['pnl_atr']:+.1f} ATR)\n")

    L.append("## 🔥 The roast\n")
    if burns:
        for x in burns:
            L.append(f"- {FIRE[x.severity]} {x.text}")
    else:
        L.append("- Annoyingly, nothing obvious to roast. That itself is suspicious — check the evidence table.")
    L.append("")

    L.append("## Trade plan (if you insist)\n")
    if plan.get("side"):
        L.append(f"| | |\n|---|---|\n| Side | **{plan['side'].upper()}** |\n| Entry (ref) | {plan['entry']:.2f} |"
                 f"\n| Stop | {plan['stop']:.2f} ({plan['stop_pct']:.1f}% away) |\n| Target | {plan['target']:.2f} ({plan['target_note']}) |"
                 f"\n| Reward:risk | {plan['rr']:.2f} |\n| Size @ {plan['risk_pct']}% risk of {plan['account']:,.0f} | "
                 f"{plan['qty']:,} shares ≈ {plan['position_value']:,.0f} ({plan['position_pct']:.0f}% of account) |\n")
        L.append("_Stop = structure (below nearest support / above resistance ± 0.5 ATR), clamped to 1–3 ATR. "
                 "Size = (account × risk%) ÷ (entry − stop)._\n")
    else:
        L.append(plan["note"] + "\n")

    L.append("## Key levels\n")
    L.append("- Resistance: " + (", ".join(f"{p:.2f} ({n}×)" for p, n in a.resistance) or "none from pivots"))
    L.append("- Support: " + (", ".join(f"{p:.2f} ({n}×)" for p, n in a.support) or "none from pivots"))
    L.append(f"- 52-week: {st['lo52']:.2f} – {st['hi52']:.2f}")
    if a.fib:
        f = a.fib
        L.append(f"- Fibonacci retracements of last {f['direction']} {f['from'].price:.2f} ({f['from'].date.date()}) → "
                 f"{f['to'].price:.2f} ({f['to'].date.date()}): "
                 + ", ".join(f"{int(r * 1000) / 10}% {lv:.2f}" for r, lv in f["levels"].items()))
    L.append("")

    pats = [f for f in a.findings if f.name != "Market structure"]
    L.append("## Patterns detected\n")
    if pats:
        for f in pats:
            arrow = "▲" if f.bias > 0 else "▼" if f.bias < 0 else "◆"
            L.append(f"- {arrow} **{f.name}** — {f.detail}")
    else:
        L.append("- None worth naming.")
    L.append("")

    L.append("## Every signal, by pillar\n")
    for k, p in a.pillars.items():
        L.append(f"### {k} ({100 * p.score:+.0f})")
        for s in p.signals:
            mark = "🟢" if s.value > 0.15 else "🔴" if s.value < -0.15 else "⚪"
            L.append(f"- {mark} {s.text}  `({s.value:+.2f})`")
        L.append("")

    L.append("## Evidence check: did these signals work on *this* stock?\n")
    L.append("Forward 20-bar return after each signal vs the unconditional base rate. Events thinned to one per 20 bars. "
             "No costs. n < 8 = anecdote.\n")
    L.append("| Rule | n | Avg fwd 20d | Hit rate | Base avg | Base hit | Edge |\n|---|---|---|---|---|---|---|")
    for r in a.evidence:
        if r.get("is_strategy"):
            continue
        if not r["n"]:
            L.append(f"| {r['rule']} | 0 | – | – | {r['base_avg']:+.2f}% | {r['base_hit']:.0f}% | – |")
            continue
        edge = r["avg_fwd"] - r["base_avg"]
        L.append(f"| {r['rule']} | {r['n']} | {r['avg_fwd']:+.2f}% | {r['hit']:.0f}% | {r['base_avg']:+.2f}% | "
                 f"{r['base_hit']:.0f}% | {edge:+.2f}{' ⚠️ small n' if r['n'] < 8 else ''} |")
    strat = [r for r in a.evidence if r.get("is_strategy")]
    if strat:
        r = strat[0]
        L.append(f"\n**50/200 trend filter vs buy & hold** (since the 200-SMA existed): "
                 f"{r['avg_fwd']:+.1f}% (max DD {r['hit']:.1f}%) vs {r['base_avg']:+.1f}% (max DD {r['base_hit']:.1f}%).")
    L.append("")

    if chart_path:
        L.append(f"## Chart\n\n![chart]({Path(chart_path).name})\n")

    L.append("## Roasting this report itself\n")
    for s in SELF_ROAST:
        L.append(f"- {s}")
    L.append("\n_Not financial advice. This is an educational tool that applies published technical-analysis "
             "principles mechanically. You are responsible for your own trades._")
    return "\n".join(L)


def chart(a: Analysis, path: str, bars: int = 250) -> str | None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return None
    df = a.df.tail(bars)
    I = {k: (v.tail(bars) if hasattr(v, "tail") else v) for k, v in a.ind.items()}
    fig, ax = plt.subplots(4, 1, figsize=(13, 11), sharex=True, gridspec_kw={"height_ratios": [4, 1, 1.2, 1.2]})
    p = ax[0]
    p.plot(df.index, df["close"], color="#222", lw=1.2, label="Close")
    for k, col in (("sma20", "#8aa"), ("sma50", "#2a6"), ("sma200", "#c33")):
        p.plot(df.index, I[k], lw=1, color=col, label=k.upper())
    p.fill_between(df.index, I["bb"]["upper"], I["bb"]["lower"], color="#88a", alpha=0.12, label="Bollinger 20,2")
    for lv, _ in a.support:
        p.axhline(lv, color="#2a6", ls="--", lw=0.8, alpha=0.7)
    for lv, _ in a.resistance:
        p.axhline(lv, color="#c33", ls="--", lw=0.8, alpha=0.7)
    pv = [x for x in a.pivots if x.date >= df.index[0]]
    p.plot([x.date for x in pv], [x.price for x in pv], color="#e90", lw=0.8, marker="o", ms=3, label="Swings")
    if a.plan.get("side"):
        p.axhline(a.plan["stop"], color="#900", lw=1.2, label=f"Stop {a.plan['stop']:.2f}")
        p.axhline(a.plan["target"], color="#060", lw=1.2, label=f"Target {a.plan['target']:.2f}")
    p.set_title(f"{a.ticker} — composite {a.composite:+.0f} ({a.stance})")
    p.legend(loc="upper left", fontsize=7, ncol=3)
    p.grid(alpha=0.2)
    colors = np.where(df["close"] >= df["open"], "#2a6", "#c33")
    ax[1].bar(df.index, df["volume"], color=colors, width=1.0)
    ax[1].plot(df.index, I["vol_sma50"], color="#333", lw=0.8)
    ax[1].set_ylabel("Volume")
    ax[2].plot(df.index, I["rsi"], color="#639")
    for y in (30, 50, 70):
        ax[2].axhline(y, color="#999", lw=0.6, ls=":")
    ax[2].set_ylabel("RSI 14")
    ax[3].plot(df.index, I["macd"]["macd"], color="#36c", lw=1, label="MACD")
    ax[3].plot(df.index, I["macd"]["signal"], color="#e90", lw=1, label="Signal")
    ax[3].bar(df.index, I["macd"]["hist"], color=np.where(I["macd"]["hist"] >= 0, "#2a6", "#c33"), width=1.0, alpha=0.5)
    ax[3].set_ylabel("MACD")
    ax[3].legend(fontsize=7, loc="upper left")
    for x in ax:
        x.grid(alpha=0.2)
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    plt.close(fig)
    return path
