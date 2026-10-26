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

## Round 3: mage keystones and the brain B ranged AI

**Changes**
- Tank trees weigh damage stats like fighters again.
- Mind Over Matter and Martyr's Vow work for fake players. A fake mage under Martyr's Vow pays its casts with HP and gets max HP from max MP; under Mind Over Matter its MP soaks damage. Both lead the mage keystone list, and `FakePvpKeystonePartners` sends a tree that took Mind Over Matter to Martyr's Vow next. The result: 62% of mages take Mind Over Matter, 53% Martyr's Vow, 44% both. An Archmage with both has about 17,600 HP (13,000 before), pays 1,500-2,700 HP in casts a fight, and soaks about 7,900 damage with its MP.
- Brain B, for archers and mages a melee is after:
  - It works out how long it may cast before the melee reaches it. Under 2.5 s it is *pressed*.
  - Pressed, it peels only with what lands in time; a mage never peels with a physical melee skill.
  - It skips combos, and casts only attacks, debuffs, heals and buffs it can finish.
  - An archer steps back and shoots normal attacks in between.
  - A mage casts what it can finish first, and steps back only with nothing short enough.
- The matrix can run A/B tests (`brain=A:B mirror`). A mirror test can't measure this change, though: an archer against an archer never meets a melee. The measure below is a full matrix with brain B against one with brain A, on the same tree. Melee classes play the same under both brains, so the difference is the archer and mage AI.

**Runs:** `matrix-20261026-001906` (brain A, new tree), then three brain B versions:
- `-005904`: party-of-one rule, mage melee peels dropped.
- `-012643`: the party-of-one rule removed.
- `-015454`: the final version. It ran at 600 at once after the server ran out of memory at 1000.

