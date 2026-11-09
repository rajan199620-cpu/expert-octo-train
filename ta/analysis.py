"""The analysis engine: runs every principle in knowledge/ against one instrument
and turns it into pillar scores, a composite score, a trade plan and an
evidence check on the stock's own history.

Scoring convention: every signal is a number in [-1, +1] (bearish..bullish).
A pillar's score is the mean of its signals. The composite is the weighted
mean of pillars, scaled to [-100, +100]. Weights are a judgement call and are
listed in WEIGHTS so you can change (and roast) them.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from . import indicators as ind
from . import patterns as pat
from .data import resample

WEIGHTS = {
    "Trend": 0.30,
    "Momentum": 0.20,
    "Volume": 0.15,
    "Structure": 0.15,
    "Relative strength": 0.10,
    "Volatility": 0.10,
}


@dataclass
class Signal:
    text: str
    value: float  # -1..+1


@dataclass
class Pillar:
    name: str
    signals: list[Signal] = field(default_factory=list)

    def add(self, text: str, value: float) -> None:
        self.signals.append(Signal(text, float(np.clip(value, -1, 1))))

    @property
    def score(self) -> float:
        return float(np.mean([s.value for s in self.signals])) if self.signals else 0.0


@dataclass
class Analysis:
    ticker: str
    df: pd.DataFrame
    ind: dict
    pillars: dict[str, Pillar]
    composite: float
    stance: str
    pivots: list
    support: list
    resistance: list
    findings: list
    fib: dict | None
    plan: dict
    evidence: list[dict]
    stats: dict
    bench_name: str | None = None
    position: dict | None = None


def _stance(score: float) -> str:
    if score >= 45:
        return "STRONG BULLISH"
    if score >= 15:
        return "BULLISH"
    if score > -15:
        return "NEUTRAL / NO EDGE"
    if score > -45:
        return "BEARISH"
    return "STRONG BEARISH"


def _slope(s: pd.Series, n: int) -> float:
    """Percent change of a series over n bars (NaN-safe)."""
    s = s.dropna()
    if len(s) <= n:
        return 0.0
    return float(s.iloc[-1] / s.iloc[-1 - n] - 1)


# --------------------------------------------------------------------------- #
def compute_indicators(df: pd.DataFrame) -> dict:
    c = df["close"]
    out = {
        "sma20": ind.sma(c, 20), "sma50": ind.sma(c, 50), "sma200": ind.sma(c, 200),
        "ema9": ind.ema(c, 9), "ema21": ind.ema(c, 21),
        "rsi": ind.rsi(c, 14), "macd": ind.macd(c), "stoch": ind.stochastic(df),
        "willr": ind.williams_r(df), "cci": ind.cci(df), "roc63": ind.roc(c, 63),
        "atr": ind.atr(df, 14), "bb": ind.bollinger(c), "kc": ind.keltner(df),
        "donchian20": ind.donchian(df, 20), "donchian55": ind.donchian(df, 55),
        "hv20": ind.hist_volatility(c, 20), "adx": ind.adx(df), "aroon": ind.aroon(df),
        "psar": ind.parabolic_sar(df), "supertrend": ind.supertrend(df), "ichimoku": ind.ichimoku(df),
        "obv": ind.obv(df), "ad": ind.ad_line(df), "cmf": ind.cmf(df), "mfi": ind.mfi(df),
        "vol_sma50": ind.sma(df["volume"], 50),
    }
    return out


def analyze(ticker: str, df: pd.DataFrame, bench: pd.DataFrame | None = None, bench_name: str | None = None,
            account: float = 100_000.0, risk_pct: float = 1.0, position: dict | None = None) -> Analysis:
    if len(df) < 60:
        raise ValueError(f"Need at least 60 bars to say anything; got {len(df)}.")
    I = compute_indicators(df)
    c = df["close"]
    price = float(c.iloc[-1])
    atr_now = float(I["atr"].iloc[-1])
    has_vol = df["volume"].tail(50).sum() > 0
    P = {k: Pillar(k) for k in WEIGHTS}

    pivots = pat.zigzag_pivots(df, I["atr"], mult=2.0)
    structure = pat.market_structure(pivots)

    # ---------------- Trend ----------------
    t = P["Trend"]
    s50, s200 = I["sma50"].iloc[-1], I["sma200"].iloc[-1]
    if not np.isnan(s200):
        t.add(f"Close {price:.2f} {'above' if price > s200 else 'below'} 200-day SMA {s200:.2f} "
              f"({100 * (price / s200 - 1):+.1f}%)", 1 if price > s200 else -1)
        t.add(f"200-day SMA slope over 20 bars: {100 * _slope(I['sma200'], 20):+.2f}%",
              np.sign(_slope(I["sma200"], 20)) * min(1, abs(_slope(I["sma200"], 20)) * 50))
        t.add(f"50-day SMA {'above' if s50 > s200 else 'below'} 200-day "
              f"({'golden-cross' if s50 > s200 else 'death-cross'} regime)", 0.7 if s50 > s200 else -0.7)
    if not np.isnan(s50):
        t.add(f"Close {'above' if price > s50 else 'below'} 50-day SMA {s50:.2f}", 0.8 if price > s50 else -0.8)
    e9, e21 = I["ema9"].iloc[-1], I["ema21"].iloc[-1]
    t.add(f"9-EMA {'above' if e9 > e21 else 'below'} 21-EMA (short-term trend)", 0.5 if e9 > e21 else -0.5)
    a = I["adx"].iloc[-1]
    di = 1 if a["plus_di"] > a["minus_di"] else -1
    strength = float(np.clip((a["adx"] - 15) / 25, 0, 1)) if not np.isnan(a["adx"]) else 0
    t.add(f"ADX {a['adx']:.1f} ({'trending' if a['adx'] >= 25 else 'weak/no trend' if a['adx'] < 20 else 'emerging'}); "
          f"+DI {a['plus_di']:.1f} vs -DI {a['minus_di']:.1f}", di * strength)
    st_dir = I["supertrend"]["direction"].iloc[-1]
    t.add(f"Supertrend(10,3) is {'long' if st_dir > 0 else 'short'}", 0.6 * st_dir)
    t.add(f"Parabolic SAR {I['psar'].iloc[-1]:.2f} is {'below' if I['psar'].iloc[-1] < price else 'above'} price",
          0.4 if I["psar"].iloc[-1] < price else -0.4)
    ich = I["ichimoku"].iloc[-1]
    if not np.isnan(ich["span_b"]):
        top, bot = max(ich["span_a"], ich["span_b"]), min(ich["span_a"], ich["span_b"])
        pos = 1 if price > top else -1 if price < bot else 0
        t.add(f"Ichimoku: price {'above' if pos > 0 else 'below' if pos < 0 else 'inside'} the cloud "
              f"({bot:.2f}–{top:.2f}); Tenkan {'>' if ich['tenkan'] > ich['kijun'] else '<'} Kijun",
              0.6 * pos + 0.2 * (1 if ich["tenkan"] > ich["kijun"] else -1))
    t.add(structure.detail, structure.bias * structure.strength)
    wk = resample(df, "W-FRI")
    if len(wk) >= 40:
        w10, w40 = ind.sma(wk["close"], 10).iloc[-1], ind.sma(wk["close"], 40).iloc[-1]
        wv = 1 if (wk["close"].iloc[-1] > w40 and w10 > w40) else -1 if (wk["close"].iloc[-1] < w40 and w10 < w40) else 0
        stage = {1: "Stage 2-like (advancing)", -1: "Stage 4-like (declining)", 0: "Stage 1/3-like (basing or topping)"}[wv]
        t.add(f"Weekly (higher timeframe): close vs 40-wk MA {w40:.2f}, 10-wk {w10:.2f} → {stage}", 0.9 * wv)

    # ---------------- Momentum ----------------
    m = P["Momentum"]
    r = float(I["rsi"].iloc[-1])
    if r >= 80:
        rv, rt = -0.3, "overbought extreme (pullback risk, though strong trends can stay here)"
    elif r >= 60:
        rv, rt = 0.7, "bullish range (40–80 holds in uptrends)"
    elif r >= 50:
        rv, rt = 0.3, "above the 50 midline, mildly bullish"
    elif r >= 40:
        rv, rt = -0.3, "below the 50 midline, mildly bearish"
    elif r > 20:
        rv, rt = -0.6, "bearish range (20–60 holds in downtrends)"
    else:
        rv, rt = 0.2, "oversold extreme (bounce odds up, trend still down)"
    m.add(f"RSI(14) {r:.1f}: {rt}", rv)
    md = I["macd"].iloc[-1]
    m.add(f"MACD {md['macd']:.3f} {'above' if md['macd'] > md['signal'] else 'below'} signal; "
          f"{'above' if md['macd'] > 0 else 'below'} zero; histogram "
          f"{'rising' if I['macd']['hist'].diff().iloc[-1] > 0 else 'falling'}",
          0.5 * (1 if md["macd"] > md["signal"] else -1) + 0.3 * (1 if md["macd"] > 0 else -1)
          + 0.2 * (1 if I["macd"]["hist"].diff().iloc[-1] > 0 else -1))
    roc63 = float(I["roc63"].iloc[-1]) if not np.isnan(I["roc63"].iloc[-1]) else 0.0
    m.add(f"3-month rate of change {roc63:+.1f}%", np.tanh(roc63 / 15))
    sk = I["stoch"].iloc[-1]
    m.add(f"Stochastic %K {sk['k']:.0f} / %D {sk['d']:.0f}",
          (0.3 if sk["k"] > sk["d"] else -0.3) + (-0.2 if sk["k"] > 80 else 0.2 if sk["k"] < 20 else 0))
    m.add(f"CCI(20) {I['cci'].iloc[-1]:.0f}", np.tanh(I["cci"].iloc[-1] / 150))
    divs = pat.divergences(pivots, I["rsi"], "RSI") + pat.divergences(pivots, I["macd"]["hist"], "MACD-hist")
    for d in divs:
        m.add(f"{d.name}: {d.detail}", d.bias * 0.8)

    # ---------------- Volume ----------------
    v = P["Volume"]
    if has_vol:
        obv_s = _slope(I["obv"] - I["obv"].min() + 1, 20)
        px_s = _slope(c, 20)
        v.add(f"OBV 20-bar trend {'up' if obv_s > 0 else 'down'} while price {'up' if px_s > 0 else 'down'}"
              + (" — volume CONFIRMS" if np.sign(obv_s) == np.sign(px_s) else " — volume DISAGREES"),
              0.7 * np.sign(obv_s))
        cm = float(I["cmf"].iloc[-1])
        v.add(f"Chaikin Money Flow(20) {cm:+.2f} ({'accumulation' if cm > 0.05 else 'distribution' if cm < -0.05 else 'neutral'})",
              np.tanh(cm * 8))
        mf = float(I["mfi"].iloc[-1])
        v.add(f"Money Flow Index {mf:.0f}" + (" (overbought)" if mf >= 80 else " (oversold)" if mf <= 20 else ""),
              -0.3 if mf >= 80 else 0.2 if mf <= 20 else np.tanh((mf - 50) / 15))
        last50 = df.tail(50)
        upv = last50["volume"][last50["close"] > last50["close"].shift(1)].mean()
        dnv = last50["volume"][last50["close"] < last50["close"].shift(1)].mean()
        if dnv and not np.isnan(upv):
            ratio = upv / dnv
            v.add(f"Avg volume on up-days vs down-days (50 bars): {ratio:.2f}x", np.tanh((ratio - 1) * 3))
        rv_ = df["volume"].iloc[-1] / I["vol_sma50"].iloc[-1] if I["vol_sma50"].iloc[-1] else np.nan
        day = 1 if c.iloc[-1] > c.iloc[-2] else -1
        if not np.isnan(rv_):
            v.add(f"Last bar volume {rv_:.1f}x its 50-day average on a {'up' if day > 0 else 'down'} day",
                  day * min(1, max(0, rv_ - 1)))
    else:
        v.add("No volume data (index/FX/forex feed?) — volume pillar is blind", 0.0)

    # ---------------- Volatility ----------------
    vo = P["Volatility"]
    bb = I["bb"].iloc[-1]
    bw = I["bb"]["bandwidth"]
    bw_rank = float((bw.tail(126) <= bw.iloc[-1]).mean())
    squeeze = I["bb"]["upper"].iloc[-1] < I["kc"]["upper"].iloc[-1] and I["bb"]["lower"].iloc[-1] > I["kc"]["lower"].iloc[-1]
    vo.add(f"Bollinger %B {bb['pct_b']:.2f}; bandwidth at {100 * bw_rank:.0f}th pct of 6 months"
           + (" — SQUEEZE (BB inside Keltner): big move pending, direction unknown" if squeeze else ""),
           (0.4 if bb["pct_b"] > 0.8 and P["Trend"].score > 0 else -0.4 if bb["pct_b"] < 0.2 and P["Trend"].score < 0 else 0)
           + (0.2 if bb["pct_b"] > 0.5 else -0.2))
    atr_pct = 100 * atr_now / price
    hv = float(I["hv20"].iloc[-1]) * 100
    hv_rank = float((I["hv20"].tail(252) <= I["hv20"].iloc[-1]).mean())
    vo.add(f"ATR(14) {atr_now:.2f} = {atr_pct:.1f}% of price; 20-day hist. vol {hv:.0f}% annualised "
           f"({100 * hv_rank:.0f}th pct of 1y)", -0.3 if hv_rank > 0.85 else 0.1 if hv_rank < 0.3 else 0)
    dc = I["donchian20"].iloc[-1]
    if not np.isnan(dc["upper"]):
        if price > dc["upper"]:
            vo.add(f"Close above prior 20-day high {dc['upper']:.2f} (Donchian breakout)", 0.7)
        elif price < dc["lower"]:
            vo.add(f"Close below prior 20-day low {dc['lower']:.2f} (Donchian breakdown)", -0.7)

    # ---------------- Structure ----------------
    s = P["Structure"]
    support, resistance = pat.support_resistance(pivots, price, atr_now)
    hi52 = float(df["high"].tail(252).max())
    lo52 = float(df["low"].tail(252).min())
    pos52 = (price - lo52) / (hi52 - lo52) if hi52 > lo52 else 0.5
    s.add(f"52-week range {lo52:.2f}–{hi52:.2f}; price at {100 * pos52:.0f}% of range "
          f"({100 * (price / hi52 - 1):+.1f}% from high)", (pos52 - 0.5) * 1.6)
    if support:
        d_s = (price - support[0][0]) / atr_now
        s.add(f"Nearest support {support[0][0]:.2f} ({support[0][1]} touches) is {d_s:.1f} ATR below", 0.3 if d_s < 2 else 0)
    if resistance:
        d_r = (resistance[0][0] - price) / atr_now
        s.add(f"Nearest resistance {resistance[0][0]:.2f} ({resistance[0][1]} touches) is {d_r:.1f} ATR above",
              -0.4 if d_r < 1 else 0)
    else:
        s.add("No overhead resistance from swing pivots (price discovery / near highs)", 0.4)
    trend_hint = 1 if _slope(c, 10) > 0 else -1
    findings = (pat.chart_patterns(pivots, df, atr_now) + pat.candlesticks(df, trend_hint)
                + pat.recent_gaps(df) + divs + [structure])
    for f in findings:
        if f.name in ("Market structure",) or f in divs:
            continue
        if f.name in ("Doji", "Gap up", "Gap down"):
            continue
        s.add(f"{f.name}: {f.detail}", f.bias * f.strength)

    # ---------------- Relative strength ----------------
    rs = P["Relative strength"]
    stats: dict = {}
    if bench is not None and len(bench) > 130:
        ratio = ind.relative_strength(c, bench["close"]).dropna()
        for n in (63, 126):
            if len(ratio) > n:
                stock_r = _slope(c, n) * 100
                bench_r = _slope(bench["close"].reindex(c.index).ffill(), n) * 100
                rs.add(f"{n}-bar return {stock_r:+.1f}% vs {bench_name} {bench_r:+.1f}% "
                       f"({'out' if stock_r > bench_r else 'under'}performing by {abs(stock_r - bench_r):.1f} pts)",
                       np.tanh((stock_r - bench_r) / 10))
        rs_ma = ind.sma(ratio, 50)
        if not np.isnan(rs_ma.iloc[-1]):
            rs.add(f"Relative-strength line {'above' if ratio.iloc[-1] > rs_ma.iloc[-1] else 'below'} its 50-bar average",
                   0.5 if ratio.iloc[-1] > rs_ma.iloc[-1] else -0.5)
        stats["bench_ret_126"] = _slope(bench["close"].reindex(c.index).ffill(), 126) * 100
    else:
        rs.add("No benchmark supplied — can't tell if this is stock strength or just the market", 0.0)

    weights = dict(WEIGHTS)
    if not has_vol:
        weights["Volume"] = 0.0
    if bench is None:
        weights["Relative strength"] = 0.0
    tot = sum(weights.values())
    composite = 100 * sum(weights[k] * P[k].score for k in P) / tot

    # ---------------- Trade plan ----------------
    plan = trade_plan(price, atr_now, composite, support, resistance, hi52, lo52, account, risk_pct)

    # ---------------- Stats for the roaster ----------------
    stats.update({
        "price": price, "atr": atr_now, "atr_pct": atr_pct, "rsi": r, "adx": float(a["adx"]),
        "hv": hv, "hv_rank": hv_rank, "squeeze": bool(squeeze), "hi52": hi52, "lo52": lo52,
        "dist_sma50_atr": (price - s50) / atr_now if not np.isnan(s50) else np.nan,
        "dist_sma200_pct": 100 * (price / s200 - 1) if not np.isnan(s200) else np.nan,
        "bars": len(df), "has_volume": bool(has_vol),
        "avg_turnover": float((df["close"] * df["volume"]).tail(50).mean()),
        "stock_ret_126": _slope(c, 126) * 100,
        "max_dd_1y": float((c.tail(252) / c.tail(252).cummax() - 1).min() * 100),
        "zero_vol_days": int((df["volume"].tail(50) == 0).sum()),
        "last_date": df.index[-1].date(),
    })

    pos_info = None
    if position:
        e = position["entry"]
        side = 1 if position.get("side", "long") == "long" else -1
        pnl = side * (price / e - 1) * 100
        pos_info = {"entry": e, "side": "long" if side > 0 else "short", "pnl_pct": pnl,
                    "pnl_atr": side * (price - e) / atr_now}

    return Analysis(ticker, df, I, P, composite, _stance(composite), pivots, support, resistance,
                    findings, pat.fibonacci(pivots), plan, evidence_check(df, I), stats, bench_name, pos_info)


# --------------------------------------------------------------------------- #
def trade_plan(price, atr_now, composite, support, resistance, hi52, lo52, account, risk_pct) -> dict:
    side = "long" if composite >= 15 else "short" if composite <= -15 else None
    if side is None:
        return {"side": None, "note": "No trade. Composite score is inside the ±15 no-edge band."}
    if side == "long":
        structural = support[0][0] - 0.5 * atr_now if support else price - 2.5 * atr_now
        stop = max(structural, price - 3 * atr_now)       # never wider than 3 ATR
        stop = min(stop, price - 1.0 * atr_now)           # never tighter than 1 ATR (noise)
        target = resistance[0][0] if resistance and resistance[0][0] > price + atr_now else None
        if target is None:
            target = price + 3 * (price - stop)
            tnote = "3R projection (no nearby resistance)"
        else:
            tnote = "nearest resistance"
    else:
        structural = resistance[0][0] + 0.5 * atr_now if resistance else price + 2.5 * atr_now
        stop = min(structural, price + 3 * atr_now)
        stop = max(stop, price + 1.0 * atr_now)
        target = support[0][0] if support and support[0][0] < price - atr_now else None
        if target is None:
            target = price - 3 * (stop - price)
            tnote = "3R projection (no nearby support)"
        else:
            tnote = "nearest support"
    risk = abs(price - stop)
    reward = abs(target - price)
    risk_amt = account * risk_pct / 100
    qty = int(risk_amt // risk) if risk > 0 else 0
    return {
        "side": side, "entry": price, "stop": stop, "target": target, "target_note": tnote,
        "risk_per_share": risk, "rr": reward / risk if risk else np.nan,
        "stop_pct": 100 * risk / price, "qty": qty, "risk_amt": risk_amt,
        "position_value": qty * price, "position_pct": 100 * qty * price / account if account else 0,
        "account": account, "risk_pct": risk_pct,
    }


# --------------------------------------------------------------------------- #
def evidence_check(df: pd.DataFrame, I: dict, horizon: int = 20) -> list[dict]:
    """Did the classic signals actually work on THIS instrument's history?

    For each rule, average the forward `horizon`-bar return after the signal
    fired and compare it with the unconditional forward return (the base rate).
    No costs, no slippage, overlapping windows — this is a sanity check, not a
    backtest you should trade from.
    """
    c = df["close"]
    fwd = c.shift(-horizon) / c - 1
    base = fwd.dropna()
    rows = []

    def add(name, mask, kind="event"):
        mask = mask.reindex(c.index).fillna(False).astype(bool)
        if kind == "event":
            mask = mask & ~mask.shift(1, fill_value=False)
            # thin out clustered events: at most one per horizon window
            idx, last = [], -10**9
            for k, flag in enumerate(mask.values):
                if flag and k - last >= horizon:
                    idx.append(k)
                    last = k
            mask = pd.Series(False, index=c.index)
            mask.iloc[idx] = True
        r = fwd[mask].dropna()
        rows.append({
            "rule": name, "n": int(len(r)),
            "avg_fwd": float(r.mean() * 100) if len(r) else np.nan,
            "hit": float((r > 0).mean() * 100) if len(r) else np.nan,
            "base_avg": float(base.mean() * 100), "base_hit": float((base > 0).mean() * 100),
        })

    sma50, sma200, rsi_, m = I["sma50"], I["sma200"], I["rsi"], I["macd"]
    add("Close above 200-SMA (state)", c > sma200, "state")
    add("Close below 200-SMA (state)", c < sma200, "state")
    add("Golden cross (50 over 200)", (sma50 > sma200) & (sma50.shift(1) <= sma200.shift(1)))
    add("Death cross (50 under 200)", (sma50 < sma200) & (sma50.shift(1) >= sma200.shift(1)))
    add("RSI crosses below 30 (buy the dip)", (rsi_ < 30) & (rsi_.shift(1) >= 30))
    add("RSI crosses above 70 ('overbought')", (rsi_ > 70) & (rsi_.shift(1) <= 70))
    add("MACD bullish cross", (m["macd"] > m["signal"]) & (m["macd"].shift(1) <= m["signal"].shift(1)))
    add("20-day Donchian breakout", c > I["donchian20"]["upper"])
    add("Close below lower Bollinger", c < I["bb"]["lower"])

    # Trend-following vs buy & hold over the whole sample (no costs)
    ret = c.pct_change().fillna(0)
    in_mkt = (sma50 > sma200).shift(1).fillna(False).astype(bool)
    strat = (1 + ret.where(in_mkt, 0)).cumprod()
    bh = (1 + ret).cumprod()
    valid = sma200.notna()
    if valid.sum() > 50:
        first = valid.idxmax()
        s_ = strat[first:] / strat[first]
        b_ = bh[first:] / bh[first]
        rows.append({
            "rule": "50/200 trend filter vs buy&hold (total return)",
            "n": int(valid.sum()), "avg_fwd": float((s_.iloc[-1] - 1) * 100),
            "hit": float((s_ / s_.cummax() - 1).min() * 100),
            "base_avg": float((b_.iloc[-1] - 1) * 100), "base_hit": float((b_ / b_.cummax() - 1).min() * 100),
            "is_strategy": True,
        })
    return rows
