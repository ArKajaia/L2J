# Class clinic: Battle Bard (the Swordsinger line)

Taken through `docs/class-clinic.md` on 2026-11-13. Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds (1,170 a run). The Battle Bard (classId 100, variant Battle Bard Legacy) is the Sword Muse that fights on its own; the Sword Muse build of the same class is its party buffer (weight 0, see section 6).

**Result: 35.2% → 66.5% overall (+31.3; batch 1 +24.4, 95% +20.4 to +28.3, batch 2 +7.0, +3.1 to +10.9).** Clearly better against tanks (33% → 51%), melee (28% → 63%), archers (19% → 73%), nukers (13% → 46%) and buffers (41% → 86%); daggers 83% → 94%. Build data only: no code changed, no other build reached, so no guard run was needed.

## 1. The class

From `skill_cards.py "Battle Bard"` and the build in `dist/game/data/FakePlayerPvp.xml`:

- A sword and shield fighter in heavy armour, role TANK (the role also picks its passive tree, so it was left alone). Its damage: **Deadly Strike** (power 4,640, 1.5 s, 5 s reuse), Sword Symphony (1,554 and a silence, 15 s reuse), Power Strike (84), Psycho Symphony (457 around it 150 and a slow, 3 s cast), and its normal attack.
- Debuffs from its level 74 kit: Entangle (3 s magic cast, speed −30%), Arrest (root), Song of Silence (silence and physical silence around it, 150 s reuse), Charm.
- Self: Battle Whisper (P. Atk +15%, crit damage +10%, attack speed +10%), Deflect Magic, Deflect Arrow, Spirit Barrier, Ultimate Defense, Elemental Heal (5 s cast, 546), and every song on itself. The TANK buff list already gives it Earth, Wind, Vitality, Hunter, Warding, Champion and Wind Storm.
- **Custom kit** (`config/Custom/Performers.ini`, `data/stats/skills/custom/performer_skills.xml`), which no retail guide covers:
  - Performances, one at a time, on the party within 900: Ballad of the Bulwark (P. Def and M. Def +12%), Hymn of Renewal (regen, heals received +10%), Anthem of Valor (P. Atk and M. Atk +10%). They stack with every retail buff.
  - Battle Hymn, a stance: P. Def +5%, 15% of the hits it deals heal its party for 360 (Healing Verse, 3 s reuse).
  - Sanctuary Chorus at 79: party heal 20% and P. Def / M. Def +30%, 5 min reuse.
  - Virtuoso: its songs last 1.5 times longer (3 minutes), which keeps them on through all but the longest fights.

