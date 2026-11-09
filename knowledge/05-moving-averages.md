# 05 · Moving averages

A moving average (MA) smooths price to show the trend underneath. Every MA **lags**.
That is the price of smoothing.

## Types

| Type | Formula | Character |
|---|---|---|
| **SMA** (simple) | Mean of the last N closes | Equal weights; stable; drops old data abruptly |
| **EMA** (exponential) | EMAₜ = α·Pₜ + (1−α)·EMAₜ₋₁, α = 2/(N+1) | Reacts faster; more whipsaw |
| **WMA** (weighted) | Linear weights N, N−1, … 1 | Between SMA and EMA |
| **Wilder / RMA** | EMA with α = 1/N | Used inside RSI, ATR, ADX |
| **Hull, DEMA, TEMA, KAMA** | Lag-reduction / adaptive variants | Less lag, more complexity, same basic trade-off |

## Standard lengths and their meaning

| Length | Who watches it | Use |
|---|---|---|
| 9/10, 20/21 | Swing traders | Short-term trend; pullback entries in strong trends |
| 50 | Institutions, swing/position traders | Intermediate trend; "first support" in uptrends |
| 100 | — | Intermediate |
| 200 (≈ 40-week) | Everyone | Long-term trend; the bull/bear line for many funds |

## Uses

1. **Trend direction:** price above a *rising* MA = uptrend. **Slope matters more than the crossing.** A flat MA means no trend.
2. **Dynamic support/resistance:** in strong trends, pullbacks often stop near the 20 or 50 MA.
3. **Crossovers:**
   - **Golden cross:** 50-day crosses above 200-day. **Death cross:** below.
   - Fast EMA over slow EMA (e.g. 9/21) for shorter-term signals.
   - Crossovers are **late by design**. Most of the move has often happened by the time they trigger. They work as **regime filters**, not as timing signals.
4. **Distance from the MA (extension):** price far above its MA (e.g. >2–3 ATR above the 50-day) tends to mean-revert or consolidate. Chasing extended moves is a classic error.
5. **MA ribbon:** several MAs fanned out in order means a strong trend. Tangled MAs mean a range.
6. **Price-MA envelope / Keltner / Bollinger** build on MAs (chapter 7).

## Evidence

- Brock, Lakonishok & LeBaron (1992): simple MA and trading-range-breakout rules had predictive power on the DJIA from 1897 to 1986.
- Sullivan, Timmermann & White (1999) repeated the test with a data-snooping correction (White's Reality Check). The best rules still looked significant in-sample, but the edge **mostly disappeared after 1986**.
- Faber (2007), *A Quantitative Approach to Tactical Asset Allocation*: a 10-month SMA filter reduced drawdowns across asset classes, with similar returns to buy-and-hold. It helps more with **risk control** than with raising returns.
- Trend filters generally **lag at turns** and **whipsaw in ranges**.

## Engine

Trend pillar: close vs 50/200 SMA, 200-SMA slope, the 50/200 regime, 9/21 EMA, and
weekly 10/40-week MAs (Weinstein stage proxy). The roaster checks extension as
distance from the 50-day in ATRs. The evidence table back-tests the 50/200 filter
against buy-and-hold **on the stock you gave it**.

## Failure modes

- Whipsaws in sideways markets (check ADX first).
- Optimising MA lengths to the past (curve-fitting).
- Treating a golden cross as a buy signal on its own.
- Ignoring slope.
