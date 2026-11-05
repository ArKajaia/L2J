# Maps and the Map Device

An endgame loop after the atlas of Path of Exile: high level monsters drop maps, a player opens them at the Map Device of their private hideout, feeds them Champion Medals for harder modifiers and better rewards, and fights through a fenced field of monsters that never respawn.

Code: `MapDeviceManager` (core logic), `MapQuestManager` (the quest "A Map of Your Own"), `MapDeviceData` (`data/MapDevice.xml`), `MapDeviceConfig` (`config/Custom/MapDevice.ini`), `model/mapdevice/*` (modifiers, tiers, arenas, runs, `MonsterStrength`), the Map Device part of the event maps (`EventMapType.MAPDEVICE`, `EventMapDevice`, edited on the event map page), and the scripts `custom/MapDevice` (device window, NPCs), `handlers/chat/commands/voiced/HideoutVoiced` (`.hideout`), `handlers/chat/commands/admin/AdminMapDevice` (`//mapdevice`) and `handlers/items/MapItem`.

## How it plays

1. **Maps drop.** Monsters of level 78 and up may drop a **Map (Tier 1-10)** (items 50101-50110). The tier follows the monster's level and strength (`dropLevel` and `minScore` in the XML: solo monsters drop tiers 1-5, party monsters 6-10), and stronger monsters drop higher tiers: Champion tier 2 +1, tier 3 +2, Mage/Thief/Infused/Resonant/wave challenge +1, raid boss +2 (`<difficulty>`). Base chance 1%, +3% per champion tier, 50% for raids, all raised by the monster's own drop rate bonuses (random passives, hot zones). No drop from monsters more than 9 levels below the player.
2. **`.hideout`** in a town (any peace zone) takes the player to their own hideout: a private copy of the Jinia Guild Hideout cabin with the **Map Device** in it.
3. **Insert a map.** Double-click a map in the hideout, or talk to the device and press *Insert*. The device asks how many **Champion Medals** (the Survival Arena medal) to feed it: 0 to 8.
4. **Each medal** rolls one **modifier** out of 50 (never twice the same) and adds **+20% experience and +20% drop rate**. The map also rolls one random **hot zone effect** (the server's `HotzoneModifier`s) for its whole field.
5. **Eight portals** open in a ring round the device. Each takes one player into the map and is gone. The owner and the owner's party may take them (`.hideout party` brings party members to the hideout).
6. **The map** is a fenced field (the Team vs Team barrier code) in its own instance, picked at random among the places that take its tier (the `<arena>`s of `data/MapDevice.xml` and your own maps from the map editor), with **100 monsters** of the tier's levels and strength, made stronger by the tier (`hp`/`attack`/`defence`) and carrying every rolled modifier. A map has a theme: its monsters are drawn from up to 6 kinds. Nothing respawns. When 10 monsters are left they come for the players, so none stays hidden.
7. **Dying** in a map: 3 seconds later the player wakes up in the hideout (full HP/MP/CP) and may take another portal. **No experience is lost** dying in a map (`DeathExpLoss = False`; the Death Penalty debuff still applies as anywhere). A **Return Portal** at the map's entry (or `.hideout back`) goes back to the hideout without dying.
   - **Leaving the party**: a player who leaves (or is removed from) the owner's party may farm on in the map. When they die or leave it, they go to a hideout of their own, made for them if needed, not to the owner's (and can't take the owner's portals again).
   - **No fake players**: maps and hideouts are private, so no fake player ever spawns in them. A Resonant monster in a map fights alone: none of the helpers or rivals it calls in the open world come.
8. **Completing** a map (every monster slain) drops one more map and closes it 2 minutes later. A map also closes after 60 minutes, or 20 seconds after its last portal is used and nobody is left inside. The owner can close it early at the device.

The first map a character gets starts the quest **A Map of Your Own** (see below).

Monsters inside a map drop maps at 2.5% (times the map's drop multiplier), of the map's tier, 25% of the time one higher and 5% two higher, so good runs sustain themselves.

## Tiers

| Tier | Item | Monster levels | Strength | Monsters | HP | Attack | Defence | Exp/drops | Drops from |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 50101 | 78-79 | 0-1.0 | Ketra/Varka, the grazing beasts (48 kinds) | x1.00 | x1.00 | x1.00 | x1.00 | level 78 |
| 2 | 50102 | 78-80 | 1.0-1.5 | the Imperial Tomb, the beetles (117) | x1.05 | x1.03 | x1.02 | x1.10 | level 78, strength 1.0 |
| 3 | 50103 | 80-82 | 0.8-1.5 | Ragna Orcs, Grave Robbers, the dinosaurs (54) | x1.10 | x1.05 | x1.03 | x1.20 | level 80, strength 0.8 |
| 4 | 50104 | 82-84 | 1.0-1.5 | Sel Mahum, Mucrokian, Solina (41) | x1.20 | x1.10 | x1.05 | x1.30 | level 82, strength 1.0 |
| 5 | 50105 | 81-84 | 1.5-2.6 | tough solo monsters: Spiked Stakato, Tanta Lizardmen, Contaminated Batur (23) | x1.10 | x1.05 | x1.03 | x1.45 | level 81, strength 1.5 |
| 6 | 50106 | 82-85 | 2.6-3.8 | light party: Hellbound scouts and mystics, Drake Scout/Mage (11) | x1.00 | x1.00 | x1.00 | x1.60 | level 82, strength 2.6 |
| 7 | 50107 | 82-85 | 3.8-5.0 | Drakos, Divinity, Dragon Valley scouts, Hellbound watchers (23) | x1.00 | x1.00 | x1.00 | x1.80 | level 82, strength 3.8 |
| 8 | 50108 | 82-85 | 5.0-6.5 | Maluks, Dragon Knights, Drake Leader, Tully's Workshop (21) | x1.00 | x1.00 | x1.00 | x2.00 | level 82, strength 5.0 |
| 9 | 50109 | 82-86 | 6.5-9.0 | Batwing Drake, Bloody Karik, the Lesser Giants, Hellbound masters (17) | x1.00 | x1.00 | x1.00 | x2.30 | level 82, strength 6.5 |
| 10 | 50110 | 83-87 | 9.0+ | the Seed of Annihilation elites, Knoriks (11) | x1.05 | x1.03 | x1.02 | x2.70 | level 85, strength 9.0 |

The monsters of a map are plain open world monsters of the tier's levels **and** strength, with a respawning world spawn (no quest, event, instance-only or raid monster, nothing that gives no experience or lies still like the cocoons). Exclude more with `<excludedNpcs>`. Tiers 1-5 are solo maps; from tier 6 the monsters are party monsters, whose own strength is the step up, so the tier multipliers stay near 1. `//mapdevice pool <tier>` lists a tier's monsters with their strength.

### Monster strength

Two monsters of the same level can be miles apart: a level 84 Batwing Drake has 14 times the HP and 10 times the P. Atk. of a level 84 Sel Mahum Soldier. So every monster gets a **strength score** (`MonsterStrength`):

1. **Effective stats**: the npc data's stats times the multipliers of the monster's passive skills: the retail npc skills 4408 "HP Increase (0.25x-12x)", 4410/4411 "P./M. Atk." and 4412/4413 "P./M. Def." (Weak to Ultra Strong). Over 7,000 monsters carry them, and they change a lot: a Male Spiked Stakato has "HP Increase (6x)", a Drakos Guardian 4x. The scores read them from the loaded skills, so a change to a skill or an npc's skill list changes the score.
2. **The standard monster of a level**: for each stat (HP, P. Atk., M. Atk., P. Def., M. Def., Atk. Spd.), the lower quartile of that effective stat over every monster of the level. Most monsters of a level share the stats of the ordinary monster of that level, so that is what the lower quartile lands on, and the party monsters don't pull it up. A level with fewer than 5 monsters takes its standard from the levels round it.
3. **Relative strength of each stat**: the monster's effective stat divided by the standard's (Batwing Drake: HP x13.9, P. Atk. x16.0, M. Atk. x18.7, P. Def. x2.6, M. Def. x1.4, Atk. Spd. x1.0).
4. **Score**: the weighted geometric mean of those ratios (weights in `<strength>` of `data/MapDevice.xml`: HP 2, P./M. Atk. 1, P./M. Def. 1, Atk. Spd. 0.5, so toughness and offence weigh the same). 1.0 is an ordinary monster; a Spiked Stakato is about 2, a Maluk Leader 6.1, Knoriks and the Seed elites 10.3. A geometric mean, because the stats multiply into how hard a monster is.

A tier takes the monsters whose level is within `minLevel`-`maxLevel` and whose score is within `minScore`-`maxScore` (0 = no upper limit). If none fits, it takes the closest in strength of its levels and the server log says so. An open world monster drops the highest tier whose `dropLevel` and `minScore` it reaches. The device windows show each tier's strength; `//mapdevice score` shows the score of your target and the tiers it fits.

What still varies inside a map, on purpose: the map's own Champions, Mages, Thieves, Infused, Resonant and wave challenge monsters, the server's random passives (up to +100% HP at 20 passives) and the hot zone effect. In a live test of a tier 8 map the six kinds' plain monsters were within 1.5x of each other (3.8x before effective stats were scored), the median monster 1.7x the weakest, and the strongest single monster (a tier 2 Champion with 20 passives) 6.7x.

## The 50 modifiers

The first 25 change numbers; the other 25 change how the map plays.

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
| 26 | Coven | Gameplay | Mage infestation: 40% of the monsters are Mages that cast without pause and keep their distance. |
| 27 | Champions' Rally | Gameplay | 30% of the monsters are Champions, some of them of the highest tier. |
| 28 | Bloodless | Gameplay | The monsters have no blood: you can't leech life or mana from them. |
| 29 | Hail of Arrows | Gameplay | Every monster fights from range and backs away when you close in. |
| 30 | Thieves' Guild | Gameplay | 25% of the monsters are Thieves that loot the fallen and run once their bag is full. |
| 31 | Elemental Chaos | Gameplay | 40% of the monsters are Infused with a random attribute. |
| 32 | Gauntlet | Gameplay | 8 monsters are wave challenges: they rise twice more, stronger each time. |
| 33 | Horde | Gameplay | The map holds 30% more monsters. |
| 34 | Pack Hunters | Gameplay | The monsters gather in tight packs of 6 to 10. |
| 35 | Titans | Gameplay | Half as many monsters, but giants: triple HP, 30% more damage, double experience and drops. |
| 36 | Volatile | Gameplay | Slain monsters explode a moment later, hitting players nearby for 10% of their max HP. |
| 37 | Undying Legion | Gameplay | 25% of the slain monsters rise once more at half HP. |
| 38 | Soul Eaters | Gameplay | When a monster dies, the monsters nearby devour its soul: they heal 20% and hit 10% harder (up to 5 times). |
| 39 | Rising Dread | Gameplay | Every 10 monsters slain make the survivors 5% stronger, up to 50%. |
| 40 | Berserkers | Gameplay | Below 35% HP, monsters deal 50% more damage and attack 30% faster. |
| 41 | Hunters | Gameplay | Every monster is aggressive and notices you from 50% farther. |
| 42 | Skittish | Gameplay | The monsters run when they're hurt, and lead you into their friends. |
| 43 | War Priests | Gameplay | 30% of the monsters are supporters that heal and empower their allies. |
| 44 | Ambush | Gameplay | Once half the map is cleared, 20 monsters ambush one of you. |
| 45 | Map Guardian | Gameplay | A Guardian holds the middle of the map: a tier 3 Champion that rises twice. It drops two more maps. |
| 46 | Storm Caller | Gameplay | Every 20 seconds lightning strikes one of you for 12% of max HP. |
| 47 | Burning Ground | Gameplay | The ground burns: players lose 1.5% of max HP every 3 seconds. |
| 48 | Flaskless | Gameplay | Potions and elixirs can't be used. |
| 49 | Fragile Portals | Gameplay | The Map Device opens only 4 portals. |
| 50 | Hourglass | Gameplay | The map collapses after 15 minutes. |

Modifiers that draw a kind of monster the server has switched off (Mage, Thief, Infused, wave challenge, Champion) are never rolled.

### Where they hook in

- Monster stats (HP, P./M. Atk, P./M. Def, attack and cast speed) and the per-monster effects (Berserkers, Rising Dread, Soul Eaters): `Monster` getters, via `MapDeviceManager.getMonsterMultiplier`.
- The other monster numbers (speed, crits, evasion, leech, reflect, resistances, regeneration) and every player-side number: stat functions added when a monster spawns or a player enters the map (`WorldObject.setInstanceId` hook), removed when they leave.
- Bloodless: `Totems.getLeechRate`, which every life and mana leech goes through (auto-attack absorb, drain skills, the passive tree, cubics).
- Hail of Arrows: `Monster.getAiType` (ARCHER) and `Monster.getPhysicalAttackRange` (600).
- Hunters (and the last 10 monsters): `Monster.isAggressive` / `getAggroRange`.
- Flaskless: `ItemSkillsTemplate`, which potions and elixirs go through, by hand, auto potions or auto use.
- Experience, SP and drop chance: `Monster.getExpReward` / `getSpReward` / `getCustomPassiveDropMultiplier`.
- Deaths: `Attackable.doDie` (counting, Volatile, Soul Eaters, Undying Legion, Ambush, completion, map drops) and `Player.doDie` (back to the hideout).
- Map monsters are spawned without a spawn point (so they never respawn) and skip the open world's random spawn rolls (`Spawn`); the map rolls Champions, Mages, Thieves, Infused, Resonant and wave challenge monsters itself, at the open world's chances times the hot zone effect's multipliers, or at the modifier's share.

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

GM commands (`//mapdevice`, the same access as `//tvt` for the page):

| Command | |
|---|---|
| `//mapdevice` | The map editor, on the Map Device points. |
| `//mapdevice list` | Every place a map can open in, from the editor and from `data/MapDevice.xml`, and whether it is ready. |
| `//mapdevice show <id>` / `hide` | Draw an editor map's Map Device points in game / take them away. |
| `//mapdevice test <id> [tier] [medals]` | In your own hideout: open a map in that place for free, to try it. |
| `//mapdevice give <tier> [count]` | Give maps to your target (or you). |
| `//mapdevice pool <tier>` | The monsters a map of that tier may hold, with their strength and each stat against the standard monster of their level. |
| `//mapdevice score` | The strength of your target, and which tiers it fits and drops. |
| `//mapdevice runs` / `close <instance>` | The maps open right now / close one. |
| `//mapdevice reload` | Read `data/MapDevice.xml` and the event maps again, and rebuild the monster pools. |

## Setup notes

- **Client**: the map items reuse the look of the retail *Pirate's Treasure Map* (`displayId` 5915) until the client is patched with `client/MapDevice` (item names and EtcItemgrp rows); then remove the `displayId` lines from `data/stats/items/custom/map_device_items.xml`.
- **Places**: the hideout and the four arenas of `data/MapDevice.xml` were checked against the geodata in `data/geodata` (single ground layer, little height difference). Your own places need open ground the monsters can walk on: the fence follows the ground, and monsters are placed from the middle (or from their spot) outwards, never through a wall or the fence. The editor's terrain view shows walls and cliffs in red.
- **Restarts**: open maps and hideouts live in memory. A restart closes them (the map and medals are spent); players inside are moved back to where they entered the hideout from when they log in.
