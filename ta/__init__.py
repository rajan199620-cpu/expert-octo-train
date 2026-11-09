"""ta — a technical-analysis engine that analyses a stock against classic
principles and then roasts the result (and you)."""
from .analysis import analyze  # noqa: F401
from .data import load  # noqa: F401
from .roast import roast, verdict  # noqa: F401
