# Class clinic: Doom Oracle (the Prophet line)

Taken through `docs/class-clinic.md` on 2026-11-13. Level 85, brain A, seed 4040, 1,170 battles a run.

A custom class: its prophecies (275xx, `dist/game/config/Custom/OraclesWarchiefs.ini`) have no retail counterpart, so there was no retail play to hold the logs against; the yardstick is the prophecies' own rules, played the way a player who knows them would.

## 1. The class

From `skill_cards.py "Doom Oracle"` and `Prophecies.java`. A prophecy does nothing when it lands and comes true when its time runs out (5 s; Salvation 6 s). Every one that comes true gives a Foresight (+3% M. Atk each, up to 5, 30 s).

- **Prophecy of Doom** (cast 0.55 s at its cast speed, reuse 2 s with Inevitable Doom): one stack of Doom Strike (power 140 at level 5, holy, no shots). Cast again on its target before it comes true it adds a stack (up to 5) and starts the 5 s again; full, it keeps its time. x1.5 if the target is still within 300 of where it stood when the Doom was last cast. Can't be resisted. Its damage: 90% of the Oracle's.
- **Prophecy of Salvation** (1 s, reuse 6 s, 6 s): heals back all the damage taken while it was on (`SalvationReturn = 1`). Cast again while one is on, the old one fizzles.
- **Prophecy of Ruin** (15 s reuse): a 3 s stun if the target cast a skill meanwhile, a silence if not. **Prophecy of Reckoning** (15 s reuse): 40% of the damage the target deals in 5 s comes back to it (at most a quarter of its max HP).
- **Fulfilment** (5 Foresight, 20 s reuse): every prophecy of the Oracle comes true now, x1.5. **Unwritten** (5 min): a Doom on every enemy within 600 and a Salvation on every party member, itself included. **Glimpse** (toggle): a hit of 20% of its HP or more makes it step back 300.
- Learned and not in the build: Turn to Stone (8 s of stone, invulnerable, 10 min), Mystic Immunity, Enlightenment, Spell Turning, Arcane Wisdom (−10% cast speed: no use to a fake player, which casts for free), Sleep (10 s cast), Mana Burn's cousins.

How a player who knows it plays it (the "difficult learning curve"): keep Salvation up all fight long, so every hit comes back 6 s later; stack Doom on its 2 s reuse and fill the gaps with Ruin, Reckoning and Salvation, which also bring the Foresight for Fulfilment; Fulfil right after a Doom stack lands (the target can't have moved: x1.5 for staying put on every stack) or when a big Salvation would come too late; Reckoning on whoever is hitting it hard.

## 2. Baseline (`matrix-20261113-085230`)

| Against | tank | melee | dagger | archer | nuker | healer | buffer | **all** |
|---|---|---|---|---|---|---|---|---|
| Win % | 90.8 | 55.3 | 66.7 | 19.2 | 50.0 | 65.0 | 83.3 | **59.4 (57-62)** |

| Skill | Casts a battle | Note |
|---|---|---|
| Prophecy of Doom | 20.2 | 92% of its damage (Doom Strike) |
| Dryad Root | 9.5 | lands 12% (26% on daggers, 10% on healers with 23 tries a battle) |
| Wind Strike, Ice Bolt | 8.8, 7.0 | 177 and 106 a cast: 7% of its damage |
| **Mana Burn** | **7.3** | **no HP damage at all** |
| Prophecy of Salvation | 6.0 | only below 85% HP |
| Prophecy of Ruin | 5.0 | lands 41% |
| Fulfilment | 1.6 | won 80% of the fights it was cast in, 24% of the others |
| **Prophecy of Reckoning** | **1.5** | scored 0.18 against 2.0 for a nuke: the scorer saw no damage |
| Unwritten | 0.5 | won 39% of its fights |

## 3. Diagnosis

| # | Fault | Evidence | Cause | Kind | Fix |
|---|---|---|---|---|---|
| 1 | Mana Burn on every opening, 7.3 casts a battle for no damage | Opening "Ruin > Doom > Mana Burn" in 81-88% of fights against every kind; damage per cast "-" | `MagicalDamageMp` burns MP only; fake players cast for free, and Mind Over Matter takes only part of a hit from MP. The scorer estimated it as a power 162 nuke, the best of the list | Build data | Out of `attack`. Batch 1 |
| 2 | Unwritten wipes the Salvation on it | `000766-Trickster-vs-DoomOracle`: Salvation at 10.5 s (80% HP), Unwritten at 14.6 s (30%), dead at 16.6 s; the first Salvation would have come true at about 16.3 s with some 9,000 HP | Unwritten casts a new Salvation on the Oracle; `Prophecies.begin` stops the old one without resolving it. The reflex (HP crash) cast it from the emergency list, first in it | Class AI | Never while a Salvation of its own is on it (`isWorthSelfCast` and, for reflexes, `isWorthReflex`); Salvation before Unwritten in `emergency`. Batch 1 |
| 3 | Salvation only below 85% HP | 6 casts a battle; against archers the first one went up after 3 hits | `SALVATION_PVP_HP` | Class AI | Kept up against a player that targets it within 1,200, whatever its HP: a whole fight's hits come back (and each one that comes true is a Foresight). Batch 1 |
| 4 | Reckoning almost never cast | 1.5 casts a battle; `scores Prophecy of Reckoning value=0.18` against 2.47 for Mana Burn | Its damage is the target's own: the estimate saw 0 | Class AI (scorer input) | `FakePlayerPvpOraclesWarchiefs.estimateDamage`: 40% of the damage it takes a second x 5 s, capped at a quarter of the target's HP, x 0.45 (what lands). Batch 1 |
| 5 | Doom's value is only its place in the list | Doom and Mana Burn both `damage=2.00`; Wind Strike 1.41-1.74 | A prophecy has no power of its own | Class AI (scorer input) | The same function: one stack of Doom Strike without shots, x1.2 (x1.5 on a target that can't move). Batch 1 |
| 6 | Fulfilment on a Doom the target has walked away from | Fulfilment checked only the stacks | `isFulfilmentWorth` | Class AI | A Doom of 3-4 stacks only while the target is still where the last stack found it (`Prophecies.isDoomStill`; right after a stack, always); and Fulfilment on its own Salvation below 45% HP when it heals a quarter of its HP or more (`Prophecies.getSalvationDamage`). Batch 1 |
| 7 | Dryad Root, 9.5 casts a battle, lands 12% | Casts by decision: peel 6,857, "Rooted doom" combo 5,906, reflex on an enemy heal 814 | In `debuff`, `peel` and a combo at 50% | Build data | Only in `peel`. Batches 2 and 2b |

