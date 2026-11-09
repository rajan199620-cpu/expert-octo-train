"""Classic technical indicators, implemented from first principles with pandas.

Every function takes a pandas Series/DataFrame indexed by date and returns a
Series/DataFrame aligned to the same index. OHLCV frames use lowercase columns:
open, high, low, close, volume.

Formulas follow the original authors where one exists (Wilder for RSI/ATR/ADX,
Appel for MACD, Lane for Stochastics, Bollinger, Granville for OBV, Chaikin,
Hosoda for Ichimoku). See knowledge/ for the theory behind each one.
"""
from __future__ import annotations

import numpy as np
import pandas as pd


# --------------------------------------------------------------------------- #
# Smoothing primitives
# --------------------------------------------------------------------------- #
def sma(s: pd.Series, n: int) -> pd.Series:
    return s.rolling(n, min_periods=n).mean()


def ema(s: pd.Series, n: int) -> pd.Series:
    return s.ewm(span=n, adjust=False, min_periods=n).mean()


def wilder(s: pd.Series, n: int) -> pd.Series:
    """Wilder's smoothing (RMA): EMA with alpha = 1/n."""
    return s.ewm(alpha=1.0 / n, adjust=False, min_periods=n).mean()


def wma(s: pd.Series, n: int) -> pd.Series:
    w = np.arange(1, n + 1, dtype=float)
    return s.rolling(n, min_periods=n).apply(lambda x: np.dot(x, w) / w.sum(), raw=True)


def true_range(df: pd.DataFrame) -> pd.Series:
    prev_close = df["close"].shift(1)
    tr = pd.concat(
        [df["high"] - df["low"], (df["high"] - prev_close).abs(), (df["low"] - prev_close).abs()],
        axis=1,
    ).max(axis=1)
    return tr


# --------------------------------------------------------------------------- #
# Trend
# --------------------------------------------------------------------------- #
def macd(close: pd.Series, fast: int = 12, slow: int = 26, signal: int = 9) -> pd.DataFrame:
    line = ema(close, fast) - ema(close, slow)
    sig = line.ewm(span=signal, adjust=False, min_periods=signal).mean()
    return pd.DataFrame({"macd": line, "signal": sig, "hist": line - sig})


def adx(df: pd.DataFrame, n: int = 14) -> pd.DataFrame:
    up = df["high"].diff()
    down = -df["low"].diff()
    plus_dm = pd.Series(np.where((up > down) & (up > 0), up, 0.0), index=df.index)
    minus_dm = pd.Series(np.where((down > up) & (down > 0), down, 0.0), index=df.index)
    atr_ = wilder(true_range(df), n)
    plus_di = 100 * wilder(plus_dm, n) / atr_
    minus_di = 100 * wilder(minus_dm, n) / atr_
    dx = 100 * (plus_di - minus_di).abs() / (plus_di + minus_di).replace(0, np.nan)
    return pd.DataFrame({"plus_di": plus_di, "minus_di": minus_di, "adx": wilder(dx, n)})


def aroon(df: pd.DataFrame, n: int = 25) -> pd.DataFrame:
    up = df["high"].rolling(n + 1).apply(lambda x: 100 * np.argmax(x) / n, raw=True)
    dn = df["low"].rolling(n + 1).apply(lambda x: 100 * np.argmin(x) / n, raw=True)
    return pd.DataFrame({"aroon_up": up, "aroon_down": dn, "aroon_osc": up - dn})


def parabolic_sar(df: pd.DataFrame, step: float = 0.02, max_step: float = 0.2) -> pd.Series:
    high, low = df["high"].values, df["low"].values
    n = len(df)
    sar = np.full(n, np.nan)
    if n < 2:
        return pd.Series(sar, index=df.index)
    bull = high[1] + low[1] >= high[0] + low[0]
    af = step
    ep = high[0] if bull else low[0]
    sar[0] = low[0] if bull else high[0]
    for i in range(1, n):
        prev = sar[i - 1]
        cur = prev + af * (ep - prev)
        if bull:
            cur = min(cur, low[i - 1], low[i - 2] if i >= 2 else low[i - 1])
            if low[i] < cur:  # flip to bear
                bull, cur, ep, af = False, ep, low[i], step
            elif high[i] > ep:
                ep, af = high[i], min(af + step, max_step)
        else:
            cur = max(cur, high[i - 1], high[i - 2] if i >= 2 else high[i - 1])
            if high[i] > cur:  # flip to bull
                bull, cur, ep, af = True, ep, high[i], step
            elif low[i] < ep:
                ep, af = low[i], min(af + step, max_step)
        sar[i] = cur
    return pd.Series(sar, index=df.index)


