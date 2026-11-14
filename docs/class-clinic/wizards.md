# Class clinic: the wizards (Archmage, Mystic Muse, Storm Screamer)

A case of `docs/class-clinic.md`, 2026-11-14. Level 85, brain A, seed 4040, 30 battles against each of the 39 other builds (1,170 a run). The three robe nukers of the Wizard line share their AI (role MAGE), their kit apart from the element (Aura Blast, Aura Cannon, Aura Flash, Arcane Shield, Enlightenment, the 5 minute "Elemental" nukes, the vortexes, Surrender) and the same faults, so they were taken through the clinic together, like the daggers.

**Result:**

| Build | Before | After | Difference (95%) |
|---|---|---|---|
| Archmage | 63.2% | 77.6% (batches 1 and 2) | +14.4 |
| Mystic Muse | 54.1% | 76.1% (batch 1) | +22.0 (+18.2 to +25.8), **better** |
| Storm Screamer | 56.2% | 84.7% (batch 1) | +28.5 (+25.0 to +32.1), **better** |

## 1. The class

From `skill_cards.py` for the three builds and their `<build>` in `dist/game/data/FakePlayerPvp.xml`:

- Every cast takes about 0.55 s at level 85 (buffs and Enlightenment reach the cast speed cap), so what a skill is worth is its damage per cast and its reuse, not its cast time. The quick nukes (Prominence, Hydro Blast, Hurricane, Death Spike: power 108) have a 1.2 s reuse: something else goes between two of them.
- The 5 minute nukes: Elemental Assault / Symphony / Storm (power 200, 3,800 to 4,500 a hit), Inferno / Blizzard / Demon Wind (150 and an element, plus a damage over time, a slow or a heal block) and Aura Symphony (150, no element: less than a Prominence with Seed of Fire on). Separate reuses.
- **Aura Blast** (level 81, power 199, 141 against a player, reach 900, 15 s reuse): the wizards' second best repeatable nuke. **None of the three builds had it** (`skill_cards.py`, "LEARNED BUT NOT IN THE BUILD"). Aura Cannon (power 302, reach 150) is its short twin, used when a melee is on the mage.
- Surrender To Fire / Water / Wind (−20 resistance to the element, 30 s), Slow, Silence (Storm Screamer), Curse Fear: learned at 74, they land 10 to 30% on a level 85 target.
- Arcane Shield (5 min): for 10 s a hit costs 30% of its damage in MP and no HP, until MP runs out. Self Heal (power 42), Heal (107).

