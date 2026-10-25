# Class balance at level 85: what the 1v1 matrix says

Analysis of the level 85 class-vs-class matrix (`//Simulate matrix`, see `tools/simulation/README.md`): why the weakest builds lose, why the top five win, and what to change. Weak builds get changes to their passive tree, gear and AI only; the top five also get skill nerfs.

## The data

| Run | Battles | What it gave |
|---|---|---|
| Full matrix `matrix-20261025-213156` | 14,060 (38 builds, 20 per pair) | Win rates, how each battle ended, HP left |
| Focused run 1 `matrix-20261025-223813` | 2,208 (24 builds, 8 per pair) | Full combat logs: stats at spawn, damage by skill, casts and aborts, crowd control, positions |
| Focused run 2 `matrix-20261025-224906` | 2,208 (same builds) | The same, plus each fighter's passive tree (keystones, stat totals) and gear |

The focused runs had the 11 weakest builds that aren't supports, the Hierophant, the top five, and a reference panel: Archmage, Duelist, Eva's Templar, Trickster, Mystic Muse, Judicator and Storm Screamer. Every fight is between two fake players with brain A, after issue #24 was fixed (both AIs think from the first second).

**How far to trust it.** These are fake players playing with this server's builds, buffs and passive trees, so the results measure the class *and* the AI. A matrix cell has 20 battles (about ±20 points), a focused-run cell 8. Overall ranks rest on 740 battles per class and are solid. At the time limit the side with more HP and CP left wins, which favours HP-heavy tanks; it decided 9% of the battles.

