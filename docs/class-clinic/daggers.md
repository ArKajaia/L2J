# Class clinic: the daggers (Adventurer, Wind Rider, Ghost Hunter)

The second case of `docs/class-clinic.md`, 2026-11-12. Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds (1,170 a run). The three daggers share their AI (role DAGGER) and most of their kit, so they were taken through the clinic together: one diagnosis, the same batches, a run for each.

**Result:**

| Build | Before | After | Difference (95%) |
|---|---|---|---|
| Wind Rider | 37.9% | 46.8% | +8.9 (+4.9 to +12.9), **better** |
| Ghost Hunter | 40.9% | 54.4% | +13.4 (+9.4 to +17.4), **better** |
| Adventurer | 40.8% | 43.1% | +2.3 (−1.7 to +6.3), no clear change (archers clearly better) |

Tanks stay at 2 to 8% for all three: out-statted (section 5).

## 1. The class

From `skill_cards.py` for the three builds and their `<build>` in `dist/game/data/FakePlayerPvp.xml`:

- Blows: Deadly Blow (power 5,479, 3 s reuse), Lethal Blow (5,773, 4 s), Backstab (5,479, only from the side or behind), Critical Blow (Adventurer, +30% blow rate on itself for 15 s), Blinding Blow (Wind Rider, Ghost Hunter), Mortal Blow (977, the low level blow). Throwing Dagger (a slow, 300 reach, 45 s). The Ghost Hunter has Sting.
- Where a blow lands from decides almost everything (`Formulas.calcBlowSuccess`, `calcBlowDamage`): from behind it lands **twice** as often (1.5 times from the side) and deals 20% more; the Focus buffs add their positional critical damage on top (Focus Death: −30% from the front, +90% from behind, halved in the blow formula). A target turns to face whoever it attacks or casts at, at the start of each attack or cast (`Creature.doAttack`, `doCast`), and the position counts when the blow lands, not when it starts.
- Getting behind: Shadow Step (75 s) puts the dagger 25 behind its target, which drops its target. Bluff, Trick and Switch have a TargetCancel effect (the target drops its target and stops what it does).
- Self: the Focus buff (2 min), Mortal Strike (Wind Rider, Ghost Hunter: +20% blow rate, the Treasure Hunter line doesn't learn it), the class buff (Exciting Adventure, Wind Riding, Ghost Walking: 30 s, evasion, speed, blow rate), Ultimate Evasion (emergency), Mirage (Adventurer: its attackers drop their target 4 hits in 5), Dodge (Wind Rider: +90% evasion of physical skills, 10 s, 75 s reuse), Counterattack (Ghost Hunter: 9 melee skills in 10 come back, 10 s, 75 s reuse), Elemental Heal (Wind Rider, 5 s cast).
- Debuffs learned at 74 and below (Hex, Power Break, Entangle, Sand Bomb, Bleed): 10 to 12% landed at 85. Critical Wound (+30% critical damage taken, blows are critical) lands 50%.

Retail High Five play: a web search found little that is specific to High Five daggers (the guides found are Interlude ones; a [Lineage 2 forum thread](https://forums.lineage2.com/topic/26134-end-game-dagger/) calls the Wind Rider the top PvP dagger, an undated post that may be from another patch). What players agree on across chronicles: get behind the target before the burst (Bluff, Shadow Step), Backstab and Lethal Blow from there, keep the class's evasion buffs for when a fighter is on it, and don't hit into a reflect. That is the yardstick used here.

## 2. Baselines

| Against | Adventurer (`matrix-20261112-151755`) | Wind Rider (`-152544`) | Ghost Hunter (`-153346`) |
|---|---|---|---|
| **tank** | 1.7% | 2.5% | 2.5% |
| **melee** | 27.8% | 26.7% | 28.3% |
| **dagger** | 26.7% | 58.3% | 65.0% |
| **archer** | 77.5% | 72.5% | 79.2% |
| **nuker** | 39.3% | 25.6% | 31.5% |
| **healer** | 100.0% | 100.0% | 100.0% |
| **buffer** | 46.7% | 42.0% | 43.3% |
| **all** | 40.8% | 37.9% | 40.9% |

Blows by where the dagger stood when it cast them (target's heading in `positions.jsonl`; a script, not part of the clinic):

| Adventurer | Casts | Landed | Damage a cast |
|---|---|---|---|
| Deadly Blow from behind | 1,000 | 91% | 1,820 |
| Deadly Blow from the front | 5,016 | 59% | 792 |
| Lethal Blow from behind | 1,216 | 87% | 2,320 |
| Lethal Blow from the front | 4,762 | 59% | 1,108 |

The Wind Rider and Ghost Hunter land 87 to 88% from the front (Focus Death, Mortal Strike) and deal 1.7 times as much from behind. About 1 blow in 5 is cast from behind.

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | It hits into Counterattack and Eye for Eye | Against the Doombringer **60% of the HP the Adventurer lost was its own reflected damage** (HP lost against damage events, per opponent), 12% against the Ghost Hunter. `battles/*Doombringer-vs-Adventurer`: a 9,391 Backstab and a 3,879 Lethal Blow into Eye for Eye, dead from 94% HP | Counter damage is the target's P. Atk × 700 / its P. Def. (`Formulas.calcDamageReflected`), 9 melee skills in 10. The defenses a fake player plays around (`Defense`) had Ultimate Defense, Guts, Zealot... and not these | General AI | `Defense.COUNTER` (skills 447, 948): once noticed, physical skills are left aside and it steps away until the buff ends (`holdOff`). Batch 3 |
| 2 | Dodge and Counterattack run out before the fight | Cast at second 3.8 with the enemy a median **860** away (90 to 93% of casts over 300 away); 10 s on a 75 s reuse. They only answer physical skills, and are cast against mages too | The buff list casts every buff that is off, at once | Class AI (role DAGGER, by abnormal type AVOID_SKILL / COUNTER_SKILL) | Cast with a fighter in reach (200, or its bow's range), never against a mage. Batch 3 |
| 3 | Its second opener starts with a debuff that doesn't land | Ghost Hunter "Hex and blow": Hex lands 10%, Power Break 12%, 19% and 15% aborted, 1.1 casts a battle each. Wind Rider "Entangle and blow": Entangle 12%, 31% aborted | Required first steps of level 74 skills | Build data | "Critical wound" (Critical Wound lands 50%, +30% blow damage) in their place, like the Adventurer's. Batch 2 |
| 4 | Mortal Blow, a fifth of Lethal Blow, cast 2 to 3.5 times a battle | 345 to 575 damage a cast from the front | The list floor (`LIST_RANK_FACTOR`) lifts a skill to its place in the list, and the blow estimate ignored the chance a blow lands | General AI (only blows, so only daggers) | Blows get no list floor; the estimate is the damage times the chance it lands from where it stands (`blowChance`). Mortal Blow 3.5 → 1.4 casts. Batch 2 |
| 5 | Shadow Step only to chase a squishy | 0.0 casts a battle against tanks, melee and daggers | `isWorthGapCloser` saves gap closers of a dagger for healers, mages and archers | Build data | "Shadow Step backstab" combo (it lands behind the target), 1.0 a battle. Batch 1 |
| 6 | Shadow Step logged as aborted every time | "Shadow Step is aborted 100% of the time" | `TeleportToTarget` calls `abortCast` after it landed, like Blink | Harness | `FakeSimulationManager.jumpsBack` covers it |

Tried and taken out again (section 4): Trick and Switch openers, "only circle a target that can't turn", Critical Blow first, Ultimate Evasion after the class buff.

## 4. The batches

### Batch 1: Trick and Switch openers, flanking only a target that can't turn (`matrix-20261112-154226`, Adventurer)

Idea: the blows behind are worth twice those in front, so get behind with the skills that make the target drop its target (Trick, Switch: "Trick backstab" and "Switch backstab" combos before "Bluff backstab"), and only run behind a target that can't turn (held, or after it dropped its target).

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **melee** | 27.8% | 15.3% | −12.5 (−18.4 to −6.6) | **worse** |
| **nuker** | 39.3% | 31.1% | −8.1 (−16.2 to −0.1) | **worse** |
| **archer** | 77.5% | 88.3% | +10.8 (+1.4 to +20.3) | **better** |
| **all** | 40.8% | 35.0% | −5.7 (−9.7 to −1.8) | **worse** |

Trick 0.06 → 3.5 casts a battle, Switch → 1.8, Bluff 2.85 → 0.7, and Backstab *fell* (2.9 → 2.2). Two things came out of the logs:

- **Bluff is the better canceller.** Bluff's stun lands 8%, but its TargetCancel part made the target drop its target **76%** of the time (Trick 38%, Switch 33%, Shadow Step 8%), for 2.5 to 3 s. When Trick lands it works as meant (`battles/000003-StormScreamer-vs-Adventurer`: Trick, three steps behind, Backstab for 11,234), but 6 casts in 10 are wasted, and the Trick openers had pushed the Bluff opener out.
- **Circling a target that faces it still reaches its side**, where Backstab works and blows land 1.5 times as often. Gating it on "can't turn" removed that.

The Trick and Switch combos were taken out and the combo's circling restored; flanking a target that can't turn stays, for the scored blows only (it adds to the circling of the combos).

### Batch 2 (with 1b): the blow estimate, Critical Wound openers (`matrix-20261112-155307`, Adventurer)

Overall 40.8% → 40.4% (−0.4, −4.4 to +3.6): nothing. Skill use moved as meant (Mortal Blow 3.5 → 1.5 casts, Bluff back to 2.2, Shadow Step 1.1), damage dealt 709 → 748 a second, damage taken 483 → 523. Blows from behind went from about 19% to 23%. The flank itself fired 453 times in 1,170 fights: a target that can't turn is rare when every cast takes 0.5 s.

### Batch 3: Counterattack and Eye for Eye as a defense, Dodge and Counterattack timing

Against each build's baseline (batches 1b to 3 together):

| Against | Adventurer (`-160700`) | Wind Rider (`-161617`) | Ghost Hunter (`-162319`) |
|---|---|---|---|
| **tank** | 1.7 → 6.7 | 2.5 → 3.3 | 2.5 → 7.5 |
| **melee** | 27.8 → 30.0 | 26.7 → 37.5 **better** (+10.8, +4.1 to +17.6) | 28.3 → 48.9 **better** (+20.6, +13.6 to +27.5) |
| **dagger** | 26.7 → 25.0 | 58.3 → 65.0 | 65.0 → 66.7 |
| **archer** | 77.5 → 88.3 **better** (+10.8, +1.4 to +20.3) | 72.5 → 78.3 | 79.2 → 90.0 **better** (+10.8, +1.8 to +19.9) |
| **nuker** | 39.3 → 38.1 | 25.6 → 41.1 **better** (+15.6, +7.7 to +23.4) | 31.5 → 44.4 **better** (+13.0, +4.8 to +21.1) |
| **healer** | 100 → 100 | 100 → 100 | 100 → 100 |
| **buffer** | 46.7 → 49.3 | 42.0 → 49.3 | 43.3 → 62.0 **better** (+18.7, +7.6 to +29.8) |
| **all** | 40.8 → 43.1 (+2.3, −1.7 to +6.3) | 37.9 → 46.8 **better** (+8.9, +4.9 to +12.9) | 40.9 → 54.4 **better** (+13.4, +9.4 to +17.4) |

- Reflected damage against the Doombringer: 60% → 13% of the Adventurer's HP loss. The Doombringer still wins on stats (P. Atk 6,855).
- Dodge 1.1 → 0.6 casts a battle and Counterattack 1.1 → 0.6: cast only with a fighter in reach. Hex 1.1 → 0.05, Power Break 1.1 → 0.04, Entangle 1.6 → 0.2.
- The Ghost Hunter's biggest gains are against the melee that use skills most: War Dancer 27 → 77%, Duelist 37 → 77%, Fortune Seeker 13 → 50%.
- **Guard** (the defense changes what every fighter does against a Ghost Hunter or Doombringer): Titan against Doombringer and Grand Khavatari, 60 battles each, before (`matrix-20261112-160421`) and after (`-161432`): Doombringer **8% → 27%** (+18, +5 to +32, better), the Titan's reflected damage 31% → 11% of its HP loss; Grand Khavatari (control) 55 → 60%, no clear change.

### Batch 4: Critical Blow first, Ultimate Evasion after the class buff (taken out)

Data only: the Adventurer opens with Critical Blow (its +30% blow rate for 15 s), and each dagger's buff list takes Ultimate Evasion after its 30 s class buff (they share an abnormal type, so it follows when the first runs out instead of waiting for 30% HP). Against batch 3: Adventurer 43.1 → 43.0% (`matrix-20261112-163101`), Wind Rider 46.8 → 46.2% (`-163848`), Ghost Hunter 54.4 → 54.6% (`-164621`), no clear change for any kind of any of them. Critical Blow casts didn't move (1.19 → 1.23 a battle): it was already used about as often as its 30 s reuse allows, the idea had nothing to gain. Ultimate Evasion 0.5 → 0.6. Taken out.

This batch was run for all three, which wasn't needed: a change to skills the three share gives the same answer for each (section 7, "How to run the daggers").

## 5. Not fixed here

- **Tanks (2 to 8%): out-statted.** 250 to 330 damage a second dealt against 400 to 510 taken; P. Def 2,200 to 2,600 against a dagger's P. Atk 3,000, 27,000 HP. Even Lethal Blow does 500 to 700 a hit on a Hell Knight under Physical Mirror. The tank wins the trade with its normal attack.
- **The robe nukers' one-shots.** `battles/000041-MysticMuse-vs-Adventurer` (batch 1b): Elemental Symphony crits for 20,074 from 870 away, at second 4.5, before the dagger is in reach. See `docs/class-balance-85.md` (M. Atk).
- **The Adventurer's blows land 59% from the front** against 87 to 88% for the other two: its line has no Mortal Strike (as in retail) and no Focus Death. Its Focus buff is Focus Power (the first of "357|356" it learns), which takes 30% (15% in the blow formula) off the critical damage of a blow from the front, where 4 blows in 5 are cast. Focus Chance (critical rate, which blows don't use) would be neutral for its blows and cost its normal attacks. Already in `docs/class-balance-85.md` ("Adventurer").
- **Bluff still costs a cast at 8% for the stun**, but its TargetCancel lands 3 times in 4, which is what opens a Backstab: it stays the opener. Its comment in the data says so now.
- **The class buff (30 s)** is cast at 900 from the target like Dodge was; four seconds of 30 are lost on the way in. Not tried.
- **Ultimate Evasion as a reflex against a mage** (HP crash): its evasion does nothing against spells. 0.4 casts a battle against nukers.
- **Doombringer's own Eye for Eye** is cast on the way in too (6.2 s, contact at 7 s here): the Dodge rule is for role DAGGER only. Worth the same rule by abnormal type for every build, with a guard.

## 6. What changed

- `ai/FakePlayerPvpAI.java` and `ai/FakePlayerPvpAIExperimental.java` (the same in both): `Defense.COUNTER`; `isAnswerToPhysicalSkills` / `isPhysicalThreatNear` in `castOnSelf` (daggers); `blowChance` in `estimateDamage` and no list floor for blows (daggers); `flank` / `wontTurn` before a scored blow (daggers).
- `dist/game/data/FakePlayerPvp.xml`, the three daggers: "Shadow Step backstab" combo; "Critical wound" in place of "Hex and blow" and "Entangle and blow"; Bluff's comment.
- `managers/simulation/FakeSimulationManager.java`: Shadow Step (`TeleportToTarget`) not logged as an aborted cast.
- Guard: only `Defense.COUNTER` reaches other builds (every fighter against a Ghost Hunter or a Doombringer): the Titan run above.

## 7. Next for this class

How to run the daggers: one clinic for a change to what they share (the blows, Bluff, Shadow Step, Critical Wound, the dagger code), with the Ghost Hunter or Wind Rider as the one to run; a run per dagger only for what differs between them (the Adventurer's Critical Blow, Mirage and missing Mortal Strike, the Wind Rider's Dodge and Elemental Heal, the Ghost Hunter's Counterattack, Hex and Sting). Batch 3 shows why the split matters: the shared part moved the Adventurer +2.3, the parts of their own kit moved the Wind Rider +8.9 and the Ghost Hunter +13.4.


- The Adventurer's Focus buff and blow rate (section 5) are a balance question for `docs/class-balance-85.md`, not an AI one.
- The short-buff rule (Dodge, Counterattack) for every build, Eye for Eye first, with a guard.
- Bluff as an optional step is no longer needed (its TargetCancel lands): the balance doc's idea can be dropped.
