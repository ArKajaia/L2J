# Team vs Team: fake player tactics against a party with a healer

How the fake players of a Team vs Team match pick their targets and where their archers and mages stand, how that was measured, and what was found. The code is `ai/FakeTvtTactics` (the knobs), `ai/FakePlayerPvpAI` and `ai/FakePartyBrain` (brain A, which plays them) and `managers/TeamVsTeamBatch` (the measuring). TvT fake players only run brain A, so brain B (`FakePlayerPvpAIExperimental`, see `docs/fake-player-simulation-issues.md`) is untouched; outside Team vs Team every fake player plays `FakeTvtTactics.LEGACY`, which is exactly brain A as it was.

## The knobs

`TvTFakeTactics` in `config/Custom/TeamVsTeam.ini` (default `best`). A preset, optionally changed with `key=value` pairs after a colon (`best:healer=0.3`), or only pairs on top of `legacy`.

| Key | What it does | legacy |
|---|---|---|
| `healer` | How much better an enemy healer is as a target for a melee or buffer (on a score where 1 is full HP; lower is better) | 0.6 |
| `assassin` | The same for a dagger | 1.2 |
| `nukerHealer` | The same for a nuker or archer. One that weighs healers less than `healer` doesn't follow a party call on a healer | 0.6 |
| `nukerDps` | How much better an enemy damage dealer (not a tank, healer or buffer) is for a nuker or archer | 0 |
| `calls` | Whether a member calls its party onto an enemy healer (and onto the healer of a low enemy) | 1 |
| `standoff` | Archers and mages stay back: a target they'd have to walk up to counts the walk twice and the enemies on the way (+0.8 each, as brain B's #35), they don't walk past their party's front (the closest tank or melee) for up to `wait` ms, and a mage between casts holds at the edge of its reach (its attack skills' reach - 50, up to 900) instead of 600 from its target, stepping back when it is well inside that and ahead of its front | 0 |
| `wait` | With `standoff`: how long it waits behind the front before it walks up anyway | 0 (4000 with `standoff`) |
| `lock` | An archer or nuker with an enemy healer within its own reach stuns, silences or paralyzes it with a skill it has ready (Stun Shot, Silence, Voice Bind...; not Archmage, Mystic Muse nor Trickster, which have none), from where it stands, then turns back to its own target | 0 |
| `nukerNuker` | How much better an enemy nuker (a mage that isn't a support) is as a target for a nuker or archer, on top of `nukerDps` | 0 |
| `rush` | A dagger weighs an enemy healer by `assassin` only while the healer is within reach of its gap closer (Shadow Step, 600, for all three dagger classes); farther it is a target like any other | 0 |
| `tankHealer` | How much better an enemy healer is as a target for a tank | 0 |
| `ease` | How much better an enemy close by is the easier its class is to kill in a team fight (`FakeTvtKillEase`, 0 for the toughest class, a tank, 1 for the easiest, a dagger), times this. Close by: within its reach for an archer or mage, within 600 for anyone else | 0 |
| `hunters` | The share of tanks and daggers (each decided once, by object id) that hunt healers at `hunterHealer` | 0 |
| `hunterHealer` | With `hunters`: their healer priority | 1.2 |
| `reach` | An enemy healer is worth its priority, and called for the party, only while one of the team's archers or nukers reaches it (a nuker's own reach; for anyone else, an archer or nuker of its party). Out of that reach it is a target like any other | 0 |

Presets: `legacy`, `focus` (healer ×2), `ignore` (nobody hunts healers, no healer calls, nukers +0.3 on damage dealers), `dps` (nukers and archers on damage dealers), `split` (daggers on healers, nukers and archers on damage dealers), `standoff`, `best` (the default, see the end).

## How it was measured

`//tvt batch [count] [level=] [a=] [b=] [size=] [minutes=] [map=]` (or `POST /api/tvt/batch` with the same names, `GET /api/tvt/batches`) starts `count` simulated matches at once, each in an instance of its own, tactics `a` against tactics `b`:

