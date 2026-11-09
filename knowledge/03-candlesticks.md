# 03 · Candlesticks

Japanese candlesticks (popularised in the West by Steve Nison) show open, high, low
and close as a **body** (open→close) and **shadows/wicks** (the extremes). They describe
who controlled the session and whether the extremes were rejected.

## Anatomy

- **Long body:** one side was in control all session.
- **Small body:** indecision.
- **Long upper wick:** buyers pushed up and sellers rejected it.
- **Long lower wick:** sellers pushed down and buyers rejected it.

## The most important rule: context

A reversal candle only means something **after a move it could reverse, at a
level that matters, confirmed by the next bar.** A hammer in the middle of a range is
just a candle.

## Single-bar patterns

| Pattern | Shape | Context | Read |
|---|---|---|---|
| Doji | Open ≈ close | Anywhere; more meaningful after a long trend | Indecision; trend may be tiring |
| Dragonfly / gravestone doji | Doji with long lower / upper wick | At support / resistance | Rejection of lows / highs |
| Hammer | Small body at top, lower wick ≥2× body | After a decline | Bullish reversal candidate |
| Hanging man | Same shape as a hammer | After an advance | Bearish warning (weak alone) |
| Inverted hammer | Small body at bottom, long upper wick | After a decline | Bullish candidate; needs confirmation |
| Shooting star | Same shape as an inverted hammer | After an advance | Bearish reversal candidate |
| Marubozu | Body is almost the whole range | Anywhere | Strong control by one side |
| Spinning top | Small body, both wicks | Anywhere | Indecision |

## Two-bar patterns

| Pattern | Definition | Read |
|---|---|---|
| Bullish engulfing | Up body fully engulfs the prior down body | Bullish after a decline |
| Bearish engulfing | Down body engulfs the prior up body | Bearish after an advance |
| Piercing line | Gap down, closes above the midpoint of the prior down body | Bullish |
| Dark cloud cover | Gap up, closes below the midpoint of the prior up body | Bearish |
| Harami | Small body inside the prior large body | Momentum stalling |
| Tweezer top/bottom | Two bars with matching highs/lows | Level rejection |

## Three-bar patterns

| Pattern | Definition | Read |
|---|---|---|
| Morning star | Big down → small body (gap) → big up closing above the 1st midpoint | Bottom reversal |
| Evening star | Big up → small body → big down | Top reversal |
| Three white soldiers | Three strong up bars, each closing higher | Strong buying; can be overextended |
| Three black crows | Three strong down bars | Strong selling |
| Three inside up/down | Harami followed by a confirming bar | Confirmed reversal |

## Confirmation

Wait for the **next** bar to close beyond the pattern's extreme (for a hammer, close
above its high). This cuts the number of signals sharply, and the false ones most of all.

## Evidence

Studies on US equities (e.g. Marshall, Young & Rose 2006; Horton 2009) generally
find **little or no standalone predictive power** for candlestick patterns on daily
data after costs. Some results appear in specific markets or eras. Treat candles as
**timing and context tools at levels found by other methods**, not as signals by themselves.

## Engine

`ta/patterns.py::candlesticks` checks the last three bars for doji, hammer, shooting star,
engulfing, marubozu, piercing/dark cloud, morning/evening star and three soldiers/crows.
A pattern counts only when the prior 10-bar trend fits it. Candles get **low weight**,
and the roaster mocks any reliance on them.

## Failure modes

- Reading candles without trend or level context.
- Using them on illiquid stocks, where one trade sets the high or low.
- Ignoring gaps caused by news.
