# 02 · Trend & market structure

## Defining a trend objectively

- **Uptrend:** higher highs (HH) and higher lows (HL).
- **Downtrend:** lower highs (LH) and lower lows (LL).
- **Range / sideways:** neither. Highs and lows overlap.
- **Contracting (coil):** LH + HL, which is triangle-like.
- **Expanding (broadening):** HH + LL. Volatile, often late-cycle, and hard to trade.

**Swing points.** A swing high is a bar whose high exceeds the highs of N bars on
each side (fractal method), or a peak followed by a reversal greater than X% or
X×ATR (zigzag method). The engine uses a **2×ATR zigzag**, so a swing only counts if
price reversed by more than twice the typical daily range.

**Break of structure (BoS).** In an uptrend, a close below the last higher low is the
first objective sign the trend has ended. Dow's tenet 6 says to assume the trend
continues until that happens.

## Trendlines

- Draw an uptrend line through at least **two swing lows**. A third touch validates it.
- Steeper lines break sooner. A 45° line is "sustainable" folklore; what matters is how many touches it has and how long it has held.
- **The fan principle:** after a third trendline break, the trend usually has reversed.
- Arithmetic vs log scale: use log scale for long-term charts or large % moves, or the lines will be misleading.
- **Failure modes:** subjectivity (people draw different lines), the temptation to redraw after a break, and wicks vs closes.

## Channels

A parallel line through the swing highs (for an uptrend) creates a channel. A failure to reach
the channel top is an early sign of weakness. A break above the top is acceleration, or
exhaustion.

## Support and resistance (S/R)

- **Support:** a price zone where demand has previously overwhelmed supply (prior swing lows, congestion, gaps, round numbers, MAs).
- **Resistance:** a zone where supply has overwhelmed demand.
- **Zones, not lines.** Allow about 0.5–1 ATR of tolerance.
- **Strength of a level** grows with the number of touches, the volume traded there, how recent it is, and the timeframe (weekly beats daily).
- **Role reversal (polarity):** broken resistance often becomes support and vice versa, because traders who missed the breakout buy the retest.
- **Each test weakens a level.** Repeated tests absorb the resting orders. The fourth test of resistance is more likely to break than the second.
- **Round numbers** (100, 500, 1000) attract orders and act as psychological S/R.
- **Engine:** clusters swing-pivot prices within 0.75×ATR, ranks by touch count and reports the nearest levels above and below.

## Pullbacks vs reversals

| Feature | Healthy pullback | Possible reversal |
|---|---|---|
| Depth | Retraces 38–62% of the leg | Beyond 78.6%, or below the prior HL |
| Volume | Contracts on the pullback | Expands on the decline |
| Momentum | RSI holds 40–50 in an uptrend | RSI breaks below 40 |
| Structure | HL holds | Close below the prior HL (BoS) |
| MA | Holds the rising 20/50 MA | Closes below a flattening 50 MA |

## Failure modes

- Calling a trend from too few swings.
- Drawing S/R from wicks on illiquid bars.
- Assuming a level will hold because it held before. Each test weakens it.
- Looking only at the trading timeframe and missing a weekly level just overhead.