- Both teams of a match get the same builds at the same levels in the same order (mirrored), only their personalities are drawn apart.
- The tactics change sides every match, and every two matches play the same map.
- While a match fights, the batch counts for each team the damage each of its roles dealt to each role of the other, whom each member targets (once a second), its deaths by role, when its first healer died, and where its archers and mages stand (how far from the closest enemy, and how often closer to it than their party's closest melee: "ahead of the front").
- Output in `log/simulation-tvt/batch-<time>/`: `results.csv` (a line per team per match), `roles.csv` (damage and target time by attacker role and target role), `summary.txt`.
- `python tools/simulation/tvt_report.py <batch folders...>` puts batches together: head to head (wins with a 95% Wilson interval, and the kill share with its 95% interval), healer focus, deaths by role, and each role's damage and target time by target role.

All runs below: 10 matches at once, 30 against 30 (4 parties of tank, archer, healer, melee, nuker, buffer, melee, and a party of tank and archer), 5 minute fights, levels 79-81 unless said otherwise, on Ruins of Despair (the only map of the datapack set up for TvT). One batch of 10 matches is 600 fake players; the game server used about 60% of one core.

"Healer focus" is the share of a team's damage that went into enemy healers. Healers are 4 of 30 (13%) of a team.

## Round 1: healer priority, nukers on damage dealers, standoff (each against legacy)

| Tactics (vs legacy) | Wins | Kill share (95%) | Kills | Healer focus (dmg) | Healer focus (target time) | Own ranged deaths (archer + nuker) | Damage dealt |
|---|---|---|---|---|---|---|---|
| legacy (calibration, vs itself) | 5.5/10 | 49.6% (43-56) | 78 - 80 | 24% | 18% | 27 | 2.6M |
| focus: healer 1.2, dagger 2.4 | 0/10 | 36.0% (31-41) | 56 - 98 | 33% | 26% | 35 | 2.1M |
| healer 2.0, dagger 3.0 | 0/10 | 32.7% (29-37) | 49 - 102 | 36% | 28% | 36 | 1.8M |
| healer 0.3, dagger 0.6 | 6/10 | 55.1% (48-62) | 88 - 70 | 20% | 15% | 23 | 3.0M |
| ignore: healer 0, no healer calls, nukers +0.3 on damage dealers | 10/10 | 63.1% (59-68) | 97 - 56 | 8% | 6% | 17 | 3.4M |
| dps: nukers and archers healer 0, +0.4 on damage dealers | 9/10 | 65.5% (59-72) | 103 - 52 | 16% | 14% | 13 | 3.3M |
| split: dps, and daggers 1.6 on healers | 9/10 | 67.2% (62-72) | 117 - 56 | 15% | 16% | 13 | 3.7M |
| standoff | 7/10 | 53.6% (47-60) | 85 - 73 | 23% | 17% | 23 | 2.6M |

Healer focus and winning go the opposite way: the more a team hunts healers, the more it loses, and the trend is monotonic from 2.0 down to 0.

### Why hunting the healer loses here

- **It gets the hunters killed.** A party's healer stands behind its front (#30), so reaching it means walking past the enemy tanks and melee. With legacy tactics the archers and nukers spend about 30% and 28% of their time on an enemy healer; for the nukers only 18% of their damage lands there (the rest of that time goes in walking and lining up). They die 17.8 + 13.5 times a match. Pointed at the damage dealers instead (`dps`, `split`) they die 6.5 + 6.5 times.
- **It wastes damage.** Teams that ignored healers dealt 60-80% more damage in total than legacy in the same matches (3.3-3.7M against 2.0-2.1M), because what they shoot is within reach.
- **Losing the healer early doesn't decide the fight.** Against `ignore`, legacy's first healer died 77 s into the fight on average and `ignore`'s after 29 s, and `ignore` still won all 10. Healers heal themselves and each other through focus fire; a damage dealer has no such answer, and while the healer is chased the enemy's damage dealers keep hitting.
- **The score counts kills.** Every kill is worth the same, and a damage dealer killed is also damage the team stops taking.

So the best strategy against a party with a healer, for this AI, is to **kill its damage dealers first**, nukers and archers above all, and leave the healer to whoever happens to be next to it.

## Round 2: refining around split

| A | B | B's wins | B's kill share | Kills (B - A) | Notes |
|---|---|---|---|---|---|
| ignore | split | 4/10 | 43.9% | 68 - 86 | Daggers hunting healers costs too, head to head |
| split | split + standoff (wait 4 s) | 4.5/10 | 47.5% | 77 - 85 | Ranged ahead of the front 30% → 23%, 425 → 478 from the closest enemy, but less damage (3.0M → 2.8M) |
| split | split, melee healer 0, no calls | 6/10 | 48.9% | 71 - 74 | Neutral |
| split | split, nukers +0.8 on damage dealers | 4/10 | 44.0% | 68 - 86 | Too much; 0.3-0.4 is enough |
| legacy | split + standoff, **levels 61-65** | 10/10 | 79.0% | 136 - 36 | The gain is bigger at mid levels |

## Round 3: standing back, on top of ignoring healers

| A | B | B's wins | B's kill share | Kills (B - A) | B's ranged: distance to the closest enemy, ahead of the front |
|---|---|---|---|---|---|
| ignore | ignore + standoff, wait 4 s | 4.5/10 | 48.6% | 63 - 67 | 465 (A 425), 24% (A 28%) |
| ignore | ignore + standoff, no wait | 5/10 | 50.2% | 71 - 70 | 478 (A 419), 25% (A 26%) |
| ignore | split (second sample) | 4/10 | 48.5% | 67 - 71 | |

Standing back does what it should (the archers and mages stand 40-60 further from the closest enemy and less often ahead of their front), and against legacy it wins on its own (round 1). On top of good targeting it is neutral in kills: once the nukers shoot the damage dealers in front of them, they rarely need to walk anyway. Waiting for the front costs a little damage, so the default doesn't wait.

`ignore` against `split`, both samples: 12/20 for `ignore`, 157 - 134 kills. Daggers sent onto the healer don't pay either.

## Confirmation, and the default

`best`, first without the dagger priority (`ignore:standoff=1,wait=0`, `best-a0` in `docs/tvt-tactics-data/`), against legacy, one batch per bracket:

| Levels | Wins | Kill share | Kills | Healer focus (best / legacy) | Ranged ahead of the front (best / legacy) |
|---|---|---|---|---|---|
| 79-81 | 10/10 | 73.7% | 114 - 41 | 8% / 29% | 21% / 30% |
| 61-65 | 10/10 | 76.9% | 128 - 38 | 9% / 27% | 24% / 30% |
| 46-50 | 10/10 | 73.2% | 131 - 48 | 9% / 27% | 22% / 29% |
| All 30 | 30/30 (95%: 89-100) | 74.7% (71-78) | 124 - 42 | | |

Deaths by role per team per match over those 30: legacy lost 28.8 archers, 20.3 nukers and 39.2 melee, `best` 6.4, 4.2 and 12.5; legacy's healers died 9.2 times, `best`'s 11.3, and `best`'s first healer died sooner (33 s against 61 s). Its healers die a little more; everyone else lives, and that is what the score counts.

Then a small healer priority for the daggers only (`assassin=0.6`) against that, 30 matches at levels 79-81 and 61-65: the daggers' variant won 68% (95%: 50-82) with a kill share of 52% (46-58), and its own healers died 4.4 times a match against 6.3. A lean rather than a clear win, but it agrees with the first batch and it is the safer choice with real players in the match: a healer nobody ever comes for heals freely. It is the default:

**`best` = `ignore:assassin=0.6,standoff=1,wait=0`** (since round 7 also `ease=2`): nobody calls the party onto a healer; melee and nukers don't prefer healers; daggers prefer them a little (0.6, half the legacy 1.2); nukers and archers prefer the enemy damage dealers (+0.3); archers and mages weigh the walk to a target twice and the enemies on the way, and a mage between casts holds at the edge of its reach.

The shipped preset (`best-a0.6` in the data), read from `TvTFakeTactics = best` on a rebuilt server, against legacy at levels 79-81: **10/10, 115 - 42 kills (73.0%, 95%: 68-78)**, healer focus 10% against 25%, archers and nukers lost 6.0 + 3.8 against 27.9 + 19.6.

## Round 4: focus the healer only while the archers and nukers reach it (2026-11-08)

`reach=1`: the healer priority and healer calls count only while one of the team's archers or nukers has the healer within its reach, so nobody walks through the enemy front for it. Shorter runs: 3 minute fights, two batches of 10 matches at once (20 matches, 1200 fake players), levels 79-81.

| A | B | B's wins | B's kill share (95%) | Kills (B - A) | Healer focus, dmg (A / B) | Damage dealt (A / B) |
|---|---|---|---|---|---|---|
| legacy | legacy + reach | 9/10 | 72.3% (64-80) | 67 - 25 | 30% / 16% | 1.0M / 2.1M |
| best | best + healer focus in reach (`healer=0.6,assassin=1.2,nukerHealer=0.6,calls=1,reach=1`) | 3/10 | 42.6% (33-52) | 37 - 48 | 9% / 16% | 1.8M / 1.4M |

Limiting healer focus to what the shooters reach fixes most of what was wrong with legacy: it halves the healer focus, doubles the damage dealt and wins 9 of 10. But on top of `best`, putting the healer focus back even within reach still does a little worse than leaving healers alone (the interval touches 50%, so it isn't settled). The default stays `best`; `reach=1` is there for a server that wants its fake players to go for healers without running into the enemy for them. Data in `docs/tvt-tactics-data/batch-20261108-*`.

## Round 5: archers and nukers only lock the healer in their own reach, then go back to the enemy nukers (2026-11-08)

`lock=1`: an archer or nuker that has an enemy healer within its own reach stuns, silences or paralyzes it (once it isn't locked already), without walking for it, then goes back to its target. `nukerNuker=0.3`: archers and nukers then prefer the enemy nukers. Each against `best`, 3 minute fights, levels 79-81, two batches of 10 at once.

| B (A = best) | Matches | B's wins | B's kill share (95%) | Kills (B - A) | B's archers on healers (time) |
|---|---|---|---|---|---|
| lock (first try) | 10 | 1/10 | 40.1% (32-48) | 34 - 51 | 17% (A 5%) |
| lock + nukerNuker 0.3 (first try) | 10 | 1.5/10 | 35.4% (27-44) | 31 - 58 | 17% |
| lock (fixed) | 10 | 1/10 | 34.1% (26-42) | 29 - 55 | 15% |
| **lock + nukerNuker 0.3 (fixed), the option asked for** | 20 | 5/20 | 45.4% (38-53) | 40 - 50 | 17% |
| nukerNuker 0.3 alone | 10 | 3/10 | 47.3% (37-58) | 40 - 45 | |

The first try left a locker's target on the healer after the cast, which its party read as focus fire on the healer; the fix turns it back to the enemy it fights once the cast is over. Even so, the lock costs: an archer's Stun Shot or a nuker's silence on the healer is a cast not spent on a damage dealer, the healer is healed or cleansed by its party anyway, and the team lost more archers and nukers (11.5 + 7.7 deaths a match against 8.4 + 6.9). Preferring the enemy nukers is neutral. Neither is in the default; both are there to try (`best:lock=1,nukerNuker=0.3`).

## Round 6: only daggers within Shadow Step range go for the healer, and tanks with them (2026-11-08)

`rush=1` with `assassin=1.2`: a dagger goes for an enemy healer (at the legacy dagger weight) only while the healer is within its Shadow Step range; nobody else does. `tankHealer=0.6`: tanks go for healers too. Each against `best`, 3 minute fights, levels 79-81, two batches of 10 at once.

| B (A = best) | Matches | B's wins | B's kill share (95%) | Kills (B - A) | Healer focus, dmg (B) | B's own healer deaths (A) |
|---|---|---|---|---|---|---|
| daggers in Shadow Step range | 10 | 5/10 | 45.8% (34-58) | 42 - 48 | 8% | 3.5 (3.6) |
| daggers in Shadow Step range + tanks 0.6 | 20 | 9/20 | 51.5% (45-59) | 42 - 40 | 10% | 2.6 (3.6) |
| tanks 0.6 alone | 10 | 6/10 | 52.0% (44-60) | 42 - 38 | 10% | 2.7 (3.6) |

None of them beats `best`: all three are within noise of it (the first 10 matches of the daggers and tanks variant leaned its way, 56%, the next 10 didn't). Pulling the healer focus onto the melee that are already close costs little, unlike sending the archers and nukers after it, but it doesn't win more either. One side effect stands out: with the tanks on the enemy healers, a team's own healers died about a quarter less often (2.6-2.7 against 3.6 a match), likely because the enemy tanks and melee turn on the tanks in their backline. The default stays `best`.

## Round 7: the easiest kill close by; half the tanks and daggers on the healers (2026-11-08)

**How easy each class is to kill.** From the about 12,000 kills logged in the simulated matches of 2026-11-07/08 (mostly `best` teams): each class's deaths per appearance relative to the average fighter (a class is drawn evenly within its role's slots: 5 tanks, 5 archers, 4 healers, 8 melee, 4 nukers and 4 buffers a team of 30):

| Class | Rate | Class | Rate | Class | Rate |
|---|---|---|---|---|---|
| Ghost Hunter | 1.94 | Archmage | 1.28 | Dreadnought | 1.06 |
| Adventurer | 1.94 | Doomcryer | 1.27 | Sagittarius | 1.03 |
| Wind Rider | 1.88 | Grand Khavatari | 1.24 | Fortune Seeker | 1.01 |
| Trickster | 1.52 | Male Soul Hound | 1.24 | Maestro | 0.97 |
| Doombringer | 1.42 | Titan | 1.23 | Duelist | 0.96 |
| Moonlight Sentinel | 1.34 | Flame Warchief | 1.21 | Shillien Saint | 0.79 |
| Ghost Sentinel | 1.32 | Storm Screamer | 1.19 | Sword Muse | 0.77 |
| Doom Oracle | 1.30 | Judicator | 1.18 | Eva's Saint | 0.72 |
| War Dancer | 1.28 | Female Soul Hound | 1.17 | Hierophant | 0.70 |
| | | Soultaker | 1.11 | Cardinal | 0.66 |
| | | Spectral Dancer | 1.09 | Hell Knight | 0.40 |
| | | Mystic Muse | 1.08 | Phoenix Knight | 0.36 |
| | | | | Shillien Templar | 0.35 |
| | | | | Battle Bard | 0.33 |
| | | | | Eva's Templar | 0.29 |

Daggers die twice as often as the average fighter, tanks a third as often, healers about two thirds. These are deaths in matches where `best` already leans onto damage dealers, so they mix how fragile a class is with how often it is targeted. The table is `FakeTvtKillEase`.

`ease=w`: an enemy close by gets `w` times its class's place on that scale (0 for Eva's Templar, 1 for the daggers) off its score. Each against `best`, 3 minute fights, levels 79-81, two batches of 10 at once:

| B (A = best) | Matches | B's wins | B's kill share (95%) | Kills (B - A) | B's damage (A) |
|---|---|---|---|---|---|
| ease 0.6 | 10 | 6/10 | 50.3% (42-59) | 43 - 43 | 1.7M (1.6M) |
| ease 1.2 | 20 | 13/20 | 56.4% (50-63) | 48 - 37 | 1.8M (1.6M) |
| ease 2.0 | 20 | 14/20 | 56.3% (49-64) | 47 - 36 | 1.8M (1.6M) |
| ease 3.0 | 10 | 5/10 | 50.4% (41-60) | 43 - 41 | 1.7M (1.6M) |
| half of the tanks and daggers hunt healers (1.2) | 20 | 10/20 | 49.9% (43-56) | 40 - 41 | 1.5M (1.6M) |

Ease peaks between 1.2 and 2.0: there its teams dealt more damage and lost fewer members in every role (at 2.0, 13.3 melee and 2.3 healer deaths a match against 19.3 and 2.8). At 3.0 the class outweighs how hurt and how close an enemy is, and the gain is gone. Over the 40 matches at 1.2 and 2.0 together ease wins about 56% of the kills. Half of the tanks and daggers on the healers is even with `best`, like the other healer options of rounds 4 to 6.

`best` now has `ease=2`. Against legacy (3 minute fights): **20/20, kill share 82.7% (80-86)**, 80 - 17 kills, at levels 79-81 (74 - 20) and 61-65 (86 - 14).

## Limits

- One map: Ruins of Despair is the only map of the datapack set up for TvT. A map with more cover or narrower lanes may change how much standing back is worth.
- Fake players against fake players. Real players on the other team play differently (a real healer runs, a real party peels); the dagger priority is kept partly for that.
- Both teams get the same builds and levels, but not the same personalities (knowledge, teamplay...), which adds noise; 10 matches decide a large effect (healer focus) but not a small one (standoff on top of good targeting).
- Fights were 5 minutes rather than the event's 20, to run more matches; 20 minute matches weren't measured.
- Rounds 1 and 2 are summaries: their raw CSVs went with a rebuild (a full `ant` build deletes `build/server`). Round 3, the dagger test and the confirmation are in `docs/tvt-tactics-data/` (`results.csv`, `summary.txt` and the tactics of each batch; `python tools/simulation/tvt_report.py docs/tvt-tactics-data/batch-*`).

## Reproducing

```bash
# On a test server with SimulationWebOpenAccess = True (config/Custom/FakeSimulation.ini), or as a GM: //tvt batch 10 level=80 a=legacy b=best size=30 minutes=5
curl -X POST http://127.0.0.1:8788/api/tvt/batch -d 'count=10&level=80&a=legacy&b=best&size=30&minutes=5'
curl -X POST http://127.0.0.1:8788/api/tvt/batch --data-urlencode 'a=legacy' --data-urlencode 'b=legacy:healer=1.2,assassin=2.4,nukerHealer=1.2,name=focus'
python tools/simulation/tvt_report.py build/server/game/log/simulation-tvt/batch-*
```