def supertrend(df: pd.DataFrame, n: int = 10, mult: float = 3.0) -> pd.DataFrame:
    atr_ = wilder(true_range(df), n)
    hl2 = (df["high"] + df["low"]) / 2
    upper = (hl2 + mult * atr_).values
    lower = (hl2 - mult * atr_).values
    close = df["close"].values
    fu, fl = upper.copy(), lower.copy()
    direction = np.ones(len(df))
    st = np.full(len(df), np.nan)
    for i in range(1, len(df)):
        if np.isnan(upper[i]) or np.isnan(fu[i - 1]):
            continue
        fu[i] = upper[i] if (upper[i] < fu[i - 1] or close[i - 1] > fu[i - 1]) else fu[i - 1]
        fl[i] = lower[i] if (lower[i] > fl[i - 1] or close[i - 1] < fl[i - 1]) else fl[i - 1]
        if direction[i - 1] == 1:
            direction[i] = -1 if close[i] < fl[i] else 1
        else:
            direction[i] = 1 if close[i] > fu[i] else -1
        st[i] = fl[i] if direction[i] == 1 else fu[i]
    return pd.DataFrame({"supertrend": st, "direction": direction}, index=df.index)


def ichimoku(df: pd.DataFrame, t: int = 9, k: int = 26, s: int = 52) -> pd.DataFrame:
    mid = lambda n: (df["high"].rolling(n).max() + df["low"].rolling(n).min()) / 2  # noqa: E731
    tenkan, kijun = mid(t), mid(k)
    # Spans are plotted k periods ahead; here we store the value that applies TODAY
    # (i.e. computed k periods ago) so comparisons with today's price are valid.
    span_a = ((tenkan + kijun) / 2).shift(k)
    span_b = mid(s).shift(k)
    return pd.DataFrame(
        {
            "tenkan": tenkan,
            "kijun": kijun,
            "span_a": span_a,
            "span_b": span_b,
            "span_a_future": (tenkan + kijun) / 2,  # cloud being projected k bars ahead
            "span_b_future": mid(s),
            "chikou_ref": df["close"].shift(k),  # price chikou is compared against
        }
    )


# --------------------------------------------------------------------------- #
# Momentum / oscillators
# --------------------------------------------------------------------------- #
def rsi(close: pd.Series, n: int = 14) -> pd.Series:
    d = close.diff()
    gain = wilder(d.clip(lower=0), n)
    loss = wilder(-d.clip(upper=0), n)
    rs = gain / loss.replace(0, np.nan)
    out = 100 - 100 / (1 + rs)
    return out.where(loss != 0, 100.0).where(gain.notna())


def stochastic(df: pd.DataFrame, k: int = 14, d: int = 3, smooth: int = 3) -> pd.DataFrame:
    ll = df["low"].rolling(k).min()
    hh = df["high"].rolling(k).max()
    raw = 100 * (df["close"] - ll) / (hh - ll).replace(0, np.nan)
    k_line = raw.rolling(smooth).mean()  # "slow" %K
    return pd.DataFrame({"k": k_line, "d": k_line.rolling(d).mean()})


def williams_r(df: pd.DataFrame, n: int = 14) -> pd.Series:
    hh = df["high"].rolling(n).max()
    ll = df["low"].rolling(n).min()
    return -100 * (hh - df["close"]) / (hh - ll).replace(0, np.nan)


def cci(df: pd.DataFrame, n: int = 20) -> pd.Series:
    tp = (df["high"] + df["low"] + df["close"]) / 3
    ma = tp.rolling(n).mean()
    md = tp.rolling(n).apply(lambda x: np.mean(np.abs(x - x.mean())), raw=True)
    return (tp - ma) / (0.015 * md.replace(0, np.nan))


def roc(close: pd.Series, n: int = 12) -> pd.Series:
    return 100 * (close / close.shift(n) - 1)


# --------------------------------------------------------------------------- #
# Volatility
# --------------------------------------------------------------------------- #
def atr(df: pd.DataFrame, n: int = 14) -> pd.Series:
    return wilder(true_range(df), n)


