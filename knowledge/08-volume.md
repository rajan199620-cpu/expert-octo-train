# 08 · Volume & money flow

Volume is the **only** input besides price in most TA. It measures participation, and
by inference conviction.

## Core principles

1. **Volume should expand in the direction of the trend.** In an uptrend, up-days should carry more volume than down-days.
2. **Breakouts need volume.** A breakout on volume of at least 1.5× average is more credible. Low-volume breakouts fail more often.
3. **Pullbacks should be quiet.** Volume drying up on a decline inside an uptrend means sellers are scarce.
4. **Climaxes end moves.** Extreme volume on a wide-range bar after a long move often marks exhaustion (a buying or selling climax, in Wyckoff's terms).
5. **Effort vs result (Wyckoff):** big volume with little price progress means absorption. Someone is taking the other side.
6. **Price leads, volume confirms.** Volume alone rarely predicts direction.

## Indicators

| Indicator | Formula (sketch) | Read |
|---|---|---|
| **OBV** (Granville) | Running total: +vol on up closes, −vol on down closes | OBV making new highs before price = accumulation; divergence = warning |
| **Accumulation/Distribution** (Chaikin) | Σ CLV × vol, CLV = ((C−L)−(H−C))/(H−L) | Weights volume by where the close sits in the range |
| **Chaikin Money Flow** | Σ₂₀(CLV × vol) / Σ₂₀ vol | >0 accumulation, <0 distribution; ±0.05 dead-band |
| **MFI** (Money Flow Index) | RSI calculated on typical-price × volume | "Volume-weighted RSI"; 80/20 extremes |
| **VWAP** | Σ(TP × vol) / Σ vol from an anchor | Institutional benchmark: above VWAP, buyers are in profit |
| **Anchored VWAP** (Brian Shannon) | VWAP from a meaningful event (earnings gap, swing low, IPO) | Shows the average cost of everyone since that event; acts as S/R |
| **Volume profile** | Volume traded at each price | High-volume nodes = acceptance/S/R; low-volume nodes = price travels fast; POC = point of control |
| **Relative volume (RVOL)** | Today's volume / 50-day average | >2 = something is happening (news?) |
| **Up/down volume ratio** | Avg up-day vol / avg down-day vol | >1 = accumulation, as in IBD's measure |

## Liquidity

Average daily turnover (price × volume) decides whether you can actually get in
and out. Thin stocks have unreliable patterns (one trader can paint the chart), wide
spreads, and gaps. Days with zero volume are a red flag.

## Engine

Volume pillar: OBV trend vs price, CMF, MFI, up/down volume ratio over 50 bars, and
last-bar relative volume weighted by the day's direction. If there is no volume (indices, some
feeds), the pillar's weight drops to zero and the roaster complains.

## Failure modes

- Reading volume on instruments where it is incomplete (FX, some indices, fragmented exchanges).
- Forgetting index-rebalance, options-expiry and block-trade days, which inflate volume without meaning anything.
- Reading volume without looking at price.