**Retail context.** High Five sources disagree, and the ones found are fan tier lists rather than official data. A 2025 High Five PvP overview ranks Ghost Sentinel among the strongest 1v1 classes and says daggers "are still strong but no longer dominate 1v1 ... HP pools grew" ([l2topservers](https://l2topservers.org/en/blog/best-pvp-classes-highfive)). Other lists put tanks and daggers at the top for Olympiad duels and archers at the top for open-field PvP (one of them covers Interlude, not High Five). None of them has archers and daggers near the bottom, as this matrix does, so most of the weakness below is the server's, not retail's.

## Ranking (full matrix)

| # | Build | Win % | | # | Build | Win % |
|---|---|---|---|---|---|---|
| 1 | Hell Knight | 92.4 | | 20 | Soultaker | 50.0 |
| 2 | Titan | 91.2 | | 21 | Battle Bard | 48.9 |
| 3 | Maestro | 89.5 | | 22 | Doom Oracle | 48.4 |
| 4 | Dreadnought | 88.4 | | 23 | **Ghost Hunter** | 43.4 |
| 5 | Doombringer | 86.6 | | 24 | **Sagittarius** | 41.6 |
| 6 | Fortune Seeker | 77.5 | | 25 | **Adventurer** | 40.9 |
| 7 | Shillien Templar | 73.4 | | 26 | **Male Soul Hound** | 39.9 |
| 8 | Phoenix Knight | 70.1 | | 27 | **Battle Oracle** | 38.2 |
| 9 | Grand Khavatari | 68.6 | | 28 | **Flame Warchief** | 34.9 |
| 10 | Spectral Dancer | 60.1 | | 29 | **Female Soul Hound** | 33.9 |
| 11 | Archmage | 59.2 | | 30 | **Wind Rider** | 33.1 |
| 12 | Duelist | 58.9 | | 31 | **Dominator** | 30.5 |
| 13 | Eva's Templar | 58.6 | | 32 | **Ghost Sentinel** | 27.5 |
| 14 | War Dancer | 58.1 | | 33 | **Moonlight Sentinel** | 26.4 |
| 15 | Trickster | 56.2 | | 34 | Doomcryer | 21.0 |
| 16 | Mystic Muse | 55.9 | | 35 | **Hierophant** | 18.4 |
| 17 | Judicator | 52.8 | | 36 | Cardinal | 9.1 |
| 18 | Storm Screamer | 52.6 | | 37 | Eva's Saint | 6.1 |
| 19 | Sword Muse | 52.2 | | 38 | Shillien Saint | 5.6 |

Win rate of the analysed builds by the kind of opponent (full matrix):

| Build | vs tanks | vs melee | vs daggers | vs archers | vs mages | vs supports |
|---|---|---|---|---|---|---|
| Hell Knight | 98 | 85 | 100 | 100 | 86 | 100 |
| Titan | 81 | 78 | 97 | 99 | 98 | 98 |
| Maestro | 75 | 71 | 97 | 100 | 99 | 99 |
| Dreadnought | 66 | 72 | 97 | 99 | 99 | 98 |
| Doombringer | 60 | 81 | 100 | 98 | 88 | 93 |
| Ghost Hunter | 5 | 25 | 75 | 71 | 44 | 63 |
| Sagittarius | 0 | 11 | 27 | 77 | 71 | 60 |
| Adventurer | 1 | 22 | 45 | 76 | 42 | 64 |
| Male Soul Hound | 16 | 24 | 78 | 44 | 32 | 62 |
| Battle Oracle | 28 | 13 | 47 | 33 | 28 | 82 |
| Flame Warchief | 29 | 8 | 43 | 48 | 31 | 67 |
| Female Soul Hound | 11 | 22 | 67 | 35 | 27 | 54 |
| Wind Rider | 2 | 18 | 30 | 60 | 26 | 62 |
| Dominator | 2 | 6 | 22 | 39 | 43 | 62 |
| Ghost Sentinel | 0 | 10 | 15 | 32 | 41 | 52 |
| Moonlight Sentinel | 0 | 6 | 7 | 25 | 41 | 56 |
| Hierophant | 1 | 1 | 12 | 5 | 32 | 46 |

Every weak build loses to tanks and heavy melee almost every time, and fast (median loss 25-33 s). That is the whole story of the bottom half.

## What a fighter brings into the fight (focused runs, averages)

| Build | Max HP | P. Atk | M. Atk | P. Def | Speed | Damage /s | Taken /s | In melee range* | Tree max HP % | Tree P. Atk % | Tree P. Def % |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Hell Knight | 31,900 | 3,857 | 616 | 3,045 | 181 | 418 | 188 | 78% | **+31** | **+47** | +12 |
| Titan | 31,600 | 6,340 | – | 1,951 | 185 | **1,178** | 742 | 59% | +10 | +66 | +3 |
| Maestro | 32,500 | 5,284 | – | 1,989 | 187 | 888 | 588 | 69% | +17 | +58 | +5 |
| Dreadnought | 29,600 | 5,559 | – | 2,036 | 185 | 991 | 706 | 69% | +15 | +62 | +5 |
| Doombringer | 18,100 | 5,482 | – | 1,479 | 200 | 697 | 484 | 65% | +11 | +62 | +6 |
| *Archmage (ref.)* | 13,000 | – | 22,121 | 1,309 | 196 | 1,159 | 547 | 25% | +24 | – | +6 |
| Ghost Hunter | 17,500 | 3,322 | – | 1,450 | 211 | 513 | 472 | 72% | +11 | +53 | **−1** |
| Sagittarius | 20,600 | 9,249 | – | 1,474 | 201 | 592 | 641 | 46% | **−3** | +62 | +7 |
| Adventurer | 19,700 | 3,189 | – | 1,444 | 200 | 528 | 490 | 76% | +9 | +54 | **−3** |
| Male Soul Hound | 19,000 | 1,695 | **4,283** | 1,596 | 216 | 491 | 433 | 38% | +32 | – | +8 |
| Battle Oracle | 15,000 | 4,908 | 7,980 | 1,556 | **176** | 327 | 598 | 63% | +18 | +46 | +2 |
| Flame Warchief | 16,700 | – | 9,807 | 1,367 | 201 | 437 | 507 | 40% | +25 | – | +4 |
| Female Soul Hound | 15,500 | 1,366 | **4,186** | 1,559 | 229 | 532 | 447 | 36% | +19 | – | +6 |
| Wind Rider | 18,200 | 2,797 | – | 1,451 | 216 | 412 | 446 | 79% | +10 | +52 | **−1** |
| Dominator | 19,300 | – | 12,830 | 1,350 | 202 | 320 | 542 | **57%** | +26 | – | +5 |
| Ghost Sentinel | 15,800 | 9,510 | – | 1,633 | 213 | 529 | 648 | 42% | **−3** | +63 | +7 |
| Moonlight Sentinel | 16,200 | 8,020 | – | 1,639 | 216 | 402 | 623 | 48% | **−5** | +64 | +7 |
| Hierophant | 14,200 | – | 14,271 | 1,320 | 197 | **77** | 627 | 63% | +25 | – | +5 |

\* Share of the fight the two stood within 200 of each other. For an archer or a mage, that is time a melee spent on them.

## Causes shared by many builds

### 1. The keystone pools work against the archers and daggers

| Role | Keystones taken (focused run 2) | What it costs in a 1v1 |
|---|---|---|
| Archer | Far Shot (99%), **Glass Cannon (66%)**, Relentless Assault (30%) | Glass Cannon: −30% max HP, so the tree leaves archers with *less* HP than without it (−3 to −5%). Far Shot: −30% damage at point-blank range, where a melee keeps an archer half the fight. Relentless Assault ramps up normal attacks, which archers barely use (8-14% of their time). |
| Dagger | **Unwavering Stance (75%)**, Purity of Flesh (50%), Child of Night (50%), Nocturne (20%) | Unwavering Stance: −15% P. Def, leaving daggers with *negative* tree P. Def. Purity of Flesh: −15% HP for poison and bleed immunity, which rarely matters. Nocturne: −12% P. Def and M. Def by day (daggers with it won 14-36%, against 31-54% without). |
| Mage | Vampiric Sorcery, Unshaken Mind, Spell Echo, **Arc Conduit**, **Chaos Weave** | Arc Conduit jumps to a second enemy, so in a 1v1 it does nothing. Chaos Weave (no magic critical hits) won 6-28% where the same classes otherwise won 30-42%. |
| Fighter | **Berserk Pact or Unending Fury** (71-96% of the top fighters take one, 5-19% both) | +30-40% P. Atk in the second half of every 1v1, with no real downside in a duel (−30% healing received, and nobody heals them). The Duelist takes them as often and sits at 59%, so they add to the top five's lead but don't make it. |

### 2. Tanks take damage nodes at full weight

`FakePlayerPvpPassiveTree.keyWeight` gives a TANK the physical keys (P. Atk %, critical rate...) at the same `ROLE_WEIGHT` as a fighter, and HP and defence on top. Hell Knights end up with +47% P. Atk *and* +31% max HP, +12% P. Def. That is a fighter's damage on a tank's body.

### 3. Long casts in melee get interrupted (issue #8)

| Build | Aborted most | Cast time |
|---|---|---|
| Moonlight Sentinel | Elemental Heal 241, Entangle 234 | 5 s, 3 s |
| Wind Rider | Elemental Heal 296, Entangle 143 | 5 s, 3 s |
| Battle Oracle | Battle Heal 273, Prophecy of Doom 149 | 2 s (−25% cast speed from Battlemage), 1.5 s |
| Hierophant | Battle Heal 209, Prophecy of Salvation 88 | |
| Flame Warchief | Spirit Trance 131, Spirit Walk 54 | |
| Soul Hounds | Blink 132-139 | |
| Daggers | Shadow Step ~95 each | |

Each of these is seconds spent standing still in front of a melee and getting nothing for it.

### 4. Bluff is cast and almost never lands

Every dagger's "Bluff backstab" combo starts with Bluff as a required step. Bluff landed **8%** of the time (618 casts for the Adventurers alone, 3-4 per battle). Shield Stun, Seal of Silence and Stun Shot land 10-21% too, but they are not a gate in front of the damage.

### 5. Archers stand and cast against melee

Archer shots take 3-4 s to cast (Stun Shot 3 s, Lethal Shot 4 s, Death Shot 3.5 s, Hamstring Shot 4 s, before attack speed). Archers spend 70% of the fight casting and 2-6% kiting. They land the first hit about 4 s in, a melee reaches them at about 8 s, and then a melee is on them 42-48% of the time. Their median gap to the enemy is 270-375, a third of their range. Sagittarius, the only archer with a movement skill (Dash), wins 42%; Ghost Sentinel and Moonlight Sentinel, without one, win 26-28%. This is issues #20 and #21 at their worst.

### 6. Supports play support when they are alone

The Hierophant spends 33% of its time in `HEALER_DEFEND` and 24% in `SUPPORT_HEAL`, and heals itself for 36,000 HP a battle while dealing 77 damage a second. 124 of its losses came at the time limit. The Dominator, not a support build, still casts party chants (Chant of Life, `SUPPORT_BUFF` / `SUPPORT_HEAL` 7% of its time). Party behaviour should be off when the fake player has no party.

### 7. Servitors don't fight

Over 2,208 battles the Hell Knight's Dark Panther dealt 7,890 damage in all and the Maestro's Mechanic Golem 105. The summons spawn and stand. (Fixing this makes two top-five builds stronger, so do it together with their nerfs.)

## Weak builds: why they lose and what to change

The AI changes go into brain B first and need an A/B run before they are promoted, as `docs/fake-player-simulation-issues.md` describes. The passive tree and gear changes apply to everyone at once; re-run the matrix after each batch.

### Moonlight Sentinel (26.4%)

**Why:** the lowest HP of the archers after the tree (16,200, tree −5% HP), 0% against tanks and 6% against melee. The worst abort rate in the run (21%): it casts Elemental Heal (5 s) and Entangle (3 s) with a melee on it. Its damage is mostly Stun Shot (20%), which lands its stun 11% of the time. It has no movement skill.
- **Tree:** archer keystones without Glass Cannon (see the shared changes below). Raise the BRUISER style chance for archers from 1 to 3.
- **Gear:** in focused run 2 its archer peers won more in Draconic Leather (Sagittarius 56%, Ghost Sentinel 38%) than in Dynasty Leather - Bow Master (38%, 20%). The samples are 26-30 battles, so test before committing.
- **AI:** drop Elemental Heal while a melee enemy is within 300 and not disabled. Entangle only as an opener at 600-900, not in `peel` (a 3 s cast is not a peel). Kite between shots (shared change).

### Ghost Sentinel (27.5%)

**Why:** the same archer problems: 15,800 HP after the tree, 0% against tanks, 10% against melee, 70% of its time casting. It has no self-heal and no movement skill. It kills other archers and mages (52% against supports, 41% against mages) but can't survive contact.
- **Tree / AI:** the shared archer changes. Fatal Counter (34 aborts) only when the target is in melee range and disabled.

### Sagittarius (41.6%)

**Why:** the best of the archers thanks to Dash and Death Shot, but still 0% against tanks and 11% against melee. 16 of its losses were routs: it ran and kept running.
- **Tree / AI:** the shared archer changes. Use Dash to open the gap before the melee arrives, not only to catch or flee.

### Shared archer changes

- **`FakePvpKeystones.ARCHER`**: `Unwavering Stance;Far Shot@BOW;Relentless Assault` (drop Glass Cannon). Stun immunity matters: Shield Stun and Stun Shot are the most common crowd control on archers.
- **AI (brain B):** at a gap under ~500 from a melee that isn't rooted or stunned, step back between shots instead of starting a 3-4 s shot. Use the peels (Stun Shot, Hamstring Shot) to *open* the gap, then shoot. When the melee is on it anyway, use normal attacks (faster than the shots) rather than standing through a cast.

### Wind Rider (33.1%)

**Why:** the lowest damage of the daggers (412 a second). Its "Entangle and blow" combo starts with a 3 s Entangle in melee range, and it casts Elemental Heal (5 s) while being hit: 296 + 143 aborts. It is rooted more than any other build (Dryad Root 48 times). Blows land 90%.
- **Tree:** the dagger keystones below.
- **AI:** Entangle out of the melee combo (an opener at range, or nothing). Elemental Heal only with no melee on it.

### Adventurer (40.9%)

**Why:** its blows land **59%** of the time, against 87-90% for the other daggers. The Wind Rider and Ghost Hunter keep Mortal Strike up (skill 410), which the Treasure Hunter line doesn't learn (it is only in the Plainswalker and Abyss Walker trees, as in retail). Its buff list takes Focus Power (357) before Focus Chance (356). It is also hit by the Bluff problem (618 casts, 8% landed).
- **Tree:** weight `BLOW_RATE_PCT` higher for this build. Builds can only set `keystones` and `hybrid` today, so this needs a small feature (e.g. `weights="BLOW_RATE_PCT:2"` on `<build>`).
- **Build:** try `356|357` (Focus Chance first) and compare blow land rates.
- **AI:** Bluff optional in its combos (shared dagger change).

### Ghost Hunter (43.4%)

**Why:** the best dagger, still 5% against tanks and 25% against melee. 17,500 HP with −1% tree P. Def. Hex and Power Break are aborted 50+ times each (cast before the blows).
- **Tree:** the dagger keystones below.
- **AI:** Hex and Power Break only when the target is disabled or already engaged; Bluff optional.

### Shared dagger changes

- **`FakePvpKeystones.DAGGER`**: `Unwavering Stance;Child of Night` (drop Purity of Flesh and Nocturne). Phoenix Heart (survive one killing blow) would suit a dagger's 1v1 best, but `PassiveMechanics.phoenixRebirth` is player-only, so it needs code before it can go in the list.
- **AI (brain B):** Bluff only as an optional step, and not against a target that already resisted it. Shadow Step not while the dagger is already in melee range.

### Male Soul Hound (39.9%) and Female Soul Hound (33.9%)

**Why:** M. Atk **4,200** against the Archmage's 22,100. The Kamael mages use rapiers (Icarus Stinger, Vesper Pincer) and light armour (Dynasty Leather - Enchanter), and that weapon and armour give a fraction of a staff and robe's M. Atk. Their damage is 491-532 a second, half the human nukers'. They kite better than other mages (36-38% of the time in melee range) but can't kill fast enough. Blink is aborted 132-139 times.
- **Gear:** check what M. Atk the rapier kit gives at S84 (the `RAPIER` kit) and give the Soul Hounds the rapier with the best magic stats. Retail Kamael can't wear robes, so the armour stays light: prefer the Vesper Noble Leather Breastplate, which won 45% against 30% for the Male Soul Hound.
- **Tree:** remove Chaos Weave from their options (6% and 16% wins with it). They already take Vampiric Sorcery and Spell Echo, which are fine.
- **AI:** Blink when the melee arrives, not after it is on them, then follow it with Soul Vortex or Voice Bind and step back.

### Battle Oracle (38.2%)

**Why:** the slowest build in the run (speed 176) and the least damage of the melee (327 a second). Its Battlemage keystone gives it P. Atk from M. Atk but costs −25% casting speed, and the build's kit is casts: Prophecy of Doom is aborted 149 times and Battle Heal 273. It fights in light mage armour with 15,000 HP and a magic mace (Icarus Hall).
- **Gear:** a melee weapon with P. Atk (it adds 40% of its M. Atk on top, so even a modest P. Atk weapon adds more than the Hall), and fighter leather (Dynasty Leather - Weapon Master) for HP and P. Def instead of Enchanter leather.
- **Tree:** keep Battlemage, but put Unwavering Stance second instead of Unending Fury (it is stunned or rooted 20 s a battle).
- **AI:** Battle Heal only with no melee on it. Cast Prophecy of Doom before closing in, not in melee.

### Flame Warchief (34.9%)

**Why:** 8% against melee. Its damage is Steal Essence (4 s cast, 53%) and the Totem of Flames (22%). It walks toward its target to plant the totem (`TOTEM_APPROACH` 8%). Spirit Trance, its emergency, is aborted 131 times.
- **Tree:** remove Chaos Weave and Arc Conduit from its options.
- **AI:** plant Frost-Teeth *before* a melee arrives (it slows), and plant Flames behind itself so the melee chases through it. Spirit Trance only when nothing is hitting it.

### Dominator (30.5%)

**Why:** 82% of its damage is Steal Essence, a 4 s drain. Its seals land 10-32% (2,438 resists in a run). It spends 57% of the fight in melee range: its median gap is 175, because its `peel` list has melee skills (Hammer Crush, Stun Attack) that draw it in. It also casts party chants with no party.
- **Tree:** remove Chaos Weave and Arc Conduit from its options.
- **AI:** no party buffs or heals with no party. Hammer Crush and Stun Attack out of `peel` (keep Seal of Binding and Dreaming Spirit). Seal of Silence only on a target that is casting.

### Hierophant (18.4%)

Yes: the Prophet line is now the Oracle, which fights with prophecies. It has three builds:
- **Hierophant:** the party support (`support="true"`, `weight="0"`).
- **Doom Oracle:** the caster, ranked 22nd at 48.4%.
- **Battle Oracle:** the battlemage, ranked 27th at 38.2%.

The Hierophant build isn't meant to duel. In a 1v1 it plays healer: it heals itself (36,000 HP a battle), deals 77 damage a second and loses at the time limit (124 losses).
- **AI:** with no party, a support build fights like its class's combat build. For the Hierophant that is the Doom Oracle's attack list and combos; the Cardinal and Saints would use their own attacks. This also fixes the bottom three healers' 6-9%, which today mostly measures the healing AI.
- **Tree:** keep Unshaken Mind (it is stunned more than any other build: Shield Stun 51, Bluff 32).
- **Doom Oracle** (for when it fights alone): Prophecy of Doom deals ×1.5 only when the target stays within 300, and a melee chasing the Oracle never does. Its "Rooted doom" combo (Dryad Root first) is the right idea. Make it the first combo when a melee is coming.

## The top five: why they win and what to nerf

All five have about 30,000 HP (the Doombringer 18,000), P. Atk above 5,000 and a tree with +58 to +66% P. Atk. All but the Hell Knight end their fights in 16-25 s, and they beat every non-tank 97-100% of the time.

**What sets them apart** is a heavy fighter's HP (or, for the Doombringer, Disarm) with a fighter's damage and a stun or crowd control loop. The low-HP keystones (Berserk Pact, Unending Fury) help them, but the Duelist takes them as often and sits at 59%. **Shared change:** lower Unending Fury from 40% to 25% and make it exclusive with Berserk Pact, like Far Shot and Point Blank; this trims the end of every close duel for every fighter, players too. Then the skill changes below, one class at a time.

### Hell Knight (92.4%)

**Why:** the highest HP (31,900) with a tree of +31% HP, +12% P. Def and +47% P. Atk (Living Fortress, Deflection, Arcane Plating). It casts Shield Stun 1,197 times (21% land) and chains Judgment (604 landed), Shackle and Hamstring. It wins long: median win 56 s, 54 wins at the time limit.
- **Tree:** halve the physical damage keys' weight for the TANK role (shared cause 2).
- **Skills:** Shield Stun reuse up (it is cast 6-7 times a battle). Or Judgment's P. Def debuff only on a stunned target.
- **Note:** the time-limit rule (most HP left wins) favours it. A draw rule for tanks would make the matrix fairer, but that changes the measurement, not the class.

### Titan (91.2%)

**Why:** 1,178 damage a second, as much as the Archmage, on 2.4 times its HP. Armor Crush, Crush of Doom and Fatal Strike make 53% of its damage. It stacks low-HP damage: Frenzy/Guts and Zealot, plus Berserk Pact or Unending Fury from the tree (164 of 184 Titans took one). Its damage is twice the Duelist's (650 a second) on about the same HP, and that gap is its skills. It heals itself 3,000 a battle.
- **Tree:** the shared low-HP change.
- **Skills:** Crush of Doom power −10%. If it is still above 80% after the tree change, Zealot's attack speed shouldn't stack with the keystones.

### Maestro (89.5%)

**Why:** the most HP of all (32,500) and +97 critical rate from its tree. It loops Hammer Crush (stun, 532 casts) into Fatal Strike (92% hit) with Armor Crush. It is a crafter class with no help from its golem (105 damage total).
- **Tree:** the shared low-HP change. Cap the critical rate the tree gives blunt fighters (its +97 is the highest after the daggers).
- **Skills:** Hammer Crush reuse up or its stun shorter in PvP. Do this together with the servitor fix (shared cause 7), which would otherwise push it higher.

### Dreadnought (88.4%)

**Why:** a polearm with range 80, twice the others', so it hits first. Cursed Pierce crits 54% of the time (25% of its damage), Power Crush, Shock Blast (stun). 29,600 HP.
- **Tree:** the shared low-HP change.
- **Skills:** Cursed Pierce critical rate or power down (it crits 54% of the time, against 26-35% for the other fighters' main skills).

### Doombringer (86.6%)

**Why:** only 18,100 HP, but **Disarm landed 122 times**: the target loses its weapon. Violent Temper (109), Death Mark, Shoulder Charge. Unending Fury (123 of 184) and Glass Cannon (52) on top.
- **Tree:** the shared low-HP change.
- **Skills:** Disarm shorter in PvP, or a higher resist chance. It is the single biggest swing in its duels.

## Where to start

1. **Passive tree, data only:** the new `FakePvpKeystones.ARCHER` and `.DAGGER` lists; Chaos Weave and Arc Conduit out of `.MAGE`; Unending Fury at 25%. Then re-run `//Simulate matrix 20`. (Making Berserk Pact and Unending Fury exclusive needs a small code change, like Far Shot and Point Blank.)
2. **Passive tree, small code:** the TANK role's physical weight halved; archer style chances; per-build `weights` (for the Adventurer).
3. **AI, as brain B:** no long casts in melee (#8), Bluff optional, archer kiting (#20, #21), supports fight alone, Dominator's peel. A/B test as the issues doc describes.
4. **Gear:** Soul Hound rapier, Battle Oracle weapon and armour, archer Draconic Leather test.
5. **Skill nerfs for the top five,** only after 1 and 2 have been re-run, since the tree changes alone may bring them down.

Re-running: a new matrix with 1000 at once takes about 20 minutes for 20 battles per pair. Keep the battle logs with `SimulationMatrixKeepLogs` in `FakeSimulation.ini`. Each `run.json` now lists the fighter's passive tree (keystones, stat totals) and gear, which is what this analysis was built on.

## Re-run after the passive tree changes (`matrix-20261025-232944`)

Applied (commit "Passive tree balance from the level 85 class matrix"):
- Unending Fury +25% (was 40%), exclusive with Berserk Pact.
- The new archer, dagger and mage keystone lists.
- The Battle Oracle's Unwavering Stance.
- Tank trees wanting damage stats half as much as fighters do.
- Bruiser trees for archers.
- Twice the blow rate in the Adventurer's tree.

The rules were checked as applied in the new battles' `run.json`:
- No Glass Cannon, Chaos Weave or Arc Conduit.
- No fighter with both low-HP keystones.
- Hell Knight tree P. Atk +30% (was +47%).

Same matrix: 14,060 battles, 20 per pair. With 740 battles per build, a change under about 4 points is noise.

| Moved | Build: before → after |
|---|---|
| Up | Doom Oracle 48.4 → 54.1, Trickster 56.2 → 61.0, Adventurer 40.9 → 45.7, Flame Warchief 34.9 → 39.7, Battle Oracle 38.2 → 42.7 |
| Down | War Dancer 58.1 → 49.1, Shillien Templar 73.4 → 66.4, Eva's Templar 58.6 → 51.6, Battle Bard 48.9 → 43.4 |
| No real change | The top five (Titan 92.8, Hell Knight 92.2, Maestro 87.5, Dreadnought 87.0, Doombringer 85.4); the archers (Sagittarius 43.4, Ghost Sentinel 29.1, Moonlight Sentinel 27.3); Wind Rider 33.4, the Soul Hounds 32-40, Dominator 32.2, Hierophant 17.2 |

What it means:
- **The tank change hit the wrong tanks.** Shillien Templar, Eva's Templar and Battle Bard (a TANK-role build) lost 5-7 points. The Hell Knight didn't move: its lead is its HP, its stun chain and the time-limit rule, not its tree's P. Atk.
- **The top five don't depend on the low-HP keystones.** Making the pair exclusive and Unending Fury weaker barely moved them. Their lead is heavy-armour HP plus skill damage and stuns, so it needs the skill changes in the top-five section.
- **The archers and most of the weak casters are held back by their AI, not their tree.** Glass Cannon gone and Unwavering Stance in made 1-2 points. They still stand through 3-4 s casts with a melee on them (shared causes 3 and 5).
- **War Dancer −9** is the largest move, and nothing of its own was changed. It wasn't in the focused runs, so there is no passive tree data from before to compare. The fighter keystone change (the exclusive pair) is the only change it shares, so it needs a focused run before the pair is kept as is.

Next: the AI changes as brain B (long casts in melee, Bluff, archer kiting, supports alone, Dominator's peel), then the top-five skill nerfs. Consider undoing the tank damage weight, or limiting it to the Hell Knight's tree.