def bollinger(close: pd.Series, n: int = 20, k: float = 2.0) -> pd.DataFrame:
    mid = sma(close, n)
    sd = close.rolling(n).std(ddof=0)
    upper, lower = mid + k * sd, mid - k * sd
    return pd.DataFrame(
        {
            "mid": mid,
            "upper": upper,
            "lower": lower,
            "pct_b": (close - lower) / (upper - lower).replace(0, np.nan),
            "bandwidth": (upper - lower) / mid,
        }
    )


def keltner(df: pd.DataFrame, n: int = 20, mult: float = 1.5) -> pd.DataFrame:
    mid = ema(df["close"], n)
    a = atr(df, n)
    return pd.DataFrame({"mid": mid, "upper": mid + mult * a, "lower": mid - mult * a})


def donchian(df: pd.DataFrame, n: int = 20) -> pd.DataFrame:
    # Channel of the PRIOR n bars so "close above upper" is a genuine breakout.
    up = df["high"].shift(1).rolling(n).max()
    lo = df["low"].shift(1).rolling(n).min()
    return pd.DataFrame({"upper": up, "lower": lo, "mid": (up + lo) / 2})


def hist_volatility(close: pd.Series, n: int = 20, periods: int = 252) -> pd.Series:
    return np.log(close / close.shift(1)).rolling(n).std() * np.sqrt(periods)


# --------------------------------------------------------------------------- #
# Volume
# --------------------------------------------------------------------------- #
def obv(df: pd.DataFrame) -> pd.Series:
    direction = np.sign(df["close"].diff()).fillna(0)
    return (direction * df["volume"]).cumsum()


def ad_line(df: pd.DataFrame) -> pd.Series:
    rng = (df["high"] - df["low"]).replace(0, np.nan)
    mfm = ((df["close"] - df["low"]) - (df["high"] - df["close"])) / rng
    return (mfm.fillna(0) * df["volume"]).cumsum()


def cmf(df: pd.DataFrame, n: int = 20) -> pd.Series:
    rng = (df["high"] - df["low"]).replace(0, np.nan)
    mfm = (((df["close"] - df["low"]) - (df["high"] - df["close"])) / rng).fillna(0)
    return (mfm * df["volume"]).rolling(n).sum() / df["volume"].rolling(n).sum().replace(0, np.nan)


def mfi(df: pd.DataFrame, n: int = 14) -> pd.Series:
    tp = (df["high"] + df["low"] + df["close"]) / 3
    flow = tp * df["volume"]
    pos = flow.where(tp > tp.shift(1), 0.0).rolling(n).sum()
    neg = flow.where(tp < tp.shift(1), 0.0).rolling(n).sum()
    ratio = pos / neg.replace(0, np.nan)
    return (100 - 100 / (1 + ratio)).where(neg != 0, 100.0).where(pos.notna())


def anchored_vwap(df: pd.DataFrame, anchor_idx: int) -> pd.Series:
    """VWAP anchored at a bar position (e.g. a major swing low or an earnings gap)."""
    tp = (df["high"] + df["low"] + df["close"]) / 3
    pv = (tp * df["volume"]).iloc[anchor_idx:].cumsum()
    v = df["volume"].iloc[anchor_idx:].cumsum().replace(0, np.nan)
    return (pv / v).reindex(df.index)


def volume_profile(df: pd.DataFrame, bins: int = 24, lookback: int = 250) -> pd.DataFrame:
    """Approximate volume-at-price using each bar's typical price."""
    d = df.tail(lookback)
    tp = (d["high"] + d["low"] + d["close"]) / 3
    edges = np.linspace(d["low"].min(), d["high"].max(), bins + 1)
    idx = np.clip(np.digitize(tp, edges) - 1, 0, bins - 1)
    vol = np.bincount(idx, weights=d["volume"].values, minlength=bins)
    return pd.DataFrame({"price_lo": edges[:-1], "price_hi": edges[1:], "volume": vol})


# --------------------------------------------------------------------------- #
# Relative strength (vs a benchmark) — NOT the same thing as RSI
# --------------------------------------------------------------------------- #
def relative_strength(close: pd.Series, bench: pd.Series) -> pd.Series:
    b = bench.reindex(close.index).ffill()
    return close / b
