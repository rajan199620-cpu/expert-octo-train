# 13 · Risk management & position sizing

> Your entry decides whether you are right. Your risk management decides whether you survive being wrong.

## The R framework (Van Tharp)

- **1R** = the amount you lose if the stop is hit = (entry − stop) × shares.
- Every outcome is measured in R: +3R is a winner three times your risk.
- **Expectancy** = (Win% × AvgWin_R) − (Loss% × AvgLoss_R). It must be positive after costs.
- **Break-even win rate** for a reward:risk of *b* = 1 / (1 + b).

| Reward:risk | Break-even win rate |
|---|---|
| 1 : 1 | 50% |
| 1.5 : 1 | 40% |
| 2 : 1 | 33% |
| 3 : 1 | 25% |

## Stops

1. **Structural stop:** beyond the level that proves the idea wrong (below the last higher low, the neckline or support), plus a buffer (0.5–1 ATR) for noise.
2. **Volatility stop:** N × ATR from entry (2–3 ATR is typical for swing trades).
3. **Time stop:** if it hasn't worked in N bars, get out. Capital tied up in a stalled trade has an opportunity cost.
4. **Trailing stop:** Chandelier (highest high − 3 ATR), Parabolic SAR, Supertrend, or a rising MA.

**Never** put a stop at an obvious round number or exactly at the level. Everyone's stop
is there. **Never** widen a stop once you are in the trade.

## Position sizing

**Fixed-fractional (the standard):**
```
Shares = (Account × Risk%) / (Entry − Stop)
```
Risk 0.5–2% of the account per trade. At 1% risk, ten straight losses cost about 10%. At
10% risk, they cost about 65%.

**Volatility parity:** size so that each position has the same ATR-based risk.

**Kelly criterion:** f* = W − (1 − W)/R. It maximises long-run growth but is extremely
aggressive, and it is only as good as your estimate of W and R. Practitioners use ¼ to ½ Kelly at most.

## Portfolio rules

- Cap total open risk (e.g. ≤6% of the account at risk across all trades — Elder's 6% rule).
- Correlated positions behave like one big position. Five IT stocks are one IT bet.
- Cap any single position's value (e.g. ≤20–25%) even when the stop is tight.
- Watch for gap risk. Earnings and news can jump straight past your stop.

## Drawdown maths

| Drawdown | Gain needed to recover |
|---|---|
| −10% | +11% |
| −20% | +25% |
| −33% | +50% |
| −50% | +100% |
| −75% | +300% |

## Engine

`trade_plan()` sets the stop below the nearest support minus 0.5 ATR (or above resistance plus 0.5 ATR),
clamped to between 1 and 3 ATR from entry. The target is the nearest opposite level, or a 3R
projection if none exists. Size is fixed-fractional from `--account` and `--risk`. The roaster
flags R:R < 1.5, stops more than 10% away, and position sizes above 100% of the account (implied leverage).

## Failure modes

- Sizing by conviction instead of by stop distance.
- Moving stops further away.
- Averaging down without a plan.
- Ignoring correlation.
- "Mental stops" that turn out to be imaginary.
