# Technical Analysis Knowledge Base

A single-place reference for the principles of technical analysis (TA): what each
tool measures, how practitioners use it, where it fails, and how the engine in
`ta/` applies it. Written as original study notes that summarise the standard
literature (see `reading-list.md`). It does not reproduce any book's text.

## How to read this

Read in order the first time. Each chapter ends with **Failure modes**, a list of
how that tool misleads people. The roaster in `ta/roast.py` uses those lists.

| # | File | What it covers |
|---|------|----------------|
| 01 | [Foundations](01-foundations.md) | Premises of TA, Dow Theory, market cycles, efficient-market objections |
| 02 | [Trend & structure](02-trend-and-structure.md) | Swing highs/lows, trendlines, channels, support/resistance, role reversal |
| 03 | [Candlesticks](03-candlesticks.md) | Single, double and triple-bar patterns, and when context matters |
| 04 | [Chart patterns](04-chart-patterns.md) | Reversal and continuation patterns, measured moves, failure rates |
| 05 | [Moving averages](05-moving-averages.md) | SMA/EMA/WMA, crossovers, slope, dynamic support, MA ribbons |
| 06 | [Momentum & oscillators](06-momentum-oscillators.md) | RSI, MACD, Stochastics, CCI, Williams %R, ROC, divergences, ranges |
| 07 | [Volatility](07-volatility.md) | ATR, Bollinger, Keltner, Donchian, squeezes, historical volatility |
| 08 | [Volume & money flow](08-volume.md) | Volume principles, OBV, A/D, CMF, MFI, VWAP, volume profile |
| 09 | [Trend systems](09-trend-systems.md) | ADX/DMI, Parabolic SAR, Supertrend, Aroon, Ichimoku |
| 10 | [Fibonacci, Elliott, Wyckoff, Gann](10-fib-elliott-wyckoff-gann.md) | The more interpretive frameworks, and how to treat them |
| 11 | [Relative strength, breadth, intermarket](11-relative-strength-breadth-intermarket.md) | Stock vs index, sector rotation, breadth, Weinstein stages |
| 12 | [Multi-timeframe analysis](12-multi-timeframe.md) | Elder's triple screen, aligning timeframes |
| 13 | [Risk & position sizing](13-risk-management.md) | Stops, R-multiples, expectancy, sizing, drawdowns |
| 14 | [Psychology & biases](14-psychology-and-biases.md) | The behavioural reasons TA might work, and the biases that wreck traders |
| 15 | [Evidence & criticisms](15-evidence-and-criticisms.md) | What the research says, data-snooping, survivorship, honest caveats |
| 16 | [Analysis checklist](16-checklist.md) | The step-by-step procedure the engine (and you) follow |
| 17 | [How the engine scores](17-engine-scoring.md) | Exact mapping from these principles to `ta/` code and weights |
| — | [Glossary](glossary.md) | Terms A–Z |
| — | [Reading list](reading-list.md) | Primary books and papers, and what each is good for |

## The short version

1. **Trend first.** Identify the trend on the higher timeframe, then trade with it.
2. **Structure defines risk.** Support, resistance and swing points tell you where you are wrong.
3. **Momentum confirms or warns.** It leads price at turns (divergence) and confirms it in trends.
4. **Volume is the lie detector.** Moves on expanding volume are more trustworthy.
5. **Volatility sets the stop distance and the position size.**
6. **Patterns are probabilities, not promises.** Wait for confirmation, and expect failures.
7. **Risk management matters more than entries.** A mediocre entry with good sizing survives. A great entry with bad sizing does not.
8. **Be sceptical of all of it.** The evidence for simple TA rules is weak once you count costs and data-mining (chapter 15).