The party-of-one rule (a healer alone fights instead of healing) lost badly in the corrected A/B test (Cardinal and Eva's Saint 1 win in 40), so it was taken out.

**The tree changes, brain A** (against the run before them):
- Nukers up: Storm Screamer +5.0, Soultaker +4.6, Mystic Muse +2.7, Archmage +2.0.
- The tanks back: Shillien Templar +2.7, Eva's Templar +2.3, Battle Bard +3.8.
- Hell Knight −6.2 and Duelist −6.9 against the tougher mages.
- The Soul Hounds and Dominator didn't move: their problem is damage, not survival.

**Brain B (final) against brain A**, archers and mages, win % against melee and tanks (overall win % in brackets):

| Build | A | B | Change |
|---|---|---|---|
| Trickster | 36.8 (60.5) | 52.8 (68.8) | **+16.0** |
| Dominator | 12.2 (34.5) | 26.2 (41.8) | **+14.0** |
| Moonlight Sentinel | 5.8 (24.7) | 14.0 (28.4) | **+8.2** |
| Doom Oracle | 59.0 (55.7) | 62.8 (58.8) | +3.8 |
| Ghost Sentinel | 8.8 (29.3) | 11.2 (29.8) | +2.5 |
| Flame Warchief | 31.0 (40.4) | 33.5 (41.2) | +2.5 |
| Storm Screamer, Soultaker, Archmage, Sagittarius | | | within ±0.5 |
| Mystic Muse | 51.2 (58.6) | 49.0 (57.8) | −2.2 |
| Female Soul Hound | 41.8 (35.5) | 37.0 (34.2) | **−4.8** |
| Male Soul Hound | 40.5 (35.7) | 32.2 (31.5) | **−8.2** |
| All archers and mages | 34.1 | 36.6 | **+2.5** (±1.8) |

What it means:
- **Stepping back and finishing the cast first** helps the builds that lose the damage race: Trickster, Dominator and Moonlight Sentinel.
- **The nukers who win by bursting** (Archmage, Storm Screamer, Soultaker) are unchanged since mages cast first.
- **Ghost Sentinel and Sagittarius barely move.** A melee as fast as them still catches them: they need a slow (Hamstring Shot) to land first, or more speed.
- **The Soul Hounds lose under brain B** in all three versions. Keep them on brain A when B is promoted (gate the pressed logic off for them), or find what costs them before promoting.
- **Promoting:** B as it stands wins 2.5 points for the archers and mages taken together, and big for three of them. Per the A/B rules it can be promoted with the Soul Hounds left out; the top five are untouched by it (Titan 92.7, Hell Knight 87.2, Maestro 86.2, Dreadnought 88.1, Doombringer 87.2).

## Round 4: the Soul Hounds, sitting, and gear tests

**Why brain B hurt the Soul Hounds.** Under brain B their damage per second fell 10-13% (Male 522 → 452, Female 539 → 492) with no gain in safety: a melee was on them 52% of the time under both brains. There were two causes:
- Pressed, brain B skipped combos, and the Soul Hounds' burst is a combo (Soul Vortex → Extinction → Soul Strike → Soul of Pain).
- The cast filter left them mostly binds (Shadow Bind and Voice Bind 3.0 → 3.8 casts a battle) in place of damage.

Stepping back gained them nothing: they were "cornered" twice as often.

The fix: a pressed mage keeps its combo, and only archers drop theirs. Brain A against B, each mage against every build, 20 battles each (740 per mage, about ±5):

| Build | vs melee/tank, A → B | Overall, A → B |
|---|---|---|
| Dominator | 12.0 → 31.0 | 33.9 → 44.6 |
| Storm Screamer | 39.2 → 45.5 | 55.4 → 58.4 |
| Flame Warchief | 32.0 → 36.8 | 43.8 → 44.8 |
| Soultaker | 44.0 → 48.0 | 54.7 → 56.0 |
| Male Soul Hound | 41.8 → 42.5 | 35.5 → 36.6 |
| Female Soul Hound | 41.5 → 38.8 | 36.6 → 35.1 |
| Doom Oracle | 61.5 → 62.8 | 59.4 → 60.5 |
| Mystic Muse | 53.8 → 53.0 | 59.3 → 61.3 |
| Archmage | 61.2 → 59.5 | 68.2 → 66.6 |

Brain B is now neutral or better for every mage. With the archer results of round 3 (Trickster +16, Moonlight Sentinel +8 against melee), it is ready to promote.

**Sitting in a fight.** The rest checks (alone, in a party, in a PvP spot, defending a siege) only looked for PvP threats. A hurt fake player could sit down with a monster still on it: after a kill, a monster it gave up on, a siege monster just past 600.

`isFightNear` now also counts a monster that targets it and fights. All four rest checks use it, and `sitDown` refuses during a fight. Alone, it hits that monster back. This is in both brains. It compiles, but it couldn't be watched in game: the simulations never rest (no REST decision in 14,060 battles).

**Gear tests** (brain A; each variant and its normal build against the same 38 builds, 10 battles each, 380 per variant, about ±7):

| Variant | Normal → variant | Kept |
|---|---|---|
| Sagittarius in heavy armour | 36.2 → 44.3 (+8.1) | yes, weight 1 |
| Ghost Sentinel in heavy armour | 25.8 → 34.3 (+8.5) | yes, weight 1 |
| Moonlight Sentinel in heavy armour | 23.0 → 27.6 (+4.7) | yes, weight 1 |
| Adventurer in tank armour | 41.1 → 43.2 (+2.1) | already has a heavy variant |
| Wind Rider in tank armour | 31.8 → 35.9 (+4.2) | already has a heavy variant |
| Ghost Hunter in tank armour | 43.4 → 46.6 (+3.2) | yes, a Heavy variant, weight 1 |
| Hell Knight with a dagger | 87.8 → 75.5 (−12.3) | no |
| Shillien Templar with a dagger | 77.2 → 69.5 (−7.7) | no |
| Phoenix Knight with a dagger | 66.1 → 59.5 (−6.6) | no |
| Eva's Templar with a dagger | 55.7 → 57.0 (+1.3) | no |

At level 84 and above, the tanks' armour kit is the same Vesper set as the daggers' heavy kit, so a dagger "in tank armour" is the heavy variant the Adventurer and Wind Rider already roam with. Trickster (Kamael) gets no heavy variant.

## Round 5: brain B promoted, tank heals, archer trees (`matrix-20261026-065134`)

**Changes**
- The ranged AI from rounds 3 and 4 is now `FakePlayerPvpAI`.
- The knights' self-heals (Divine Heal, Holy Blessing, and Elemental Heal for the Elven knights) have twice their reuse, through `ClassSkillReuseMultipliers` in `ClassBalance.ini`.
- Archer trees want P. Atk % and critical damage % twice as much.

Full matrix, 14,060 battles, 500 at once. Each class has 740 battles, so a change under about 4 points is noise. The archers' new heavy variants don't take part: the matrix uses each class's main build.

**Tank heals:** the reuse doubled as intended. The shortest gap between two heals went from 3.0 to 6.0 s, and casts per battle fell by a third (Eva's Templar Elemental Heal 9.5 → 6.1, Phoenix Knight Divine Heal 6.3 → 4.6, Hell Knight 2.9 → 1.9). The tanks didn't get weaker: Hell Knight 87.2 → 90.3, Phoenix Knight 63.6 → 66.6, Eva's Templar 51.2 → 54.1, all within noise. Holy Blessing wasn't cast in either run. Their self-heals aren't what keeps them alive; their HP, defence and stun chains are.

**Archer trees:** every archer gained a little:
- Sagittarius 40.7 → 43.0
- Ghost Sentinel 29.8 → 31.9
- Moonlight Sentinel 28.4 → 31.1
- Trickster 68.8 → 71.5

About +2.5 on average, a consistent direction but each within noise.

**Ranking now** (round 3 with brain B in brackets):
- **Top five:** Titan 91.2 (92.7), Hell Knight 90.3 (87.2), Dreadnought 87.0 (88.1), Maestro 85.4 (86.2), Doombringer 83.9 (87.2).
- **Middle:** Trickster 71.5, Archmage 62.6, Storm Screamer 60.8, Doom Oracle 59.1, Mystic Muse 58.5, Soultaker 58.4, Dominator 45.1.
- **Bottom, healers aside:** Moonlight Sentinel 31.1, Ghost Sentinel 31.9, Male Soul Hound 31.9, Wind Rider 26.1.
- **Moves to watch:** Wind Rider 30.9 → 26.1, Adventurer 42.0 → 37.8 and Duelist 55.4 → 50.1 lost the most. Nothing in this round touched them directly, so the likely cause is the archers and mages around them getting stronger.

The spread of the win rates (standard deviation 22) hasn't moved since round 3. The top five are still where they were, and the skill nerfs of the "top five" section are what's left to bring them down.

## Round 6: top five skill nerfs and stun reuse (`matrix-20261026-075343`, stopped)

**Changes**
- Crush of Doom (315) power −10%, Cursed Pierce (921) critical rate 30 → 15.
- Stuns take 1.5× as long to come back for the top five (Shield Stun, Hammer Crush, Stun Attack, Armor Crush, Shock Blast, Shoulder Charge), and Doombringer's Disarm 2×, through `ClassSkillReuseMultipliers`.
- PvP physical damage ×0.9 for the top five in `ClassBalance.ini`. The class balance PvP multipliers now also apply when a roaming fake player is in the fight.

The longer reuses showed up in the logs: the shortest gap between two Shield Stuns went from 2.6 to 4.0 s, Hammer Crush 2.7 → 3.9 s, Armor Crush 3.5 → 5.3 s, Shock Blast 7.0 → 11.0 s and Disarm 7.0 → 14.0 s. Shoulder Charge stayed at 7.2 s.

The run was stopped at 16,671 of 35,150 battles, about 25 per pair. The top five barely moved: Titan 91.2 → 90.6, Hell Knight 90.3 → 87.7, Dreadnought 87.0 → 85.2, Maestro 85.4 → 84.9, Doombringer 83.9 → 84.4. Each class had about 880 battles, so a change under about 3 points is noise. Stun uptime wasn't what kept them on top.

## Round 7: top seven melee damage, crits and speed (`matrix-20261026-090555`)

**Changes** (on top of round 6), for the seven melee classes at the top of round 6: Titan, Hell Knight, Maestro, Dreadnought, Doombringer, Fortune Seeker and Trickster.
- PvP physical damage (attacks and skills) ×0.8, instead of ×0.9.
- PvP physical critical damage ×0.7. These multipliers now also apply when a roaming fake player is in the fight.
- Run speed −15, through the new `RunSpeedBonus` option of `ClassBalance.ini` (a flat bonus by class, negative to take speed away, for players and roaming fake players).

Full matrix with 10 battles per pair, 7,030 battles. Each class has 370 battles, so a change under about 5 points is noise. Compared with round 6:

| Class | Round 6 | Round 7 | Change |
|---|---|---|---|
| Titan | 90.6 | 88.4 | −2.2 |
| Maestro | 84.9 | 82.4 | −2.5 |
| Hell Knight | 87.7 | 82.2 | −5.5 |
| Dreadnought | 85.2 | 76.5 | −8.7 |
| Doombringer | 84.4 | 76.1 | −8.3 |
| Fortune Seeker | 73.8 | 61.9 | −11.9 |
| Trickster | 72.1 | 51.6 | −20.5 |

- **Fortune Seeker and Trickster** fell to the middle, with this as their first nerf.
- **Dreadnought, Doombringer and Hell Knight** came down clearly.
- **Titan and Maestro** hardly moved, and they are still first and second.
- **Who gained:** the classes those seven used to beat. Doom Oracle +6.8, Grand Khavatari +6.1, Flame Warchief +6.2, War Dancer +6.0, Spectral Dancer +5.8, Female Soul Hound +5.6, Battle Oracle +5.5. Shillien Templar (75.9) and Phoenix Knight (71.6) are now 6th and 7th.
- **Ghost Sentinel** went 36.2 → 29.7, the only big drop outside the seven.
- **Bottom, healers aside:** Wind Rider 29.6, Ghost Sentinel 29.7, Moonlight Sentinel 30.5 and Doomcryer 31.8.

## Round 8: weaker passive tree defences, daggers, bows and nukers up (`matrix-20261026-101147`)

**Changes** (on top of round 7)
- Passive tree: every MAXHP and MAXHP_PCT bonus halved, every PDEF_PCT and MDEF_PCT bonus cut by 20%. Drawbacks (negative values) kept as they were.
- Daggers (Adventurer, Wind Rider, Ghost Hunter): blows +30% in PvP. Backstab now uses the blow multiplier too.
- Bow archers (Sagittarius, Moonlight Sentinel, Ghost Sentinel): critical damage +15% in PvP. The Trickster (crossbow) is left out.
- Nukers (Archmage, Soultaker, Mystic Muse, Storm Screamer, the Soul Hounds, the summoners): skill reuse −10%. `SkillReuseMultipliers` now also applies to roaming fake players.

The four healers (Hierophant, Cardinal, Shillien Saint, Eva's Saint) are no longer in the matrix: 34 classes, 20 battles per pair, 11,220 battles. Each class has 660 battles, so a change under about 4 points is noise. The comparison is with round 7 without those four classes.

| Class | Round 7 | Round 8 | Change |
|---|---|---|---|
| Titan | 87.0 | 85.6 | −1.4 |
| Hell Knight | 80.0 | 82.3 | +2.3 |
| Maestro | 80.5 | 81.4 | +0.9 |
| Adventurer | 31.2 | 40.6 | +9.4 |
| Wind Rider | 21.2 | 28.8 | +7.6 |
| Ghost Hunter | 34.8 | 38.9 | +4.1 |
| Ghost Sentinel | 22.1 | 29.2 | +7.1 |
| Moonlight Sentinel | 22.4 | 27.4 | +5.0 |
| Sagittarius | 39.1 | 41.1 | +2.0 |
| Archmage | 58.2 | 63.5 | +5.3 |
| Mystic Muse | 55.2 | 58.9 | +3.7 |
| Female Soul Hound | 38.2 | 30.8 | −7.4 |
| War Dancer | 42.4 | 36.1 | −6.3 |
| Shillien Templar | 73.5 | 67.6 | −5.9 |
| Battle Bard | 40.6 | 35.5 | −5.1 |
| Battle Oracle | 31.8 | 26.8 | −5.0 |
| Phoenix Knight | 68.3 | 63.5 | −4.8 |

- The dagger, bow and nuker buffs worked. The daggers gained the most.
- The tree's HP and defence nerf didn't touch the top three: Titan, Hell Knight and Maestro are within noise of round 7. The ones it hurt are the tanks and support fighters who take those nodes: Shillien Templar, Phoenix Knight, War Dancer, Battle Bard, Battle Oracle.
- Female Soul Hound fell 7.4 even with the reuse cut. Male Soul Hound didn't move.
- Bottom four: Battle Oracle 26.8, Moonlight Sentinel 27.4, Wind Rider 28.8, Ghost Sentinel 29.2.

## Round 9: Battle Roar, Titan and Maestro defences, Judgment (`matrix-20261026-112619`)

**Why the top three were still on top after round 8** (5,000 kept battle logs): they have 27,600–30,700 HP, and nothing so far had touched it. Titan takes damage nodes (+65% P.Atk) rather than HP nodes, so the round 8 tree HP cut barely reached it. Battle Roar healed Titans about 5,200 HP a battle and Dreadnoughts about 7,100. Hell Knight took the least damage of any class (193/s, 2,959 P.Def), and Judgment, back every 4 s, cut the enemy's critical damage by 35%. Maestro had four stuns, two of them (Golem Punch, Rush Impact) never nerfed.

**Changes**
- Battle Roar (121) heals and raises max HP by half the retail High Five values, for every class (level 6: 35% → 18%).
- Titan and Maestro: P.Def and M.Def ×0.85 against players and roaming fake players (`PvpPhysicalAttackDefenceMultipliers`, `PvpMagicalSkillDefenceMultipliers`).
- Hell Knight: Judgment (401) reuse ×2. Maestro: Golem Punch (808) and Rush Impact (995) reuse ×1.5.
- Every `ClassBalance.ini` setting now applies to roaming fake players (and their servitors) as to players (and their summons), including magic damage, defence and critical settings, critical chance, P.Def, skill mastery, healing and reuse. A player hitting a roaming fake player no longer gets the PvE multipliers on top of the PvP ones.

**Checked in the logs** (round 8 → round 9):
- Shortest gap between two Judgments 3.5 → 7.0 s, Golem Punch 2.6 → 3.9 s, Maestro's Rush Impact 17.9 → 26.4 s.
- Battle Roar heals half as much: Titan 10,500 → 5,500 HP a cast, 5,200 → 3,000 a battle; Dreadnought 7,100 → 3,400 a battle.
- Damage taken per second: Titan 786 → 901, Maestro 636 → 764, Hell Knight 244 → 240.

20 battles per pair, 34 classes, 11,220 battles. Each class has 660 battles, so a change under about 4 points is noise.

| Class | Round 8 | Round 9 | Change |
|---|---|---|---|
| Hell Knight | 82.3 | 81.4 | −0.9 |
| Titan | 85.6 | 79.5 | −6.1 |
| Maestro | 81.4 | 73.4 | −8.0 |
| Doombringer | 73.0 | 71.4 | −1.6 |
| Dreadnought | 72.9 | 69.2 | −3.7 |
| Female Soul Hound | 30.8 | 36.4 | +5.6 |
| Male Soul Hound | 36.5 | 41.2 | +4.7 |
| Fortune Seeker | 57.7 | 61.9 | +4.2 |
| Ghost Sentinel | 29.2 | 25.0 | −4.2 |

- Titan and Maestro came down clearly. Hell Knight is now first: Judgment is cast 28% less, but it still takes no more damage than before, so its defence, not Judgment, is what keeps it on top.
- Bottom four: Ghost Sentinel 25.0, Wind Rider 26.8, Moonlight Sentinel 28.0, Battle Oracle 29.7.

## Round 10: Hell Knight defences, faster daggers, archers and mages, M.Atk nodes (`matrix-20261026-123905`)

**Changes** (on top of round 9)
- Hell Knight: P.Def and M.Def ×0.85 in PvP, like Titan and Maestro.
- Run speed +20 for daggers (Adventurer, Wind Rider, Ghost Hunter), archers (Sagittarius, Moonlight Sentinel, Ghost Sentinel, Trickster: −15 + 20 = +5) and mages (Archmage, Soultaker, Mystic Muse, Storm Screamer, the Soul Hounds, the summoners).
- The round 8 cut of the passive tree's HP and P.Def/M.Def nodes is reverted.
- Every M.Atk % bonus of the passive tree +20%, rounded to one decimal (the 0.2% nodes stay 0.2%).
- The Soultaker may take Soul Link (a build's `keystones="+Name"` adds to its role's list). Vampiric Sorcery was already open to every mage.

**Checked in the logs:** run speed Adventurer 219, Sagittarius 222, Archmage 217, Trickster 228 (all +20 on round 8); HP back up (Titan 30,700 → 34,150, Archmage 13,700 → 16,050); 23% of the Soultakers took Soul Link.

The disk of the test machine filled up halfway through. The run was resumed, and 7 result lines written during the failure were mixed up and are left out: 5,605 of 5,610 battles. Each class has 330 battles, so a change under about 5 points is noise.

| Class | Round 9 | Round 10 | Change |
|---|---|---|---|
| Doombringer | 71.4 | 78.3 | +6.9 |
| Titan | 79.5 | 77.9 | −1.6 |
| Hell Knight | 81.4 | 76.0 | −5.4 |
| Maestro | 73.4 | 72.4 | −1.0 |
| Mystic Muse | 55.6 | 63.9 | +8.3 |
| Storm Screamer | 53.2 | 59.1 | +5.9 |
| Battle Oracle | 29.7 | 36.0 | +6.3 |
| Fortune Seeker | 61.9 | 56.1 | −5.8 |
| Flame Warchief | 41.9 | 35.5 | −6.4 |
| Male Soul Hound | 41.2 | 31.5 | −9.7 |

- Hell Knight came down 5.4 and the top four are now within 6 points of each other. Doombringer is first, likely helped by the tree's HP coming back (not checked in the logs).
- Mystic Muse and Storm Screamer gained from the M.Atk nodes and the speed. Archmage +2.8, Soultaker flat.
- The Soul Hounds lost ground (Male −9.7) despite the speed. A likely cause, not checked in the logs: the HP the melee classes got back weighs more against them than the M.Atk they gained.
- Daggers and archers moved little: Adventurer +4.1, Ghost Sentinel +2.9, Moonlight Sentinel −3.2. Bottom: Moonlight Sentinel 24.8, Ghost Sentinel 27.9, Wind Rider 28.6.
