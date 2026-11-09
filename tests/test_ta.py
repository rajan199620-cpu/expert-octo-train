import numpy as np
import pandas as pd
import pytest

from ta import indicators as ind
from ta import patterns as pat
from ta.analysis import analyze
from ta.data import load_csv
from ta.roast import roast, roast_thesis, verdict
from ta.__main__ import main


def synth(n=600, drift=0.0008, vol=0.015, seed=1, start=100.0):
    rng = np.random.default_rng(seed)
    r = rng.normal(drift, vol, n)
    close = start * np.exp(np.cumsum(r))
    open_ = np.r_[close[0], close[:-1]] * (1 + rng.normal(0, 0.002, n))
    high = np.maximum(open_, close) * (1 + np.abs(rng.normal(0, 0.006, n)))
    low = np.minimum(open_, close) * (1 - np.abs(rng.normal(0, 0.006, n)))
    vol_ = rng.integers(500_000, 2_000_000, n).astype(float)
    idx = pd.bdate_range("2022-01-03", periods=n)
    return pd.DataFrame({"open": open_, "high": high, "low": low, "close": close, "volume": vol_}, index=idx)


def test_sma_ema_basic():
    s = pd.Series(np.arange(1, 11, dtype=float))
    assert ind.sma(s, 3).iloc[-1] == pytest.approx(9.0)
    assert np.isnan(ind.sma(s, 3).iloc[1])
    assert ind.ema(s, 3).iloc[-1] < 10 and ind.ema(s, 3).iloc[-1] > 8


def test_rsi_bounds_and_extremes():
    up = pd.Series(np.arange(1, 60, dtype=float))
    assert ind.rsi(up).iloc[-1] == pytest.approx(100.0)
    down = pd.Series(np.arange(60, 1, -1, dtype=float))
    assert ind.rsi(down).iloc[-1] == pytest.approx(0.0)
    r = ind.rsi(synth()["close"]).dropna()
    assert r.between(0, 100).all()


def test_atr_bollinger_stoch_ranges():
    df = synth()
    assert (ind.atr(df).dropna() > 0).all()
    bb = ind.bollinger(df["close"]).dropna()
    assert (bb["upper"] >= bb["lower"]).all()
    st = ind.stochastic(df).dropna()
    assert st["k"].between(0, 100).all()
    assert ind.mfi(df).dropna().between(0, 100).all()
    assert ind.cmf(df).dropna().between(-1, 1).all()


def test_adx_and_supertrend_on_trend():
    df = synth(drift=0.004, vol=0.004)
    a = ind.adx(df).iloc[-1]
    assert a["plus_di"] > a["minus_di"]
    assert ind.supertrend(df)["direction"].iloc[-1] == 1


def test_zigzag_alternates():
    df = synth()
    piv = pat.zigzag_pivots(df, ind.atr(df))
    assert len(piv) > 5
    assert all(a.kind != b.kind for a, b in zip(piv, piv[1:]))


def test_double_top_detection():
    # Build an explicit H-L-H with equal tops, then break the neckline.
    path = np.r_[np.linspace(100, 130, 40), np.linspace(130, 110, 25), np.linspace(110, 130, 25), np.linspace(130, 100, 30)]
    idx = pd.bdate_range("2023-01-02", periods=len(path))
    df = pd.DataFrame({"open": path, "high": path + 0.5, "low": path - 0.5, "close": path, "volume": 1e6}, index=idx)
    atr = ind.atr(df)
    piv = pat.zigzag_pivots(df, atr, mult=2.0)
    found = pat.chart_patterns(piv[:-1] + [piv[-1]], df, float(atr.iloc[-1]))
    names = [f.name for f in found]
    # last pivot may be the L after the second top; check over pivots up to second top
    found2 = pat.chart_patterns([p for p in piv if p.price > 105], df, float(atr.iloc[-1]))
    assert "Double top" in names + [f.name for f in found2]


def test_full_pipeline_bull_vs_bear():
    bull = analyze("BULL", synth(drift=0.003, vol=0.006, seed=3))
    bear = analyze("BEAR", synth(drift=-0.003, vol=0.006, seed=3))
    assert bull.composite > 15 and bull.plan["side"] == "long"
    assert bear.composite < -15 and bear.plan["side"] == "short"
    assert bull.plan["stop"] < bull.plan["entry"] < bull.plan["target"]
    assert bear.plan["stop"] > bear.plan["entry"] > bear.plan["target"]
    for a in (bull, bear):
        burns = roast(a)
        v, _ = verdict(a, burns)
        assert isinstance(v, str) and burns


def test_thesis_roast_catches_nonsense():
    a = analyze("BEAR", synth(drift=-0.003, vol=0.006, seed=3))
    burns = roast_thesis("guaranteed to the moon, going all in", a)
    text = " ".join(b.text for b in burns)
    assert "guaranteed" in text and "all in" in text and "no exit" in text
    assert any(b.severity == 3 and "bullish" in b.text for b in burns)


def test_csv_roundtrip_and_cli(tmp_path):
    df = synth()
    p = tmp_path / "XYZ.csv"
    out = df.rename(columns=str.capitalize).rename_axis("Date")
    out["Adj Close"] = out["Close"]
    out.to_csv(p)
    loaded = load_csv(str(p))
    assert len(loaded) == len(df)
    bp = tmp_path / "BENCH.csv"
    synth(seed=9).rename(columns=str.capitalize).rename_axis("Date").to_csv(bp)
    rc = main([str(p), "--benchmark", str(bp), "--out", str(tmp_path), "--thesis", "breakout, buy it"])
    assert rc == 0
    reports = list(tmp_path.glob("XYZ_*.md"))
    assert reports and "The roast" in reports[0].read_text()
