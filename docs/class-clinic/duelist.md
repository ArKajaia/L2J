# Class clinic: Duelist (the Gladiator line)

Taken through `docs/class-clinic.md` on 2026-11-12. Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds (1,170 a run).

## 1. The class

From `skill_cards.py "Duelist"` and the build in `dist/game/data/FakePlayerPvp.xml`:

- A dual-sword warrior in heavy armour that deals its damage with **energy skills**, which spend charges (up to 8) and hit 20% harder for every charge it holds past the first: Triple Sonic Slash (4 charges, power 4,262, 5 s reuse), Double Sonic Slash (3, 3,196), Sonic Blaster (2, 1,827, **reaches 600**), Sonic Storm and Sonic Buster (area, 2, 457). Triple Slash (power 2,557) needs none.
- Charging: Sonic Focus (+1 charge, no damage), Maximum Focus Sonic (all 8 at once, 75 s reuse) and **Sonic Rage** (+1 charge *and* a hit, reach 600, no reuse, the same 0.5 s cast as Sonic Focus at the cast-speed cap).
- Self: Duelist Spirit, War Cry, Final Secret (+30% skill power for 30 s, 150 s reuse), Lionheart (resist stun, hold, sleep, paralysis), Battle Roar, Braveheart (+1,000 CP), Sonic Barrier (10 s invincible, 5 charges, **ends on any action except moving**), Sonic Move (+66 speed), Rush, Weapon Blockade (disarm 5 s, 2 charges).
- The energy skills hold their power in their `EnergyDamage` effect, not in the skill's `power`. That turns out to matter (section 3).

