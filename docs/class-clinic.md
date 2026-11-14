# The class clinic: making one class's AI stronger against every kind of opponent

A repeatable way to take one fake player build, fight it against every other build, find from the battle logs what its AI does wrong against each kind of opponent (tank, melee, dagger, archer, nuker, healer, buffer), fix it, and prove the fix. One build at a time, or one family of builds that share an AI and most of a kit (the three daggers: `docs/class-clinic/daggers.md`). The first case is the Female Soul Hound: `docs/class-clinic/female-soul-hound.md`. Read it once before doing a class: it shows every step on real data.

It is for the **micro of one class** in a duel: which skill, when, from where. Party tactics (focus, healers, formation) are tested with the arena and the TvT batches instead (`docs/fake-player-simulation-issues.md`, `docs/tvt-tactics.md`).

## What you need

- A game server: `tools/simulation/local_server.sh` (about 5 minutes for a build and start; `--no-build` to restart). No client, no login server.
- `tools/simulation/class_clinic.py` (run, report, timeline, compare) and `tools/simulation/skill_cards.py` (what each skill of a build does). Python 3, standard library only.
- Time: a full run is 1,170 battles and takes about 13 minutes (the healer fights go to the time limit); a run against one kind of opponent takes 3 to 5. A rebuild and restart takes 5.
- Disk: a full run keeps about 300 MB of battle logs in `../build/server/game/log/simulation-matrix/`. Delete old runs you no longer compare against.
- **Commit and push after every proven batch.** Cloud sessions restart; the working tree and the logs usually survive, the server process never does, and a new container has neither. Copy the numbers you need into the case file as you go.

## The loop

### 1. Know the class

- `python3 tools/simulation/skill_cards.py "<Build>"`: every skill of the build with its cast time, reuse, range, power, land rate and effects, its combos, and notes on weak or overlapping skills. Read the effects: they hold what the name doesn't say (Curse of Divinity does +30% damage per buff on the target; Final Form is a melee form; Painkiller ends on any action).
- **Its last section, "LEARNED BUT NOT IN THE BUILD", lists the active skills the class has and the build never uses. Go through every one.** The Soul Hound's build was missing its second blink (Warp), a speed buff, a speed toggle and an area slow that paralyzes (Lightning Shock); adding them was worth 10 points against melee. The report can't show a skill the build doesn't know about.
- How good players play it in retail High Five: search as `CLAUDE.md` says ("Research before changing game content") and write down five to ten lines: what it opens with, what it does against a fighter, an archer, a mage, a healer, and which skills define it. This is the yardstick the logs are held against.
- The build itself: its `<build>` in `dist/game/data/FakePlayerPvp.xml` (skill lists in order of preference, combos, peel, emergency, role, gear kit, buff list).

### 2. Baseline

```bash
python3 tools/simulation/class_clinic.py run "<Build>" --battles 30 --seed 4040
```

It fights the build against the 39 others at level 85, 30 battles each, every battle in its own instance, keeps every log, and writes `clinic.md` into the run's folder (printed at the end). **Keep the folder name: it is the "before" of every comparison.** Always use the same seed, so the same fighters (gear, personality) meet again in later runs.

