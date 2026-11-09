# 06 · Momentum & oscillators

Momentum measures the **rate of change** of price. Oscillators bound it to a range,
so you can talk about "overbought" and "oversold". Their two legitimate uses:

1. **Confirming trend strength:** strong trends keep momentum in a characteristic range.
2. **Warning of exhaustion:** divergences and extremes.

They are **not** buy/sell buttons. "Overbought" in a strong uptrend usually means "strong".

## RSI — Relative Strength Index (Wilder, 1978)

```
RS  = Wilder-avg(gains, 14) / Wilder-avg(losses, 14)
RSI = 100 − 100 / (1 + RS)
```
- Classic thresholds: 70 overbought, 30 oversold.
- **Range rules (Andrew Cardwell, Constance Brown):** in uptrends RSI tends to stay between **40 and 80** and finds support at 40–50. In downtrends it stays between **20 and 60** and meets resistance at 50–60. A *range shift* (e.g. RSI failing at 60 after months above 40) is a regime-change clue.
- **Failure swing (Wilder):** RSI makes a high above 70, pulls back, fails to make a new high, then breaks its prior low. This is a signal from the RSI itself, independent of price.
- **50-line:** above 50 means bullish momentum; below means bearish.
- **Positive/negative reversals (Cardwell):** in an uptrend, price makes a higher low while RSI makes a lower low. That is a bullish *continuation* signal.

## MACD — Moving Average Convergence Divergence (Gerald Appel)

```
MACD line = EMA12 − EMA26
Signal    = EMA9(MACD line)
Histogram = MACD − Signal
```
- Above zero means the 12-EMA is above the 26-EMA (uptrend). Zero-line crosses equal the 12/26 EMA cross.
- Signal-line crosses are earlier but noisier signals.
- The **histogram** measures momentum *of* momentum. It turns before the MACD line crosses.
- **Divergence** between MACD/histogram and price is the most watched use.

## Stochastic oscillator (George Lane)

```
%K raw = 100 × (Close − LowestLow14) / (HighestHigh14 − LowestLow14)
Slow %K = SMA3(%K raw);  %D = SMA3(Slow %K)
```
- Measures where the close sits within the recent range. Lane: "momentum changes direction before price."
- >80 / <20 are extremes. %K/%D crosses in the extremes are the classic signal. In trends it can stay pinned at an extreme for weeks.

## Williams %R (Larry Williams)

The same idea as the stochastic, inverted to a 0 to −100 scale. Above −20 is overbought; below −80 is oversold.

## CCI — Commodity Channel Index (Donald Lambert)

```
TP = (H+L+C)/3;  CCI = (TP − SMA20(TP)) / (0.015 × MeanDeviation)
```
- ±100 contains about 70–80% of readings. Moves beyond +100 can mean the start of a strong trend (momentum use) or an extreme (fade use). Know which one you mean.

## ROC / Momentum

`ROC(n) = 100 × (Cₜ / Cₜ₋ₙ − 1)`. Raw momentum. A 3–12 month ROC is the basis of
the academic **cross-sectional momentum** effect (Jegadeesh & Titman 1993): past
3–12 month winners tend to keep outperforming for 3–12 months. This is one of the most
robust anomalies in finance, though it is subject to sharp "momentum crashes".

## Divergence

| Type | Price | Oscillator | Implication |
|---|---|---|---|
| Regular bearish | Higher high | Lower high | Uptrend weakening |
| Regular bullish | Lower low | Higher low | Downtrend weakening |
| Hidden bullish | Higher low | Lower low | Uptrend continuation |
| Hidden bearish | Lower high | Higher high | Downtrend continuation |

**Caveats:** divergences can stack up repeatedly in a strong trend before any turn.
They are **warnings, not triggers**. Act only on a structure break.

## Engine

The momentum pillar scores RSI by range (not just 30/70), MACD (signal, zero and histogram slope),
63-day ROC, the stochastic, CCI, and RSI and MACD-histogram divergences at the last two swing pivots.

## Failure modes

- Selling "overbought" in a strong uptrend.
- Treating every divergence as a top.
- Indicator stacking: RSI, stochastic and Williams %R are almost the same measurement. Agreement between them is not independent confirmation.
- Optimising lookback periods to the past.