Retail play, from a Freya-era Duelist guide ([maxcheaters.com](https://maxcheaters.com/topic/117827-lineage-ii-gladiator-guidefreya/), a player guide, not an official source) and an Essence guide ([4gameforum.com](https://eu.4gameforum.com/threads/675773/), another chronicle; used only where it agrees): everything turns on charges. Fill them, then spend them on Triple Sonic Slash and Double Sonic Slash; Sonic Rage is "your main ranged damage skill" that charges while it hits from a safe distance; keep Maximum Focus Sonic for when a big skill needs it; Sonic Move to catch anyone; Lionheart before going in; Sonic Barrier to engage or disengage. Neither guide covers specific matchups.

## 2. Baseline (`matrix-20261112-214810`)

| Against | Battles | Win % (95%) | Fight s (won / lost) | Enemy HP left when it loses | Damage /s dealt / taken | Enemy within 200 | Waiting |
|---|---|---|---|---|---|---|---|
| **tank** | 120 | 38.3% (30-47) | 64 / 55 | 35% | 437 / 490 | 59% | 19% |
| **melee** | 330 | 44.7% (39-50) | 24 / 24 | 44% | 861 / 948 | 61% | 4% |
| **dagger** | 90 | 42.2% (33-53) | 28 / 27 | 40% | 586 / 1009 | 75% | 4% |
| **archer** | 120 | 41.7% (33-51) | 44 / 74 | 46% | 241 / 430 | 54% | 2% |
| **nuker** | 270 | 32.2% (27-38) | 24 / 24 | 62% | 688 / 937 | 46% | 3% |
| **healer** | 90 | 100.0% (96-100) | 46 / - | - | 1384 / 5 | 93% | 1% |
| **buffer** | 150 | 74.0% (66-80) | 33 / 48 | 38% | 717 / 425 | 62% | 9% |
| **all** | 1,170 | 48.7% (46-52) | 30 / 30 | 49% | 736 / 559 | 64% | 6% |

Its skills, casts per battle: **Sonic Focus 15.6** (22 against tanks), Double Sonic Slash 3.5, Triple Slash 3.3, Triple Sonic Slash 3.0, **Sonic Rage 1.7** (1,297 a cast), Rush 1.8. **Never cast: Sonic Blaster**, Sonic Storm, Sonic Buster, Power Smash, Power Strike; Weapon Blockade 0.01.

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | The scorer thinks its energy skills deal nothing | Triple Sonic Slash (5,704 damage per cast second, its best) was worth 0.6 of a Triple Slash (2,934) to the scorer; Sonic Blaster never cast | `estimateDamage` returns 0 for a skill with no `power`, and `SkillMeanings` doesn't call it a NUKE; the energy skills hold their power in `EnergyDamage`. Only their place in the list counted, and Triple Slash, listed after them, set the scale | General AI (reaches every energy skill: Duelist and Grand Khavatari) | `AbstractEffect.getPower()` (EnergyDamage returns its power), `SkillFactsReader` reads it, `estimateEnergyDamage` follows the effect's formula with the charges it holds. Batch 1 |
| 2 | It spends a third of a fight charging one point at a time | Sonic Focus 15.6 casts a battle, 0.5 s each. `battles/000407-Duelist-vs-Titan`: 11 Sonic Focus in a 24 s loss, standing in the Titan's reach. `000045-PhoenixKnight-vs-Duelist`: three Sonic Focus before every slash | Below 4 charges it always casts its `charge` list on itself | Class AI (by effect: an attack with a `FocusEnergy` self effect) | In a fight, unless Maximum Focus Sonic is ready, it charges with an attack that charges as it hits (Sonic Rage) when one reaches from where it stands. Batch 1 |
| 3 | The attack list leads with skills it can't or shouldn't cast | `7,9,345,261,5,1,6,255,3`: two area skills (power 457, need two enemies), Sonic Rage, then the slashes; Power Smash and Power Strike need a sword or blunt | Build data | Build data | `261,5,6,1,345,7,9`: by damage per cast second, Sonic Blaster before Triple Slash for its reach. Batch 1 |
| 4 | It waits out Ultimate Defense with no charges, then charges in front of the tank | 19% of a tank fight on HOLD_OFF; `000045`: 20 s waiting at 1 charge, then 3 Sonic Focus before each slash | `holdOff` stands still | Class AI (any build with charges) | While it holds off it charges up. Batch 1 |
| 5 | It walks after a kiting archer for up to 15 s without a cast | `000716-MoonlightSentinel-vs-Duelist`: "APPROACH COMBO Triple Sonic Slash" from 12 to 20 s and from 23 to 38 s; Sonic Blaster and Sonic Rage reach 600 | `playCombo` walks to a melee step until the combo's 15 s timeout | General AI (every melee combo) | Batch 2 |
| 6 | Sonic Barrier is broken by its next action | `000407`: cast at 7% HP at 22.1 s, Sonic Focus at 22.7 s ended it, dead at 23.7 s. Cast in 413 fights, 16.5% won | The skill ends on any action except moving; nothing stops the AI from acting | General AI (the Painkiller finding in `docs/class-clinic.md`) | Not fixed here (section 5) |

## 4. The batches

Each against the run before it, same seed. "Clearly" means the 95% interval of the difference doesn't include zero.

### Batch 1: energy skills valued, Sonic Rage charges, list, charge while holding off (`matrix-20261112-215949`)

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 38.3% | 52.5% | +14.2 (+1.7 to +26.6) | **better** |
| **melee** | 44.7% | 53.8% | +9.1 (+1.5 to +16.7) | **better** |
| **dagger** | 42.2% | 62.2% | +20.0 (+5.7 to +34.3) | **better** |
| **archer** | 41.7% | 91.7% | +50.0 (+39.9 to +60.1) | **better** |
| **nuker** | 32.2% | 57.0% | +24.8 (+16.7 to +32.9) | **better** |
| **healer** | 100.0% | 98.9% | -1.1 (-3.3 to +1.1) | no clear change |
| **buffer** | 74.0% | 87.3% | +13.3 (+4.5 to +22.1) | **better** |
| **all** | 48.7% | 66.7% | +18.0 (+14.1 to +22.0) | **better** |

Skill use moved as meant: Sonic Focus 15.6 → 0.7 casts a battle, Sonic Rage 1.7 → 12.9 (2,169 → 14,582 damage a battle), Sonic Blaster 0 → 0.5. Damage dealt 736 → 986 a second. Against archers Sonic Rage, which reaches 600, is what charges it now: the Moonlight Sentinel went from 10% to 87%.