**Builds that share an AI and a kit** (the daggers, the Soul Hounds, the Templars...): take a baseline of each once, to see where they differ, then diagnose them together. A change to what they share (the role's code, skills all of them have) needs one run of one of them: the others give the same answer. Run each only for the changes to what is its own (its own skills, combos, buffs). In the daggers' case the shared part of a batch moved the Adventurer +2 and the parts of their own kit moved the other two +9 and +13.

### 3. Read the report, then the fights

`python3 tools/simulation/class_clinic.py report <folder> [--group nuker]` prints it again, whole or for one kind of opponent. In this order:

1. **Scorecard**: the win rate per kind of opponent with its 95% interval. The kinds under 50% are where the work is. "By opponent" shows whether a kind is lost to all of its builds or to one.
2. **Findings to check**: what the numbers flag. They are questions, not conclusions.
3. **Its skills**: casts per battle, aborted, landed, damage per cast and per second of casting, and the casts per kind of opponent. Look for: a strong skill cast seldom, a weak one cast often, a debuff that never lands cast every fight, a skill of the kit never cast.
4. **Self skills: the fights they are cast in**: a buff or a transformation that goes with losing is the first thing to read fights for.
5. **What the enemy does to it** and **where its time goes**: what kills it, what holds it, how long an enemy stands on it, how much it moves instead of acting.
6. **Its own build**: the same class with other keystones, gear or player skill. A gap there is a build problem (passive tree, gear), not an AI one: write it down, don't tune the AI around it.
7. **Battles to read**: `python3 tools/simulation/class_clinic.py timeline <battle folder>` prints one fight second by second, with what each side decided and why. **Read at least two losses per weak kind before you believe a finding.** The Soul Hound's worst fault (transforming into a melee form while ahead) was plain in the first timeline and invisible in the win rates.

Traps when reading:

- Fake players don't pay MP for skills. With the Mind Over Matter keystone their MP is a second health bar; "MP empty" is not "can't cast".
- "Between" time (about a quarter of a caster's fight) is the short pause between two actions that every build has (`SKILL_CHAIN_*`). "Waiting" is the one to look at.
- An emergency skill is cast by the side that is losing: its low win rate says little. A buff or transformation with a low win rate says a lot.
- Debuffs learned at 74 land 10 to 30% on a level 85 target in full buffs. That is the server's formula, not a bug; what matters is how often the AI spends a cast on them and whether it walks into danger to do so.
- A 1v1 between fake players can't judge everything: buff steals only work on real players (Steal Divinity does nothing to a fake player), and nobody heals or peels for anyone.
- Stats differ a lot between two fighters of the same build (gear level, enchant, keystones). Read several fights.

### 4. Write the diagnosis

In the case file (`docs/class-clinic/<build>.md`, copy the layout of the Soul Hound's): one row per fault with its **evidence** (numbers from the report, a battle folder), its **cause** (the line of code or data behind it) and its **kind**, which decides where the fix goes:

| Kind | Example | Where the fix goes |
|---|---|---|
| Build data | The attack list ranks a weak skill above a strong one; a combo opens with a silence on a warrior; the peel list holds a skill that peels nothing | The build in `dist/game/data/FakePlayerPvp.xml`. Cheapest and safest: only that build changes. Do these first |
| Class AI | Final Form for a caster; a class skill used at the wrong moment | `FakePlayerPvpAI`, by role or by what the skill does (its effects), never by class name. The same change in `FakePlayerPvpAIExperimental`, so the two brains don't drift apart |
| General AI | The damage estimate misses an effect; the land chance guess is far off; mages don't kite a slower melee | The same files, but it changes other builds too: it needs a guard run (step 6) |
| Harness | A log line that misleads (Blink logged as aborted) | `managers/simulation/`, both sides alike, no test needed |
| Not AI | The class is out-statted (M. Atk 6,000 against 22,000) | Write it in the case file under "not fixed here". Balance changes go through `docs/class-balance-85.md`, not through the clinic |

Check the Java level before writing code (`CLAUDE.md`, "Java versions"): `java/` is Java 25, the scripts under `dist/game/data/scripts/` are Java 8.

### 5. Fix one batch, then prove it

A batch is a few changes that belong together. Rebuild, restart, run again with the same seed, and compare with the baseline:

```bash
tools/simulation/local_server.sh
python3 tools/simulation/class_clinic.py run "<Build>" --battles 30 --seed 4040
python3 tools/simulation/class_clinic.py compare <baseline folder> <new folder>
```

`compare` gives the change per kind of opponent with its 95% interval, per opponent, and how the skill use moved. Rules:

- A kind is **better** only when the interval of its difference is above zero, **worse** when it is below. Anything else is "no clear change", however good the number looks.
- A batch is kept when the overall difference is clearly better and no kind is clearly worse. A batch that changes nothing is taken out again, not left in "because it can't hurt". The one exception: a change whose own measure moved as meant (the skill is cast, the damage taken fell) and no kind clearly worse may stay, with that written in the case file.
- Check in "Skill use" that the change did what you meant (the skill you promoted is cast more, the one you removed is gone). A win rate that moved for another reason is luck.
- How small a change a run can show, at 30 battles per opponent (a 95% interval of about plus or minus this many points):

| Kind | Battles a side | Shows a change of about |
|---|---|---|
| all | 1,170 | 4 points |
| melee (12 builds) | 360 | 7 |
| nuker (8) | 240 | 9 |
| buffer (5) | 150 | 11 |
| tank, archer (4) | 120 | 13 |
| dagger, healer (3) | 90 | 15 |

  For a small kind, run it alone with more battles on both sides: `class_clinic.py run "<Build>" --against archer --battles 80 --seed 4040` (and the same on the baseline code, since a run against one kind is a different set of battles than the full run's).
- While working on one kind, `--against <kind>` gives an answer in a few minutes. The batch still needs the full run before it is kept: a fix against archers can cost against melee.

Then read the new report like the first one and go round again. Stop when the kinds still under 50% are "not AI" (out-statted), or when two batches in a row show no clear change.

### 6. Guard: nobody else got worse

- A change to one build's data, or to code that only that build's skills reach, needs no guard.
- A change to shared code (anything another build can reach: the scorer, kiting, a rule by role) needs one: list the builds it can reach, and run the clinic for two of them before and after (`--battles 15` is enough to see damage). If it reaches most builds, compare two full matrices instead (`tools/simulation/matrix.py --start --battles 10 --seed 4040` on both, then `matrix_compare.py`).
- A change that is clearly general and promising (it would help every caster, every archer...) is better tested the way `docs/fake-player-simulation-issues.md` describes: in brain B only, against brain A, in a mirror matrix. Note it under "General findings" below instead of slipping it in with a class.

### 7. Record

- The case file: baseline, diagnosis table, each batch with its comparison table, what was not fixed and why, and the run folders.
- This document: a line in "Classes done", and anything the next class should know under "Lessons so far" or "General findings".
- `docs/class-balance-85.md` if you found a balance problem.
- Commit (the code, the data and the case file together) and push.

## Where a behaviour comes from in the code

A decision in a timeline (`decides KITE_STEP OUTRUN`, `scores Aura Blast`) is a `trace(Reason..., kind)` call: search `FakePlayerPvpAI.java` for the reason and the kind to find the line that made it. Everything a fake player does in a fight starts in `thinkAttack`, which tries these in order and stops at the first that acts:

1. Reflexes to what just happened (`runReflex`: an enemy cast, a gap closer, its HP crashing; this is where the `emergency` skills are cast in a PvP).
2. Looking after itself: `castCleanse`, a quick heal, its `buff` list (`castOnSelf`), the `transform` skill.
3. The enemy's defensive buffs (`getKnownDefenses`, `holdOff`).
4. Keeping away: `outrun`, an archer's kite (`archerKiteStep`), the pressed block (`jumpAway`, then `pickPeel` over the `peel` list), the kite step (`kiteStep`).
5. Closing in, for melee: the `move` and `rush` lists.
6. Its combos (`playCombo`, `isComboReady`).
7. A skill by value (`useScoredSkill` → `scoredOptions` → `addScoredOptions` with `estimateDamage` and `isWorthCasting`, scored by `ai/fight/SkillScorer`), or by chance for a fight without a fight read (`pickSkill`).
8. Where a mage stands between casts, and the normal attack for everyone else.

The build's lists are read through `profile.getSkills(SkillCategory...)`; the build attributes are in `FakePlayerPvpBuild` and parsed in `FakePlayerPvpData`. `FakePlayerPvpAIExperimental.java` (brain B) has the same methods with the same text in nearly all of these places: make the change in `FakePlayerPvpAI.java`, then make the identical change in the other file, and build. The clinic runs brain A only, so brain B's copy is compiled but not measured.

## What the report's numbers mean

| Number | Meaning |
|---|---|
| Win % (95%) | Wins plus half the draws, over battles, with its Wilson interval |
| Fight s (won / lost) | Median length of the fights it won and lost. Short losses: it is burst down. Long losses: it can't finish |
| HP left when it wins / enemy HP left when it loses | Medians of HP and CP left. A kind lost with the enemy at 20% is close: small gains flip it. One lost with the enemy at 70% needs another plan, not tuning |
| Damage /s dealt / taken | Over the time it was alive in the fight |
| Enemy within 200 | Share of the fight an enemy stood within 200 of it. For an archer or mage, time a melee spent on it |
| Disabled, rooted | Share of the fight it could not act, or not move |
| Waiting | Standing on a hold or wait decision with no cast, attack or move |
| Hits first | Share of the fights in which it dealt the first damage |
| Landed | For a skill with a debuff: landed against resisted |
| Per cast second | Damage divided by the time spent casting the skill: which skills pay |

The kinds of opponent are fixed in `class_clinic.py` (`GROUPS`); a build that is missing there takes its kind from its role.

## Lessons so far

Faults of one class that other classes may share. Check for them first.

- **A transformation or stance that changes how the class fights** (Final Form): does the AI still have its damage afterwards? The report's "Self skills" table shows it at once.
- **The order of the attack list.** A skill is worth at least its place in the list allows (`LIST_RANK_FACTOR`), so a weak skill listed early is cast a lot. Order the list by "Per cast second" from the report, not by habit.
- **Damage the estimate doesn't see.** `estimateDamage` knows power, M. Atk and P. Atk. A skill whose effect multiplies its damage (by the target's buffs, by its HP, by souls) is undervalued until the estimate learns its effect.
- **Power that isn't the skill's.** The energy skills (Duelist, Grand Khavatari) keep their power in the `EnergyDamage` effect: the estimate saw 0 and only their place in the list counted, so Triple Sonic Slash was worth 0.6 of a Triple Slash. Effects can now report their power (`AbstractEffect.getPower()`); an effect that holds its own power needs the same, or a skill card with a power in its effects and none on the skill is the sign.
- **A defence that roots its caster, cast at full HP** (Ultimate Defense from the burst reflex): 30 s it can't move while an archer shoots it, and nothing left for the end. Gate it on time to die, and let it be taken off (`Reflexes.rootsCaster`, `dropImmobileDefense`).
- **A sustain the class has only in a skill chain** (Eva's Will → Touch of Eva): a stacking seed buff in `buff`, its icon right after it. A guide of the class found it; the skill table didn't.
- **Charging that costs a turn.** A build with charges recharged with its bare charge skill (Sonic Focus, 15.6 casts a battle) while it had an attack that charges as it hits (Sonic Rage) in the same cast time. Count the casts of the `charge` list against the fight's length.
- **Waiting inside the enemy's reach.** Ultimate Defense roots a tank, not its spells: a melee holding off at 300 took Drain Health for 20 s. A wait is only free out of the enemy's reach.
- **Debuffs that don't fit the target.** A silence on a warrior, a root on an archer. Combos take `mageTarget="true"` and `disabledTarget="true"` on a step; `isWastedSilence` covers pure silences (kept for a tank under a defensive stance and anyone casting a spell).
- **Standing just outside a skill's reach.** A mage holds 600 from its target, which leaves it at about 660 to 675: its skills with a reach of 600 are "out of reach" and it falls back on whatever reaches. With the build attribute `range` it keeps the skills that reach from that distance and steps in for them.
- **Skills the class has and the build doesn't list** (see step 1). Escapes and speed skills go in `move` (used when it flees, and by `outrun` and the jump away from a melee), stuns and slows around it in `peel`, toggles in `toggle`. Mind the order of the lists in a build: `xsd/FakePlayerPvp.xsd` fixes it, and a build that breaks it isn't loaded.
- **A build without its best skill or its own buffs.** The War Dancer had no Deadly Strike (power 4,640, three times its Sting) and no `buff` list at all (Defense Motion, Deflect Magic, Dance of Fire): +24 points before any code. A lasting buff in `buff` is on before the fight (`isPreFightBuff`), so it costs no fight time. Start from "LEARNED BUT NOT IN THE BUILD" and the build's combos' first steps.
- **A follow-up skill that only exists in a combo.** If it needs a debuff the first skill puts on (Soul Vortex Extinction), put it in the attack list too: its own condition keeps it off targets without the debuff.

- **Short buffs on a long reuse cast on the way in.** The buff list casts every buff that is off at once: a 10 s Dodge or Counterattack (75 s reuse) ran out as the fight started, cast 860 from the target. The report's skill table doesn't show it; the gap at cast does (see the daggers' case).
- **Reflected damage doesn't show as damage.** Counterattack and Eye for Eye send melee skills back as P. Atk × 700 / P. Def; the report's "what the enemy does to it" has no line for it. Compare the HP it lost with the damage events against it, per opponent: 60% of what the Adventurer lost to a Doombringer was its own.
- **A skill has more than its headline effect.** Bluff lands its stun 8% of the time at 85 and its TargetCancel 76%, which is what opens a Backstab. Read a skill's whole effect list before calling it wasted.
- **Damage that isn't HP damage.** Mana Burn (`MagicalDamageMp`) has a power of 162 and burns only MP: the estimate made it the Doom Oracle's best nuke, cast 7 times a battle for nothing (fake players cast for free). Check "Damage per cast" for a "-" on an attack skill.
- **Delayed effects that a second cast replaces.** A prophecy (or anything that resolves when it runs out) cast again before it comes true throws away what the first one gathered: the Doom Oracle's Unwritten put a new Salvation on it and wiped a pending one holding 9,000 HP of heal. Look for a skill in `emergency` or `buff` that puts on an effect another of its skills already has on.
- **A heal that pays back damage is a buff, not a heal.** Prophecy of Salvation returns all damage taken in 6 s: kept up from the first hit (not below 85% HP) it was worth more than any other change to the class.
- **A fix ported from a sibling build can be half right.** The nuker Soul Hound's changes gave the hybrids +9.5 and +4.9, but its "no Final Form" rule would have hurt them: the hybrids win 63 to 65% of the fights they transform in (the nuker 3%). Read the "Self skills" table of each build before porting a rule about it.
- **Skill power is added to P. Atk.** Triple Thrust (power 3,069) hits for 500 to 650 where a rapier's normal attack crits for 2,000: a skill fighter's best damage may be its normal attack. Compare the damage per hit in a timeline, not the power on the skill card.
- **A keystone that depends on how the class plays needs the AI to play for it.** Far Shot (+30% bow damage from 900) was taken by three archers in four, and they stood exactly where the others did: on average it cost them damage, and Rapid Fire (half the reach) threw it away. `tools/simulation/archer_range.py` measures the distance at each arrow; the same check fits any keystone or skill that pays by distance, position or timing.
- **A step between attacks costs a shot unless it fits in the reload.** The archers' first range steps (up to 250) moved them as meant and lowered their damage per second; limited to what they walk before the bow is ready again, the same idea gained.
- **A debuff that multiplies the class's main damage, hidden behind a rule that skips debuffs.** Real Target (the target takes ×1.4 crossbow damage for 30 s) was cast once in 1,170 battles: arrows first (#29) skips combos and debuffs while the arrows land, and the arrows were 98% of the damage it would have multiplied. Worth +11.8 on its own. Look for a debuff whose effect is the class's own damage (a `DefenceTrait` against its weapon, a vulnerability) in "never cast".
- **A jump's direction is its fly course.** Blink jumps back from where it faces, Warp (`flyCourse` 180) forward; the peel faced the target for both, so Warp took the Trickster through the melee. `faceToJumpAway` handles any course.
- **A buff that defends against nothing the opponent does** (M. Def against a fighter, a defence against bows against a melee) is a cast thrown away; `defendsAgainst` skips it in a fight. The reverse doesn't hold: leaving out a buff that is often interrupted lost more than the aborts cost (Trickster batch 4).
- **Position.** A dagger's blow lands twice as often from behind and hits harder; a target turns at the start of each of its own attacks and casts. Chopping the blow casts by where the dagger stood (target's heading in `positions.jsonl`) found it.

- **A skill that does nothing for a fake player.** Arcane Shield was cast as the wizards' emergency skill and changed nothing: only `PlayerStatus` read its stat, and a fake player is an NPC. The report shows it as a self skill whose fights are lost as often after it as before; a timeline shows the HP falling at the same rate under it. Check a defensive skill's stat is read for an NPC (`NpcStatus`, `CreatureStatus`) before tuning when it is cast.
- **A debuff opener that lands 30% of the time costs the burst.** All three wizards opened every fight with Surrender (−20 to an element) before their 5 minute nuke; against archers, who win in 10 s, that cast was the race. Open with the damage, leave the debuff to the scorer.

Lessons about the process, from the same cases:

- **A batch with no effect may never have run.** The first standing distance changed no win rate; the skill table showed the casts hadn't moved either, because the code that read it was never reached. Look at "Skill use" in `compare` before you judge an idea.
- **Read a fight where the new behaviour happens.** Outrunning a slower melee looked right on paper and was flat against melee: one timeline showed a Titan's Rush every 4 seconds and the Hound running for a minute without a cast.
- **A fix for one kind can cost another.** The first repair of the outrun rule (give up a run after 4.5 s) won back the melee fights and lost the tanks, who never catch it and against whom long runs were the point. Only the full run showed it.
- **A skill already used at its reuse rate can't be helped by promoting it.** The Adventurer's Critical Blow combo changed nothing: it was cast 1.2 times in a 25 s fight with a 30 s reuse. Check the casts against the reuse before an idea.
- **A replacement can be worse than the thing it replaces.** Trick and Switch openers (to make the target drop its target) pushed Bluff out, which does the same thing twice as often. Measure the effect you count on (here: does the target drop its target?) before promoting a skill for it.
- **Put a new behaviour behind a build attribute** when it is a way of playing rather than a correction (`range`, `outrun` in `data/FakePlayerPvp.xml`). The next class can switch it on and test it in its own clinic, and no other build changes meanwhile.

## General findings (not yet done: they change every class)

These came out of a class's clinic but reach beyond it. Each needs brain B and a mirror matrix, or a guard run.

| Finding | Evidence | Idea |
|---|---|---|
| The land chance the scorer assumes is the skill's base rate (80% for Voice Bind), far from what lands (10%) | Soul Hound: Voice Bind 10%, Shadow Bind 10%, Death Mark 22%, Surrender to Dark 24% over thousands of tries | `FightRead.landChance`: start from the server's formula without the roll (level difference, the target's stat and resists), blurred by the fake player's knowledge |
| The list floor (`LIST_RANK_FACTOR`) lifts plain damage skills whose estimate is exact | Abyssal Blaze (power 46) valued like a skill of power 90 | Apply the floor only to skills with effects the estimate can't see (damage over time, curses, prophecies) |
| A mage faster than the melee on it doesn't use its speed | Done for the Soul Hounds behind the build attribute `outrun` (tanks 82% → 94%; against melee, who have gap closers, it took the class's own escapes in the `move` list to gain). Other fast casters and archers stand in the same way | Try `outrun="true"` on the build in its own clinic; an archer needs its own version (it shoots while it moves away) |
| Painkiller-type invincibility that ends on any action is cast and broken by the next cast | Cast in 241 fights, 10% won; the enemy waited 1 to 2 s. Duelist's Sonic Barrier the same (5 charges, broken by a Sonic Focus 0.6 s later in `000407-Duelist-vs-Titan`) | After casting it: move out of reach (melee) or wait while the enemy's burst buffs run, instead of casting |
| Short self buffs on a long reuse are cast on the way in | Done for daggers (Dodge, Counterattack: cast with a fighter in reach, never against a mage). Doombringer's Eye for Eye is cast 1 s before contact; Enlightenment (20 s, 10 min) by every mage at the start | The same rule by abnormal type for every build (COUNTER_SKILL, AVOID_SKILL first), with a guard |
| Ultimate Evasion and the like as an HP-crash reflex against a mage | 0.4 casts a battle against nukers; its evasion does nothing against spells | The reflex leaves an evasion buff aside against a target that fights with magic |
| A mage's reflex STEP_BACK against another mage walks it out of its own reach | Doom Oracle vs Soultaker (`docs/class-clinic/doom-oracle.md`): ENEMY_CAST_AT_ME → STEP_BACK twice, 574 → 1,185, then APPROACH back; a step back doesn't dodge a spell that is already cast | In `Reflexes`, STEP_BACK only against a melee or a target whose skill has a short reach; with a guard |
| The burst reflex casts long-reuse DEFENSIVE skills at full HP | Battle Bard: `REFLEX ENEMY_BURST;Ultimate Defense` at 100% HP (`000838-Doombringer-vs-BattleBard`), then 30 s unable to move; taking it out of the build was worth +7 with batch 2 (time disabled 19% → 10%) | In `Reflexes` (`ENEMY_BURST`), the time-to-die check `HP_CRASH` already has, or leave out skills that immobilise; guard run on the knights |
| A fake Kamael in Final Form keeps its weapon | `FakePlayerPvpManager.transform` applies the stats (P. Atk, P. Def ×2) and swaps the skill lists; the NPC keeps its weapon, so a Trickster shoots on from 850 at double P. Atk (bolt damage a second doubled after the transform, `docs/class-clinic/trickster.md`). A player in Final Form fights with the form's base attack (SWORD, range 20) | A balance decision (`docs/class-balance-85.md`): give the fake player the form's attack type and reach, or keep it and say so. It reaches the Trickster, the hybrid Soul Hounds and the Judicator |
| A fake player's skills used their base power, not their PvP power | **Done** (2026-11-14): `Formulas.isPvp` / `isPve` count roaming fake players and their servitors as a player's side (power, PvP stats, PvP/PvE effect scopes, the AI's estimate). Archmage 85.4% → 77.4%; tanks gained most against it (PvP defence stats now count) | A full matrix before and after is still to do (`matrix-20261114-045903`, stopped at 639 battles): Final Secret's +30% PvP damage and the PvP armour sets now work for fake players |
| Buff steals do nothing against fake players | `StealAbnormal` only works on a player: Steal Divinity is never worth a cast in a simulation | Let it work on fake players, then value it by what it would take |

## Classes done

| Build | Case file | Before | After | Date |
|---|---|---|---|---|
| Female Soul Hound | `docs/class-clinic/female-soul-hound.md` | 50.6% | 70.1% (+19.5, 95% +15.6 to +23.4): tank 98, melee 80, dagger 94, archer 57, nuker 40, healer 51, buffer 80 | 2026-11-12 |
| Duelist (Gladiator line) | `docs/class-clinic/duelist.md` | 48.7% | 69.2% (+20.6, 95% +16.7 to +24.5): tank 71, melee 52, dagger 67, archer 91, nuker 60, healer 100, buffer 89 | 2026-11-12 |
| Grand Khavatari (Tyrant line) | `docs/class-clinic/grand-khavatari.md` | 71.1% (60.3% before the Duelist's shared fixes) | 72.7% (+1.6, 95% -2.0 to +5.3, no clear change): tank 65, melee 59, dagger 86, archer 76, nuker 70, healer 99, buffer 89 | 2026-11-13 |
| Flame Warchief (Warcryer line) | `docs/class-clinic/flame-warchief.md` | 35.5% | 41.7% (+6.2, 95% +2.3 to +10.2): tank 94, melee 25, dagger 32, archer 23, nuker 23, healer 95, buffer 59 | 2026-11-13 |
| Doom Oracle (Prophet line) | `docs/class-clinic/doom-oracle.md` | 59.4% | 73.2% (+13.9, 95% +10.1 to +17.7): tank 96, melee 68, dagger 88, archer 48, nuker 63, healer 83, buffer 91. The shared prophecy rules took the Battle Oracle from 28.5% to 41.2% (guard run) | 2026-11-13 |
| Female and Male Soul Hound Hybrid | `docs/class-clinic/soul-hound-hybrid.md` | 53.4% / 55.9% | 63.6% / 63.9% (+10.2, 95% +6.2 to +14.2 / +8.0, +4.1 to +12.0): the nuker Soul Hound's changes ported (+9.5 / +4.9), then the rapier when a melee has caught it (`caughtMain`: daggers +10 / +18, overall no clear change). Female: tank 65, melee 46, dagger 79, archer 73, nuker 50, healer 100, buffer 89 | 2026-11-13 |
| War Dancer (Bladedancer line) | `docs/class-clinic/war-dancer.md` | 35.3% | 71.7% (+36.4; batch 1 +23.7, 95% +19.7 to +27.6, batch 2 +12.7, +8.9 to +16.5): tank 62, melee 61, dagger 91, archer 94, nuker 51, healer 100, buffer 93. Build data only: Deadly Strike, its own buffs and dances, no 10%-landing debuffs or 3 s spells | 2026-11-13 |
| Battle Bard (Swordsinger line) | `docs/class-clinic/battle-bard.md` | 35.2% | 66.5% (+31.3; batch 1 +24.4, 95% +20.4 to +28.3, batch 2 +7.0, +3.1 to +10.9): tank 51, melee 63, dagger 94, archer 73, nuker 46, healer 99, buffer 86. Build data only: Deadly Strike, its own buffs and songs, no 10%-landing debuffs, 5 s heal, Power Strike or Ultimate Defense. Anthem of Valor in PvP tried, not kept | 2026-11-13 |
| Ghost Sentinel and Moonlight Sentinel (archers) | `docs/class-clinic/archers.md` | 55.3% / 48.7% | 59.7% / 55.8% (+4.4, 95% +0.4 to +8.4 / +7.1, +3.1 to +11.2): an archer plays for its range keystone (Far Shot steps back to 900+ in the bow's reuse, no Rapid Fire against a ranged target) and opens on a melee walking in with Hamstring Shot. Point Blank in the archers' keystone list tried, clearly worse (−7.2), not kept. Ghost Sentinel: tank 66, melee 43, dagger 12, archer 52, nuker 66, healer 100, buffer 93 | 2026-11-13 |
| Trickster (Arbalester line) | `docs/class-clinic/trickster.md` | 54.4% | 70.1% (+15.7, 95% +11.8 to +19.6): tank 68, melee 61, dagger 37, archer 36, nuker 89, healer 99, buffer 85. Real Target kept on the target (×1.4 on every bolt, never cast before: arrows first skipped it), Warp jumps away (it jumped through the melee) and before Soul Shock, no buff that defends against nothing the opponent does. Sharpshooting and "no magic buff under fire" tried, not kept | 2026-11-13 |
| Archmage, Mystic Muse, Storm Screamer (the wizards) | `docs/class-clinic/wizards.md` | 63.2% / 54.1% / 56.2% | 85.4% / 81.8% / 90.9% (+22.2 / +27.7 / +34.7): Aura Blast (none of the three had it), an opening burst instead of a 30% Surrender, no Curse Fear, Ice Bolt or heals, and Arcane Shield at the start of the fight, which did nothing for a fake player until `NpcStatus` applied it. Archmage: tank 90, melee 92, dagger 96, archer 83, nuker 72, healer 63, buffer 96. Now the strongest builds of the clinic: a balance question | 2026-11-14 |
| Phoenix Knight, Hell Knight, Eva's Templar, Shillien Templar (the tanks) | `docs/class-clinic/tanks.md` | 49.8% / 57.4% / 26.3% / 29.1% | Batch 1 (full runs): 71.0 / 72.5 / 45.0 / 59.9%; then in short runs 72.9 / 70.5 / 74.8 / 77.3%. Ultimate Defense only when about to die and taken off on demand; weak skills, 10%-landing debuffs and long heals out; Tribunal and Judgment in; the seed skills and their icons (Touch of Eva, Flame Icon, Spirit of Shilen) and Vengeance from an Eva's Templar guide; **cubics for fake players** (both Templars). Long heals back, root-then-stun and defensive passive weights tried, not kept | 2026-11-14 |
