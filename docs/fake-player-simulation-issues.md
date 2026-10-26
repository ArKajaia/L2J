# Fake player simulations: findings and A/B testing

Issues found by running party-vs-party simulations (`//Simulate`, `//SimulationWeb`), and how AI changes are tested before they replace the current AI.

## Report (2026-10-25)

**Where it stands.** 30 rounds of random teams at levels 30-80 found the issues below: healers died first in 29 of 30 rounds, ranged pressure was never answered, the backline was under-focused, and a few plain bugs. Six fixes (#1, #4, #5, #6, #7, #16) were tested as brain B against the unchanged AI in mirrored A/B rounds with the brains swapping sides, and B won **37 of 57 rounds (65%, 95% interval 52-76%)**: 14/17 at level 40, 14/20 at 61, 9/20 at 80. They are now promoted into `FakePlayerPvpAI` (brain A), and brain B is a fresh copy for the next experiment. A second round of fixes (#8, #10, #20, #21, #23, #25, #27, #28, #29) won **41 of 60 rounds (68%, 95% interval 56-79%)**: 10/20 at level 40, 16/20 at 61, 15/20 at 80, with the healer dying first in 9 rounds against 38. They are promoted too (2026-10-26).

**What the tests don't settle.**
- Level 80 shows no gain (9/20): B healers died earlier there, see #27 and #28.
- Each level's A/B series had one tank class only (#26, now fixed), so the per-level results are partly results for Eva's Templar (40, 61) and Shillien Templar (80).
- The healer still dies first in most rounds with either brain: earlier self-heals don't outlast level 70-80 burst on their own.

**Harness fixes done along the way:** monitor crash (#2), no start during boot (#3), standstill push (#18), tied timeouts are draws (#19), rout ends a round (#22), varied A/B compositions (#26), plus `SimulationWebOpenAccess` for testing, A/B mode (`brains=`, `swap`), swapped spawn points and `tools/simulation/ab_test.py`.

**Memory.** The fake player template leak fixed in PR #190 (Spawn equality) stays fixed: templates, spawns and profiles stay level over 40 rounds, and live heap data was flat at ~2.8 GB with siege, PvP spots and quests off. Running from IntelliJ in debug mode adds the debugger's async stack capture (~85k captured stacks) and ignores `java.cfg` (no ZGC, `-Xmx` set as program arguments): use Run and copy the `java.cfg` flags into the VM options. The leak seen with everything on is most likely in one of the systems that were off; turn them back on one at a time with `jcmd <pid> GC.class_histogram` every 10 minutes to find it.

**Suggested next fixes, in order** (each as brain B, then an A/B run of at least ~60 rounds across levels before promoting):
1. **Confirm with preparation.** The last A/B run skipped the 10 s preparation in 37 of 60 rounds (#24, fixed since): rerun A (promoted) against itself to check that it is ~50% and that preparation now runs with no player around.
2. **Level 40.** The second round of fixes didn't win there (10/20): compare the level 40 rounds of both brains (the classes at level 40 have fewer skills, the arrows hit for less than skills there).
3. **Anomalies the promoted fixes raised.** In the last run B had more `IDLE_IN_FIGHT` (35 against 22), `TARGET_FLIP` (31 against 14) and `HEALER_IGNORED` (17 against 4): look at what the members were doing then (likely the healer holding instead of following, #23, and the pressure bonus switching targets, #4).
4. **Logs:** a stunned or silenced member keeps showing its last decision (a stun read as "CASTING" for 7 s): note "DISABLED" in the positions while it can't act.
5. **#11, balance.** Only after the AI fixes: compare Mystic Muse, Flame Warchief and Soultaker damage with retail H5 before touching skill data.

---

## 1. How changes are tested: brain A and brain B

Every simulated fake player runs one of two brains:

| Brain | AI | Party | Role |
|---|---|---|---|
| **A** | `ai/FakePlayerPvpAI` | `FakePlayerParty` | Baseline. Also what every roaming fake player outside simulations uses. Frozen while an experiment runs. |
| **B** | `ai/FakePlayerPvpAIExperimental` | `FakePlayerPartyExperimental` | Experimental. Fixes from this document go here first. |

Rules:

- A fix goes into the B classes only. `FakePlayerPvpAIExperimental` starts as a copy of `FakePlayerPvpAI` (only the class name differs), and `FakePlayerPartyExperimental` starts as an empty subclass of `FakePlayerParty`.
- When B needs a change in a shared helper (`FakePlayerPvpManager`, `FakePlayerPvpOraclesWarchiefs`, `FakePartyManager`...), copy the helper into the B class, or gate the change with `profile.getBrain() == FakeBrain.B`. Never change A's behavior during an experiment.
- A change is **promoted** once B beats A: copy `FakePlayerPvpAIExperimental` over `FakePlayerPvpAI` (renaming the class back), move the party overrides into `FakePlayerParty`, then start the next experiment from a fresh copy.
- Harness changes (logging, the monitor, the simulation manager) affect both teams equally, so they don't need a brain.
- Promoted so far: #1, #4, #5, #6, #7, #16 on 2026-10-25 (B won 37 of 57 rounds).
- Promoted on 2026-10-26: #8, #10, #20, #21, #23 (softened), #25, #27, #28, #29 (B won 41 of 60 rounds); #9 was tried and reverted. Brain B is a fresh copy again.

### Running an experiment

`//Simulate 5 rounds=10 brains=A:B swap` (or the web page: Brains "A vs B", "Swap sides" ticked):

- `brains=A:B` gives team 1 brain A and team 2 brain B (one letter per team, the last one repeats).
- `swap` mirrors the builds (both teams get the same characters), rotates the brains between the points every round (so each brain plays each side equally), and draws a new composition every 2 rounds (round 1 and 2 share one, 3 and 4 the next...). Only the brain differs between the teams.
- The series summary reports **wins by brain** with a 95% confidence interval and **wins by side**.

`tools/simulation/ab_test.py` runs a whole set of brackets through the web API and prints the comparison (see `tools/simulation/README.md`).

Before trusting a result:

1. **A vs A** (`brains=A:A swap`): both brains win about 50%. The "wins by side" line then measures how much the terrain favors a point.
2. **A vs an unchanged B**: about 50%, which shows the B plumbing adds no bias.
3. Then the fix goes into B, and B has to win clearly more than 50% (the confidence interval above 50%) across the level brackets without new anomalies.

---

## 2. The run behind these findings

Run on 2026-10-25 through the web API with `SimulationWebOpenAccess`: 5v5 (tank, archer, healer, melee, nuker), 5 rounds per level, random builds (not mirrored), the two saved points near (134218, 40197) and (130964, 42160). Logs: `build/server/game/log/simulation/`.

| Level | Series | Result (team 1 - team 2) | Avg fight | Decided at* | Healer death after fight start | Top damage build (per round) |
|---|---|---|---|---|---|---|
| 30 | `20261025-133126-393834156` | 0 - 5 | 96 s | 68 s | 29 s | Doom Oracle 7k |
| 40 | `20261025-134117-264484967` | 1 - 4 | 50 s | 31 s | 18 s | Mystic Muse 19k |
| 50 | `20261025-134717-472655668` | 1 - 4 | 55 s | 43 s | 25 s | Mystic Muse 24k |
| 60 | `20261025-135338-2116685979` | 2 - 3 | 103 s | 53 s | 20 s | Soultaker 35k |
| 70 | `20261025-140358-1806319487` | 1 - 4 | 64 s | 49 s | 26 s | Mystic Muse 43k |
| 80 | `20261025-141108-1501731730` | 0 - 5 | 91 s | 52 s | 25 s | Flame Warchief 75k |

\* When one team was down to its last member. The difference to the fight length is mostly a lone tank under Ultimate Defense: the tank was its team's last survivor in 14 of the first 17 rounds, alone for up to 61 s.

Overall: a healer was the first death in 29 of 30 rounds, 56 of 60 healers died, and 142 `PRESSURE_UNANSWERED` anomalies were logged.

### Why team 2 won 25 of 30

Not the terrain. Point 1 (team 1) is 224 higher than point 2, and team 1 had the better side: its ranged members lost line of sight 2.8% of the time against 7.4% for team 2, and spent 5.8% of their time repositioning against 10.9%. Builds are drawn uniformly for every team (`SimulationComposer.pick`). Without mirroring, a series replays one random composition 5 times, so 25 of 30 rounds is really 6 compositions, and team 2 drew the stronger nuker most of the time (Mystic Muse 3 times, Soultaker, Flame Warchief, against Soul Hounds and a Storm Screamer). Mirrored builds with swapped sides remove both effects; the A vs A run measures what is left.

**Side test, level 61 (seed 611588825860, brain A, random builds):** 4 rounds from the saved points (`20261025-144555-611588825860`), then the same two teams for 4 rounds with the points swapped (`20261025-145716-611588825860`, `swapPoints`). Team 1 (Eva's Templar, Trickster, Shillien Saint, Wind Rider, Archmage) against team 2 (Hell Knight, Trickster, Cardinal, Doombringer, Storm Screamer):

| Half | Team 1 spawns at | Result (team 1 - team 2) |
|---|---|---|
| Saved points | (134218, 40197, -3496), the high point | 2 - 2 (one by timeout) |
| Swapped | (130964, 42160, -3720), the low point | 2 - 2 |

No sign of a side advantage: each team won as often from either point. 8 rounds only rule out a large effect (95% interval 22-78%). A healer died first in 7 of 8 rounds.

### First A vs B test (B with #1, #4, #5, #6, #7, #16)

Level 61, `brains=A:B swap`, 8 rounds, base seed 611588825860 (`20261025-151408-611588825860`): 4 compositions, each played from both sides.

| Rounds | Composition (both teams) | Winner |
|---|---|---|
| 1-2 | Hell Knight, Ghost Sentinel, Cardinal, Duelist, Mystic Muse | A, A |
| 3-4 | Hell Knight, Trickster, Cardinal, Wind Rider, Female Soul Hound | B, B |
| 5-6 | Hell Knight, Sagittarius, Shillien Saint, Judicator, Doom Oracle | A, A |
| 7-8 | Hell Knight, Moonlight Sentinel, Shillien Saint, Titan, Male Soul Hound | A, draw* |

A 5, B 2, 1 draw (\*logged as a B win: a timeout with both teams at full health went to the first team, fixed as #19). Far too few rounds to call either way (A 5/8: 95% interval 31-86%).

The fixes do fire: B healers never targeted themselves (A: 3 times), healed themselves 84 times against 52, and defended themselves or took cover 44 times against 24. B healers still died 19-31 s into the fight, about as early as A's: healing itself sooner doesn't outlast the burst on its own.

---

### Second A vs B test: 57 rounds, levels 40, 61, 80

`brains=A:B swap`, 20 rounds per level (level 40 stopped after 17 when the server went down), with the standstill push (#18) and tied timeouts as draws (#19); siege, PvP spots and quests off for levels 61 and 80.

| Level | Series | B wins | 95% interval |
|---|---|---|---|
| 40 | `20261025-174302-952828367` | 14 / 17 (82%) | 59-94% |
| 61 | `20261025-182032-951576081` | 14 / 20 (70%) | 48-85% |
| 80 | `20261025-191346-1944397400` | 9 / 20 (45%) | 26-66% |
| All | | **37 / 57 (65%)** | **52-76%** |

B wins more overall (interval above 50%), clearly at level 40, likely at 61, and not at 80. With B, a healer died first in 13 rounds against 19 for A (levels 61-80), and the team died first in 14 rounds against 26. Anomalies (all three levels): `PRESSURE_UNANSWERED` 89 for B against 132 for A, `SPLIT` 45 against 104; B has more `IDLE_IN_FIGHT` (13 against 5), `STUCK` and `HEALER_IGNORED`. 53 rounds ended in a victory, 4 at the time limit; the standstill push fired 14 times.

**Why B doesn't win at level 80.** B lost rounds 11-12 and 15-17 with its healer dying first and dealing 40-80k damage against A's 101-133k. B healers died 13-26 s into the fight (median ~20 s) against A's 18-38 s (median ~25 s). In round 17 an Archmage and an archer opened on the B Cardinal from ~880, just inside their reach: 97% to 51% in one second, dead 5 s later, without moving. B's #5 rule keeps a healer where it is when only ranged enemies are on it ("running to its tank doesn't take it out of a mage's reach"); A's healer instead spends ~20% of its time in `GUARD_HOLD` with its party and lives a few seconds longer (#27). Separately, B's pressure bonus (#4) also lands on an enemy tank that targets a squishy, cancelling the tank penalty: at level 61 B spent 38% of its target time on the enemy tank against 20% for A (#28).

**Caveat on these tests: one tank per level.** `java.util.Random` gives the same first number for seeds next to each other, and the A/B mode made compositions from seed, seed + 1, seed + 2...: the tank (the first slot drawn) was the same in every composition of a series. Hell Knight (level 61, first test), Eva's Templar (40 and 61), Shillien Templar (80); the other slots vary. Each level's result is also a result for that tank class. Fixed as #26 for the next runs.

### Third A vs B test (B with #8-10, #20, #21, #23, #25, #27, #28), partial

Level 40: B 9/20 (95% 26-66%); level 61 after 15 rounds: B 8/15 (30-75%). No clear gain so far. What the logs show at level 40:
- B's healers heal far less (2,384 per round against 5,889) and die more (17 of 20 against 13). They spend 16% of the time with enemies near following or walking to allies, against 8% for A: with #23 the tank leader no longer holds, the party moves more, and the healer moves instead of casting. They are not closer to the enemies (median 522 against 451).
- B takes 18% more damage from enemy nukers (11,065 per round against 9,412) and starts the fight with one buff less per member (19.5 against 20.5): #9 also dropped the elemental resists A's healers put up during the preparation.
- The archer step (#21) only fired once the trigger was widened to an enemy within 600 (it was 250, where the peel and the kite step take over); the first version never fired.

### Fourth A vs B test: B with #8, #10, #20, #21, #23 (softened), #25, #27, #28, #29 (#9 reverted)

60 rounds, `brains=A:B swap`, the two original points (`--points 20261025-191346-1944397400`).

| Level | Series | B wins | 95% interval |
|---|---|---|---|
| 40 | `20261026-004838-510422132` | 10 / 20 (50%) | 30-70% |
| 61 | `20261026-012550-999225405` | 16 / 20 (80%) | 58-92% |
| 80 | `20261026-020722-1308551653` | 15 / 20 (75%) | 53-89% |
| All | | **41 / 60 (68%)** | **56-79%** |

- A's healer died first in 38 rounds, B's in 9; A's team lost the first member in 45 rounds, B's in 15.
- Archers: B's deal 87-98% of their damage with arrows (A: 45-63%). In won rounds at level 61, B's archers dealt 355 per second alive against 206 for A's.
- Sides: point 1 won 33 of 60, point 2 won 27 (both brains won from both points).
- The new behaviors fired: archer `RELOAD` steps 91 / 51 / 31 (levels 40 / 61 / 80), healer retreats 36 / 24 / 13, tank `ADVANCE` 11 / 7 / 7, healer `HEALER` holds 2 / 6 / 33.
- **Caveat: no preparation in 37 rounds.** The GM logged off at 00:52 (level 40, round 3); from then on no fake player's AI started until the 2 s kick (#24), so both teams skipped the 10 s preparation (buffs, resists, summons) in those rounds. It hit both brains alike, so the comparison holds, but prep-time behavior wasn't tested there. Fixed for the next runs: the simulation starts each fake player's AI right after it spawns.

Heap during levels 61-80 (siege, PvP spots and quests off): live data flat at ~2.82 GB from 18:50 to 19:40; fake player templates, spawns and profiles level; no simulation class grows.

## 3. Issues

Status: **open**, **in B** (being tested), **promoted** (in A), **done** (harness). Line numbers are from when the issue was written.

### Bugs

| # | Issue | Evidence | Cause | Fix | Brain | Status |
|---|---|---|---|---|---|---|
| 1 | Supports root themselves at the start of every fight | 19 casts of Dryad Root with the caster as target (decision `CAST PEEL Dryad Root -> <itself>`, gap -14), right after `SIM_ENGAGE` | `guardBackline` (`FakePlayerPvpAI` ~4870) takes anyone targeting a guarded squishy as a diver. A healer targeting an ally to buff it passes, because `isValidTarget(npc, npc)` is true: `FakePartyManager.isSameGroup` returns false when both are the same creature | `if (target == npc) return false;` at the top of `isValidTarget` | B | promoted |
| 2 | Monitor throws while a member has no decision yet | 230 `monitor_error` NPEs in level 30 round 1; the stuck/idle/split checks were skipped for those ticks | `SimulationMonitor.WAITING` is a `Set.of(...)`, whose `contains(null)` throws | `EnumSet`, or a null check on `getLastReason()` | harness | done |
| 3 | A simulation can start while the server boots | Level 30 round 1 started 10 s before "Server loaded"; every AI stood idle for 21 s | The web server starts during script loading | Refuse to start until the server has finished starting (`GameServer.isStarted()`) | harness | done |

### AI

| # | Issue | Evidence | Cause | Suggested fix | Brain | Status |
|---|---|---|---|---|---|---|
| 4 | Pressure from ranged enemies goes unanswered | 74% of the attackers in `PRESSURE_UNANSWERED` are nukers or archers; healers are the most frequent victims (50 of 130) | Every protective reaction is range-gated: healer self-defense `HEALER_DIVE_RANGE` 200, guard `GUARD_THREAT_RANGE` 450, peel `PEEL_DISTANCE` 300. A Mystic Muse casting from 600-900 never triggers one | Count enemies casting on or shooting at a squishy from up to ~900 as threats; have a ranged teammate switch to that caster | B | promoted |
| 5 | Healers react too late, or with the wrong skill | Eva's Saint at 77% HP in `GUARD_HOLD` while an archer and a mage cast on it; at 6% HP it casts Dryad Root instead of healing. Shillien Saint opens with Vampiric Touch and Wind Shackle at full HP while focused | Self-heal threshold 0.85 inside `defendHealer` (only with a melee diver within 200), party heal threshold 0.75; offense and peel come first | Pre-heal when an enemy starts a cast on the healer; at low HP heal before peeling; no offense while focused | B | promoted |
| 6 | A silenced or rooted healer does nothing | Shillien Saint silenced for 30 s stands in `HEALER_DEFEND` with no skill until it dies (level 60 round 1, 22.9-27.1 s) | `defendHealer` falls through to "by its tank already" and returns; it also returns before `tryPotion` in `thinkAttack`, so a healer that defends itself never drinks a potion | Drink potions, step behind the tank, ask for a cleanse | B | promoted |
| 7 | The backline is under-focused | Share of enemy targeting while the target still has team mates: melee 15.6%, healer 13.3%, tank 7.9%, nuker 7.1%, archer 6.1%. The Mystic Muse team won 4 of 5 at levels 40, 50 and 70; it averaged 24-43k damage and 0.2 deaths per game | `focusScore`: distance counts even inside a ranged attacker's own range; the threat bonus reaches its maximum only after the enemy dealt twice the chooser's own max HP; only daggers get a bonus for squishy targets | Ignore distance inside the attacker's reach; build the threat bonus faster; a small squishy bonus for ranged attackers | B | promoted |
| 8 | Long self-heals are cast in melee and interrupted | Divine Heal (4 s cast) aborted 57% of the time, Elemental Heal (5.2 s) 47% | Tanks heal themselves while being hit | Skip them while a melee enemy hits it, or cast them right after a stun or peel | B | promoted |
| 9 | Prep time goes to minor buffs | Shillien Saint spends the 10 s of preparation on Resist Wind and Resist Holy for each member | `supportParty` buffs whatever is missing, minor buffs included, outside a fight | Skip resist buffs in a simulation's prep (the class buffs are applied anyway) | B | reverted: resists matter (B took 18% more nuker damage without them, third A vs B run) |
| 10 | Debuffs on a target that reflects them | A Shillien Saint rooted itself for 15 s with its own reflected Dryad Root | The AI doesn't remember reflection | Note a reflected debuff and stop debuffing that target for a while | B | promoted |
| 11 | Possible balance outliers | Mystic Muse, Flame Warchief and Soultaker one-shot healers at levels 40-70 | Unknown: AI focus (issue 7) or skill/stat data | Retest after issues 4-7; then compare damage with retail H5 before touching skill data | - | open |
| 16 | Two tanks with bows never finish a fight | Level 61 side test, round 2: the last two tanks (Eva's Templar, Hell Knight) stood 473 apart, shooting and kiting each other, with Divine Heal and Elemental Heal, for 195 s until the time limit | `chooseWeapon` (`FakePlayerPvpAI` ~3514) gives a tank its bow when the enemy stays out of melee reach; with the bow out it is treated as ranged and kites (`KITE_CORNERED`), so neither closes in, and it only puts the bow away once the enemy is close | A tank with its bow out doesn't kite; against a melee or tank that isn't running away it puts the bow away and closes in | B | promoted |
| 18 | Two teams stand still all round | Round 8 of the first A vs B test: both Hell Knights in `GUARD_HOLD` for 290 s on targets 870-1000 away out of sight, fighting each other's Dark Panther; everyone else `IDLE` behind their leader; no one died (both brains) | The tank holds by its backline when its target is in the enemy backline; the others follow the tank and never see an enemy, so the simulation's engage (sight 1200, needs line of sight) never fires | Done in the harness: when no fake player hurt another team's for `SimulationStandstillTime` (20 s; servitors don't count), every fake player is sent at its closest enemy, the same push for both brains (`standstill_push` event). Still open for the AI: a tank's `GUARD_HOLD` could end by itself when nobody threatens its backline | harness / B | done (harness) |
| 20 | Kiting away from one enemy walks into others | Level 61 A vs B, round 9 (`20261025-182032-951576081`, both brains): a Moonlight Sentinel kiting from the diver BlackMaster2 stepped toward Pyra34 (432 -> 260); a Doom Oracle stepped back with 3 enemies within 450 | `kiteStep` (`FakePlayerPvpAI` ~4115; also flight in `runAway`) only moves straight away from its target, then fans out when a wall is in the way; the other enemies around are ignored | Score the candidate steps (the fan of angles) by the distance to all enemies nearby, the closest weighted most, plus line of sight to its target and staying near its party; pick the best instead of the first free one | B | promoted |
| 21 | Archers stand still between shots with a melee on them | Level 40 and 61 A vs B runs (`20261025-174302-952828367`, `20261025-182032-951576081`): with a melee enemy within 200 targeting it, an archer was moving 3-8% of the time and standing doing nothing (no attack, no cast, no move) 15-32%, both brains | For a bow, `isAttackingNow()` lasts the time to hit plus half the reuse (`Creature.java` ~1278); for the rest of the reuse the bow can't fire, but the AI goes back to `AUTO_ATTACK` (`FakePlayerPvpAI` ~1064) and waits. Its kite step (~953) needs `!isAttackingNow()`, a target inside the kite distance, and only steps straight back | Use the window between the arrow leaving and the bow's reuse ending (`isAttackingNow()` false, `isRangeAttackingNow()` true): a short step that keeps the target within bow range, sideways from the melee attacker (combined with #20: away from the other enemies too), so the next shot is ready when the reuse ends | B | promoted |
| 23 | A tank leader that holds freezes its party | Level 61 A vs B, round 19 (`20261025-182032-951576081`): team 1's Eva's Templar (leader) marched, saw team 2 ~2400 away at 30 s, took its Male Soul Hound as target and went `GUARD_HOLD` (106 samples on the nuker); the other 4 stood `IDLE` behind it until team 2 walked in. Team 2's backline was never pressed: the fight happened where team 1 stood | `guardBackline` keeps a tank by its own backline when its target is in the enemy backline; the party follows its leader, so when the leader is a holding tank the whole team stops. Same root as #18 | While nobody dives its backline, a tank leader advances with its party (holding is for when an enemy is on its squishies); or the party follows a damage dealer as leader in a fight; the others engage targets in their reach without waiting for the leader | B | promoted (softened): the leader holds 500 ahead (not 200) while nobody is on the party and its target is over 700 away, so the team moves up together; a healer with enemies within 1200 doesn't follow a leader its heals reach (550). Going straight in left B's healers chasing it (16% of the time against 8%) |
| 25 | A melee drops a target it is already hitting to hold for its tank | Level 80 A vs B, round 1 (`20261025-191346-1944397400`): WildGirl (Titan, brain B) auto-attacked the mage GrimMage80 at gap 17 from 28 to 31 s, then went `HOLD_FOR_TANK`, ran back to its party and stood there 10 s (31-42 s, no target from 34 s) with the mage ~140 away | `holdForTank` (`FakePlayerPvpAI` ~4990) holds whenever the target is more than `MELEE_LEASH` (700) from the middle of the rest of the party, unless the target is on one of the party or the tank is within 400 of it. It doesn't look at whether the melee is already on that target, and the middle of the party sits far back when the backline is far behind | No hold while it is in melee reach of its target (or hit it in the last few seconds): holding is for not charging in, not for leaving a fight; take the tank or leader, not the middle of the party, as the anchor; end a hold after a few seconds | B | promoted |
| 27 | A healer under ranged fire stays inside the enemies' reach | Level 80, round 17 (`20261025-191346-1944397400`): B Cardinal hit from ~880 by an Archmage and an archer, 97% to 51% in 1 s, healed in place and died 5 s later; B healers died earlier than A's at level 80 (median ~20 s against ~25 s) | #5 (now in A) keeps a healer where it is when only ranged enemies are on it, skipping the `GUARD_HOLD` that kept it back with its party | Under ranged burst, step out of the shooters' reach (or behind cover) toward its party, healing on the way; keep the stay-in-place only when nothing it can reach would be out of their range | B | promoted |
| 28 | The pressure bonus pulls fire onto enemy tanks | Level 61: B spent 38% of its target time on the enemy tank against 20% for A | #4 (now in A) gives -0.35 to an enemy targeting a squishy of the party, which cancels the tank penalty (+0.35) when that enemy is a tank (a tank peeling onto the healer) | No pressure bonus (or a small one) for a tank attacker: answer it with peels (stun, root) rather than damage | B | promoted |
| 29 | Archers spend their shots on skills | Third A vs B run, levels 40 and 61: archer skills averaged 480 / 776 per hit against 465 / 804 for arrows, and arrows crit 46-51% at level 61 against 8-28% for skills; Stun Shot was the most cast archer skill (125 and 120 casts) | Archers play combos, debuffs (Stun Shot, Hamstring Shot) and attack skills on a chance before their normal attack; buffed archers deal most of their damage with arrows in any chronicle | In a PvP an archer shoots arrows while its hit chance on the target (the `Formulas.calcHitMiss` formula without the roll) is at least 75%, and uses its skills every time below that (they don't miss); peeling (Stun Shot in the step back, for the healer) unchanged | B | promoted |

### Logging and metrics

| # | Issue | Evidence | Cause | Suggested fix | Brain | Status |
|---|---|---|---|---|---|---|
| 12 | Kills are credited to the wrong skill | "killed with Divine Heal / Elemental Heal / Howl / Rush" | The `death` event uses the killer's last cast | Take the skill from the killing blow's `damage` event | harness | done |
| 13 | Shadow Step always logs as aborted | 5 of 5 aborted, 5 of 5 finished | The teleport calls `abortCast` | Don't log an abort for a cast that already finished | harness | done |
| 14 | `FLEE_ALONE` while kiting | 10 for Sagittarius at level 80, all kiting a dagger standing on it | The rule doesn't look at the pursuer's distance | Don't flag a flight from a melee enemy within ~150 | harness | done |
| 15 | A lone tank inflates the metrics | Tank was the last survivor in 14 of 17 rounds, alone 6-61 s; "targeted" share 15.9% overall, 7.9% while it had team mates | Ultimate Defense and self-heals at the end of a lost round | Report "decided at" (a team down to its last member) next to the fight length, and count targeting only while the target has team mates | harness | done |
| 17 | Weapon swaps aren't logged | The bow duel of issue 16 only showed as melee builds "auto-attacking" from 473 | `chooseWeapon` / `choosePolearm` swap without a trace or event | Log a `weapon_swap` event (and the weapon) | harness | done |
| 19 | A tied timeout counts as a win | Round 8 of the first A vs B test: no one died, both teams at 100%, and team 1 (B) was logged the winner | At the time limit the first team with the most HP and CP left wins, ties included | Teams within 1% of each other end in a draw | harness | done |
| 22 | A team that runs away keeps the round going | A team whose members all fled could only lose by being caught, or the round dragged on to the time limit | Nothing ended a round on a rout | Done: when every living member of a team is fleeing (`FLEE`, `ESCAPE`) and at least `SimulationRoutDistance` (1500) from the closest enemy, the team is routed; once all teams but one are, the round ends with a `rout` event and that team wins (end reason `rout`) | harness | done |
| 24 | The AI sometimes starts 18-31 s late | 4 of 37 rounds of the level 40/61 A vs B runs: no decision at all from one or both teams until 18-31 s (the fight starts at 10 s): level 40 round 1 (30/30 s), round 10 (6/10 s), level 61 round 16 (18 s), round 19 (23/31 s). In round 19 the standstill push fired at 30 s and team 2 only then started. The class matrix (every battle in its own instance, no player around) showed it in most battles: 60% of the fighters idle during the preparation, a tenth of them until the push at 20 s | `Attackable.onSpawn` only starts an AI that already exists, and the AI is otherwise only created and started when a player sees the NPC (`World.addVisibleObject`); `AttackableAI.changeIntention` also turns ACTIVE back into IDLE (and detaches the AI) with no player around. With the GM nearby that hid it | `spawnSimulant` sets the new simulant ACTIVE, and `AttackableAI` keeps a simulant ACTIVE with no player around. After the fix all 180 fighters of a 90-battle matrix thought during the preparation and decided within 0.94 s of the fight start | harness | done |
| 26 | A/B compositions all drew the same tank | Every composition of an A/B series had the same tank (Eva's Templar at 40 and 61, Shillien Templar at 80, Hell Knight in the first test); other slots varied | `compositionSeed` used seed + 1, seed + 2... and `java.util.Random` gives the same first number for seeds next to each other | Done: the seed of each composition after the first is scrambled (SplitMix64) | harness | done |
