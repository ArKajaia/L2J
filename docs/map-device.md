# Maps and the Map Device

An endgame loop after the atlas of Path of Exile: high level monsters drop maps, a player opens them at the Map Device of their private hideout, feeds them Champion Medals for harder modifiers and better rewards, and fights through a fenced field of monsters that never respawn.

Code: `MapDeviceManager` (core logic), `UniqueMapManager` (the unique maps, with `PrimalCrucibleManager`, `KetraVarkaFrontManager` and `TombRaidersManager`), `MapQuestManager` (the quest "A Map of Your Own"), `MapDeviceData` (`data/MapDevice.xml`), `MapDeviceConfig` (`config/Custom/MapDevice.ini`), `model/mapdevice/*` (modifiers, tiers, arenas, runs, `MonsterStrength`), the Map Device part of the event maps (`EventMapType.MAPDEVICE`, `EventMapDevice`, edited on the event map page), and the scripts `custom/MapDevice` (device window, NPCs, the Gambler's Den Dealer), `handlers/chat/commands/voiced/HideoutVoiced` (`.hideout`), `handlers/chat/commands/admin/AdminMapDevice` (`//mapdevice`) and `handlers/items/MapItem`.

## How it plays

1. **Maps drop.** Monsters of level 78 and up may drop a **Map (Tier 1-10)** (items 18101-18110; the Map Device's items are 18101-18119, inside the High Five client's retail id range: they were 50101-50119, which the client doesn't take, and the server moves items saved under the old ids at startup, `MapItemIds`). The tier follows the monster's level and strength (`dropLevel` and `minScore` in the XML: solo monsters drop tiers 1-5, party monsters 6-10), and stronger monsters drop higher tiers: Champion tier 2 +1, tier 3 +2, Mage/Thief/Infused/Resonant/wave challenge +1, raid boss +2 (`<difficulty>`). Base chance 0.3%, +1% per champion tier, 20% for raids, all raised by the monster's own drop rate bonuses (random passives, hot zones). No drop from monsters more than 9 levels below the player.
2. **`.hideout`** in a town (any peace zone) takes the player to their own hideout: a private copy of the Jinia Guild Hideout cabin with the **Map Device** in it. The whole hideout is a **peace zone** (`Instance.setPeace`): no fighting between the players who visit it, the peace zone icon, and whatever else a town allows (changing the passive tree template, the Titan Scroll...).
3. **Insert a map.** Double-click a map in the hideout, or talk to the device and press *Insert*. The device asks how many **Champion Medals** (the Survival Arena medal) to feed it: 0 to 8.
4. **Each medal** rolls one **modifier** out of 72 (never twice the same) and adds **+20% experience and +20% drop rate**. The map also rolls one random **hot zone effect** (the server's `HotzoneModifier`s) for its whole field.
5. **Eight portals** open in a ring round the device. Each takes one player into the map and is gone. The owner and the owner's party may take them (`.hideout party` brings party members to the hideout).
6. **The map** is a fenced field (the Team vs Team barrier code) in its own instance, picked at random among the places that take its tier (the `<arena>`s of `data/MapDevice.xml` and your own maps from the map editor), with **100 monsters** of the tier's levels and strength, made stronger by the tier (`hp`/`attack`/`defence`) and carrying every rolled modifier. A map has a theme: its monsters are drawn from up to 6 kinds. Nothing respawns. When 10 monsters are left to go (before completion, below) they come for the players, so none stays hidden, and each player's **radar** points at one of them, the nearest, following it as it moves; when it dies the radar moves on to the next nearest, one by one, with a line in chat saying how many are left and which way (regular maps only, not unique maps).
7. **Dying** in a map: 3 seconds later the player wakes up in the hideout (full HP/MP/CP) and may take another portal. **No experience is lost** dying in a map (`DeathExpLoss = False`; the Death Penalty debuff still applies as anywhere). A **Return Portal** at the map's entry (or `.hideout back`) goes back to the hideout without dying.
   - **Leaving the party**: a player who leaves (or is removed from) the owner's party may farm on in the map. When they die or leave it, they go to a hideout of their own, made for them if needed, not to the owner's (and can't take the owner's portals again).
   - **No fake players**: maps and hideouts are private, so no fake player ever spawns in them. A Resonant monster in a map fights alone: none of the helpers or rivals it calls in the open world come.
8. **Completing** a map (**99%** of its monsters slain, `CompletionPercent`; a map of fewer than 100 monsters, or a unique map, needs every one) drops one more map and closes it 2 minutes later. A map also closes after 60 minutes, or 20 seconds after its last portal is used and nobody is left inside. The owner can close it early at the device.

The first map a character gets starts the quest **A Map of Your Own** (see below).

**The first map is guaranteed**: the first monster a character kills once it is 50% into level 75 (or higher) drops a Map (Tier 1), once per character (`FirstMapLevel`, `FirstMapLevelPercent`; player variable `MAP_FIRST_GIVEN`).

**Maps from maps**: a map's monsters drop 0.5 maps on average over a full clear (`MapDropsInMapAverage`: each monster has a 0.5 in *monsters in the map* chance), and the last kill drops one more, so a cleared map gives **about 1.5 maps**. Medals and drop bonuses don't change this. A map drops **at most 3 maps in all** (`MapDropsPerMapMax`), counting its monsters' drops, the completion map, the Map Guardian's, Speedrun's and unique maps. Their tier is the map's own, 25% of the time one higher and 5% two higher.

## Tiers

| Tier | Item | Monster levels | Strength | Monsters | HP | Attack | Defence | Exp/drops | Drops from |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 18101 | 78-79 | 0-1.0 | Ketra/Varka, the grazing beasts (48 kinds) | x1.00 | x1.00 | x1.00 | x1.00 | level 78 |
| 2 | 18102 | 78-80 | 1.0-1.5 | the Imperial Tomb, the beetles (117) | x1.05 | x1.03 | x1.02 | x1.10 | level 78, strength 1.0 |
| 3 | 18103 | 80-82 | 0.8-1.5 | Ragna Orcs, Grave Robbers, the dinosaurs (54) | x1.10 | x1.05 | x1.03 | x1.20 | level 80, strength 0.8 |
| 4 | 18104 | 82-84 | 1.0-1.5 | Sel Mahum, Mucrokian, Solina (41) | x1.20 | x1.10 | x1.05 | x1.30 | level 82, strength 1.0 |
| 5 | 18105 | 81-84 | 1.5-2.6 | tough solo monsters: Spiked Stakato, Tanta Lizardmen, Contaminated Batur (23) | x1.10 | x1.05 | x1.03 | x1.45 | level 81, strength 1.5 |
| 6 | 18106 | 82-85 | 2.6-3.8 | light party: Hellbound scouts and mystics, Drake Scout/Mage (11) | x1.00 | x1.00 | x1.00 | x1.60 | level 82, strength 2.6 |
| 7 | 18107 | 82-85 | 3.8-5.0 | Drakos, Divinity, Dragon Valley scouts, Hellbound watchers (23) | x1.00 | x1.00 | x1.00 | x1.80 | level 82, strength 3.8 |
| 8 | 18108 | 82-85 | 5.0-6.5 | Maluks, Dragon Knights, Drake Leader, Tully's Workshop (21) | x1.00 | x1.00 | x1.00 | x2.00 | level 82, strength 5.0 |
| 9 | 18109 | 82-86 | 6.5-9.0 | Batwing Drake, Bloody Karik, the Lesser Giants, Hellbound masters (17) | x1.00 | x1.00 | x1.00 | x2.30 | level 82, strength 6.5 |
| 10 | 18110 | 83-87 | 9.0+ | the Seed of Annihilation elites, Knoriks (11) | x1.05 | x1.03 | x1.02 | x2.70 | level 85, strength 9.0 |

The monsters of a map are plain open world monsters of the tier's levels **and** strength, with a respawning world spawn (no quest, event, instance-only or raid monster, nothing that gives no experience or lies still like the cocoons). Exclude more with `<excludedNpcs>`. Tiers 1-5 are solo maps; from tier 6 the monsters are party monsters, whose own strength is the step up, so the tier multipliers stay near 1. `//mapdevice pool <tier>` lists a tier's monsters with their strength.

### Monster strength

Two monsters of the same level can be miles apart: a level 84 Batwing Drake has 14 times the HP and 10 times the P. Atk. of a level 84 Sel Mahum Soldier. So every monster gets a **strength score** (`MonsterStrength`):

1. **Effective stats**: the npc data's stats times the multipliers of the monster's passive skills: the retail npc skills 4408 "HP Increase (0.25x-12x)", 4410/4411 "P./M. Atk." and 4412/4413 "P./M. Def." (Weak to Ultra Strong). Over 7,000 monsters carry them, and they change a lot: a Male Spiked Stakato has "HP Increase (6x)", a Drakos Guardian 4x. The scores read them from the loaded skills, so a change to a skill or an npc's skill list changes the score.
2. **The standard monster of a level**: for each stat (HP, P. Atk., M. Atk., P. Def., M. Def., Atk. Spd.), the lower quartile of that effective stat over every monster of the level. Most monsters of a level share the stats of the ordinary monster of that level, so that is what the lower quartile lands on, and the party monsters don't pull it up. A level with fewer than 5 monsters takes its standard from the levels round it.
3. **Relative strength of each stat**: the monster's effective stat divided by the standard's (Batwing Drake: HP x13.9, P. Atk. x16.0, M. Atk. x18.7, P. Def. x2.6, M. Def. x1.4, Atk. Spd. x1.0).
4. **Score**: the weighted geometric mean of those ratios (weights in `<strength>` of `data/MapDevice.xml`: HP 2, P./M. Atk. 1, P./M. Def. 1, Atk. Spd. 0.5, so toughness and offence weigh the same). 1.0 is an ordinary monster; a Spiked Stakato is about 2, a Maluk Leader 6.1, Knoriks and the Seed elites 10.3. A geometric mean, because the stats multiply into how hard a monster is.

A tier takes the monsters whose level is within `minLevel`-`maxLevel` and whose score is within `minScore`-`maxScore` (0 = no upper limit). If none fits, it takes the closest in strength of its levels and the server log says so. An open world monster drops the highest tier whose `dropLevel` and `minScore` it reaches. The device windows show each tier's strength; `//mapdevice score` shows the score of your target and the tiers it fits.

What still varies inside a map, on purpose: the map's own Champions, Mages, Thieves, Infused, Resonant and wave challenge monsters, the server's random passives (up to +100% HP at 20 passives) and the hot zone effect. In a live test of a tier 8 map the six kinds' plain monsters were within 1.5x of each other (3.8x before effective stats were scored), the median monster 1.7x the weakest, and the strongest single monster (a tier 2 Champion with 20 passives) 6.7x.

## The 72 modifiers

The first 33 change numbers; the other 39 change how the map plays. The last ones (68 and up) are the big moments: they pay off a fast, aggressive clear, each with a price so that a medal never makes a map easier.

| # | Modifier | Kind | Effect |
|---|---|---|---|
| 1 | Brutal | Numbers | Monsters deal 25% more physical damage. |
| 2 | Empowered | Numbers | Monsters deal 25% more magic damage. |
| 3 | Armoured | Numbers | Monsters have 30% more P. Def. |
| 4 | Warded | Numbers | Monsters have 30% more M. Def. |
| 5 | Fortified | Numbers | Monsters have 40% more HP. |
| 6 | Fleet | Numbers | Monsters move 25% faster. |
| 7 | Frenzied | Numbers | Monsters attack 20% faster. |
| 8 | Quickened | Numbers | Monsters cast spells 30% faster. |
| 9 | Deadly | Numbers | Monsters land critical hits 50% more often, for 30% more damage. |
| 10 | Elusive | Numbers | Monsters have +8 evasion. |
| 11 | Vampiric | Numbers | Monsters heal for 15% of the melee damage they deal. |
| 12 | Elemental Aegis | Numbers | Monsters have +80 to every attribute resistance. |
| 13 | Spiked | Numbers | Monsters reflect 8% of the melee damage they take. |
| 14 | Regenerative | Numbers | Monsters regenerate HP five times as fast. |
| 15 | Enfeebled | Numbers | Players have 20% less max HP. |
| 16 | Exposed | Numbers | Players have 15% less P. Def. and M. Def. |
| 17 | Mana Starved | Numbers | Players regenerate MP 50% slower. |
| 18 | Withered | Numbers | Players regenerate HP 60% slower. |
| 19 | Taxing | Numbers | Players' skills cost 40% more MP. |
| 20 | Sluggish | Numbers | Players move 15% slower. |
| 21 | Lethargic | Numbers | Players' skills take 25% longer to come back. |
| 22 | Elemental Weakness | Numbers | Players have -50 to every attribute resistance. |
| 23 | Blurred Vision | Numbers | Players have -6 accuracy and land critical hits 30% less often. |
| 24 | Fragile | Numbers | Players take 30% more damage from critical hits. |
| 25 | Desecrated | Numbers | Healing received by players is halved. |
| 26 | Arcane Precision | Numbers | Monsters land magic critical hits twice as often. |
| 27 | Unshakable | Numbers | Monsters resist stun, sleep, root and fear 50% better. |
| 28 | Hexproof | Numbers | Debuffs land on monsters 40% less often. |
| 29 | Shieldbearers | Numbers | Monsters block 25% of attacks with a shield. |
| 30 | Thick-Skinned | Numbers | Monsters take 30% less damage from physical critical hits. |
| 31 | Spell-Warded | Numbers | Monsters take 25% less damage from skills. |
| 32 | Weakened Will | Numbers | Players' debuffs land 40% less often. |
| 33 | Glass Cannon | Numbers | Players deal 20% more damage, but monsters deal 25% more. |
| 34 | Coven | Gameplay | Mage infestation: 40% of the monsters are Mages that cast without pause and keep their distance. |
| 35 | Champions' Rally | Gameplay | 30% of the monsters are Champions, some of them of the highest tier. |
| 36 | Bloodless | Gameplay | The monsters have no blood: you can't leech life or mana from them. |
| 37 | Hail of Arrows | Gameplay | Every monster fights from range and backs away when you close in. |
| 38 | Thieves' Guild | Gameplay | 25% of the monsters are Thieves that loot the fallen and run once their bag is full. |
| 39 | Elemental Chaos | Gameplay | 40% of the monsters are Infused with a random attribute. |
| 40 | Gauntlet | Gameplay | 8 monsters are wave challenges: they rise twice more, stronger each time. |
| 41 | Horde | Gameplay | The map holds 30% more monsters. |
| 42 | Pack Hunters | Gameplay | The monsters gather in tight packs of 6 to 10. |
| 43 | Titans | Gameplay | Half as many monsters, but giants: triple HP, 30% more damage, double experience and drops. |
| 44 | Mana Siphon | Gameplay | Monster attacks burn 2% of your max MP. |
| 45 | Venomous | Gameplay | Monster attacks have a 10% chance to poison you. |
| 46 | Lacerating | Gameplay | Monster critical hits make you bleed. |
| 47 | Linked Souls | Gameplay | Monsters come in pairs that share the damage they take, while they stand near each other. |
| 48 | Summoners | Gameplay | 20% of the monsters call 2 minions when the fight starts. |
| 49 | Volatile | Gameplay | Slain monsters explode a moment later, hitting players nearby for 10% of their max HP. |
| 50 | Undying Legion | Gameplay | 25% of the slain monsters rise once more at half HP. |
| 51 | Splitters | Gameplay | A slain monster splits into two copies with half its HP, once. |
| 52 | Death Curse | Gameplay | A slain monster curses its killer: 5% weaker for 10 seconds, stacking up to 6 times. |
| 53 | Soul Eaters | Gameplay | When a monster dies, the monsters nearby devour its soul: they heal 20% and hit 10% harder (up to 5 times). |
| 54 | Rising Dread | Gameplay | Every 10 monsters slain make the survivors 5% stronger, up to 50%. |
| 55 | Berserkers | Gameplay | Below 35% HP, monsters deal 50% more damage and attack 30% faster. |
| 56 | Hunters | Gameplay | Every monster is aggressive and notices you from 50% farther. |
| 57 | Skittish | Gameplay | The monsters run when they're hurt, and lead you into their friends. |
| 58 | War Priests | Gameplay | 30% of the monsters are supporters that heal and empower their allies. |
| 59 | Ambush | Gameplay | Once half the map is cleared, 20 monsters ambush one of you. |
| 60 | Bounty Mark | Gameplay | One monster at a time is marked: kill it within 2 minutes for triple drops. |
| 61 | Map Guardian | Gameplay | A Guardian holds the middle of the map: a tier 3 Champion that rises twice. It drops a bonus map. |
| 62 | Storm Caller | Gameplay | Every 20 seconds lightning strikes one of you for 12% of max HP. |
| 63 | Burning Ground | Gameplay | The ground burns: players lose 1.5% of max HP every 3 seconds. |
| 64 | Flaskless | Gameplay | Potions and elixirs can't be used. |
| 65 | Fragile Portals | Gameplay | The Map Device opens only 4 portals. |
| 66 | Hourglass | Gameplay | The map collapses after 15 minutes. |
| 67 | Speedrun | Gameplay | Clear the map within 10 minutes for a bonus map. |
| 68 | Chain Reaction | Gameplay | Slain monsters burst: monsters within 300 take 20% of their max HP, players within 300 take 4% of theirs. |
| 69 | Culling Strike | Gameplay | A monster below 12% HP dies from any hit you land. Monsters deal 10% more damage. |
| 70 | Rising Stakes | Gameplay | Every 10 kills: +4% experience and drops (up to +60%), but monsters deal 2% more damage (up to +30%). |
| 71 | Rampage | Gameplay | Every 25 kills: 15 seconds of double damage and +50% run speed for everyone inside. Monsters have 20% more HP. |
| 72 | Momentum | Gameplay | Each kill within 8 seconds of the last: +4% run speed and +3% attack and cast speed, up to 10 stacks. Monsters attack 10% faster. |

Modifiers that draw a kind of monster the server has switched off (Mage, Thief, Infused, wave challenge, Champion) are never rolled.

### Where they hook in

- Monster stats (HP, P./M. Atk, P./M. Def, attack and cast speed) and the per-monster effects (Berserkers, Rising Dread, Soul Eaters): `Monster` getters, via `MapDeviceManager.getMonsterMultiplier`.
- The other monster numbers (speed, crits, evasion, leech, reflect, resistances, regeneration) and every player-side number: stat functions added when a monster spawns or a player enters the map (`WorldObject.setInstanceId` hook), removed when they leave.
- Bloodless: `Totems.getLeechRate`, which every life and mana leech goes through (auto-attack absorb, drain skills, the passive tree, cubics).
- Hail of Arrows: `Monster.getAiType` (ARCHER) and `Monster.getPhysicalAttackRange` (600).
- Hunters (and the last 10 monsters): `Monster.isAggressive` / `getAggroRange`. Completion count: `MapDeviceManager.getMonstersToGo`; the last hunt radar: `updateLastHuntRadar` (each kill and every 2 seconds).
- Flaskless: `ItemSkillsTemplate`, which potions and elixirs go through, by hand, auto potions or auto use.
- Experience, SP and drop chance: `Monster.getExpReward` / `getSpReward` / `getCustomPassiveDropMultiplier`.
- Deaths: `Attackable.doDie` (counting, Volatile, Soul Eaters, Splitters, Undying Legion, Death Curse, Bounty Mark, Ambush, completion, Speedrun, map drops) and `Player.doDie` (back to the hideout).
- Damage taken by map monsters: `Monster.reduceCurrentHp`, via `MapDeviceManager.onMonsterDamaged` (Spell-Warded, Glass Cannon's extra damage, Summoners calling their minions on the first hit, Linked Souls passing half to a partner within 1,200).
- Monster attacks landing on players: `Creature.onHitTimer`, via `MapDeviceManager.onMonsterHit` (Mana Siphon, Venomous with NPC Poison 4182, Lacerating with NPC Bleed 4188, at the level that fits the monster).
- Debuff chances: `Formulas.calcEffectSuccess`, via `MapDeviceManager.getEffectChanceMultiplier` (Hexproof, Unshakable on stun/sleep/root/fear traits, Weakened Will). Shield blocks: `Formulas.calcShldUse`, via `MapDeviceManager.getShieldRate` (Shieldbearers; a block adds half the monster's P. Def.).
- The run's clock (`RunTick`, once a second): Bounty Mark picks and times the marked monster (players are told how far and which way), Death Curse stacks wear off, the Speedrun countdown.
- Chain Reaction: `onMapMonsterDied` schedules `chainBurst` 0.4 s later. Its damage to monsters is credited to the killer, so a monster it slays bursts too and the chain carries on by itself; monsters are a map's usual 500 apart, so it chains best with Pack Hunters, Linked Souls or a crowd you pulled.
- Culling Strike and Rampage's double damage: `onMonsterDamaged` (a player's hit that would leave the monster below 12% HP takes all of it). Rampage's run speed: a stat function owned by the run (`getRampageFuncOwner`), on everyone inside and anyone who comes in while it lasts; the run's clock ends it.
- Rising Stakes: `MapRun.getStakesSteps` (kills / 10, at most 15) in `getXpMultiplier`, `getDropMultiplier` and the monsters' P./M. Atk. multiplier, like Rising Dread.
- Momentum: per player `{stacks, last kill}` (`MapRun.getMomentum`), applied as stat functions like Death Curse; the run's clock breaks a chain 8 s after its last kill. Leaving the map clears it.
- Splitters' copies and Summoners' minions have half HP (minions 70% damage) and give half the experience and drops; they count toward clearing the map.
- Map monsters are spawned without a spawn point (so they never respawn) and skip the open world's random spawn rolls (`Spawn`); the map rolls Champions, Mages, Thieves, Infused, Resonant and wave challenge monsters itself, at the open world's chances times the hot zone effect's multipliers, or at the modifier's share.

## Unique maps

Seven map items that always open the same map: its modifiers are chosen for it (no medal, no hot zone effect), it has a twist of its own while the field is cleared, a **final encounter** once it is, and drops of its own for every monster of the map and for its boss. Defined in `data/MapDevice.xml` (`<uniqueMap>`: tier, monster count, arena, modifiers, boss npc and multipliers, drops); the twists and encounters are worked by `UniqueMapManager` (the Primal Crucible's by `PrimalCrucibleManager`, the Ketra-Varka Front's by `KetraVarkaFrontManager`, the Tomb Raiders' by `TombRaidersManager`). The first four are compared in the table below; the Primal Crucible, the Ketra-Varka Front and the Tomb Raiders have their own sections.

**Getting one**: they are rare, **about one map in 100**. Completing a map drops one at `UniqueMapCompletionChance` (1%) + `UniqueMapChancePerMedal` (0%) per medal, at random among the unique maps that drop at its tier; each unique boss drops another at `UniqueMapBossChance` (1%), among those that drop at the boss's tier (all in `MapDevice.ini`). A unique map drops at the tiers `dropMinTier`-`dropMaxTier` of its `<uniqueMap>`, by default `UniqueMapMinTier` (5) and up: the Ketra-Varka Front and the Tomb Raiders drop from tiers **1-4**, the other five from 5 and up. Unique maps **only drop inside maps**, and count toward the map's limit of 3. Double-click it in the hideout (or *Insert* under **Unique Maps** at the device) to see what it holds and open it.

**Their difficulty** shows like a normal map's: every unique map has a tier (its monsters' levels and strength, and the tier's multipliers), in its item name ("Unique Map: The Crimson Coliseum (Tier 5)"), in the device's list and windows, and on its portals. Tiers: the Ketra-Varka Front 1; the Tomb Raiders 2; the Gilded Vault 4; the Crimson Coliseum, the Gambler's Den and the Primal Crucible 5; the Ossuary of Echoes 6.

**Their field is packed tight.** A unique map holds 30-80 monsters where a normal map holds 100 on the whole arena, so on the whole arena they would stand far apart. A unique map opens on a smaller copy of its arena instead (`MapDeviceManager.uniqueField`): the fence is pulled in round the same middle, with the entry, until its monsters stand **twice as densely** as a normal map's (`UniqueMapDensity` = 2.0 in `MapDevice.ini`; 1.0 as dense as a normal map, 0 the whole arena), never smaller than a radius of `UniqueMapMinRadius` (3,000). Everything a twist places as a share of the field comes in with it: the Coliseum's waves, the Front's totems, the Tomb's reliquaries, the Ossuary's phylacteries, the Vault's corners. The Front's sectors shrink with their totems (half the gap between two, 2,000 at most) and keep clear of the entry. An arena of the event map page (the Crucible's `primeval_isle`) keeps its hand-placed fence, entry and packs. With the defaults:

| Unique map | Monsters | Arena | Whole arena | Its field |
|---|---|---|---|---|
| The Crimson Coliseum | 30 (waves) | Ruins of Despair | 9,600 x 8,800 | 3,720 x 3,410 |
| The Gilded Vault | 60 | Fields of Massacre | 6,000 x 6,000 | 3,290 x 3,290 |
| The Ossuary of Echoes | 70 | Silent Valley | 8,000 x 8,000 | 4,730 x 4,730 |
| The Gambler's Den | 50 | Field of Whispers | 8,000 x 7,000 | 4,000 x 3,500 |
| The Primal Crucible | 60 | `primeval_isle` (as placed); a tier 5 arena until it exists | | 55% of that arena's |
| The Ketra-Varka Front | 80 | Ruins of Despair | 9,600 x 8,800 | 6,070 x 5,570 |
| The Tomb Raiders | 70 | Silent Valley | 8,000 x 8,000 | 4,730 x 4,730 |

| | The Crimson Coliseum (18111) | The Gilded Vault (18112) | The Ossuary of Echoes (18113) | The Gambler's Den (18114) |
|---|---|---|---|---|
| Monsters | 30 of tier 5 (Lv. 81-84), Ruins of Despair | 60 of tier 4 (Lv. 82-84), Fields of Massacre | 70 of tier 6 (Lv. 82-85 party), Silent Valley | 50 of tier 5 (Lv. 81-84), Field of Whispers |
| Modifiers | Brutal, Fortified, Hunters, Berserkers, Rising Dread, Soul Eaters | Thieves' Guild, Fleet, Elusive, Skittish, Bounty Mark, Speedrun | Undying Legion, Volatile, Death Curse, Linked Souls, Desecrated, Withered | Champions' Rally, Glass Cannon, Deadly, Fragile, Elemental Chaos, Splitters |
| The idea | An arms race: the monsters grow with every death, and so do you. | A heist against the clock. | The dead don't stay down. | Luck, both ways. |
| Twist | **Deathmatch: Mayhem.** Every kill (and every assist: `MayhemAssistShare` % of the damage) offers a strong passive, notables and keystones of the tree plus the Mayhem keystones, to stack **on top of your own tree**, working only inside the map (see below). The monsters come in three waves: 6, 10, 14. | **Borrowed Time and Greed.** The vault seals in 5 minutes; every kill adds 12 s (a claimed bounty 60 s) and 1% Greed, which raises the map's and the boss's drop chances (up to x2 at 100 kills). | **Echoes.** A quarter of the slain counted monsters leave an Echo (half HP, faster attacks, uncounted, its own drops). Every Echo still standing at the end feeds the boss +5% HP (up to +100%). | **The Wheel of Fate and the Pot.** Every 12 counted kills the wheel spins one of 24 fortunes (below), which holds until the next spin, and gives a **bet**. The **Dealer** by the Return Portal takes bets: the Pot doubles or halves. The **Pot** (x1 to start, x0.25-x4) multiplies the Den's drop chances; past 100% the drops grow bigger. |
| Final encounter | The **Crimson Champion** (a Maluk Warlord, x25 HP, x1.5 defence) in the middle of the ring. At 75/50/25% HP the crowd roars: +10% attack each time and 3 **Challengers** (tier 2 Champions of the map's kinds, double HP), each another passive. | The **Gilded Mimic** (a Mutant Treasure Chest, x400 HP) wakes with at least 2 minutes on the clock. A coward: it runs, spills coins when struck (8% per hit), and at 80/60/40/20% HP vanishes to another corner, leaving 4 Vault Thieves behind. If the vault seals first, it escapes with the hoard. | **The Hollow King** (an Immortal Necromancer, x500 HP, x5 attack) is shielded while his **Bone Phylacteries** stand: 4 at the edges of the field, then 3 more at 60% HP, then 2 more at 30%. Each raises a Bone Servant every 20 s (up to 10). From 30% a **Death Nova** hits everyone within 900 for 12% of max HP every 15 s. | **The House** (a Maluk Princess, x45 HP, x1.5 defence) takes the table in the middle once the den is cleared, with one more bet. The fortunes stop. At 80/60/40/20% HP it draws a card from a shuffled deck of 5 (below); a card holds until the next. |
| Map drops | Champion Medal, Life Stones Lv 84, Scroll: Enchant Weapon (S), Giant's Codex | Adena, Ancient Adena, Gemstone S, Champion Medal, Scroll: Enchant Armor (S) | Dark/Holy Stones, Mid-Grade Life Stone Lv 84, Dark/Holy Crystals, Giant's Codex - Oblivion | 5k-7.5k Adena, Gemstone S, Champion Medal, Mid-Grade Life Stone Lv 84, Scroll: Enchant Weapon (S) |
| Special drops | Challengers: Champion Medals, High-Grade Life Stone | Spilled coins: 100k-300k Adena, Champion Medal | Echoes: Dark/Holy Stones and Crystals, High-Grade Life Stone | Jackpot Chest (3 rolls), Bluffs, Lucky Seven: 75k-125k Adena, Champion Medals, Blessed Scroll: Enchant Armor (S) |
| Boss drops | Champion Medals, Top-Grade Life Stones, Blessed Scroll: Enchant Weapon (S), Giant's Codex - Mastery, Vesper Ring, Vesper Earring (2%), Vesper Necklace (0.5%) | 15-30M Adena, 1-3M Ancient Adena, Gemstone S, 5-10 Champion Medals, Blessed Scrolls: Enchant Armor/Weapon (S), Vesper Earring/Necklace | Dark/Holy Crystals, Giant's Codex - Oblivion, Top-Grade Life Stones, Blessed Scroll: Enchant Weapon (S), Vesper Earring, Vesper Ring (2%) | 1.5-2.25M Adena, 8-15 Champion Medals, Top-Grade Life Stones, Blessed Scrolls: Enchant Weapon/Armor (S), Vesper Ring, Vesper Earring (2%), Vesper Necklace (0.5%) |

**No raid-only drops**: a unique map's boss is a fight, not a raid, so nothing a unique map drops is only obtainable from raids. Every item can also be had without raids: an ordinary monster drops it, or a player shop or recipe makes it (the GM store doesn't count). Ring of Baium (grand bosses only) and Elegia Ring (its sealed form comes only from a grand boss) were swapped for Vesper jewelry at the same chance; the sealed Vesper pieces drop from Dragon Knights, Tardyons, Kaionas and Divinity Protectors.

Exact chances and amounts are in the XML. The boss of each takes the map's tier and modifier multipliers too, then its own (`bossHp`, `bossAttack`, `bossDefence`); the boss, Challengers, phylacteries, Card Guards and the Den's Raise the Stakes Champions count toward the clear, so the map completes when the last of them falls. Bosses and the other encounter monsters never split or rise again.

**Server rates**: the unique maps' own drops (the map, special and boss groups of the XML) are not the monsters' normal drop lists, so they don't go through `Rates.ini`: the Coliseum, the Vault and the Ossuary drop exactly what the XML says. **The Gambler's Den is the exception**: its drops take the rates a normal death drop takes (`DropChanceMultiplierByItemId` / `DropAmountMultiplierByItemId` for the items listed there, else `DeathDropChanceMultiplier` / `DeathDropAmountMultiplier`; the rated chance stops at 100%, as for any monster), then the Pot. With the shipped `Rates.ini` that is chance x4 and Adena amount x5, so the House's 1.5-2.25M Adena comes to 7.5-11.25M before the Pot. Champion and premium bonuses don't apply.

**The Den's Adena stops at 150M a run** (`GamblersDenMaxAdena` in `MapDevice.ini`, 0 for no limit): all its own drops together, monsters, Jackpot Chest, Bluffs and the House, after the rates and the Pot; past it the Den drops no more Adena. The XML amounts are set so that even the luckiest run stays about there: with Adena x5 from the rates and up to x8 from the Pot (x4) and Hot Table (x2), each Adena drop is worth up to 40 times its XML amount, so the House gives at most 90M, the 6 special rolls 30M and the monsters (about 94 kills, Splitters' copies counting half) 30M. A run at Pot x1 with no Hot Table gives about 12-15M. The monsters' own retail drops (and the map drops) work as in any map.

### The Gambler's Den

**The Wheel of Fate** spins every 12 counted kills of the field (Splitters' copies and Raise the Stakes Champions count). Its 24 fortunes come from a shuffled bag: none comes twice before all 24 have come. A fortune holds until the next spin; when the House takes the table every fortune ends. Every spin gives a bet. "Pot +25%" adds 0.25 to the Pot.

| | Blessings | Curses |
|---|---|---|
| 1 | Lucky Streak: players deal +30% damage | Cold Deck: players deal 20% less damage |
| 2 | Golden Touch: Pot +25% | Bad Beat: Pot -15% |
| 3 | Second Wind: everyone inside healed to full HP/MP/CP | Bust: everyone loses 30% of current HP (never kills) |
| 4 | Jackpot: a Jackpot Chest (a Mutant Treasure Chest, triple HP, uncounted) stands somewhere for 30 s: three rolls of the special drops | Raise the Stakes: 6 Champions (tier 1-2, the map's kinds, counted) rush one player |
| 5 | Sleight of Hand: monsters -25% P./M. Def. | Iron Skin: monsters +40% P./M. Def. |
| 6 | Fold: monsters -20% P./M. Atk. | Loaded Dice: monsters +30% P./M. Atk. |
| 7 | Free Spin: Pot +10%, and the wheel spins again 3 s later | Cracked Dice: monsters attack and cast 25% faster |
| 8 | Hot Table: the Den's drop chances x2 | Cold Streak: the Den's drop chances x0.5 |
| 9 | Card Counter: every kill adds 2% to the Pot | Short Stack: one bet in hand is lost |
| 10 | Lucky Seven: every 7th kill also rolls the special drops | Pickpockets: 3 thieves (uncounted) grab for the Pot: each slain Pot +5%, each still loose after 45 s escapes with 5% |
| 11 | Comp'd Drinks: players heal 5% HP and MP every 5 s | Hangover: players lose 4% of max HP every 5 s (never below 1 HP) |
| 12 | High Roller: one more bet | House Rules: the map's monsters heal 3% HP every 5 s |

Monster HP is never changed by a fortune, so a monster's HP can't end up above its maximum. The fortunes reach the boss too (from the Joker), but never the Jackpot Chest.

**The Dealer** (npc `MapDealerNpcId`, 900703, the Lottery Ticket Seller's look) stands beside the Return Portal. His table shows the Pot, the bets in hand, the kills to the next spin, the fortune and the House's card. **Bet the Pot**: even odds, x2 (up to x4) or x0.5 (down to x0.25). The bets belong to the map: anyone inside may place one. The device window shows the same.

**The House's cards** (one deck of 5, shuffled when the House appears, 4 drawn):

| Card | |
|---|---|
| Ace of Blades | The House hits 40% harder and attacks 30% faster. |
| King of Shields | The House is invulnerable until its 3 Card Guards (the map's kinds, double HP, counted) fall. |
| Queen of Mirrors | 2 Bluffs: copies of the House under the same title, at 15% of its HP and half its attack, uncounted. Each slain gives Pot +10% and a roll of the special drops. |
| Jack of Thieves | The House vanishes to another corner, leaving 4 Card Sharps (thieves) behind. |
| Joker | The wheel spins for the House (no bet); that fortune holds until the next card. |

### The Primal Crucible (18115)

*Evolve, or be eaten.* 60 monsters of tier 5 (the toughest solo tier), on the event map `primeval_isle`; until that map exists, in any arena of tier 5 (see *Setting up its arena* below). Modifiers: Fleet, Venomous, Berserkers, Vampiric, Fortified, Lacerating. It is tuned to be soloable: in live server tests a geared solo level 85 Duelist died to whole packs with Hunters and Pack Hunters (even at tier 5), and still died every 30 seconds at tier 6 without them, while a plain tier 5 map was safe. So: tier 5, no Hunters or Pack Hunters, and Stage I comes free. Code: `PrimalCrucibleManager`, called from `UniqueMapManager`. The players type **`.crucible`** for their evolution (it also opens by itself when a choice comes up).

**Essence and Evolution.** Every counted kill gives 10 Essence to each living player inside within 2,000 of it, and 5 more to the killer (double during a Rampage). An Alpha gives everyone 60 more, an Apex Predator 150. **Stage I is free**: entering the map offers the Beast forms at once. Then at **100, 200 and 330** Essence the player evolves again: one form of each path is offered (retail H5 transformations, with their own models and skill bars), or Defiance.

| Stage | Fighter | Caster | Support | HP and damage |
|---|---|---|---|---|
| I Beast | Saber Tooth Tiger or Doll Blader | Veil Master | Heretic | x1.2 |
| II Monster | Dragon Bomber, Lilim Knight, Golem Guardian or Oel Mahum | Inferno Drake | Unicorn or Grail Apostle | x1.5 |
| III Legend | Zaken, Kechi, Gordon, Venom or Demon Prince | Anakim, Ranku or Divine Wizard | Divine Healer, Divine Knight or Divine Enchanter | x2 |
| IV **Titan** | your Legend form and two other Legends | | | x3, giant (BIG_BODY), burning aura, Rampage |

- **Defiance**: refuse the forms and stay yourself, with your own skills: +15% HP and damage for every refusal, on top of the stage. Defying at stage IV makes a **Defiant Titan**: yourself, giant.
- **The Titan**: the aura burns every monster within 250 for 1% of its max HP every 2 s (0.2% for the Primordial). **Rampage** (`.crucible rampage`, good in a macro; once a minute): 10 s of +50% damage that splashes 30% onto the monsters within 200 of the target, double P. Def. and M. Def., +20% attack speed and double Essence.
- **Mutations**: everyone inside is offered 1 of 3 when an Alpha, an Apex Predator or a hatchling dies, for the rest of the run: Horns (+20% P. Atk.), Arcane Gland (+20% M. Atk.), Adrenal Surge (+15% attack and casting speed), Thick Hide (+25% P./M. Def.), Wings (+25% speed), Predator's Eye (+30% critical rate), Hunger (heal 3% of max HP per kill), Regeneration (triple HP regeneration).
- **The forms never leave the map**, like the Coliseum's passives: dying, going back or the map closing takes the form, the giant look, Rampage and every bonus off (and a sweep every second catches anyone who slips out). Coming back puts them on again. After *Transform Dispel*, `.crucible form` (or the button) takes the form again.

**The Food Chain.** Every 40 s, the monster with the most meals (not fighting) eats one with no more meals within 800; a monster that has eaten already **hunts** farther: it walks up to 3,000 to its next meal (announced, given up after 30 s or if players attack it), so Alphas grow even when the monsters are spread out. The meal vanishes (it no longer counts toward the clear), the eater heals and grows (+40% HP, +12% attack, +5% defence per meal). **3 meals: an Alpha** (giant, "[Alpha]"), which drops the special drops, more often the more it ate. **6 meals: an Apex Predator**, which hunts the player with the most Essence. At most 12 meals a run, and none once 6 monsters or fewer are left. Every meal is announced, with where it happened.

**The Primordial** (a Tyrannosaurus, x3.5 HP, about 4-5M with Fortified, the random passives and a full +48%, x1.6 attack, x1.5 defence) rises once the valley is cleared, **+4% HP for every meal the field ate**. At 60% and 20% of its HP it lays **3 eggs** (Ancient Eggs, uncounted) at the edges of the field; each hatches after 20 s into a **hatchling Alpha** unless smashed. At 40% it goes into a **frenzy** (+25% attack and attack speed).

**Rewards.** The drops are in the XML (the special group is the Alphas'). When the Primordial dies everyone inside gets **Primal Essence** (18116): 10, 12 if a Legend stood in the Crucible, 30 if a Titan did. **30 Primal Essence buy a Titan Scroll** (18117, `.crucible` outside the map, *Buy a Titan Scroll*). The **Titan Scroll** shows your last Titan form, giant, for 30 minutes in a town or another peace zone. It is looks only: the form is a non-combat copy (`data/stats/transformations/09xxx.xml`, id 9000 + the form's id, its `displayId` the real form), with no stats of its own. It ends when you leave the peace zone, use Transform Dispel, or the time runs out. A Defiant Titan is just giant. It needs a Titan reached once (player variable `CRUCIBLE_TITAN_FORM`).

**Setting up its arena.** In game on Primeval Isle, type `//mapdevice`, *New map here*, give it the id **`primeval_isle`**, tick *Map Device*, place the fence and the entry, and under *Monsters that fill it* list the island's dinosaurs (Velociraptor, Ornithomimus, Deinonychus, Pachycephalosaurus, Tyrannosaurus...): they are made stronger by tier 5. Save. From then on the Crucible opens there.

### The Ketra-Varka Front (18118)

*Pick a side. Then maybe pick the other one.* Tier 1, 80 monsters, both factions (the Ketra Orcs and the Varka Silenos of level 77-78, without the Commanders, Head Shamans and Prophets whose guards would follow them: `npcs` of its `<uniqueMap>`), in the Ruins of Despair. Modifiers: War Priests, Summoners, Bounty Mark, Fleet. Code: `KetraVarkaFrontManager`. The players type **`.front`** (the War Banner, the envoy and the totems open the same window).

- **The field**: three **war totems** on the line from the entry to the far side (the Near, Middle and Far Totem), the monsters shared out in sectors round them, half of each side.
- **The oath**: at the **War Banner** by the Return Portal (its window opens by itself the first time a player comes in) the first player to swear to Ketra or Varka picks the side for everyone. Every monster of that side in the field becomes one of its **warriors** (Ketra Warrior/Archer, Varka Warrior/Hunter: `QuestGuard`s, so players can't hit them and their area skills spare them) and leaves the count; the side's **Prophet** stands at the camp by the entry (safe until the siege). Without an oath the Front plays like a plain map.
- **The warriors** fight the nearest enemy within 1,200 every 3 seconds, else walk to the nearest totem you don't hold. Every 30 seconds each totem you hold sends 2 more (14 at most).
- **The totems** use Conquest's capture rule (`ConquestCapture.step`): the players and their warriors within 600 against the enemy within 600; 5% a second with one more, +2.5% for each more, 15% at most, only with 25% more, never with 10 of each. A totem's flag and title show its owner and progress. The enemy of a sector you hold is **20% weaker** (attack and defence).
- **Counterattacks**: 3 minutes after the first capture, and every 3 minutes after (4 at most, none once the field is cleared), 6 enemies march on a totem you hold. They count toward the clear.
- **Betrayal**: holding 2 totems brings the enemy's **envoy** to the banner, once a run, for 2 minutes. Taking the deal turns your warriors into monsters of their side (counted), the enemy's monsters into your warriors, and its Prophet takes your camp; the totems you hold stay yours. The Front's drops (map, special, boss) come **50% more often**.
- **The Siege of the Camp**: the enemy General (the Ketra Orc General or the Varka Silenos General, x30 HP, x1.3 attack and defence) marches on your Prophet from the far totem with **3 Siege Totems** (Wards of Death, x8 HP, counted): each one standing makes him 15% stronger. At 75/50/25% of his HP he calls 3 **escorts**. The siege totems and escorts drop the special drops. **If the Prophet falls** he hits 30% harder and his own drops come half as often.
- **The title**: everyone inside when the Warlord falls is named **Hero of Ketra**, **Hero of Varka**, or **the Twice-Sworn** after a betrayal, for 7 days (player variables `FRONT_TITLE`, `FRONT_TITLE_END`). It shows only over an empty title, through the title-carrying packets (`LuckyLootManager.decorateTitle`): the saved title never changes. `.front` outside the map shows it and its end.
- **The alliance**: the Ketra and Varka of any map (the Front, and the tier 1 maps) are not the barracks': killing them costs no Ketra or Varka alliance, and the marks of an alliance don't keep them from attacking you (`ai/others/VarkaKetra`).

### The Tomb Raiders (18119)

*You are not the only party down here.* Tier 2, 70 monsters of the Imperial Tomb (the Imperial Guards, Royal Guards, Healers and a Commander), in Silent Valley. Modifiers: Shieldbearers, Armoured, War Priests, Volatile. Code: `TombRaidersManager`, and the rivals' `FakePlayerPvpAI.thinkMapRival`. The players type **`.tomb`** (the reliquaries and pedestals open the same window).

- **The rivals**: when the first player comes in, a rival party of 4 **Tomb Raiders** (fake players: one support, three fighters, 2 levels below that player, at most 78) comes in from the far side (`FakePlayerPvpManager.spawnMapRival`; the only fake players a map ever has). They race you for the reliquaries, hunt the tomb's monsters (their kills count toward the clear), attack a player breaking a seal within 900, and ambush the players near them when they are behind in relics. They never flee, escape, log off or come back after dying, and they stay flagged: killing them, or being killed by them, is no PK, no PvP count and no announcement.
- **The reliquaries**: 5 **Sealed Reliquaries** spread over the field. Talk to one (or `.tomb break` beside it) to break its seal: **30 seconds** of standing still, broken by any hit (HP or CP lost), a step, or death. A rival breaks one the same way, standing still out of a fight, and a hit breaks it too. You bank your relics; a rival carries its own, and **drops them to you when slain**.
- **The parley**: when the second reliquary opens the rivals' leader calls a parley (a window for everyone inside, 30 seconds). **A Truce**: the rivals join your party (a fake party, like invited fake players: they heal, buff and fight beside you) and every relic is shared, yours being half of them, rounded up. **Rivalry** (or no answer): winner takes all.
- **The Undying Emperor** (an Imperial Commander, x35 HP, x1.3 attack and defence) rises in the middle with **4 Honour Guards** (Imperial Royal Guards, x3 HP, uncounted, no drops) at 4 **Relic Pedestals**. While one stands he takes **half damage**; a slain guard rises again at its pedestal 10 seconds later, and at half his HP every fallen guard rises at once, unless its pedestal is **sealed**: talk to it (or `.tomb seal`) and spend a relic. In Rivalry the rivals come for the Emperor too, and every 15 seconds one turns on a player: a three-way fight. **If a rival lands the last blow** his spoils are halved (they still fall for the owner, or someone inside).
- **The spoils**: every relic you hold at the end (minus those spent on the pedestals) is **one roll of the special drops**. All five relics yours (not in a Truce) add a **Map (Tier 3)**, within the map budget.

Both maps keep the world regions of their field awake while they are open (`UniqueMapManager.keepAwake`), so the warriors, the counterattacks and the rival party carry on where no player stands.

### The Coliseum's passives

They use the Deathmatch: Mayhem picks (`PassiveDeathmatchManager.joinMap`): the same offers (`MayhemChoices`, class-aware, a keystone turn every `MayhemKeystoneEvery` picks), the blinking question mark and `.dmpick`, but stacked on the player's own tree (`PassiveTreeManager.setBonusNodes`) rather than replacing it, and without Mayhem's PvP balance. **They never leave the map.** They apply only while the player stands in the map's instance: the hideout, the world, anywhere else, they are off. Dying or going back to the hideout keeps the *picks* (they come back on re-entering the map while it is open, and you may pick in the hideout, applied once back inside), but never their effect. This is enforced on its own at every layer (`PassiveTreeManager`, the map passives are bound to the instance they were given in):
- they are refused to a player who is not standing in that map;
- every instance change (death, portal, `.hideout back`, a GM teleport, the map closing, the instance being destroyed) takes them off before anything else, strips the tree's proc buffs (Last Stand, Blade Dance... skills 27470-27487) and lowers HP/MP/CP to the new maximums;
- every stat rebuild leaves them out unless the player is inside a map still open in that instance;
- a login keeps them only if the player logs in inside that map;
- a sweep every second takes them off anyone offline, elsewhere, or whose map has closed (and logs it).

While they are stacked the tree can't be changed.

## The quest: A Map of Your Own

The first time a character gets a map (a drop, a trade, a GM gift), a quest pops up: the blinking tutorial question mark (it needs the client fix in `client/TutorialQuestionMark`) and a line in chat. Clicking the mark, or `.hideout quest`, opens the quest window with its steps and reward. Each new step raises the mark again; an unclicked mark comes back at login.

1. **Go to your hideout**: `.hideout` in a town.
2. **Open the map** at the Map Device.
3. **Take a portal** into the map.
4. **Clear the map**: be inside a map when its last monster falls.

A step already done is passed at once: a map picked up in the hideout skips the first, and a party member who enters their party's map skips to the last. Clearing the map pays `MapQuestRewardMedals` Champion Medals (5). Each character does it once (player variable `MAP_QUEST_STEP`). `MapQuestEnabled = False` in `MapDevice.ini` turns it off.

## Hot zone effect

Every map rolls one of the server's hot zone modifiers for its whole instance (`HotzoneModifierManager.setInstanceModifier`), shown on the device. Those that need a world hot zone or respawning monsters are excluded (`ExcludedHotzoneEffects`: Bounty Hunt, Rising Heat, Miniboss Frenzy, Blood Moon, Contested Ground). Their spawn multipliers (Coven, Thieves' Den, Elemental Storm...) apply to the map's own variant rolls, their player buffs are given inside the map, and Fragile Ground drains HP there.

## Making your own maps (the map editor)

Maps open in places made on the **event map page**, the same editor as the Team vs Team, Conquest, Deathmatch and Race Wars battlefields (`data/maps/<id>.xml`). The four arenas of `data/MapDevice.xml` stay in the rotation next to them (delete them there if you only want your own; an `<arena>` there can also take `minTier`/`maxTier`).

1. In game, stand where the map should be and type **`//mapdevice`**: the page opens on the **Map Device** points. *New map here* starts a map round you (or pick an existing battlefield: one place can serve several events).
2. Tick **Map Device** under *Used in*.
3. Put up the **fence** (it is the field's edge; nothing gets through it, the monsters are placed inside it).
4. Place the **entry** (where the players come in through a portal, facing into the field; the Return Portal stands there). Its ring is `EntrySafeRadius`: no monster stands that close.
5. Optional:
   - **Monster spots** with a spread radius: the monsters are shared out among them in turn (about 100 / spots each). Without spots they are spread over the whole field.
   - **Map Guardian spot**: where the Map Guardian modifier's boss stands (else the fence's middle).
   - **Lowest / highest tier**: which map tiers may open here (any by default).
   - **Monsters in the map**: overrides `MonsterCount` for this place (the modifiers still change it).
   - **Monsters that fill it**: npc ids of `type="Monster"` npcs. Every tier then uses exactly these, made stronger by the tier; the page shows their names, levels and strength, and warns about a level or a strength far from the tiers, and about monsters of very different strength in one map. Empty: 6 kinds of the tier's levels and strength by chance.
   - **Doors**: click a door on the map to have it open or shut in the map's copy of the place (good for dungeons).
6. **Save** (it checks the map and warns about what's missing, like an entry beyond the fence or monster spots reaching the entry). `//mapdevice show <id>` draws the points in game.
7. **Try it**: in your hideout (`.hideout`), `//mapdevice test <id> [tier] [medals]` opens a map there for free.

A saved map takes part at once: the next map item opened (of a tier it allows) may land in it. `//mapdevice list` shows every place and whether it is ready.

## Commands

| Command | |
|---|---|
| `.hideout` | Go to your hideout (from a peace zone). In a hideout or a map: open the Map Device window. |
| `.hideout party` | Visit the hideout of your party (the leader's first). |
| `.hideout back` | From a map, back to the hideout (not while fighting). |
| `.hideout leave` | From the hideout, back to where you came from. |
| `.hideout quest` | The quest "A Map of Your Own". |
| `.crucible` | The Primal Crucible: inside it your stage, Essence, Mutations and choices (`evolve <n>`, `mutate <n>`, `rampage`, `form`); outside, the Titan Scroll exchange (`exchange`). |
| `.front` | The Ketra-Varka Front: your side, the war totems, the Prophet, the envoy (`swear ketra`, `swear varka`, `betray`); outside, your Hero title. |
| `.tomb` | The Tomb Raiders: the relics, the reliquaries, the parley and the pedestals (`break`, `truce`, `rivalry`, `seal`). |

GM commands (`//mapdevice`, the same access as `//tvt` for the page):

| Command | |
|---|---|
| `//mapdevice` | The map editor, on the Map Device points. |
| `//mapdevice list` | Every place a map can open in, from the editor and from `data/MapDevice.xml`, and whether it is ready. |
| `//mapdevice show <id>` / `hide` | Draw an editor map's Map Device points in game / take them away. |
| `//mapdevice test <id> [tier] [medals] [mods=A,B]` | In your own hideout: open a map in that place for free, to try it. `mods=` names its modifiers (MapModifier names, e.g. `mods=CHAIN_REACTION,RAMPAGE`) instead of rolling them. |
| `//mapdevice give <tier> [count]` | Give maps to your target (or you). |
| `//mapdevice unique [list]` | The unique maps. |
| `//mapdevice unique give <COLISEUM\|VAULT\|OSSUARY\|DEN\|CRUCIBLE\|FRONT\|TOMB> [count]` | Give a unique map to your target (or you). |
| `//mapdevice unique test <COLISEUM\|VAULT\|OSSUARY\|DEN\|CRUCIBLE\|FRONT\|TOMB>` | In your own hideout: open that unique map for free. |
| `//mapdevice pool <tier>` | The monsters a map of that tier may hold, with their strength and each stat against the standard monster of their level. |
| `//mapdevice score` | The strength of your target, and which tiers it fits and drops. |
| `//mapdevice runs` / `close <instance>` | The maps open right now / close one. |
| `//mapdevice reload` | Read `data/MapDevice.xml` and the event maps again, and rebuild the monster pools. |

### Trying modifiers without a game client: the map probe

`POST /api/mapdevice/probe` on the simulation web API (`SimulationWebOpenAccess`, see `tools/simulation/README.md`) opens a map with the modifiers named, with no hideout and no portals, and has a client-less player of its own (the character **MapProbe**, made once in the database, level 85, invulnerable by default) slay the nearest monsters hit by hit (`MapProbe`). It answers, as JSON, what the map did: kills, monsters left, chain kills, Rising Stakes steps, Rampage and Momentum (also after a wait), the probe's run and attack speed, a sample monster's multipliers, and every line the map said to the players inside. Then it closes the map (`keep=1` leaves it open).

```
curl -X POST http://127.0.0.1:8788/api/mapdevice/probe -d 'tier=3&mods=CHAIN_REACTION,PACK_HUNTERS&kills=30&seconds=60&pace=100'
```

Parameters: `arena` (default the tier's first), `tier` (3), `mods`, `kills` (30), `seconds` (60, at most 900), `pace` (ms between hits, 250), `hit` (share of a monster's max HP per hit, 0.3), `after` (seconds to wait at the end, 0), `invul` (0 to let the probe take damage), `keep`.

## Setup notes

- **Client**: the map items reuse the look of the retail *Pirate's Treasure Map* (`displayId` 5915) until the client is patched with `client/MapDevice` (item names and EtcItemgrp rows); then remove the `displayId` lines from `data/stats/items/custom/map_device_items.xml`.
- **Places**: the hideout and the four arenas of `data/MapDevice.xml` were checked against the geodata in `data/geodata` (single ground layer, little height difference). Your own places need open ground the monsters can walk on: the fence follows the ground, and monsters are placed from the middle (or from their spot) outwards, never through a wall or the fence. The editor's terrain view shows walls and cliffs in red.
- **Restarts**: open maps and hideouts live in memory. A restart closes them (the map and medals are spent); players inside are moved back to where they entered the hideout from when they log in.
