# Class clinic: Trickster (the Arbalester line)

Taken through `docs/class-clinic.md` on 2026-11-13. Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds (1,170 a run). The Trickster (classId 134) is the Kamael crossbow archer; it shares the archers' AI (role ARCHER, `docs/class-clinic/archers.md`), whose batches it already had at baseline.

**Result: 54.4% → 70.1% overall (+15.7, 95% +11.8 to +19.6; batch 1 +11.8, 95% +7.9 to +15.8, batch 2 +3.8, +0.1 to +7.6).** Clearly better against tanks (48% → 68%), melee (36% → 61%), daggers (18% → 37%), archers (11% → 36%) and buffers (73% → 85%). Batches 3 (Sharpshooting) and 4 (no magic buff under ranged fire) were not kept. Final Form keeps a fake Trickster's crossbow, which a player's doesn't: written down, not used (section 3).

## 1. The class

From `skill_cards.py "Trickster"` and the build in `dist/game/data/FakePlayerPvp.xml`:

- A crossbow archer in light armour. The crossbow reaches **850** (the elven and human bows 1,000), so another archer outranges it by 150 and always hits first (0% "hits first" against archers at baseline). Its P. Atk is about 5,000 to 5,700 where a bow archer has 7,000 to 11,000; the crossbow shoots faster.
- **Real Target** (1.5 s, 3 s reuse, reach 900, no land roll): the target's defence against crossbows ×0.6 for 30 s, which `Formulas.calcWeaponTraitBonus` turns into **×1.4 damage on every bolt** (normal attacks included, `calcPhysDam`). Its bolts are 98% of its damage.
- Shots: Death Shot (power 24,306, 3.5 s, 12 s reuse), Multiple Shot, Twin Shot, Wild Shot (needs souls), Bleeding Shot, Rising Shot, Penetrating Shot, Soul Rage. With arrows first (#29) an archer whose bolts land uses none of them; Death Shot is cast 0.04 times a battle.
- Control: Soul Shock (reach 200, a 3 s fear that lands 7 to 14%), Warp (a jump of 500, every 10 s), Death Mark, Betrayal Mark and Surrender to Dark (low level debuffs).
- Self: Furious Soul (crit), Soul Barrier (M. Def ×2 and +6 to +10% defence against bows and crossbows, 10 s), **Prahnah (M. Def +3,000 for 30 s, nothing else)**, Fast Shot, Ultimate Escape, Soul Cleanse, Final Form.
- Learned and not in the build: **Sharpshooting** (the crossbow's Snipe: +199 P. Atk, crit rate ×1.2, **+200 reach**, no moving, 2 min, 75 s reuse), Decoy, the traps, Deadly Roulette (a lethal at 10% HP or less).

Retail play: a High Five PvP guide for the Trickster could not be fetched (`forums.hopzone.net` and `forum.exiliumworld.com` are outside the session's allowed hosts; add them to read the guides). From the search summaries: a Trickster opens with Ultimate Escape, then Sharpshooting, then Decoy to take the enemy's target; Sharpshooting "won't allow you to move" and is useless against daggers; two traps are standard; enchants go to Soul Shock, Real Target, Deadly Roulette, Rising Shot, the traps, Prahnah and Soul Barrier. Real Target's crossbow vulnerability is given as about 60%. None of these sources names its chronicle with certainty.

## 2. Baseline (`matrix-20261113-222901`)

| Against | Battles | Win % (95%) | Fight s (won / lost) | Enemy HP left when it loses | Damage /s dealt / taken | Enemy within 200 |
|---|---|---|---|---|---|---|
| **tank** | 120 | 47.5% (39-56) | 134 / 100 | 27% | 243 / 129 | 22% |
| **melee** | 360 | 36.0% (31-41) | 43 / 30 | 39% | 543 / 332 | 42% |
| **dagger** | 90 | 17.8% (11-27) | 28 / 16 | 94% | 360 / 864 | 52% |
| **archer** | 90 | 11.1% (6-19) | 13 / 9 | 81% | 692 / 1724 | 0% |
| **nuker** | 270 | 84.8% (80-89) | 22 / 22 | 57% | 1138 / 405 | 2% |
| **healer** | 90 | 95.6% (89-98) | 54 / 300 | 94% | 1302 / 18 | 0% |
| **buffer** | 150 | 72.7% (65-79) | 40 / 52 | 31% | 441 / 144 | 32% |
| **all** | 1,170 | 54.4% (52-57) | 32 / 28 | 48% | 645 / 245 | 23% |

Casts per battle: Soul Barrier 1.7, Soul Shock 1.2 (lands 11%), Furious Soul 1.2, Nail Attack 1.1, Prahnah 1.0 (aborted 100% against archers), Warp 1.0, Soul Cleanse 0.9, Final Form 0.7, **Real Target 0.00 (one cast in 1,170 battles)**. Normal attack: 98% of its damage.

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | It never puts Real Target on (×1.4 on every bolt) | 1 cast in 1,170 battles; bolts are 98% of its damage | Arrows first (#29) skips combos and the debuff list while its bolts land, and the "Real target" combo was the only place that cast it | Class AI (role ARCHER, by effect) | `useWeaponWeakness`: an archer keeps on its target the debuff of its `debuff` list that lowers the target's defence against its own weapon, when it reaches and is off before a melee gets there. Effects report the factor (`AbstractEffect.getDefenceTraitFactor`, `DefenceTrait`). Batch 1 |
| 2 | It opens with Prahnah against fighters and archers | 1.0 casts a battle against every kind, 2.7 s; aborted 100% against archers (`000326-MoonlightSentinel-vs-Trickster`: Soul Barrier and Prahnah aborted under fire, 12% HP lost before it shoots) | Nothing told `castOnSelf` that an M. Def buff is worth nothing against a player without magic | General AI (by effect) | `onlyRaisesMagicDefence`: a buff whose only effect is M. Def is skipped against a PvP target that doesn't fight with magic. Batch 1 |

| 3 | Warp jumps through the melee it peels from | `000016-Duelist-vs-Trickster` (baseline): the Duelist west of it at gap 7, the Trickster facing it; after Warp it stands 500 west, on the Duelist's far side. From a gap of 200 that lands 300 behind the melee instead of 700 away | Warp's fly course is 180 ("to the front"); `useSkill` faced the target before any Blink, which is right for Blink (course 0) only | Harness / general AI | `faceToJumpAway`: face the target turned by the skill's fly course, in `useSkill` and `jumpAway`. Reaches Warp in the Judicator's `peel` too. Batch 2 |
| 4 | Its peel opens with Soul Shock, which lands 12% | Soul Shock 0.9 to 1.2 casts a battle, landed 11 to 12%; Warp (sure, every 10 s) only after it | `<peel>627,628</peel>` | Build data | `<peel>628,627</peel>`. Batch 2 |
| 5 | Soul Barrier against a melee | 1.4 casts a battle after batch 1, 2.3 against tanks; its effects are M. Def ×2 and a defence against bows and crossbows | Nothing checked what the buff defends against | General AI (by effect) | `defendsAgainst` replaces batch 1's `onlyRaisesMagicDefence`: a buff in a fight is skipped when every effect is M. Def against a player without magic or a defence against a weapon the player doesn't use. A resistance to a state (Courage: fear) always counts. Batch 2 |

Not fixed here:

- **Final Form keeps the crossbow (harness).** For a fake player the Transformation effect only applies its stats (P. Atk, P. Def, M. Atk, M. Def ×2) and swaps the skill lists (`FakePlayerPvpManager.transform`); the NPC keeps its weapon, so a transformed Trickster shoots on from 850 at twice its P. Atk. A player in Final Form fights with the form's base attack (`stats/transformations/00251.xml`: SWORD, range 20). Measured at baseline after the transform: bolt damage per second 1,896 → 2,671 against archers, 1,276 → 2,548 against nukers, 470 → 1,454 against melee; damage taken against archers 3,067 → 1,341. Casting it at the start of every fight would win more fights by leaning on a mechanic no player has, so the AI is not tuned around it. Giving the fake player the form's attack type and range is a balance change (it would weaken the Trickster and touch the hybrid Soul Hounds and the Judicator): `docs/class-balance-85.md`, not the clinic.

## 4. The batches

### Batch 1: Real Target before the bolts, no Prahnah without magic (`matrix-20261113-224154`)

| Against | Before | After | Difference (95%) | Verdict | Damage /s dealt | Damage /s taken |
|---|---|---|---|---|---|---|
| **tank** | 47.5% | 72.5% | +25.0 (+13.0 to +37.0) | **better** | 243 → 334 | 129 → 125 |
| **melee** | 36.0% | 54.9% | +18.9 (+11.7 to +26.0) | **better** | 543 → 646 | 332 → 319 |
| **dagger** | 17.8% | 28.3% | +10.6 (−1.7 to +22.8) | no clear change | 360 → 488 | 864 → 821 |
| **archer** | 11.1% | 22.2% | +11.1 (+0.3 to +21.9) | **better** | 692 → 946 | 1724 → 1910 |
| **nuker** | 84.8% | 86.3% | +1.5 (−4.4 to +7.4) | no clear change | 1138 → 1373 | 405 → 467 |
| **healer** | 95.6% | 97.2% | +1.7 (−3.8 to +7.1) | no clear change | 1302 → 1533 | 18 → 16 |
| **buffer** | 72.7% | 83.0% | +10.3 (+1.0 to +19.7) | **better** | 441 → 602 | 144 → 159 |
| **all** | 54.4% | 66.2% | +11.8 (+7.9 to +15.8) | **better** | 645 → 759 | 245 → 279 |

Real Target 0 → 1.66 casts a battle (1% aborted), Prahnah 1.01 → 0.44 (only against magic users now). Damage dealt +18% a second.

Guard: `useWeaponWeakness` reaches only a build with a debuff that lowers the defence against its own weapon (the Trickster's Real Target; no other build lists one). `onlyRaisesMagicDefence` also reaches the tanks' Anti Magic Armor (Phoenix Knight, Hell Knight, Eva's and Shillien Templar: M. Def only, 30 s, 15 min reuse), which is now kept for an opponent with magic; Spirit Barrier (20 min) is cast before the fight and is not touched. Like `isWastedSilence`, it only removes casts that do nothing to the opponent; no guard run.

### Batch 2: Warp away and first, no Soul Barrier against a melee (`matrix-20261113-225308`, against batch 1)

| Against | Before | After | Difference (95%) | Verdict | Damage /s dealt | Damage /s taken |
|---|---|---|---|---|---|---|
| **tank** | 72.5% | 68.3% | −4.2 (−15.7 to +7.4) | no clear change | 334 → 312 | 125 → 125 |
| **melee** | 54.9% | 60.6% | +5.7 (−1.5 to +12.9) | no clear change | 646 → 718 | 319 → 321 |
| **dagger** | 28.3% | 36.7% | +8.3 (−5.3 to +22.0) | no clear change | 488 → 641 | 821 → 875 |
| **archer** | 22.2% | 35.6% | +13.3 (+0.2 to +26.4) | **better** | 946 → 1153 | 1910 → 1633 |
| **nuker** | 86.3% | 88.5% | +2.2 (−3.4 to +7.8) | no clear change | 1373 → 1353 | 467 → 428 |
| **healer** | 97.2% | 98.9% | +1.7 (−2.4 to +5.7) | no clear change | 1533 → 1486 | 16 → 14 |
| **buffer** | 83.0% | 84.7% | +1.7 (−6.7 to +10.0) | no clear change | 602 → 633 | 159 → 158 |
| **all** | 66.2% | 70.1% | +3.8 (+0.1 to +7.6) | **better** | 759 → 777 | 279 → 271 |

Soul Barrier 1.39 → 0.92 casts a battle, Soul Shock 0.92 → 0.74. Warp (`positions.jsonl`, the gap to the target just before a Warp and 0.4 to 1 s after): median 50 → 453 at batch 1, 69 → 504 now. Against archers the gain is the walk-in: Soul Barrier is kept (it defends against bows) but no longer stands beside Prahnah and the debuffs that went before.

Guard: `defendsAgainst` reaches every buff cast in a fight that is all M. Def or weapon defence: Anti Magic Armor (the four tanks), Soul Barrier (Doombringer), Song of Wind Storm (Battle Bard, Sword Muse, if sung in a fight), Deflect Arrow (a lasting buff, on before the fight, so not touched). It only removes casts that defend against nothing the opponent does; no guard run. `faceToJumpAway` changes the direction of the Judicator's peel Warp (from through the target to away from it); no guard run.

### Batch 3: Sharpshooting, not kept (`matrix-20261113-230330`, against batch 2)

Sharpshooting (521) at the end of the `buff` list, under the Sagittarius' Snipe rule (`castOnSelf`: only against an archer or mage by class, no melee coming), with the reach the buff itself adds counted (`addedReach`, so a crossbow at 850 could cast it at the 986 a fight starts from).

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 68.3% | 63.3% | −5.0 (−17.0 to +7.0) | no clear change |
| **melee** | 60.6% | 57.2% | −3.3 (−10.5 to +3.9) | no clear change |
| **dagger** | 36.7% | 35.6% | −1.1 (−15.1 to +12.9) | no clear change |
| **archer** | 35.6% | 22.2% | **−13.3 (−26.4 to −0.2)** | **worse** |
| **nuker** | 88.5% | 88.1% | −0.4 (−5.8 to +5.0) | no clear change |
| **healer** | 98.9% | 98.9% | +0.0 (−3.1 to +3.1) | no clear change |
| **buffer** | 84.7% | 84.7% | +0.0 (−8.2 to +8.2) | no clear change |
| **all** | 70.1% | 67.4% | −2.7 (−6.5 to +1.0) | no clear change |

Cast 0.45 times a battle. Against archers damage taken rose 1,633 → 2,035 a second and dealt fell 1,153 → 951. `000013-Sagittarius-vs-Trickster`: Soul Barrier aborted by the first arrow, then Sharpshooting at 3.5 s under fire, and from then on a standing trade at 985 against a bow with nearly twice its P. Atk (6,037 crits): the Trickster's first bolt flies at 6.1 s. Standing still in reach of an archer that out-stats it loses the race; moving (batch 2's walk-in without buffs, the range steps) won it. Taken back out, the `addedReach` code with it.

### Batch 4: no magic buff under ranged fire, not kept (`matrix-20261113-231349`, against batch 2)

An archer leaves out of its `buff` list the magic buffs that take over 0.6 s while an archer or a mage that targets it has it in reach (`isUnderRangedFire`): at batch 2, Soul Barrier was aborted 88% of the time against archers and Prahnah 52% against nukers.

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 68.3% | 64.2% | −4.2 (−16.1 to +7.8) | no clear change |
| **melee** | 60.6% | 55.8% | −4.7 (−11.9 to +2.5) | no clear change |
| **dagger** | 36.7% | 30.0% | −6.7 (−20.4 to +7.1) | no clear change |
| **archer** | 35.6% | 30.0% | −5.6 (−19.2 to +8.1) | no clear change |
| **nuker** | 88.5% | 87.8% | −0.7 (−6.2 to +4.7) | no clear change |
| **healer** | 98.9% | 100.0% | +1.1 (−1.1 to +3.3) | no clear change |
| **buffer** | 84.7% | 84.0% | −0.7 (−8.9 to +7.6) | no clear change |
| **all** | 70.1% | 67.1% | −3.0 (−6.8 to +0.8) | no clear change |

Its own measure moved (Prahnah 0.44 → 0.17 casts a battle, aborted 33% → 2%; Soul Barrier aborted 24% → 13%), but damage taken rose against nukers (428 → 494 a second) and archers (1,633 → 1,815): a Prahnah or Soul Barrier that does finish is worth the ones that don't. Down in six kinds of seven, so not kept under the exception either. Two batches in a row without a gain: the clinic stops here.

## 5. Before and after

Baseline `matrix-20261113-222901` against batch 2 `matrix-20261113-225308`, 1,170 battles each:

| Against | Before | After | Difference (95%) | Verdict | Damage /s dealt | Damage /s taken |
|---|---|---|---|---|---|---|
| **tank** | 47.5% | 68.3% | +20.8 (+8.6 to +33.0) | **better** | 243 → 312 | 129 → 125 |
| **melee** | 36.0% | 60.6% | +24.6 (+17.5 to +31.7) | **better** | 543 → 718 | 332 → 321 |
| **dagger** | 17.8% | 36.7% | +18.9 (+6.2 to +31.6) | **better** | 360 → 641 | 864 → 875 |
| **archer** | 11.1% | 35.6% | +24.4 (+12.6 to +36.3) | **better** | 692 → 1153 | 1724 → 1633 |
| **nuker** | 84.8% | 88.5% | +3.7 (−2.0 to +9.4) | no clear change | 1138 → 1353 | 405 → 428 |
| **healer** | 95.6% | 98.9% | +3.3 (−1.4 to +8.1) | no clear change | 1302 → 1486 | 18 → 14 |
| **buffer** | 72.7% | 84.7% | +12.0 (+2.8 to +21.2) | **better** | 441 → 633 | 144 → 158 |
| **all** | 54.4% | 70.1% | +15.7 (+11.8 to +19.6) | **better** | 645 → 777 | 245 → 271 |

## 6. Next for this class

- **Daggers (37%) and archers (36%)** stay under 50%. A dagger is on it half the fight and kills it in about 20 s, as it does every archer (the elven and human archers win 3 to 17% against daggers). Against archers it is outranged (850 against 1,000) and out-statted (P. Atk 5,000 to 5,700 against 7,000 to 11,000); standing to trade (batch 3) made it worse.
- **Decoy and the traps** (learned, not in the build) are what retail Tricksters use against daggers and to take an enemy's target. A fake player's Decoy and traps are NPCs: whether other fake players target or step on them needs checking before they are worth a try.
- **Final Form** (section 3, not fixed here): decide in `docs/class-balance-85.md` whether a fake Kamael in Final Form should fight with the form's attack like a player. Until then its win rates include a crossbow at double P. Atk once it is under 70% HP.

## 7. Files and runs

- `java/org/l2jmobius/gameserver/ai/FakePlayerPvpAI.java` and `FakePlayerPvpAIExperimental.java`: `useWeaponWeakness`, `weakensAgainst`, `defendsAgainst`, `faceToJumpAway`.
- `java/org/l2jmobius/gameserver/model/effects/AbstractEffect.java`, `dist/game/data/scripts/handlers/skill/effects/DefenceTrait.java`: `getDefenceTraitFactor`.
- `dist/game/data/FakePlayerPvp.xml`: the Trickster's `peel` (Warp first).
- Runs in `../build/server/game/log/simulation-matrix/`: baseline `matrix-20261113-222901`, batch 1 `matrix-20261113-224154`, batch 2 `matrix-20261113-225308`, batch 3 `matrix-20261113-230330`, batch 4 `matrix-20261113-231349`.
