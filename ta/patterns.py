"""Pattern recognition: swing pivots, market structure, support/resistance,
candlesticks, classic chart patterns, divergences and Fibonacci levels.

Pattern detection is inherently fuzzy. Every detector here uses explicit,
documented tolerances so you can see (and argue with) what was counted.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd


@dataclass
class Pivot:
    i: int            # integer bar position
    date: pd.Timestamp
    price: float
    kind: str         # "H" or "L"


@dataclass
class Finding:
    name: str
    bias: int          # +1 bullish, -1 bearish, 0 neutral
    strength: float    # 0..1 rough confidence
    detail: str
    meta: dict = field(default_factory=dict)


# --------------------------------------------------------------------------- #
# Swing pivots (zigzag by ATR threshold)
# --------------------------------------------------------------------------- #
def zigzag_pivots(df: pd.DataFrame, atr: pd.Series, mult: float = 2.0) -> list[Pivot]:
    """Alternating swing highs/lows; a reversal must exceed mult * ATR."""
    highs, lows = df["high"].values, df["low"].values
    a = atr.bfill().values
    dates = df.index
    pivots: list[Pivot] = []
    if len(df) < 3:
        return pivots
    trend = 0
    hi_i, lo_i = 0, 0
    for i in range(1, len(df)):
        th = mult * a[i]
        if highs[i] > highs[hi_i]:
            hi_i = i
        if lows[i] < lows[lo_i]:
            lo_i = i
        if trend >= 0 and highs[hi_i] - lows[i] > th and hi_i < i:
            if trend == 0 or not pivots or pivots[-1].kind != "H":
                pivots.append(Pivot(hi_i, dates[hi_i], float(highs[hi_i]), "H"))
            trend, lo_i = -1, i
        elif trend <= 0 and highs[i] - lows[lo_i] > th and lo_i < i:
            if trend == 0 or not pivots or pivots[-1].kind != "L":
                pivots.append(Pivot(lo_i, dates[lo_i], float(lows[lo_i]), "L"))
            trend, hi_i = 1, i
    # De-duplicate same-kind neighbours (keep the extreme)
    clean: list[Pivot] = []
    for p in pivots:
        if clean and clean[-1].kind == p.kind:
            better = (p.price > clean[-1].price) if p.kind == "H" else (p.price < clean[-1].price)
            if better:
                clean[-1] = p
        else:
            clean.append(p)
    return clean


def market_structure(pivots: list[Pivot]) -> Finding:
    """Dow-style structure from the last two swing highs and lows."""
    hs = [p for p in pivots if p.kind == "H"][-2:]
    ls = [p for p in pivots if p.kind == "L"][-2:]
    if len(hs) < 2 or len(ls) < 2:
        return Finding("Market structure", 0, 0.0, "Not enough swings to define structure.")
    hh = hs[1].price > hs[0].price
    hl = ls[1].price > ls[0].price
    if hh and hl:
        return Finding("Market structure", +1, 0.8, "Higher highs AND higher lows — textbook uptrend (Dow).")
    if not hh and not hl:
        return Finding("Market structure", -1, 0.8, "Lower highs AND lower lows — textbook downtrend (Dow).")
    if hh and not hl:
        return Finding("Market structure", 0, 0.3, "Higher high but lower low — expanding/broadening, no clean trend.")
    return Finding("Market structure", 0, 0.3, "Lower high but higher low — contracting/coiling (triangle-like).")


# --------------------------------------------------------------------------- #
# Support / resistance clustering
# --------------------------------------------------------------------------- #
def support_resistance(pivots: list[Pivot], price: float, atr_now: float, max_levels: int = 4):
    """Cluster pivot prices within 0.75*ATR; levels touched more often rank higher."""
    if not pivots:
        return [], []
    prices = sorted(p.price for p in pivots)
    tol = 0.75 * atr_now
    clusters: list[list[float]] = []
    for pr in prices:
        if clusters and abs(pr - np.mean(clusters[-1])) <= tol:
            clusters[-1].append(pr)
        else:
            clusters.append([pr])
    levels = [(float(np.mean(c)), len(c)) for c in clusters]
    support = sorted([lv for lv in levels if lv[0] < price], key=lambda x: -x[0])[:max_levels]
    resistance = sorted([lv for lv in levels if lv[0] >= price], key=lambda x: x[0])[:max_levels]
    return support, resistance


# --------------------------------------------------------------------------- #
# Candlesticks (last few bars only — older candles are stale information)
# --------------------------------------------------------------------------- #
def candlesticks(df: pd.DataFrame, trend_hint: int, lookback: int = 3) -> list[Finding]:
    """trend_hint: +1 if prior trend up, -1 if down. Reversal candles only matter
    in context (a hammer in an uptrend is just a candle)."""
    o, h, l, c = (df[k].values for k in ("open", "high", "low", "close"))
    out: list[Finding] = []
    n = len(df)
    avg_body = np.nanmean(np.abs(c[-20:] - o[-20:])) or 1e-9
    for j in range(max(2, n - lookback), n):
        body = abs(c[j] - o[j])
        rng = h[j] - l[j] or 1e-9
        upper = h[j] - max(c[j], o[j])
        lower = min(c[j], o[j]) - l[j]
        when = df.index[j].date()
        pb = abs(c[j - 1] - o[j - 1])
        if body <= 0.1 * rng:
            out.append(Finding("Doji", 0, 0.3, f"{when}: indecision candle (body ≤10% of range)."))
        if lower >= 2 * body and upper <= 0.3 * rng and body > 0 and trend_hint < 0:
            out.append(Finding("Hammer", +1, 0.4, f"{when}: hammer after decline — buyers rejected lows."))
        if upper >= 2 * body and lower <= 0.3 * rng and body > 0 and trend_hint > 0:
            out.append(Finding("Shooting star", -1, 0.4, f"{when}: shooting star after advance — sellers rejected highs."))
        if c[j] > o[j] and c[j - 1] < o[j - 1] and c[j] >= o[j - 1] and o[j] <= c[j - 1] and body > pb:
            out.append(Finding("Bullish engulfing", +1, 0.5 if trend_hint < 0 else 0.25, f"{when}: bullish engulfing."))
        if c[j] < o[j] and c[j - 1] > o[j - 1] and o[j] >= c[j - 1] and c[j] <= o[j - 1] and body > pb:
            out.append(Finding("Bearish engulfing", -1, 0.5 if trend_hint > 0 else 0.25, f"{when}: bearish engulfing."))
        if body >= 0.95 * rng and body > 1.5 * avg_body:
            b = 1 if c[j] > o[j] else -1
            out.append(Finding("Marubozu", b, 0.3, f"{when}: {'bullish' if b > 0 else 'bearish'} marubozu — one side in total control."))
        if c[j - 1] < o[j - 1] and c[j] > o[j] and o[j] < l[j - 1] and c[j] > (o[j - 1] + c[j - 1]) / 2 and c[j] < o[j - 1]:
            out.append(Finding("Piercing line", +1, 0.35, f"{when}: piercing line."))
        if c[j - 1] > o[j - 1] and c[j] < o[j] and o[j] > h[j - 1] and c[j] < (o[j - 1] + c[j - 1]) / 2 and c[j] > o[j - 1]:
            out.append(Finding("Dark cloud cover", -1, 0.35, f"{when}: dark cloud cover."))
        if j >= 2:
            b0 = abs(c[j - 2] - o[j - 2])
            if c[j - 2] < o[j - 2] and b0 > avg_body and pb < 0.5 * b0 and c[j] > o[j] and c[j] > (o[j - 2] + c[j - 2]) / 2:
                out.append(Finding("Morning star", +1, 0.5, f"{when}: morning star (3-bar bottom reversal)."))
            if c[j - 2] > o[j - 2] and b0 > avg_body and pb < 0.5 * b0 and c[j] < o[j] and c[j] < (o[j - 2] + c[j - 2]) / 2:
                out.append(Finding("Evening star", -1, 0.5, f"{when}: evening star (3-bar top reversal)."))
            if all(c[x] > o[x] for x in (j - 2, j - 1, j)) and c[j] > c[j - 1] > c[j - 2] and body > avg_body:
                out.append(Finding("Three white soldiers", +1, 0.4, f"{when}: three white soldiers."))
            if all(c[x] < o[x] for x in (j - 2, j - 1, j)) and c[j] < c[j - 1] < c[j - 2] and body > avg_body:
                out.append(Finding("Three black crows", -1, 0.4, f"{when}: three black crows."))
    return out


# --------------------------------------------------------------------------- #
# Classic chart patterns from pivots
# --------------------------------------------------------------------------- #
def chart_patterns(pivots: list[Pivot], df: pd.DataFrame, atr_now: float) -> list[Finding]:
    out: list[Finding] = []
    price = float(df["close"].iloc[-1])
    tol = 1.0 * atr_now
    p = pivots[-7:]
    if len(p) >= 4:
        # Double top: H L H with tops within tol; confirmation = close below the L.
        a, b, c_ = p[-3], p[-2], p[-1]
        if a.kind == "H" and b.kind == "L" and c_.kind == "H" and abs(a.price - c_.price) <= tol:
            conf = price < b.price
            tgt = b.price - (max(a.price, c_.price) - b.price)
            out.append(Finding("Double top", -1, 0.6 if conf else 0.3,
                               f"Tops {a.price:.2f}/{c_.price:.2f}, neckline {b.price:.2f} "
                               f"({'CONFIRMED' if conf else 'unconfirmed — needs close below neckline'}); measured target {tgt:.2f}.",
                               {"neckline": b.price, "target": tgt, "confirmed": conf}))
        if a.kind == "L" and b.kind == "H" and c_.kind == "L" and abs(a.price - c_.price) <= tol:
            conf = price > b.price
            tgt = b.price + (b.price - min(a.price, c_.price))
            out.append(Finding("Double bottom", +1, 0.6 if conf else 0.3,
                               f"Bottoms {a.price:.2f}/{c_.price:.2f}, neckline {b.price:.2f} "
                               f"({'CONFIRMED' if conf else 'unconfirmed — needs close above neckline'}); measured target {tgt:.2f}.",
                               {"neckline": b.price, "target": tgt, "confirmed": conf}))
    if len(p) >= 5:
        l_s, n1, hd, n2, r_s = p[-5:]
        if [x.kind for x in (l_s, n1, hd, n2, r_s)] == ["H", "L", "H", "L", "H"]:
            if hd.price > l_s.price + 0.5 * tol and hd.price > r_s.price + 0.5 * tol and abs(l_s.price - r_s.price) <= 1.5 * tol:
                neck = (n1.price + n2.price) / 2
                conf = price < min(n1.price, n2.price)
                out.append(Finding("Head & shoulders", -1, 0.65 if conf else 0.35,
                                   f"Head {hd.price:.2f}, shoulders {l_s.price:.2f}/{r_s.price:.2f}, neckline ~{neck:.2f} "
                                   f"({'CONFIRMED' if conf else 'unconfirmed'}); target {neck - (hd.price - neck):.2f}.",
                                   {"neckline": neck, "target": neck - (hd.price - neck), "confirmed": conf}))
        if [x.kind for x in (l_s, n1, hd, n2, r_s)] == ["L", "H", "L", "H", "L"]:
            if hd.price < l_s.price - 0.5 * tol and hd.price < r_s.price - 0.5 * tol and abs(l_s.price - r_s.price) <= 1.5 * tol:
                neck = (n1.price + n2.price) / 2
                conf = price > max(n1.price, n2.price)
                out.append(Finding("Inverse head & shoulders", +1, 0.65 if conf else 0.35,
                                   f"Head {hd.price:.2f}, shoulders {l_s.price:.2f}/{r_s.price:.2f}, neckline ~{neck:.2f} "
                                   f"({'CONFIRMED' if conf else 'unconfirmed'}); target {neck + (neck - hd.price):.2f}.",
                                   {"neckline": neck, "target": neck + (neck - hd.price), "confirmed": conf}))
    # Triangles / wedges: slopes of the last 3 highs and last 3 lows.
    hs = [x for x in pivots if x.kind == "H"][-3:]
    ls = [x for x in pivots if x.kind == "L"][-3:]
    if len(hs) >= 2 and len(ls) >= 2:
        sh = np.polyfit([x.i for x in hs], [x.price for x in hs], 1)[0]
        sl = np.polyfit([x.i for x in ls], [x.price for x in ls], 1)[0]
        flat = 0.02 * atr_now  # per-bar slope considered "flat"
        name = None
        bias = 0
        if abs(sh) <= flat and sl > flat:
            name, bias = "Ascending triangle", +1
        elif sh < -flat and abs(sl) <= flat:
            name, bias = "Descending triangle", -1
        elif sh < -flat and sl > flat:
            name, bias = "Symmetrical triangle", 0
        elif sh > flat and sl > flat and sl > sh * 1.2:
            name, bias = "Rising wedge", -1
        elif sh < -flat and sl < -flat and sh < sl * 1.2:
            name, bias = "Falling wedge", +1
        if name:
            out.append(Finding(name, bias, 0.35,
                               f"Swing-high slope {sh:+.3f}/bar, swing-low slope {sl:+.3f}/bar. "
                               "Breakout direction (on volume) matters more than the shape."))
    return out


# --------------------------------------------------------------------------- #
# Divergence (price pivot vs oscillator at the same bars)
# --------------------------------------------------------------------------- #
def divergences(pivots: list[Pivot], osc: pd.Series, name: str, recent_bars: int = 40) -> list[Finding]:
    out: list[Finding] = []
    n = len(osc)
    hs = [p for p in pivots if p.kind == "H"][-2:]
    ls = [p for p in pivots if p.kind == "L"][-2:]
    if len(hs) == 2 and hs[1].i >= n - recent_bars:
        o0, o1 = osc.iloc[hs[0].i], osc.iloc[hs[1].i]
        if hs[1].price > hs[0].price and o1 < o0:
            out.append(Finding(f"Bearish {name} divergence", -1, 0.45,
                               f"Price higher high ({hs[0].price:.2f}→{hs[1].price:.2f}) but {name} lower ({o0:.3g}→{o1:.3g})."))
    if len(ls) == 2 and ls[1].i >= n - recent_bars:
        o0, o1 = osc.iloc[ls[0].i], osc.iloc[ls[1].i]
        if ls[1].price < ls[0].price and o1 > o0:
            out.append(Finding(f"Bullish {name} divergence", +1, 0.45,
                               f"Price lower low ({ls[0].price:.2f}→{ls[1].price:.2f}) but {name} higher ({o0:.3g}→{o1:.3g})."))
    return out


# --------------------------------------------------------------------------- #
# Fibonacci retracements of the last completed swing
# --------------------------------------------------------------------------- #
FIB_RATIOS = (0.236, 0.382, 0.5, 0.618, 0.786)


def fibonacci(pivots: list[Pivot]) -> dict | None:
    if len(pivots) < 2:
        return None
    a, b = pivots[-2], pivots[-1]
    up = b.price > a.price
    span = b.price - a.price
    levels = {r: b.price - r * span for r in FIB_RATIOS}
    return {"from": a, "to": b, "direction": "up-swing" if up else "down-swing", "levels": levels}


# --------------------------------------------------------------------------- #
# Gaps
# --------------------------------------------------------------------------- #
def recent_gaps(df: pd.DataFrame, lookback: int = 20) -> list[Finding]:
    out = []
    d = df.tail(lookback + 1)
    for j in range(1, len(d)):
        prev_h, prev_l = d["high"].iloc[j - 1], d["low"].iloc[j - 1]
        lo, hi = d["low"].iloc[j], d["high"].iloc[j]
        date = d.index[j].date()
        if lo > prev_h:
            filled = (d["low"].iloc[j:] <= prev_h).any()
            out.append(Finding("Gap up", +1, 0.2, f"{date}: gap up {prev_h:.2f}→{lo:.2f} ({'filled' if filled else 'UNFILLED'})."))
        elif hi < prev_l:
            filled = (d["high"].iloc[j:] >= prev_l).any()
            out.append(Finding("Gap down", -1, 0.2, f"{date}: gap down {prev_l:.2f}→{hi:.2f} ({'filled' if filled else 'UNFILLED'})."))
    return out