## 4. The batches

### Batch 1: Salvation kept up, the prophecies' damage seen, no Mana Burn (`matrix-20261113-090629`): kept

Faults 1 to 6. **59.4% → 70.9% (+11.5, 95% +7.7 to +15.4)**; clearly better against melee (+10.0), daggers (+20.0), archers (+23.7), nukers (+9.2) and buffers (+9.7); no kind clearly worse. By opponent: Duelist 17 → 60, Archmage 30 → 63, Moonlight Sentinel 23 → 57, Storm Screamer 17 → 47. Mana Burn 7.3 → 0 casts, Salvation 6.0 → 11.0, Reckoning 1.5 → 3.5 (3% of its damage), Fulfilment 1.6 → 2.5, Unwritten 0.5 → 0.03 (a Salvation is nearly always on it now).

### Batch 2: no Dryad Root at all (`matrix-20261113-091903`): taken out

70.9% → 72.9% (+2.0, 95% −1.6 to +5.7), no clear change; **daggers 86.7% → 61.1% (−25.6, −37.8 to −13.3), clearly worse** (an enemy within 200 36% → 68% of the fight, damage taken 566 → 1,193 a second): a rooted dagger can't get behind it, and a root lands 25% on a dagger. Archers +12.1 and nukers +8.3, not clear.

### Batch 2b: Dryad Root only as a peel (`matrix-20261113-093018`): kept

Out of `debuff` and without the "Rooted doom" combo; still first in `peel`, so it is cast on what reaches it (the daggers above). 70.9% → 73.2% (+2.4, 95% −1.3 to +6.0), no kind clearly better or worse; daggers 86.7% → 87.8%, archers +4.6, healers +7.2. Root 10.9 → 6.3 casts a battle (its own measure moved as meant, no kind worse: kept, by the rule in `docs/class-clinic.md`).

### Batch 3: Turn to Stone over a big Salvation (`matrix-20261113-094143`): taken out

The idea: 8 s of stone (invulnerable) below 30% HP while a Salvation that heals 30% of its HP is on it; its prophecies keep running, so the Salvation comes true while nothing gets through. 73.2% → 72.0% (−1.2, 95% −4.9 to +2.4), no clear change (buffers +6.0). Cast 0.13 times a battle, and too late: its cast takes 0.8 s and the reflex reached it at 5% HP (`battles/000016-Duelist-vs-DoomOracle`: cast at 14.4 s, dead at 14.6 s). Logged 100% aborted, like Spirit Trance (its own paralysis ends the cast). Code and data taken back out.

### Batch 4: no step back while a Doom is about to come true (`matrix-20261113-095406`): taken out

Measured first, with a script over the battle logs (the target's position when the last Doom stack landed against when Doom Strike hit, in `matrix-20261113-093018`): **81.5% of Doom Strikes got the x1.5 for a target that stayed put; 57-70% against melee, tanks and daggers**, 93-99% against archers, nukers and healers (who don't chase it). The Oracle kites (16-26% of a melee or tank fight on KITE_MOVING) and the chaser follows it out of the 300. The batch: `kiteStep` does nothing for an Oracle whose Doom on its target comes true within 1.5 s while the target still stands where the Doom found it (logged DOOM_HOLD, 2,631 times). 73.2% → 72.1% (−1.2, 95% −4.8 to +2.5), no clear change; daggers −8.9, tanks −4.2. The still share moved 81.5% → 82.5% only: a melee chasing it has already walked the 300 by the time the Doom is 1.5 s from coming true; holding longer means standing in its reach.
