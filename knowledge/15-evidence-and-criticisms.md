# 15 · Evidence & criticisms: what the research actually says

This chapter keeps the rest of the repo honest. Summaries are paraphrased. Read the
originals (see the reading list) before quoting them.

## Evidence that something is there

| Finding | Source | Caveat |
|---|---|---|
| **Time-series momentum / trend-following** works across asset classes over long histories | Moskowitz, Ooi & Pedersen (2012); Hurst, Ooi & Pedersen, *A Century of Evidence on Trend-Following* (2017) | Diversified futures portfolios, not single stocks; long flat periods |
| **Cross-sectional momentum** (3–12 month winners keep winning) | Jegadeesh & Titman (1993); Asness, Moskowitz & Pedersen (2013) | Severe "momentum crashes" (e.g. 2009); needs a portfolio, not one stock |
| **52-week-high effect:** stocks near their 52-week high keep outperforming | George & Hwang (2004) | Anchoring explanation |
| Simple MA and range-breakout rules predicted the DJIA from 1897 to 1986 | Brock, Lakonishok & LeBaron (1992) | Edge largely gone after 1986 (Sullivan, Timmermann & White 1999) |
| Chart patterns detected objectively alter return distributions | Lo, Mamaysky & Wang (2000) | Effects are modest; profitability after costs not shown |
| Survey of 95 modern studies: 56 found TA profitable | Park & Irwin (2007) | Many studies suffer from data-snooping, ex-post rule selection and ignored costs |
| Short-term reversal (1-week to 1-month losers bounce) | Jegadeesh (1990); Lehmann (1990) | High turnover; costs eat most of it |
| Post-earnings-announcement drift | Bernard & Thomas (1989) | Fundamental trigger, with technical persistence |

## Evidence against / reasons for scepticism

- **Data-snooping.** Test enough rules and some will look great by chance. White's Reality Check and Hansen's SPA test correct for this, and many apparent edges don't survive (Sullivan, Timmermann & White 1999; Bajgrowicz & Scaillet 2012).
- **Transaction costs and slippage** destroy most short-horizon rules.
- **Edge decay.** Published rules get arbitraged away (McLean & Pontiff 2016: anomaly returns fall about 30–50% after publication).
- **Survivorship bias.** Back-testing only today's listed stocks ignores the ones that went to zero.
- **Look-ahead bias.** Using data not available at decision time, such as adjusted prices, revised fundamentals, or future-shifted Ichimoku spans.
- **Subjectivity.** Pattern recognition varies between analysts. Humans see patterns in random walks (Roberts 1959 showed random series that "look like" stock charts).
- **Candlesticks:** little standalone predictive power on US equities (Marshall, Young & Rose 2006).
- **Fibonacci / Gann / Elliott:** little rigorous out-of-sample evidence.
- **Professional performance.** Most active managers underperform their benchmarks after fees over long horizons (SPIVA reports, including SPIVA India).

## A defensible position

1. **Trend and momentum effects** have the strongest evidence, mostly at the **portfolio level** and on **months-long horizons**.
2. TA's most valuable, least controversial use is **risk management**: defining invalidation, sizing by volatility, avoiding fights with strong trends.
3. Short-term single-stock signals (candles, oscillator crosses, Fibonacci) should be treated as **low-confidence timing aids**, not edges.
4. Any rule you rely on should be tested **out-of-sample, with costs**, on many instruments. The engine's evidence table is a sanity check on one stock, not proof.

## How the engine respects this

- It reports the base rate next to every signal's forward return, so you can see whether the "signal" beats doing nothing.
- It back-tests a 50/200 trend filter against buy-and-hold on the same stock.
- It gives candles and Fibonacci low weight and roasts any reliance on them.
- The "Roasting this report itself" section prints these caveats on every report.
