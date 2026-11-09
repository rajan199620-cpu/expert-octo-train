"""Price-data loaders.

Sources (pick with --source):
  csv    : any CSV with Date, Open, High, Low, Close, Volume columns (case-insensitive;
           'Adj Close' is used to back-adjust OHLC if present). Works offline.
  yahoo  : Yahoo Finance chart API (no key). Tickers: AAPL, MSFT, RELIANCE.NS,
           TCS.BO, ^NSEI (Nifty 50), ^GSPC (S&P 500), BTC-USD ...
  stooq  : stooq.com daily CSV (no key). Tickers: aapl.us, ^spx ...
  auto   : csv if the argument is a path that exists, else yahoo, then stooq.

Downloads are cached to data/cache/ so re-runs and benchmarks don't re-fetch.
"""
from __future__ import annotations

import io
import os
import time
from pathlib import Path

import pandas as pd

CACHE = Path(__file__).resolve().parent.parent / "data" / "cache"
UA = {"User-Agent": "Mozilla/5.0 (compatible; ta-roaster/1.0)"}


def _normalise(df: pd.DataFrame) -> pd.DataFrame:
    df = df.rename(columns={c: c.strip().lower().replace(" ", "_") for c in df.columns})
    date_col = next((c for c in ("date", "datetime", "timestamp", "time") if c in df.columns), None)
    if date_col:
        df[date_col] = pd.to_datetime(df[date_col], utc=False, errors="coerce")
        df = df.set_index(date_col)
    df.index = pd.to_datetime(df.index)
    if getattr(df.index, "tz", None) is not None:
        df.index = df.index.tz_localize(None)
    need = ["open", "high", "low", "close"]
    missing = [c for c in need if c not in df.columns]
    if missing:
        raise ValueError(f"price data missing columns: {missing}; got {list(df.columns)}")
    if "volume" not in df.columns:
        df["volume"] = 0.0
    if "adj_close" in df.columns:  # back-adjust for splits/dividends
        f = (df["adj_close"] / df["close"]).where(df["close"] > 0)
        for c in ("open", "high", "low"):
            df[c] = df[c] * f
        df["close"] = df["adj_close"]
    df = df[["open", "high", "low", "close", "volume"]].apply(pd.to_numeric, errors="coerce")
    df = df.dropna(subset=["open", "high", "low", "close"]).sort_index()
    df = df[~df.index.duplicated(keep="last")]
    df["volume"] = df["volume"].fillna(0)
    return df


def load_csv(path: str) -> pd.DataFrame:
    return _normalise(pd.read_csv(path))


def _cache_path(source: str, ticker: str, years: int) -> Path:
    safe = "".join(ch if ch.isalnum() or ch in "-_." else "_" for ch in ticker)
    return CACHE / f"{source}_{safe}_{years}y_{time.strftime('%Y%m%d')}.csv"


def load_yahoo(ticker: str, years: int = 3, interval: str = "1d") -> pd.DataFrame:
    import requests

    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{ticker}"
    params = {"range": f"{years}y", "interval": interval, "includeAdjustedClose": "true", "events": "div,splits"}
    r = requests.get(url, params=params, headers=UA, timeout=20)
    r.raise_for_status()
    res = r.json()["chart"]["result"][0]
    q = res["indicators"]["quote"][0]
    df = pd.DataFrame(
        {
            "date": pd.to_datetime(res["timestamp"], unit="s"),
            "open": q["open"],
            "high": q["high"],
            "low": q["low"],
            "close": q["close"],
            "volume": q["volume"],
        }
    )
    adj = res["indicators"].get("adjclose")
    if adj:
        df["adj_close"] = adj[0]["adjclose"]
    df["date"] = df["date"].dt.normalize()
    return _normalise(df)


def load_stooq(ticker: str, years: int = 3) -> pd.DataFrame:
    import requests

    r = requests.get("https://stooq.com/q/d/l/", params={"s": ticker.lower(), "i": "d"}, headers=UA, timeout=20)
    r.raise_for_status()
    if not r.text.lower().startswith("date"):
        raise ValueError(f"stooq returned no data for {ticker!r}")
    df = _normalise(pd.read_csv(io.StringIO(r.text)))
    return df[df.index >= df.index.max() - pd.DateOffset(years=years)]


def load(ticker: str, source: str = "auto", years: int = 3, use_cache: bool = True) -> pd.DataFrame:
    if source == "csv" or (source == "auto" and os.path.exists(ticker)):
        return load_csv(ticker)
    sources = ["yahoo", "stooq"] if source == "auto" else [source]
    errors = []
    for src in sources:
        cp = _cache_path(src, ticker, years)
        if use_cache and cp.exists():
            return load_csv(str(cp))
        try:
            df = load_yahoo(ticker, years) if src == "yahoo" else load_stooq(ticker, years)
            if len(df) < 30:
                raise ValueError(f"only {len(df)} bars returned")
            CACHE.mkdir(parents=True, exist_ok=True)
            df.rename_axis("date").to_csv(cp)
            return df
        except Exception as e:  # noqa: BLE001 — report every source's failure
            errors.append(f"{src}: {e}")
    raise RuntimeError(
        f"Could not load {ticker!r}. " + " | ".join(errors) +
        "\nTip: download a CSV (Date,Open,High,Low,Close,Volume) and pass its path instead."
    )


def resample(df: pd.DataFrame, rule: str) -> pd.DataFrame:
    """Daily -> weekly ('W-FRI') or monthly ('ME') bars for multi-timeframe analysis."""
    agg = {"open": "first", "high": "max", "low": "min", "close": "last", "volume": "sum"}
    return df.resample(rule).agg(agg).dropna(subset=["close"])
