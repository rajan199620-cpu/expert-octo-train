# 17 · How the engine scores (principle → code)

Every signal is a number from **−1 (bearish) to +1 (bullish)**. A pillar's score is the mean
of its signals. The composite is the weighted mean of the pillars × 100.

| Pillar | Weight | Signals (file: `ta/analysis.py`) | Chapter |
|---|---|---|---|
| Trend | 30% | Close vs SMA200, SMA200 slope, 50/200 regime, close vs SMA50, 9/21 EMA, ADX×DI, Supertrend, SAR, Ichimoku, Dow structure, weekly 10/40 MA stage | 02, 05, 09, 12 |
| Momentum | 20% | RSI range, MACD, 63-day ROC, Stochastic, CCI, RSI & MACD-hist divergences | 06 |
| Volume | 15% | OBV trend, CMF, MFI, up/down volume ratio, last-bar RVOL | 08 |
| Structure | 15% | 52-week range position, distance to S/R in ATRs, chart patterns, candlesticks | 02, 03, 04 |
| Relative strength | 10% | 63/126-bar return vs benchmark, RS line vs its 50-MA | 11 |
| Volatility | 10% | Bollinger %B and squeeze, HV percentile, Donchian 20 breakout | 07 |

- No volume data → Volume weight = 0. No benchmark → RS weight = 0. Weights are renormalised.
- **Stance bands:** ≥ +45 strong bullish · +15…+45 bullish · −15…+15 **no edge** · −45…−15 bearish · ≤ −45 strong bearish.
- **Trade plan** only when |composite| ≥ 15 (chapter 13).
- **Verdict** (from `ta/roast.py`): PASS (no edge) → DON'T (yet) (any 🔥🔥🔥 deal-breaker) → WAIT (too many 🔥🔥) → CONSIDER LONG/SHORT.

## Things to argue with

- The weights. Trend gets 30% because trend persistence has the best evidence (chapter 15). You could reasonably push it to 40%.
- RSI scoring by range (Cardwell/Brown) rather than 30/70 fade logic. This is a deliberate trend-following choice.
- Pattern tolerances (1 ATR for "equal" tops, 2 ATR zigzag). Change `mult` in `zigzag_pivots` for more or fewer swings.
- Evidence horizon (20 bars). Change `horizon` in `evidence_check`.
