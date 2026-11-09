# 12 · Multi-timeframe analysis

## The principle

Every chart is part of a larger one. A daily "breakout" straight into weekly
resistance is a lower-quality trade than the same breakout in a clear weekly uptrend.

## Elder's Triple Screen (Alexander Elder)

1. **Screen 1, the tide (higher timeframe, e.g. weekly):** find the trend with a trend-following tool (weekly MACD-histogram slope, or a 13/26-week EMA). **Only trade in this direction.**
2. **Screen 2, the wave (trading timeframe, e.g. daily):** use an oscillator to find pullbacks *against* the tide (daily stochastic/RSI oversold in a weekly uptrend).
3. **Screen 3, the ripple (entry):** enter on an intraday breakout of the prior day's high (for longs), or with a buy-stop above it.

The usual timeframe factor is about 4–6× (monthly→weekly→daily, or daily→hourly→15-min).

## Practical alignment table

| Higher TF | Trading TF | Action |
|---|---|---|
| Uptrend | Pullback to support / oversold | **Best long setups** |
| Uptrend | Breakout | Good, but check for extension |
| Uptrend | Downtrend | Wait. It's a correction until the higher TF breaks |
| Range | Anything | Smaller size; trade the range edges |
| Downtrend | Bounce to resistance / overbought | **Best short / exit-long setups** |
| Downtrend | Breakout up | Often a bear-market rally. Be sceptical |

## Engine

The engine resamples daily bars to weekly and scores the 10/40-week MA alignment as a
Weinstein-stage proxy inside the trend pillar (weight 0.9, the heaviest single
trend signal). To go further, run a monthly check by hand, or extend
`analysis.py` with `resample(df, "ME")`.

## Failure modes

- Picking whichever timeframe agrees with the trade you already want (timeframe shopping).
- Running too many timeframes and ending up paralysed.
