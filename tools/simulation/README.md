# Simulation tools

`ab_test.py` compares the two fake player AI brains (A = current, B = experimental) through the simulation web page's API. See `docs/fake-player-simulation-issues.md` for what the brains are and how a change is tested.

Needs Python 3 (standard library only), a running game server, at least 2 saved simulation points (`//SetSimulationPoint 1`, `//SetSimulationPoint 2`), and either `SimulationWebOpenAccess = True` in `config/Custom/FakeSimulation.ini` (test servers only) or a GM token: the `admin=...` part of a `//SimulationWeb` link, passed with `--token`.

```bash
# Calibration: A against A. Both should win about 50%; "By side" shows how much the points favor one team.
python tools/simulation/ab_test.py --brains A:A --swap --levels 40,60,80 --rounds 10

# The test: B against A, every level bracket.
python tools/simulation/ab_test.py --brains A:B --swap --levels 30,40,50,60,70,80 --rounds 10

# Report again on simulations that already ran (folder names from the web page or log/simulation).
python tools/simulation/ab_test.py --report 20261025-133126-393834156 20261025-134117-264484967
```

With `--swap` both teams get the same builds, the brains change sides every round, and a new composition comes every 2 rounds, so only the brain differs. B is better only if its 95% interval stays above 50%. About 100 rounds are needed before a 60% win rate is clearly above 50%.
