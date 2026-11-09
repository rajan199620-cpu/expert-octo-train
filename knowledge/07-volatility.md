# 07 · Volatility

Volatility tells you **how far price normally moves**. That sets stop distance,
position size and target realism. It also has a direction-neutral rhythm:
**contraction precedes expansion**.

## ATR — Average True Range (Wilder)

```
TR  = max(High − Low, |High − PrevClose|, |Low − PrevClose|)
ATR = Wilder-avg(TR, 14)
```
- Expressed in price units. ATR% = ATR / Close is comparable across stocks.
- **Uses:** stop placement (1.5–3× ATR beyond structure), position sizing (risk ÷ ATR-based stop), trailing stops (Chandelier exit: highest high − 3×ATR), and judging extension (distance from an MA measured in ATRs).

## Bollinger Bands (John Bollinger)

```
Middle = SMA20;  Upper/Lower = Middle ± 2σ(20)
%B = (Close − Lower) / (Upper − Lower);  Bandwidth = (Upper − Lower) / Middle
```
- **The squeeze:** bandwidth at a multi-month low means low volatility, which tends to be followed by expansion. It does not tell you the direction.
- **Walking the band:** in strong trends price hugs the upper (or lower) band. A tag of the band is **not** a sell signal by itself.
- **W-bottoms / M-tops:** a second low that holds *inside* the lower band (higher %B) after a first low outside it suggests a bottom.
- Bollinger's own rule: use the bands together with a non-price indicator (volume/momentum), not alone.

## Keltner Channels

EMA20 ± 1.5–2 × ATR. Smoother than Bollinger.
**TTM Squeeze (John Carter):** Bollinger Bands *inside* Keltner Channels means a squeeze. The
release, with momentum direction, is the trade.

## Donchian Channels (Richard Donchian)

Highest high / lowest low of the last N bars. A close above the prior 20- or 55-day high was the
**Turtle Traders'** breakout entry. It is a pure trend-following rule with low win
rates (often 30–40%) and large winners.

## Historical vs implied volatility

- **Historical volatility (HV):** annualised standard deviation of log returns (`σ_daily × √252`).
- **Implied volatility (IV):** what option prices expect. When IV is well above HV, the market expects an event (often earnings).
- **Volatility clustering:** high-volatility days cluster (the GARCH idea). After a shock, expect more volatility, not calm.

## Volatility regimes and what they mean

| Regime | Signs | Implication |
|---|---|---|
| Compression | Low bandwidth, low ATR%, inside bars | Breakout pending; size can be larger (tighter stop) |
| Expansion with trend | ATR rising, price walking the bands | Trend-following works |
| Expansion against trend | ATR spike, wide-range down bars | Capitulation or new downtrend; reduce size |
| Extreme high | HV in the top decile of its year | Stops get hit by noise; cut size |

## Engine

Volatility pillar: Bollinger %B and bandwidth percentile, Bollinger-inside-Keltner squeeze,
ATR%, 20-day HV percentile, and 20-day Donchian breakouts/breakdowns. ATR drives the
stop, the size, the pattern tolerances and the zigzag threshold.

## Failure modes

- Fixed-percentage stops that ignore each stock's volatility.
- Shorting a band tag in a trending market.
- Assuming you know the squeeze direction.