Retail High Five play: web searches found High Five skill lists (Aura Blast at 81 and Aura Cannon at 82 or 83 depending on the database: [lineage2wiki.org](https://lineage2wiki.org/hi-five/class/mystic_muse/), [la2.infometria.ru](https://la2.infometria.ru/index.php?chr=hf&d=class_skills&id=94&lang=en), [L2DB](https://l2db.info/high-five/classes?class=132&level=83&race=5)) but no High Five wizard PvP guide; the guides found are Interlude or Essence ones. What holds across chronicles: a nuker wins by bursting first (its big cooldowns at the start), keeps its quick nuke going, and keeps its 10% debuffs for when they matter. That is the yardstick here.

## 2. Baselines

| Against | Archmage (`matrix-20261113-233705`) | Mystic Muse (`-234453`) | Storm Screamer (`-235241`) |
|---|---|---|---|
| **tank** | 82.5% | 75.0% | 71.7% |
| **melee** | 65.0% | 53.9% | 46.9% |
| **dagger** | 67.8% | 52.2% | 31.7% |
| **archer** | 33.3% | 25.0% | 19.2% |
| **nuker** | 57.5% | 48.3% | 60.4% |
| **healer** | 43.9% | 43.9% | 100.0% |
| **buffer** | 85.3% | 77.3% | 77.3% |
| **all** | 63.2% | 54.1% | 56.2% |

Against archers all three lose in 8 to 12 s and hit first only 25% of the time: the fight is a race the archer wins (about 2,000 damage a second taken against 900 to 1,300 dealt).

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | Its second best nuke is missing | Aura Blast in no list of the three builds | Build data | Build data | In the attack list after the 5 minute nukes, and in the opening combo. Batch 1 |
| 2 | Every fight opens with a debuff that lands 30% of the time | Openings "Surrender To Fire > Elemental Assault > Prominence" in 95 to 100% of the fights; Surrender 4.3 casts a battle (Archmage), 30% landed, 26.5 tries a battle against healers. Storm Screamer: Silence first on anyone (12% landed), and it walked from 986 to 610 to cast it on a Ghost Sentinel (`battles/001028-StormScreamer-vs-GhostSentinel`) | The required first step of the "Surrender burst" / "Silence and burst" combos | Build data | "Opening burst": the Elemental nuke, Aura Blast, the second 5 minute nuke, then the quick nuke. Surrender stays in the debuff list for the scorer; Silence only on a target that fights with magic (`mageTarget`). Batch 1 |
| 3 | It casts a 10% fear with a melee on it | Curse Fear in `peel`: 1.5 to 1.8 casts a battle, 9 to 11% landed, median gap 49 to 74. `battles/001119-Archmage-vs-Maestro`: three Curse Fears at gap 0, all resisted, while Aura Cannon hits for 3,500 | Peel list | Build data | Out of `peel`. Batch 1 |
| 4 | Ice Bolt, a level 20 skill | Lands its slow 11%, 85 damage a cast; the Archmage cast it 4.6 times a battle against tanks | Attack list | Build data | Out. Batch 1 |
| 5 | Self Heal in a race | 439 HP for a cast in `battles/001028-StormScreamer-vs-GhostSentinel` (a nuke does 1,100 to 1,600); 1 to 2.4 casts a battle | Heal list | Build data | Out of `heal` (Heal, about three times as much, stays). Batch 1 |
| 6 | Arcane Shield does nothing for a fake player | Cast 0.3 times a battle (as the HP crash reflex), and the HP still falls at the same rate after it (`battles/001028-StormScreamer-vs-GhostSentinel`: 40% → 0% in 4 s under the shield) | Only `PlayerStatus` reads `manaShield`; a fake player is an NPC (`NpcStatus`) | Not AI: the game's mechanics for fake players | `NpcStatus.reduceHp` applies it like a player's. Batch 2 |

Not faults:

- Rain of Fire, Aqua Splash and Tempest lead the attack lists and are never cast in a duel: `isWorthCasting` skips an area skill with a short reuse on one target. They are for team fights.
- Frost Bolt (Mystic Muse, 5.5 casts a battle at 660 a cast) fills the reuse of Hydro Blast; the other fillers are no better.
- Aura Symphony hits for less than a Prominence (no element): kept in the list, left out of the opening.

## 4. The batches

### Batch 1: Aura Blast, an opening burst, no Surrender/Silence opener, no Curse Fear, Ice Bolt or Self Heal

Data only, the same for the three builds (Silence on a caster only for the Storm Screamer).

| Against | Archmage (`matrix-20261113-235848`) | Mystic Muse (`matrix-20261114-000605`) | Storm Screamer (`matrix-20261114-001338`) |
|---|---|---|---|
| **tank** | 82.5 → 79.2 | 75.0 → 78.3 | 71.7 → 82.5 **better** (+10.8, +0.3 to +21.4) |
| **melee** | 65.0 → 84.6 **better** (+19.6, +13.4 to +25.8) | 53.9 → 81.4 **better** (+27.5, +21.0 to +34.0) | 46.9 → 87.1 **better** (+40.1, +33.9 to +46.4) |
| **dagger** | 67.8 → 83.3 **better** (+15.6, +3.2 to +27.9) | 52.2 → 83.3 **better** (+31.1, +18.2 to +44.0) | 31.7 → 87.8 **better** (+56.1, +44.4 to +67.9) |
| **archer** | 33.3 → 56.7 **better** (+23.3, +11.1 to +35.6) | 25.0 → 53.3 **better** (+28.3, +16.5 to +40.2) | 19.2 → 62.5 **better** (+43.3, +32.2 to +54.5) |
| **nuker** | 57.5 → 65.0 | 48.3 → 66.2 **better** (+17.9, +9.2 to +26.6) | 60.4 → 78.3 **better** (+17.9, +9.8 to +26.0) |
| **healer** | 43.9 → 56.7 | 43.9 → 65.6 **better** (+21.7, +7.5 to +35.9) | 100 → 100 |
| **buffer** | 85.3 → 93.3 **better** (+8.0, +1.1 to +14.9) | 77.3 → 97.3 **better** (+20.0, +12.8 to +27.2) | 77.3 → 98.0 **better** (+20.7, +13.6 to +27.7) |
| **all** | 63.2 → 76.0 **better** (+12.8, +9.1 to +16.5) | 54.1 → 76.1 **better** (+22.0, +18.2 to +25.8) | 56.2 → 84.7 **better** (+28.5, +25.0 to +32.1) |

Skill use moved as meant: Aura Blast 0 → 3.7 casts a battle (Archmage, 2,500 a cast, 9,400 damage a battle; Storm Screamer 1.8 casts), Surrender 4.3 → 0.4 (Archmage) and 4.4 → 0 (Mystic Muse), Silence 2.3 → 0.6, Curse Fear, Ice Bolt and Self Heal gone, Inferno, Blizzard and Demon Wind cast more (they are in the opening now). Damage dealt a second went up by a quarter to a half (Archmage 1,050 → 1,276, Storm Screamer 953 → 1,503), damage taken went down (the fights end sooner). Against melee and daggers the gain is mostly time: no 0.55 s cast thrown away on a fear or a silence with the fighter on it.

The Archmage's "Slow and burn" combo (chance 50) now runs where "Surrender burst" used to come first: Slow 0.05 → 2 casts a battle, 33% landed. Not changed in this batch.

### Batch 2: Arcane Shield works for a fake player (`matrix-20261114-002158`, Archmage, against batch 1)

`NpcStatus.reduceHp` now does what `PlayerStatus` does under the `manaShield` stat: a hit costs 30% of its damage in MP and no HP, and when MP runs out the shield ends and the rest goes to HP. Only the three wizards have the skill (1556); fake players don't pay MP for skills, so their MP lasts the 10 s.

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 79.2% | 85.0% | +5.8 (−3.8 to +15.5) | no clear change |
| **melee** | 84.6% | 87.5% | +2.9 (−2.1 to +8.0) | no clear change |
| **dagger** | 83.3% | 91.1% | +7.8 (−1.9 to +17.5) | no clear change |
| **archer** | 56.7% | 55.0% | −1.7 (−14.2 to +10.9) | no clear change |
| **nuker** | 65.0% | 63.3% | −1.7 (−10.2 to +6.9) | no clear change |
| **healer** | 56.7% | 53.3% | −3.3 (−17.9 to +11.2) | no clear change |
| **buffer** | 93.3% | 95.3% | +2.0 (−3.2 to +7.2) | no clear change |
| **all** | 76.0% | 77.6% | +1.6 (−1.8 to +5.0) | no clear change |

Its own measure moved: the fights it casts Arcane Shield in are won **48%** of the time (26% in batch 1, 15% in the baseline). It is cast 0.2 times a battle, as the HP crash reflex, at about 10% HP (`battles/000012-Archmage-vs-Sagittarius`: shield at 8.3 s, then a 1,689 hit moves its HP by 1%): too late to turn many fights. Kept, as a correction of the game's mechanics with no kind clearly worse (the exception in `docs/class-clinic.md`, step 5). The same fight shows Heal restoring 479 HP for a cast.

### Batch 3: Arcane Shield at the start, no heals, no Slow combo (`matrix-20261114-003156`, Archmage, against batch 2)

Data only: Arcane Shield in the `buff` list after Enlightenment (both are cast as the fight starts, about 1.5 s in, at 985 from the target), the `heal` list taken out (Heal 1.6 casts a battle at 479 HP), the Archmage's "Slow and burn" combo taken out.

| Against | Before | After | Difference (95%) | Verdict |
|---|---|---|---|---|
| **tank** | 85.0% | 90.0% | +5.0 (−3.3 to +13.3) | no clear change |
| **melee** | 87.5% | 92.4% | +4.9 (+0.5 to +9.2) | **better** |
| **dagger** | 91.1% | 95.6% | +4.4 (−2.8 to +11.7) | no clear change |
| **archer** | 55.0% | 82.5% | +27.5 (+16.3 to +38.7) | **better** |
| **nuker** | 63.3% | 72.1% | +8.8 (+0.4 to +17.1) | **better** |
| **healer** | 53.3% | 62.8% | +9.4 (−4.9 to +23.8) | no clear change |
| **buffer** | 95.3% | 96.0% | +0.7 (−3.9 to +5.3) | no clear change |
| **all** | 77.6% | 85.4% | +7.8 (+4.6 to +10.9) | **better** |

Arcane Shield 0.2 → 1.04 casts a battle, none aborted (8% before); Slow 2.0 → 0.05; Heal 1.6 → 0. The archer race is the gain: the archer's first 10 s of arrows cost MP instead of HP. This is what batch 2 was for: without batch 2 the shield in the buff list would do nothing.

Batches 1 to 3 against the Archmage's baseline: 63.2% → 85.4% (+22.2, +18.8 to +25.6), better against every kind but tanks (82.5 → 90.0, no clear change): melee 65 → 92, dagger 68 → 96, archer 33 → 83, nuker 58 → 72, healer 44 → 63, buffer 85 → 96. Its worst opponents now are the Storm Screamer (27%) and the Doom Oracle (43%).

FINAL

## 5. Not fixed here

- **PvP power.** A fake player's skill hits another fake player, and a real player, with its base power, not its PvP power: `Formulas` takes `isPvP` and `isPvE` from `attacker.isPlayable()`, and a fake player is an NPC. Aura Blast hits for power 199 instead of 141, Aura Cannon 302 instead of 178. It reaches every skill with a PvP power, every class: a general finding (`docs/class-clinic.md`), not tuned around here.
- **Healers** (Archmage 57%, Mystic Muse 66% after batch 1): most fights go to the time limit with the healer healing back 95%. The Storm Screamer wins all of them (Demon Wind blocks heals for 2 minutes, Silence on a caster). The Archmage and Mystic Muse have no heal block or silence in their kit.
- **Balance.** After batch 1 the three are at 76 to 85%, among the strongest builds. That is the AI using the kit; whether robe nukers should be this strong is for `docs/class-balance-85.md`.

## 6. What changed

- `dist/game/data/FakePlayerPvp.xml`, the three wizards: Aura Blast in the attack list; "Opening burst" (Archmage, Mystic Muse) and "Silence and burst" (Storm Screamer, Silence on a caster only) open with the Elemental nuke, Aura Blast and the second 5 minute nuke; no Ice Bolt, no Curse Fear in `peel`, no Self Heal in `heal`.
- `model/actor/status/NpcStatus.java`: Arcane Shield (the `manaShield` stat) for a fake player, as `PlayerStatus` does for a player. Batch 2.

## 7. Next for this class

- Soultaker and Dominator, the other two nukers not yet through the clinic.
- The Archmage's Slow (2 casts a battle at 33%) and Count of Fire / Throne of Ice / Throne of Wind (75 s, 25 to 30% landed).
- Star Fall / Meteor (5 min, power 117 and a damage over time) and Diamond Dust (Mystic Muse, an aura slow) are learned and not in the builds.
