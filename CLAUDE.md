# CLAUDE.md

This repo is a technical-analysis knowledge base (`knowledge/`) plus an engine (`ta/`)
that analyses stocks and roasts the result. See README.md.

## When the user gives you stock tickers

1. Run the engine. Use a sensible benchmark (`^NSEI` for `.NS`/`.BO` tickers, `^GSPC` for US):
   `python -m ta <TICKERS> --benchmark <INDEX> --chart [--thesis "..."] [--entry X --side long]`
   Pass the user's own reasoning as `--thesis` and any existing position as `--entry/--side`.
2. Read each generated report in `reports/` and look at the chart PNG.
3. Reply with **your own** roast on top of the engine's. Be blunt, specific and funny, but
   every jab must be backed by a number from the report or a principle from `knowledge/`.
   Cover: the verdict, the strongest bull point, the strongest bear point, the trade plan
   (stop, target, R:R, size), what would invalidate it, and what the engine can't see
   (earnings dates, news, valuation, sector context). Call out pillar conflicts and any
   signal whose evidence-table edge is negative on that stock.
4. Never present it as a guaranteed call or as financial advice. Roast the idea, not the person.
5. If live data is blocked (proxy 403 for query1.finance.yahoo.com / stooq.com), say so,
   and ask for CSVs or for network access. Never invent prices.

## Dev

- `python -m pytest -q` must pass. Tests use synthetic data only.
- Indicator formulas follow the original authors. Cite the chapter in `knowledge/` when adding one.
- Keep scoring weights in `ta/analysis.py::WEIGHTS` and document changes in `knowledge/17-engine-scoring.md`.
