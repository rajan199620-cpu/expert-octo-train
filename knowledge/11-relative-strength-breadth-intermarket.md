# 11 · Relative strength, breadth & intermarket analysis

## Relative strength (RS) — not RSI

`RS line = Stock close / Benchmark close`. A rising RS line means the stock is
outperforming, whatever the market itself is doing.

- **Why it matters:** cross-sectional momentum is one of the most robust findings in empirical finance. Stocks that outperformed over the past 3–12 months tend to keep outperforming over the next 3–12 months (Jegadeesh & Titman 1993; Asness, Moskowitz & Pedersen 2013). The usual approach skips the most recent month, because of short-term reversal.
- **RS new highs before price new highs** is a classic leadership tell (O'Neil, Minervini).
- **IBD-style RS rating:** percentile rank of a weighted 12-month return across the universe.
- **Mansfield RS:** the RS line relative to its own 52-week MA. Above zero means outperforming.
- **Question every long:** if the stock is underperforming the index, why not own the index?

## Sector and group rotation

Roughly 30–50% of a stock's move is often explained by its market and sector. Strong
stocks in strong sectors in a strong market is the best combination. The textbook
business-cycle rotation (Stovall) runs:
- Early recovery: financials, consumer discretionary, tech
- Mid: industrials, materials
- Late: energy, staples
- Recession: utilities, healthcare, staples

Use it as a loose prior, not a rule.

## Market breadth (for index-level context)

| Measure | Meaning |
|---|---|
| Advance/Decline line | Cumulative (advancers − decliners). Divergence from the index = narrowing leadership |
| % of stocks above their 50/200-day MA | Participation; >70% strong, <30% washed out |
| New highs − new lows | Internal strength |
| McClellan Oscillator / Summation Index | Breadth momentum |
| Arms Index (TRIN) | (Adv/Dec) ÷ (AdvVol/DecVol); >2 = panic selling |

An index making new highs on falling breadth is a classic late-stage warning.

## Intermarket analysis (John Murphy)

Typical relationships, which **shift across regimes**:
- Bonds often lead stocks (falling yields can support equities, unless yields are falling because of recession fears).
- A strong dollar is often a headwind for commodities and for emerging markets.
- Commodities and inflation expectations move together, and lead bond yields.
- For Indian markets, watch USD/INR, crude oil, FII flows, and the US 10-year yield.

## Sentiment (contrarian at extremes)

Put/call ratio, VIX / India VIX, AAII surveys, margin debt, fund flows, media
covers. Extremes are contrarian signals. Middling readings carry little information.

## Engine

Relative-strength pillar (needs `--benchmark`): 63- and 126-bar return vs the
benchmark, and the RS line vs its 50-bar MA. The roaster asks "why not the index?" when a
bullish setup is lagging the benchmark. Breadth, intermarket and sentiment need
other data and are left to your judgement. Use the checklist.

Suggested benchmarks: `^NSEI` Nifty 50, `^NSEBANK` Bank Nifty, `^BSESN` Sensex,
`^GSPC` S&P 500, `^NDX` Nasdaq 100, `^RUT` Russell 2000.
