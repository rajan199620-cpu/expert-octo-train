# 09 · Trend systems: ADX/DMI, Parabolic SAR, Supertrend, Aroon, Ichimoku

## ADX & the Directional Movement System (Wilder, 1978)

```
+DM = H − prevH if (H − prevH) > (prevL − L) and > 0, else 0
−DM = prevL − L if (prevL − L) > (H − prevH) and > 0, else 0
+DI = 100 × Wilder(+DM)/ATR;  −DI = 100 × Wilder(−DM)/ATR
DX  = 100 × |+DI − −DI| / (+DI + −DI);  ADX = Wilder(DX, 14)
```
- **ADX measures trend strength, not direction.** +DI vs −DI gives the direction.
- **Below 20:** no trend. Trend tools whipsaw, and range/mean-reversion tactics fit better.
- **20–25:** an emerging trend. **Above 25:** trending. **Above 40–50:** very strong, and often late.
- A rising ADX means the trend is strengthening. ADX turning down from a high level means the trend is maturing.
- **This is the first filter to check before trusting any MA or breakout signal.**

## Parabolic SAR (Wilder)

A trailing stop that speeds up toward price as the trend extends (acceleration factor 0.02,
rising by 0.02 at each new extreme, up to 0.20). It flips when price crosses it.
Good for **trailing exits**, poor for entries (it is always in the market, so it whipsaws in ranges).

## Supertrend

Built from ATR bands around (H+L)/2, usually (10, 3). The band ratchets in the
trend's direction and flips on a close through it. Popular in Indian markets. In effect it is a
volatility-based trailing stop with a direction label.

## Aroon (Tushar Chande)

Aroon Up = 100 × (N − bars since the N-period high)/N, and Down is the same for lows. Up > 70 with
Down < 30 means a strong uptrend. A crossover means a possible trend change. It measures how
*recent* the extremes are.

## Ichimoku Kinkō Hyō (Goichi Hosoda, 1969)

| Line | Formula | Role |
|---|---|---|
| Tenkan-sen | (9-high + 9-low)/2 | Fast equilibrium |
| Kijun-sen | (26-high + 26-low)/2 | Slow equilibrium; key S/R and stop |
| Senkou Span A | (Tenkan + Kijun)/2, plotted 26 ahead | Cloud edge |
| Senkou Span B | (52-high + 52-low)/2, plotted 26 ahead | Cloud edge (slower) |
| Chikou Span | Close plotted 26 back | Momentum check vs past price |

**Reading:**
- Price **above the cloud** = bullish, **below** = bearish, **inside** = no-man's land.
- Tenkan above Kijun = bullish momentum. A TK cross above the cloud is the strongest signal.
- A thick cloud is strong S/R. The future cloud's colour (A > B) shows the trend bias.
- Chikou above the price 26 bars ago confirms the trend.
- **Strong buy = all five agree.** That rarely happens, which is part of why it works as a filter.

## Engine

The trend pillar includes ADX/DI (weighted by ADX strength), Supertrend(10,3), Parabolic
SAR position, and Ichimoku cloud position plus TK. The roaster flags **ADX < 20** as
"trend signals are coin flips here". Aroon is computed in `indicators.py` for manual use.

## Failure modes

- Using trend systems in a range (low ADX).
- Treating ADX direction as price direction.
- Ichimoku's fixed 9/26/52 settings came from a six-day trading week. Some people adjust them; most leave them as defaults because everyone else does.