Retail play: no High Five PvP guide for the Swordsinger turned up. The StrategyWiki page ([strategywiki.org](https://strategywiki.org/wiki/Lineage_II/Swordsinger), from the search summary, chronicle not named) says the class "was not made for PvP" and names Hunter, Wind and Warding as its PvP songs, which the TANK buff list already has. As for the War Dancer, the kit is the yardstick: its strongest hits first, its own songs and buffs on before the fight, no 10%-landing debuffs or long casts in melee.

## 2. Baseline (`matrix-20261113-144715`)

| Against | Battles | Win % (95%) | Fight s (won / lost) | Enemy HP left when it loses | Damage /s dealt / taken |
|---|---|---|---|---|---|
| **tank** | 120 | 32.5% (25-41) | 140 / 97 | 74% | 227 / 341 |
| **melee** | 360 | 27.8% (23-33) | 61 / 63 | 54% | 392 / 552 |
| **dagger** | 90 | 83.3% (74-90) | 46 / 40 | 17% | 390 / 466 |
| **archer** | 120 | 19.2% (13-27) | 71 / 68 | 74% | 120 / 355 |
| **nuker** | 270 | 13.3% (10-18) | 52 / 34 | 89% | 279 / 619 |
| **healer** | 90 | 99.4% (95-100) | 300 / - | - | 712 / 1 |
| **buffer** | 120 | 40.8% (32-50) | 122 / 89 | 64% | 415 / 261 |
| **all** | 1,170 | 35.2% (32-38) | 82 / 61 | 68% | 394 / 354 |

Casts per battle: Power Strike 8.9, Entangle 4.8 (40% aborted, lands 13%), Arrest 4.8 (lands 10%; 30 a battle against healers), Sword Symphony 3.8, Elemental Heal 3.7 (48% aborted), Song of Silence 1.1 (lands 11%), Ultimate Defense 0.5. Normal attack: 87% of its damage.

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | Its best single-target skill isn't in the build | Deadly Strike (power 4,640, 5 s reuse) under "LEARNED BUT NOT IN THE BUILD"; its attack list was Sword Symphony and Power Strike (84) | Build data | Build data | First in `attack`, and in the combo after Sword Symphony. Batch 1 |
| 2 | Debuffs that land 10 to 13%, one of them a 3 s magic cast in melee | `000838-Doombringer-vs-BattleBard` (baseline): stops at gap 35 to cast Entangle (2.8 s), resisted; `000794-MoonlightSentinel-vs-BattleBard`: walks after an archer on an Arrest plan, Entangle aborted | Entangle, Arrest, Song of Silence in `debuff`; the Song of Silence combo (50%) | Build data | `debuff` and the combo gone; `peel` down to Arrest. Batch 1 |
| 3 | A 5-second self heal | Elemental Heal 3.7 casts a battle, 48% aborted (75% against archers) | Build data | Build data | Out. Battle Hymn's Healing Verse and Sanctuary Chorus stay. Batch 1 |
| 4 | Its own buffs and songs weren't on | Battle Whisper, Deflect Magic, Songs of Vengeance, Renewal, Water learned and unused. Spirit Barrier (M. Def, MD_UP) is replaced by the TANK list's Magic Barrier | Build data | Build data | `<buff>988,913,112,305,349,266</buff>`: lasting, so on before the fight (`FakePlayerPvpManager.isPreFightBuff`). Batch 1 |
| 5 | Power Strike earns less than its normal attack | Batch 1: 335 damage a cast, 654 per cast second, against 1,134 for Deadly Strike and 1,091 for Sword Symphony; its normal attack hits for about 455 | Build data | Build data | Out of `attack`. Batch 2 |
| 6 | Ultimate Defense at full HP | `000838`: `REFLEX ENEMY_BURST;Ultimate Defense` at 6.1 s, HP 100%; then 30 s it can't move (ImmobileBuff) and enemies hold off it (`Defense`). Disabled 24% of the fight against melee with almost no enemy root landing | The burst reflex (`Reflexes`, `ENEMY_BURST`) takes any DEFENSIVE skill of the build whatever its HP: shared code | Build data here | Out of `emergency`. Batch 2. The reflex itself is a general finding (section 6) |

## 4. The batches

Each against the run before it, same seed. "Clearly" means the 95% interval of the difference doesn't include zero.

### Batch 1: Deadly Strike, its own buffs and songs, no low-landing debuffs or 5 s heal (`matrix-20261113-145947`)

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 32.5% | 51.7% | +19.2 (+6.9 to +31.4) | **better** |
| **melee** | 27.8% | 54.4% | +26.7 (+19.7 to +33.6) | **better** |
| **dagger** | 83.3% | 87.8% | +4.4 (-5.8 to +14.7) | no clear change |
| **archer** | 19.2% | 65.0% | +45.8 (+34.8 to +56.9) | **better** |
| **nuker** | 13.3% | 37.4% | +24.1 (+17.0 to +31.1) | **better** |
| **healer** | 99.4% | 100.0% | +0.6 (-1.0 to +2.1) | no clear change |
| **buffer** | 40.8% | 75.4% | +34.6 (+22.9 to +46.3) | **better** |
| **all** | 35.2% | 59.5% | +24.4 (+20.4 to +28.3) | **better** |

Skill use moved as meant: Deadly Strike 0 → 8.7 casts a battle (4,950 damage a battle), Entangle, Arrest, Elemental Heal and Song of Silence gone. Damage dealt 394 → 542 a second.

### Batch 2: no Power Strike, no Ultimate Defense (`matrix-20261113-151139`, against batch 1)

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 51.7% | 50.8% | -0.8 (-13.5 to +11.8) | no clear change |
| **melee** | 54.4% | 63.1% | +8.6 (+1.4 to +15.8) | **better** |
| **dagger** | 87.8% | 94.4% | +6.7 (-1.6 to +14.9) | no clear change |
| **archer** | 65.0% | 72.5% | +7.5 (-4.2 to +19.2) | no clear change |
| **nuker** | 37.4% | 46.3% | +8.9 (+0.6 to +17.2) | **better** |
| **healer** | 100.0% | 99.4% | -0.6 (-2.1 to +1.0) | no clear change |
| **buffer** | 75.4% | 86.2% | +10.8 (+1.0 to +20.7) | **better** |
| **all** | 59.5% | 66.5% | +7.0 (+3.1 to +10.9) | **better** |

Time disabled 19% → 10% of the fight (24% → 9% against melee): the rooted time had been its own Ultimate Defense. Damage dealt 542 → 591 a second; the casts freed went to its normal attack.

### Batch 3: Anthem of Valor as its PvP performance, not kept (`matrix-20261113-152231`, against batch 2)

Anthem of Valor (P. Atk +10%) in place of Ballad of the Bulwark (P. Def and M. Def +12%) while it fights a player. It did what it says, damage dealt 591 → 630 a second, but damage taken rose more, 375 → 418: **−3.4 overall (−7.3 to +0.4)**, no kind clearly better or worse, every kind but buffers lower. Taken out: Ballad of the Bulwark stays its PvP performance.

## 5. After

After batch 2 (`matrix-20261113-151139`):

| Against | Win % (95%) | Enemy HP left when it loses | Damage /s dealt / taken |
|---|---|---|---|
| tank | 50.8% (42-60) | 22% | 374 / 370 |
| melee | 63.1% (58-68) | 27% | 741 / 716 |
| dagger | 94.4% (88-98) | 16% | 647 / 471 |
| archer | 72.5% (64-80) | 45% | 196 / 254 |
| nuker | 46.3% (40-52) | 73% | 583 / 589 |
| healer | 99.4% (95-100) | - | 1087 / 3 |
| buffer | 86.2% (79-91) | 27% | 494 / 239 |
| **all** | **66.5% (64-69)** | 40% | 591 / 375 |

Casts per battle: Deadly Strike 8.2 (566 a cast, 1,126 per cast second), Sword Symphony 3.9 (564, 1,094). Normal attack: 82% of its damage. Skill power is added to P. Atk, so a power of 4,640 hits for about 1.2 normal attacks: what the skills buy is time, not burst.

## 6. Not fixed here

- **The burst reflex casts long-reuse DEFENSIVE skills at full HP** (`ai/fight/Reflexes.java`, `ENEMY_BURST`: any DEFENSIVE option is a candidate on an enemy burst, without the HP or time-to-die check that `HP_CRASH` has). Every tank build with Ultimate Defense in `emergency` reaches it. A general finding: the same check as `HP_CRASH` (time to die under `burstReflexTtd`), or leaving out skills that immobilise, needs a guard run on the knights.
- **Sanctuary Chorus comes late.** Its 15 s of P. Def / M. Def +30% is worth most before the HP is low, but in a PvP the `emergency` list is cast by the HP-crash reflex, which is shared code. Not tried.
- **The Soul Hounds (0 to 3%), Hell Knight, Doom Oracle**: out-ranged or out-statted. `000615-BattleBard-vs-DoomOracle` (batch 1): a Prophecy of Doom every 2 s and a Dryad Root, dead at 32 s without reaching it. It has no gap closer, dash or ranged attack worth the name (its bow swap hits an archer for a third of what it takes).
- **Songs run out in the longest fights** (3 min with Virtuoso): recast at 2.5 s each, mostly against tanks (0.3 to 0.4 casts a battle each). The fights they are cast in are the long losing ones.
- **The Sword Muse** (the support build of the same class, weight 0) keeps the old attack list. It is a buffer opponent in every other build's runs, so changing it moves their numbers; left for its own pass.

## 7. Files and runs

- `dist/game/data/FakePlayerPvp.xml`: the Battle Bard build (attack, buff, emergency, perform, peel, combo).
- Runs in `../build/server/game/log/simulation-matrix/`: `matrix-20261113-144715` (baseline), `matrix-20261113-145947` (batch 1), `matrix-20261113-151139` (batch 2, the "after"), `matrix-20261113-152231` (batch 3, not kept).
