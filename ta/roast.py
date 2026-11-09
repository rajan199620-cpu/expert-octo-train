"""The roaster. Its whole job is to argue against the analysis, against your
thesis, and against technical analysis itself — using numbers, not vibes.

Every roast line has a severity: 1 = jab, 2 = real problem, 3 = deal-breaker.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

import numpy as np

from .analysis import Analysis


@dataclass
class Burn:
    severity: int
    text: str


def _evidence(a: Analysis) -> dict:
    return {r["rule"]: r for r in a.evidence}


def roast(a: Analysis, thesis: str | None = None) -> list[Burn]:
    b: list[Burn] = []
    st, P, plan = a.stats, a.pillars, a.plan
    bull = a.composite >= 15
    bear = a.composite <= -15

    # ---- data quality ----
    if st["bars"] < 250:
        b.append(Burn(2, f"Only {st['bars']} bars of history. A 200-day moving average on that is a rumour, not an indicator."))
    if not st["has_volume"]:
        b.append(Burn(2, "No volume data. You're doing technical analysis with one eye shut — OBV, CMF, MFI all blind."))
    elif st["zero_vol_days"] >= 5:
        b.append(Burn(3, f"{st['zero_vol_days']} of the last 50 sessions had ZERO volume. That's not a stock, it's a roach motel: you can get in, good luck getting out."))

    # ---- conflict between pillars ----
    scores = {k: p.score for k, p in P.items() if p.signals and p.score != 0}
    pos = [k for k, s in scores.items() if s > 0.2]
    neg = [k for k, s in scores.items() if s < -0.2]
    if pos and neg:
        b.append(Burn(2, f"Pillars are fighting: {', '.join(pos)} point up; {', '.join(neg)} point down. "
                         "A composite score averages that civil war into a number and calls it a conclusion."))
    if abs(a.composite) < 15:
        b.append(Burn(1, f"Composite {a.composite:+.0f}. That's the market telling you it has nothing to say. Listening is free; trading isn't."))

    # ---- trend quality ----
    if not np.isnan(st["adx"]) and st["adx"] < 20:
        b.append(Burn(2, f"ADX {st['adx']:.0f}: there is no trend. Every moving-average and crossover signal in this report is a coin flip with extra steps."))
    if P["Trend"].score > 0.3 and P["Momentum"].score < -0.1:
        b.append(Burn(2, "Trend still up but momentum is rolling over — this is what tops look like right before people say 'it's just a pullback'."))
    if P["Trend"].score < -0.3 and P["Momentum"].score > 0.1:
        b.append(Burn(1, "Momentum is bouncing inside a downtrend. Dead-cat bounces also have positive momentum, briefly."))

    # ---- extension / chasing ----
    d50 = st["dist_sma50_atr"]
    if bull and not np.isnan(d50) and d50 > 4:
        b.append(Burn(3, f"Price is {d50:.1f} ATRs above the 50-day. You're not early, you're the exit liquidity for people who were."))
    elif bull and not np.isnan(d50) and d50 > 2.5:
        b.append(Burn(2, f"{d50:.1f} ATRs above the 50-day — stretched. Waiting for a pullback costs nothing but ego."))
    if bull and st["rsi"] > 75:
        b.append(Burn(1, f"RSI {st['rsi']:.0f}. Strong trends can stay overbought, sure — but you're buying at the price everyone else already liked."))
    if bear and not np.isnan(d50) and d50 < -4:
        b.append(Burn(2, f"Price is {abs(d50):.1f} ATRs BELOW the 50-day. Shorting this far down means fighting every mean-reversion bot on earth."))
    if bear and st["rsi"] < 25:
        b.append(Burn(1, f"RSI {st['rsi']:.0f}: shorting a capitulation is how short squeezes get their fuel."))

    # ---- trade plan ----
    if plan.get("side"):
        if plan["rr"] < 1.5:
            b.append(Burn(3, f"Reward:risk is {plan['rr']:.2f}. You need to be right {100 / (1 + plan['rr']):.0f}%+ of the time just to break even before costs. You aren't."))
        elif plan["rr"] < 2:
            b.append(Burn(1, f"Reward:risk {plan['rr']:.2f} — workable only if your hit rate is genuinely above {100 / (1 + plan['rr']):.0f}%."))
        if plan["stop_pct"] > 10:
            b.append(Burn(2, f"Stop is {plan['stop_pct']:.1f}% away. That's not a stop, that's a hope with a number on it. Size down hard."))
        if plan["position_pct"] > 100:
            b.append(Burn(3, f"Position sizing says {plan['position_pct']:.0f}% of the account — i.e. leverage — because the stop is so tight. Tight stops in volatile names get hit by noise."))
        if plan["target_note"].startswith("3R"):
            b.append(Burn(1, "Target is a made-up 3R projection because nothing on the chart defines one. Price doesn't know your R multiple."))

    # ---- structure / patterns ----
    for f in a.findings:
        if f.name in ("Double top", "Double bottom", "Head & shoulders", "Inverse head & shoulders") and not f.meta.get("confirmed"):
            b.append(Burn(2, f"Unconfirmed {f.name.lower()}. Until the neckline breaks it's a shape you saw in some clouds. Bulkowski's own stats show a big chunk of these fail."))
        if f.name in ("Hammer", "Shooting star", "Doji", "Bullish engulfing", "Bearish engulfing", "Morning star", "Evening star"):
            b.append(Burn(1, f"A {f.name.lower()} on one bar is being counted. Single candles on daily charts have close to zero standalone predictive value in most studies."))
            break
    if a.fib:
        b.append(Burn(1, "Fibonacci levels are listed. They work mostly because enough people draw them — and everyone picks a different swing to draw them from."))
    if st["squeeze"]:
        b.append(Burn(1, "Bollinger squeeze: a big move is likely. Direction: unknown. Anyone who tells you which way is guessing."))
    if bull and a.resistance and (a.resistance[0][0] - st["price"]) / st["atr"] < 1:
        b.append(Burn(2, f"Buying right under resistance at {a.resistance[0][0]:.2f}. Let it break and hold first, or enjoy being the top tick of a double top."))
    if bear and a.support and (st["price"] - a.support[0][0]) / st["atr"] < 1:
        b.append(Burn(2, f"Shorting right on top of support at {a.support[0][0]:.2f}. That's where buyers showed up last time."))

    # ---- relative strength ----
    if "bench_ret_126" in st and bull and st["stock_ret_126"] < st["bench_ret_126"]:
        b.append(Burn(2, f"6-month return {st['stock_ret_126']:+.1f}% vs {a.bench_name} {st['bench_ret_126']:+.1f}%. "
                         "Why take single-stock risk to underperform an index fund?"))
    if st["max_dd_1y"] < -40:
        b.append(Burn(2, f"Max drawdown in the last year was {st['max_dd_1y']:.0f}%. Ask yourself honestly whether you'd have held through that."))
    if st["hv_rank"] > 0.85:
        b.append(Burn(1, f"Volatility is in the top {100 - 100 * st['hv_rank']:.0f}% of its 1-year range. Your stops will be noise-tested."))

    # ---- evidence: did these signals ever work here? ----
    ev = _evidence(a)
    for rule, r in ev.items():
        if r.get("is_strategy"):
            if r["avg_fwd"] < r["base_avg"]:
                b.append(Burn(2, f"On this stock's own history the 50/200 trend filter returned {r['avg_fwd']:+.0f}% vs {r['base_avg']:+.0f}% "
                                 f"for just holding (max DD {r['hit']:.0f}% vs {r['base_hit']:.0f}%). Trend-following lost to doing nothing here."))
            continue
        if r["n"] and r["n"] < 8 and "state" not in rule:
            continue
        if r["n"] >= 8 and not np.isnan(r["avg_fwd"]):
            edge = r["avg_fwd"] - r["base_avg"]
            bullish_rule = any(k in rule for k in ("above 200", "Golden", "below 30", "MACD bullish", "Donchian", "lower Bollinger"))
            if bullish_rule and edge < 0:
                b.append(Burn(1, f"'{rule}' historically gave {r['avg_fwd']:+.1f}% over 20 bars here vs a {r['base_avg']:+.1f}% base rate (n={r['n']}). It had NEGATIVE edge on this name."))
    # ---- existing position ----
    if a.position:
        p = a.position
        if p["side"] == "long" and bear:
            b.append(Burn(3, f"You're long from {p['entry']:.2f} ({p['pnl_pct']:+.1f}%) and every pillar is leaning against you. 'Hold and hope' is not a strategy, it's a coping mechanism."))
        if p["side"] == "short" and bull:
            b.append(Burn(3, f"You're short from {p['entry']:.2f} ({p['pnl_pct']:+.1f}%) into a bullish tape. Shorts have unlimited downside; your conviction doesn't change that."))
        if p["pnl_atr"] < -2:
            b.append(Burn(2, f"You're {abs(p['pnl_atr']):.1f} ATRs underwater. If your stop was where it should have been, you'd already be out."))
        if p["pnl_atr"] > 4:
            b.append(Burn(1, f"Up {p['pnl_atr']:.1f} ATRs — are you trailing a stop, or planning to round-trip this to breakeven?"))

    if thesis:
        b.extend(roast_thesis(thesis, a))
    b.sort(key=lambda x: -x.severity)
    return b


THESIS_TRIGGERS = [
    (r"\b(moon|rocket|to the moon|10x|100x|multibagger|multi-bagger)\b", 2, "'{m}'? Price targets based on emojis have a 0% hit rate in the literature."),
    (r"\b(guarantee|guaranteed|can'?t lose|sure ?shot|no risk|risk[- ]free|100%)\b", 3, "'{m}'. Nothing in markets is guaranteed. The person who believes this is the one who doesn't use a stop."),
    (r"\b(all[- ]in|entire (savings|portfolio)|margin|leverage|borrow)\b", 3, "'{m}'. Position sizing is the only free lunch in trading and you just declined it."),
    (r"\b(undervalued|cheap|value|p/?e|earnings|fundamental)", 1, "You're arguing '{m}' — that's fundamentals. This toolkit can't see valuation; a cheap stock can get cheaper for years."),
    (r"\b(bottom(ed)?|can'?t go lower|oversold)\b", 2, "'{m}'. Calling bottoms is how people catch falling knives. Wait for structure (a higher low) instead."),
    (r"\b(tip|telegram|whatsapp|youtube|influencer|insider|friend said|heard)\b", 3, "Source of the idea: '{m}'. If they knew, why are they telling you?"),
    (r"\b(average down|averaging down|buy more if it drops)\b", 2, "'{m}'. Averaging down without a plan is how a small loss gets promoted to a big one."),
    (r"\b(long[- ]term|forever|hold for years)\b", 1, "'{m}' — then why are you asking about daily technicals? Pick a timeframe and own it."),
    (r"\b(breakout|breaking out)\b", 1, "'{m}': most breakouts on daily charts fail or retest. Did volume expand? Did it close above, or just poke?"),
]


def roast_thesis(thesis: str, a: Analysis) -> list[Burn]:
    out: list[Burn] = []
    t = thesis.lower()
    for pattern, sev, msg in THESIS_TRIGGERS:
        mt = re.search(pattern, t)
        if mt:
            out.append(Burn(sev, "Your thesis: " + msg.format(m=mt.group(0))))
    wants_up = bool(re.search(r"\b(buy|long|bull|up|higher|rally|breakout|moon|calls?)\b", t))
    wants_down = bool(re.search(r"\b(sell|short|bear|down|lower|crash|dump|puts?)\b", t))
    if wants_up and not wants_down and a.composite <= -15:
        out.append(Burn(3, f"Your thesis is bullish; the chart scores {a.composite:+.0f} ({a.stance}). You're not contrarian, you're early — and early is a polite word for wrong."))
    if wants_down and not wants_up and a.composite >= 15:
        out.append(Burn(3, f"Your thesis is bearish; the chart scores {a.composite:+.0f} ({a.stance}). Shorting strength because it 'feels too high' is a classic way to fund other people's yachts."))
    if not re.search(r"\b(stop|stop[- ]loss|invalidat|exit|risk)\b", t):
        out.append(Burn(2, "Your thesis has no exit or invalidation level. If you can't say where you're wrong, you don't have a trade, you have a belief."))
    if len(t.split()) < 6:
        out.append(Burn(1, "That thesis is shorter than a tweet. Conviction without reasons is just volume."))
    return out


SELF_ROAST = [
    "The pillar weights (30/20/15/15/10/10) are a judgement call, not a law of physics. Change them and the 'answer' changes.",
    "Every indicator here is a transformation of the same price series. Ten indicators agreeing is closer to one opinion said ten times than ten independent votes.",
    "This sees no earnings dates, news, guidance, insider trades, macro, or valuation. An earnings gap ignores all of your support levels.",
    "Pattern detection uses fixed tolerances. A human would draw some of these patterns differently — that subjectivity is a known weakness of chart reading.",
    "The evidence table uses overlapping windows, no costs, no slippage, and one stock. Small n = anecdote. It can embarrass a signal; it can't prove one.",
    "Academic evidence on simple TA rules is mixed at best once costs and data-snooping are accounted for (see knowledge/15-evidence-and-criticisms.md). Treat this as a risk framework, not a crystal ball.",
]


def verdict(a: Analysis, burns: list[Burn]) -> tuple[str, str]:
    heat = sum(x.severity for x in burns if x.severity >= 2)
    fatal = [x for x in burns if x.severity >= 3]
    if a.plan.get("side") is None:
        return "PASS", "No edge. Close the chart, go outside."
    if fatal:
        return "DON'T (yet)", f"{len(fatal)} deal-breaker(s) above. Fix those or skip it."
    if heat >= 8:
        return "WAIT", "Direction is plausible but too many real problems stack up. Let the setup clean itself up."
    side = "LONG" if a.plan["side"] == "long" else "SHORT"
    return f"CONSIDER {side}", "Setup survives the roast. Size by the stop, not by conviction, and respect the invalidation level."
