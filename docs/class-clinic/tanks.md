# Class clinic: the tanks (Phoenix Knight, Hell Knight, Eva's Templar, Shillien Templar)

Taken through `docs/class-clinic.md` on 2026-11-14. Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds. The four builds share the role TANK, the TANK buff list, the shield toggles and Ultimate Defense, so they were diagnosed together; each has a kit of its own (Shield Stun and Shackle for the knights, Shield Bash and Arrest for the Templars, Insane Crusher for the Hell Knight, Sting and Touch of Death for the Shillien Templar).

The four ran in one matrix from batch 1 on (`class_clinic.py run "Phoenix Knight,Hell Knight,Eva's Templar,Shillien Templar"`): a battle's fighters come from the seed and the pair, not from the focus, so each tank meets the same fighters as in its own run, and the long healer fights of the four overlap (4,500 battles in about 20 minutes, against about 50 for four runs). The six pairs of two tanks are played once and count for both, with both sides changed.

## 1. The class

From `skill_cards.py` and the builds in `dist/game/data/FakePlayerPvp.xml`:

- Damage: Shield Strike (power 4,640, 6 s reuse) for all four; Tribunal (Phoenix Knight, Eva's Templar) and Judgment (Hell Knight, Shillien Templar), power 1,841 / 2,104 with a 30 s debuff that lowers the target's defence against swords by 20%; Insane Crusher (power 8,409 around it, once Seed of Revenge has stacked); Sting (1,218 and a bleed). Their normal attack is half or more of their damage.
- Control: Shield Stun (9 s, knights), Shield Bash (2 s and a target cancel, Templars), Shackle (root, reach 600, knights), Arrest (root, reach 150, Templars). At level 85 they land 15 to 25% on a same-level target, 50% for Shackle and Hamstring.
- Defence: Ultimate Defense (P. Def +3,600, M. Def +3,240 for 30 s, 15 min reuse, **it roots its caster**), Angelic Icon (at 30% HP, Phoenix Knight), Anti Magic Armor, the mirrors, Deflect Arrow (on before the fight), Shield Fortress.
- Not usable by a fake player: the Templars' cubics (`SummonCubic` works on a player only).

Retail play: the web search found no High Five PvP guide (Interlude guides, Freya-era skill tables on linedia.ru). One Eva's Templar guide from April 2012 (High Five time on Russian servers; it doesn't name the chronicle), on the shock-world.org forum ("Гайд по Temple Knight/Eva's Templar", Olympiad and PvP sections), says:

- **No Ultimate Defense at the start of a fight**: "while you have CP your heal cubic sleeps". The exception is an enemy under short burst buffs that strips its CP fast (a Destroyer under Frenzy and Zealot): stand in Ultimate Defense while it is on you, take it off and walk away when it steps back, and so outlast its buffs. Take Ultimate Defense off at once when it starts a buff steal.
- **Shield Bash on cooldown from the first second**, against everyone: "the stun is weak but it knocks the target off reliably". Its Chance route is the first enchant of the guide; Arrest, Entangle and Tribunal on anything *but* Chance.
- **Its sustain is the Life Cubic** (heals HP once CP is gone), Touch of Life and **Touch of Eva** (50% HP, 50 HP a second and +30% heals received for 60 s, after Eva's Will has stacked): "use Touch of Life as soon as it is up in a long fight (tanks, daggers)". The Storm Cubic hits a mage for 300 to 400, often.
- It kills with its normal attack: "almost no attack skills". Against daggers, which it misses, Tribunal and Power Strike; a bow and Power Shot against one that runs.
- Against a mage: start as close as it can, Shield Bash on cooldown, Anti Magic Armor when the mage casts Enlightenment, then just hit.
- Against a Doombringer: stop hitting while Eye for Eye is on (10 s).

The kit stays the yardstick for the other three.

## 2. Baselines

| Build | Run | Win % | tank | melee | dagger | archer | nuker | healer | buffer |
|---|---|---|---|---|---|---|---|---|---|
| Phoenix Knight | `matrix-20261114-073052` | 49.8% | 64 | 60 | 61 | 24 | 23 | 73 | 64 |
| Hell Knight | `matrix-20261114-074359` | 57.4% | 99 | 74 | 59 | 13 | 14 | 97 | 80 |
| Eva's Templar | `matrix-20261114-075604` | 26.3% | 27 | 25 | 40 | 17 | 19 | 31 | 38 |
| Shillien Templar | `matrix-20261114-080925` | 29.1% | 14 | 24 | 42 | 15 | 19 | 56 | 56 |

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | Ultimate Defense at full HP, then 30 s rooted | `001116-PhoenixKnight-vs-Trickster` (baseline): `REFLEX ENEMY_BURST;Ultimate Defense` at 2.1 s, HP 100%, the Trickster shoots it for 119 s. 209 of the Phoenix Knight's casts came from the burst reflex. The Battle Bard had the same (`docs/class-clinic/battle-bard.md`) | `Reflexes`, `ENEMY_BURST`: any DEFENSIVE option, whatever its HP | General AI (reaches the builds with an immobilising emergency skill: the four tanks and the Sword Muse) | A skill that roots its caster (`ImmobileBuff`) only when it is about to die (`Reflexes.rootsCaster`). Batch 1 |
| 2 | Nothing to do once in Ultimate Defense | Enemies hold off a player in Ultimate Defense (`Defense`) and wait out the 30 s; an archer shoots on from out of reach | A player can click the buff away; the fake player couldn't | Class AI | `dropImmobileDefense` (both brains): after 3 s it takes off its immobilising emergency buff when its target is out of its reach and it would live 10 s more at the damage it takes. Batch 1 (693 drops in its run) |
| 3 | Skills that pay less than the normal attack | Power Strike 529 to 663 per cast second (Shield Strike 1,021 to 1,209); Drain Health 11 to 43; the Shillien Templar's Lightning Strike 65, Freezing Strike 28, Life Leech 25 (3 s magic casts, 26 to 51% aborted); Divine Strike (undead only) | Build data | Build data | Out. Batch 1 |
| 4 | Its own best skills not in the build or not in its attack list | Eva's Templar never had Tribunal (learned); the Shillien Templar's Judgment was a debuff behind six others | Build data | Build data | Tribunal and Judgment in `attack` after Shield Strike. Batch 1 |
| 5 | Debuffs that land 9 to 15% | Shield Slam 11 to 15%, Horror 9%, Hex 11%, Power Break 12%, Poison 9%, Entangle 15% (and 38% aborted) | Build data | Build data | Out of `debuff` and the combos. Batch 1 |
| 6 | Long self-heals interrupted | Divine Heal 49%, Divine Blessing 58%, Elemental Heal 45% aborted (74 to 84% against archers) | Build data | Build data | Out in batch 1; back in batch 2 under a rule (fault 8) |
| 7 | Own buffs missing | Combat Aura (P. Atk, Atk. Spd +5%) and Deflect Magic learned and unused by all four | Build data | Build data | In `buff` (lasting: on before the fight). Batch 1 |
| 8 | Without a heal, a healer wears Eva's Templar down | Batch 1: Eva's Templar against healers 31% → 14% (clearly worse), killed in 12 of 30 fights against the Cardinal (`000081-EvasTemplar-vs-Cardinal`: from 99% to 0% over 130 s, 200 to 1,100 a hit from the Cardinal's mace) | Its heal gone, and `quickHeals` leaves long heals aside with a melee on it | Class AI | `selfHeals` (both brains): a tank keeps its long heals with an enemy on it while the damage it takes over the heal's cast time is under 6% of its max HP. Heals back in the Phoenix Knight, Hell Knight and Eva's Templar builds. Batch 2 |
| 9 | A root on an archer doesn't win | Knights against archers (baseline): won 27% of the fights Shackle landed in, 21% of the others. `000013-PhoenixKnight-vs-MoonlightSentinel`: Shackle lands for 5 s at 563, the knight swaps back from its bow and arrives as it ends, opens with Shield Strike and the archer steps away. The Templars' Arrest reaches 150: 0.3 casts a battle against archers | The combos open with Shield Stun in reach; nothing ties the root to the stun | Build data, with a new combo step attribute | Combo "Root and stun" first in the knights' builds: Shackle with `rangedTarget="true"` (new: only against an archer or mage by class), then Shield Stun, then their hits. Batch 2 |
| 10 | Root not enchanted on poor or low-level Templars | 76 to 82% of the knights had Shackle +Chance at 85, 69 to 76% of the Templars Arrest; 55 Shillien Templars had Shield Bash enchanted and Arrest not | Arrest was second in the `enchant` list; the first skill of the list gets two thirds of the way before the next one gets an attempt | Build data | The root's Chance route first in every tank's `enchant` list. Batch 2 |

## 4. The batches

### Batch 1: faults 1 to 7 (`matrix-20261114-082301`, against each baseline)

| Build | Before | After | Difference (95%) | Clearly better against | Clearly worse against |
|---|---|---|---|---|---|
| Phoenix Knight | 49.8% | 71.0% | +21.2 (+17.3 to +25.0) | melee, archer (24 → 78), nuker (23 → 61), buffer | - |
| Hell Knight | 57.4% | 72.5% | +15.1 (+11.3 to +18.9) | melee, archer (13 → 43), nuker (14 → 44) | - |
| Eva's Templar | 26.3% | 45.0% | +18.7 (+14.9 to +22.5) | melee, dagger, archer, nuker, buffer | tank (27 → 14), healer (31 → 14) |
| Shillien Templar | 29.1% | 59.9% | +30.8 (+26.9 to +34.6) | every kind | - |

Skill use moved as meant: Ultimate Defense from the burst reflex 209 → 11 casts (the rest from `HP_CRASH`, about to die); time disabled fell by a third to two thirds (Eva's Templar 11% → 4%); Tribunal 0 → 17.5 casts a battle for Eva's Templar (10,388 damage a battle). Eva's Templar "worse against tanks" is against the three other tanks, all of them stronger in the same run.

### Batch 2: long heals under low pressure, root then stun, not kept (`matrix-20261114-085814`, against batch 1)

| Build | Before | After | Difference (95%) | Clearly worse against |
|---|---|---|---|---|
| Phoenix Knight | 71.0% | 51.6% | -19.4 (-23.2 to -15.5) | tank, melee, archer, nuker, buffer |
| Hell Knight | 72.5% | 65.1% | -7.4 (-11.1 to -3.7) | melee, nuker |
| Eva's Templar | 45.0% | 39.7% | -5.4 (-9.4 to -1.4) | melee, nuker (better against tanks) |
| Shillien Templar | 59.9% | 61.3% | +1.4 (-2.6 to +5.3) | - (it only had the enchant order) |

The heals came back as 6.4 casts a battle for the Phoenix Knight, 63% of them aborted, and didn't win back the healer fights (Eva's Templar 14% → 14%): the `selfHeals` rule let them in far more often than meant. The root-then-stun combo left Shield Stun at 4.6 casts a battle: no sign it did anything. Both reverted. The root first in the `enchant` lists (fault 10, an earlier commit) stays: no measurable effect, the intended behaviour. Fault 8 stays open: Eva's Templar against healers is 14%.

### Passive trees leaning on defence and speed, not kept (`matrix-20261114-094718`, against `matrix-20261114-093250`)

The build attribute `weights` at ×1.25 on PDEF_PCT, MDEF_PCT, SHIELD_RATE_PCT, SHIELD_DEF_PCT, CRIT_DMG_TAKEN_RED_PCT, DEBUFF_RES_PCT and MOVE_SPEED_ADD for the four tanks. Both runs on the batch 1 code, in the short form (15 battles a pair, 150 s limit: 2,250 battles in about 11 minutes, against 4,500 in 20 to 30 for the full form; it shows a change of about 5 to 6 points overall, 12 to 20 per kind).

| Build | Before | After | Difference (95%) | Damage /s dealt | Damage /s taken |
|---|---|---|---|---|---|
| Phoenix Knight | 70.3% | 72.0% | +1.6 (-3.6 to +6.8) | 449 → 412 | 314 → 304 |
| Hell Knight | 73.2% | 69.1% | -4.1 (-9.3 to +1.1) | 393 → 369 | 285 → 287 |
| Eva's Templar | 50.4% | 51.1% | +0.7 (-5.0 to +6.4) | 453 → 413 | 312 → 309 |
| Shillien Templar | 60.1% | 60.0% | -0.1 (-5.7 to +5.5) | 479 → 434 | 278 → 276 |

No clear change for any of them, overall or against any kind. The points the trees moved into defence came out of damage: dealt fell 5 to 10%, taken 0 to 3%. Every tank did worse against nukers (−7 to −11, none clearly). Taken out.

## 5. Not fixed here

- **Archers and nukers outrange them.** Speed 175 against 222 for an archer, no gap closer, a bow that hits for a third of what it takes. What the root and the stun can win is measured in batch 2.
- **Sword Muse** (the support build of the Swordsinger, role TANK) still has Ultimate Defense in `emergency`: it now reaches the new reflex rule. Its build is left for its own pass, as in the Battle Bard's case.
- **Seed of Revenge / Spirit of Phoenix / Eva's Will / Pain of Shilen** and the Icons they lead to (Flame Icon, Spirit of Shilen, Touch of Eva) are not tried.

## 6. Files and runs

- `java/org/l2jmobius/gameserver/ai/fight/Reflexes.java` (`rootsCaster`), `FakePlayerPvpAI.java` and `FakePlayerPvpAIExperimental.java` (`dropImmobileDefense`, `selfHeals`, the `rangedTarget` step), `FakePlayerPvpCombo.java`, `FakePlayerPvpData.java`, `xsd/FakePlayerPvp.xsd`.
- `dist/game/data/FakePlayerPvp.xml`: the four tank builds.
- `tools/simulation/class_clinic.py`: `run` takes several builds separated by commas and writes a `clinic-<build>.md` for each.
- Runs in `../build/server/game/log/simulation-matrix/`: the four baselines above, `matrix-20261114-082301` (batch 1).
