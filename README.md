# Technical Analysis Roaster

A self-contained technical-analysis toolkit:

1. **`knowledge/`** — the principles of technical analysis in one place: Dow Theory, trend
   and structure, candlesticks, chart patterns, moving averages, oscillators, volatility,
   volume, trend systems (ADX, Ichimoku and others), Fibonacci/Elliott/Wyckoff/Gann, relative strength,
   multi-timeframe analysis, risk management, psychology, and what the academic evidence says. Start at
   [`knowledge/00-index.md`](knowledge/00-index.md).
2. **`ta/`** — a Python engine that applies those principles to any stock. It scores six
   pillars, finds levels and patterns, builds a trade plan, checks whether each signal
   ever worked **on that stock's own history**, and then **roasts** everything: the setup,
   your thesis, your existing position, and the report itself.

> Educational tool, not financial advice.

## Quick start

```bash
pip install -r requirements.txt

# US stock vs S&P 500
python -m ta AAPL --benchmark ^GSPC --chart

# Indian stocks vs Nifty 50, with your thesis roasted too
python -m ta RELIANCE.NS TCS.NS --benchmark ^NSEI \
    --thesis "breaking out, will rally to 3500, stop below 2800"

# Roast a position you already hold
python -m ta INFY.NS --benchmark ^NSEI --entry 1520 --side long

# Offline: any CSV with Date,Open,High,Low,Close,Volume
python -m ta data/mystock.csv --benchmark data/nifty.csv
```

Reports are written to `reports/<TICKER>_<date>.md` (plus a `.png` with `--chart`).
Add `--print` to print them to the terminal.

### Options

| Flag | Default | Meaning |
|---|---|---|
| `--benchmark/-b` | none | Index for relative strength (`^NSEI`, `^GSPC`, a CSV path) |
| `--thesis/-t` | none | Your reasoning, which gets roasted |
| `--entry`, `--side` | none, long | Your existing position, which gets roasted |
| `--account` | 100000 | Account size for position sizing |
| `--risk` | 1.0 | % of account risked per trade |
| `--years` | 3 | Years of daily history |
| `--source` | auto | `yahoo`, `stooq`, `csv`, or `auto` |
| `--chart` | off | Save a PNG (price/MAs/Bollinger/levels/volume/RSI/MACD) |

### Ticker formats (Yahoo)

| Market | Example |
|---|---|
| US | `AAPL`, `MSFT`, `^GSPC`, `^NDX` |
| India NSE / BSE | `RELIANCE.NS`, `HDFCBANK.NS`, `TCS.BO`, `^NSEI`, `^NSEBANK`, `^BSESN` |
| UK / Europe | `VOD.L`, `SAP.DE` |
| Crypto / FX | `BTC-USD`, `USDINR=X` |

## What a report contains

1. **Verdict** — PASS / DON'T (yet) / WAIT / CONSIDER LONG / CONSIDER SHORT
2. **Composite score** (−100…+100) and six pillar scores: Trend 30%, Momentum 20%, Volume 15%, Structure 15%, Relative strength 10%, Volatility 10%
3. **🔥 The roast** — data-driven criticisms ranked by severity (🔥 jab, 🔥🔥 real problem, 🔥🔥🔥 deal-breaker)
4. **Trade plan** — entry, structural/ATR stop, target, reward:risk, position size at your risk %
5. **Key levels** — support/resistance with touch counts, 52-week range, Fibonacci
6. **Patterns** — double tops/bottoms, (inverse) H&S, triangles, wedges, candles, gaps, divergences
7. **Every signal by pillar** — nothing hidden; each signal shows its −1…+1 contribution
8. **Evidence check** — forward 20-day returns after golden crosses, RSI<30, MACD crosses, breakouts and so on, **on this stock**, compared with the base rate, plus a 50/200 trend filter vs buy & hold
9. **Roasting this report itself** — the methodology's limitations, printed every time

## Layout

```
knowledge/          principles (00-index.md is the table of contents)
ta/indicators.py    SMA/EMA/WMA, RSI, MACD, Stoch, %R, CCI, ROC, ATR, Bollinger, Keltner,
                    Donchian, HV, ADX/DMI, Aroon, PSAR, Supertrend, Ichimoku, OBV, A/D,
                    CMF, MFI, anchored VWAP, volume profile, relative strength
ta/patterns.py      zigzag pivots, Dow structure, S/R clustering, candlesticks,
                    chart patterns, divergences, Fibonacci, gaps
ta/analysis.py      pillar scoring, trade plan, evidence check
ta/roast.py         the roaster (setup, thesis, position, self-roast) and verdict
ta/report.py        markdown report and chart
ta/data.py          Yahoo / Stooq / CSV loaders with caching
tests/              pytest suite (synthetic data, no network needed)
reports/            generated reports
monitor/            (unrelated) Amazon price watcher
```

## Tests

```bash
python -m pytest -q
```

## Network note

Live data comes from `query1.finance.yahoo.com` (or `stooq.com`). If those are
blocked where you run it, download a CSV from your broker or exchange and pass its path.
