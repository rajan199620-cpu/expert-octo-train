# 04 · Chart patterns

Patterns are recurring shapes formed by swing highs and lows. They matter because
they **define a level** (neckline, trendline) where the balance of supply and demand
visibly changes, and because they give a **measured-move** target. The shape is just
the way of finding that level.

## Universal rules

1. **A pattern is not complete until it breaks out.** An unconfirmed pattern is just a shape.
2. **Confirmation = a close beyond the boundary**, ideally on **above-average volume**.
3. **Measured move:** project the pattern's height from the breakout point. Treat it as a rough first target, not a promise.
4. **Throwbacks and pullbacks:** price often comes back to retest the broken level (Bulkowski reports this in roughly half or more of cases for many patterns). Better entries often come on the retest.
5. **Failed patterns are signals too.** A breakout that quickly reverses back inside the pattern ("bull trap" / "bear trap") often runs hard the other way.
6. **The prior trend matters.** Reversal patterns need a trend to reverse.

## Reversal patterns

| Pattern | Structure | Confirmation | Target |
|---|---|---|---|
| **Head & shoulders top** | Peak (LS), higher peak (head), lower peak (RS); lows form a neckline | Close below the neckline | Neckline − (head − neckline) |
| **Inverse H&S** | Mirror image at a bottom | Close above the neckline | Neckline + (neckline − head) |
| **Double top ("M")** | Two similar highs with a trough between | Close below the trough | Trough − height |
| **Double bottom ("W")** | Two similar lows with a peak between | Close above the peak | Peak + height |
| **Triple top/bottom** | Three tests of a level | Break of the intervening swing | Height projected |
| **Rounding bottom (saucer)** | Gradual U-turn | Break of the rim | Depth projected |
| **V-reversal / spike** | Sharp turn without a base | Hard to confirm in real time | — |
| **Rising wedge** | Converging lines, both sloping up, lows rising faster | Break of the lower line | Start of the wedge |
| **Falling wedge** | Converging lines, both sloping down | Break of the upper line | Start of the wedge |
| **Broadening top** | Diverging highs and lows | Break of the lower line | Often volatile, unreliable |

Volume in an H&S top usually **falls** from the left shoulder to the head to the right
shoulder (buyers running out) and rises on the neckline break.

## Continuation patterns

| Pattern | Structure | Bias | Notes |
|---|---|---|---|
| **Ascending triangle** | Flat top, rising lows | Bullish | Buyers keep paying more; sellers are capped at one price |
| **Descending triangle** | Flat bottom, falling highs | Bearish | Mirror |
| **Symmetrical triangle** | Converging LH and HL | Continuation of the prior trend (≈60/40) | Breakout often 2/3–3/4 of the way to the apex |
| **Flag** | Sharp pole, then a small counter-trend parallel channel | With the pole | Target = pole length from the breakout |
| **Pennant** | Pole, then a small symmetrical triangle | With the pole | Same as a flag |
| **Rectangle** | Horizontal range | Breakout direction | Height projected |
| **Cup & handle** (O'Neil) | Rounded base 7–65 weeks, then a small handle pullback in the upper half | Bullish | Buy the handle-high breakout on volume |
| **Flat base / VCP** (Minervini) | Successively tighter pullbacks with volume drying up | Bullish | Volatility contraction precedes expansion |

## Gaps

| Type | Where | Meaning |
|---|---|---|
| Common | Inside ranges | Usually filled quickly; little meaning |
| Breakaway | Out of a pattern/base on high volume | Start of a move; often **not** filled soon |
| Runaway / measuring | Mid-trend | Trend strength; often near the halfway point |
| Exhaustion | Late in a trend, then a reversal | End of the move; filled quickly |
| Island reversal | Gap, cluster, then an opposite gap | Strong reversal |

## Evidence

- Lo, Mamaysky & Wang (2000), *Foundations of Technical Analysis*, used kernel regression to detect patterns objectively and found that several patterns **do change the distribution of subsequent returns**, though the effect is modest.
- Bulkowski's *Encyclopedia of Chart Patterns* catalogues thousands of patterns. Failure rates are substantial, and many patterns reach their measured target well under 2/3 of the time.
- Savin, Weller & Zvingelis (2007) found H&S had some predictive power for US stocks when combined with other factors.

## Engine

`ta/patterns.py::chart_patterns` detects double tops/bottoms, H&S / inverse H&S
(from the last 5 zigzag pivots, with ATR-based tolerances), and triangles/wedges
(from linear-regression slopes of the last 3 swing highs and lows). Each one
reports whether it is **confirmed** and gives its measured target. Unconfirmed patterns
get half weight and get roasted.

## Failure modes

- Seeing patterns that aren't there (apophenia). Random walks produce H&S patterns.
- Acting before confirmation.
- Ignoring the volume signature.
- Trusting measured targets as if they were promises.
