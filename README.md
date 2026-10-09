# L2J Mobius — Chaotic Throne: High Five

An open-source, Java server emulator for the MMORPG **Lineage II**, implementing the **Chaotic Throne: High Five (CT 2.6)** chronicle. The project reproduces the client/server protocol, world simulation, and content of that chronicle as two independent server processes plus a large data-driven content pack.

Licensed under the **GNU General Public License v3**. Project homepage: `l2jmobius.org`.

This document describes what the codebase actually *does* — its architecture and systems — rather than gameplay patch-note history. It intentionally omits tunable configuration values (rates, thresholds, ports, durations); every system below is configurable through the `.ini`/`.xml` files under `dist/game/config` and `dist/login/config`, but the numbers themselves are left to the operator.

## Table of Contents

1. [Custom (Non-Retail) Feature Set](#custom-non-retail-feature-set)
2. [Architecture at a Glance](#architecture-at-a-glance)
3. [Repository Layout](#repository-layout)
4. [Login & Authentication System](#login--authentication-system)
5. [Network Engine](#network-engine)
6. [World & Entity Model](#world--entity-model)
7. [Stat & Combat Calculation](#stat--combat-calculation)
8. [Skill System](#skill-system)
9. [Item & Economy System](#item--economy-system)
10. [Zone System](#zone-system)
11. [Instance Dungeon System](#instance-dungeon-system)
12. [AI System](#ai-system)
13. [Geodata & Pathfinding Engine](#geodata--pathfinding-engine)
14. [Clan, Residence & Siege Systems](#clan-residence--siege-systems)
15. [Olympiad, Heroes & Seven Signs](#olympiad-heroes--seven-signs)
16. [Minigames & Competitive Systems](#minigames--competitive-systems)
17. [Social & Grouping Systems](#social--grouping-systems)
18. [Community Board](#community-board)
19. [Scripting / Quest Engine](#scripting--quest-engine)
20. [Manager & Background Task Subsystems](#manager--background-task-subsystems)
21. [Security & Anti-Cheat](#security--anti-cheat)
22. [Caching](#caching)
23. [Admin Tools & Console](#admin-tools--console)
24. [Standalone Utilities](#standalone-utilities)
25. [Database Layer](#database-layer)
26. [Data-Driven Content Pack](#data-driven-content-pack)
27. [Build System & Requirements](#build-system--requirements)
28. [License](#license)
29. [Recent Updates](#recent-updates)

---

## Custom (Non-Retail) Feature Set

Beyond reproducing the High Five chronicle, this server layers on a substantial set of original, operator-toggleable systems. The most significant are described below; the rest are cataloged in the summary table at the end of this section.

### Passive Skill Tree

A Path-of-Exile-style node graph layered on top of normal class progression, giving characters a second, independent source of permanent stat bonuses and bonus skills.

- Points are earned from character level rather than a separate currency, so allocation always tracks the character's current level.
- Nodes are organized into six class-archetype sectors (tank, melee DPS, rogue, archer, mage, support), connected by hybrid sectors and a central hub, so a character isn't hard-locked into one branch.
- Node tiers range from small stat bonuses up to rare "keystone" nodes that grant a powerful bonus alongside a deliberate drawback; some nodes grant a real, permanent skill.
- Points can be refunded node-by-node or via a full tree reset.
- Allocation happens on a secure web page linked from in-game, not an in-client window — the Community Board page only shows a read-only summary.
- GMs edit the tree itself in a web editor opened with `//passivetree`: node positions, links, effects, costs, types, skills and icons, plus adding and removing nodes. Saving rewrites `data/passivetree/*.xml` and the new tree is live at once. The same editor also works without the game server, on the tree files of a folder on your computer.

### Archetype & Random Monster Skills

Two independent systems that make monster encounters less uniform by granting extra skills on spawn.

- Every monster is assigned a behavioral **archetype** (aggressive, supporter, mage, fighter, coward, etc.) that grants a small set of real, actively-used combat skills matching that personality.
- Separately, monsters can be granted a handful of **random passive skills** (resistances, masteries, stat bonuses) drawn from a large shared pool, independent of archetype or monster type.
- Passive-skill count scales with monster level, is shown on its nameplate, and adds a visual aura for heavily-buffed instances.
- Both are re-rolled on every respawn, so the "same" monster plays differently over time.

### Fake Player Population & PvP

NPCs built to look and act like real player characters, used to populate the world and add open-world PvP encounters.

- Static fake players stand around town and respond to whispered chat with canned replies, to make the world feel populated.
- Town fake players: about 12 per town (configurable, drifting up and down) live in every major town and village like players do. They arrive by gatekeeper, Scroll of Escape or login (sometimes a whole party back from a hunt saying goodbye), run their own errands (warehouse, grocer, blacksmith, shops, masters, quest npcs), get buffed by the Newbie/Adventurers' Guide or by a buffer character of the town, stand together and talk in general chat with social actions, walk across town, browse private stores, greet players walking by, ask for a little adena as newbies, sit in private stores selling 1-3 crafting materials of their level (sometimes a pricey rare find) that players can really buy from, sit or go AFK, and leave through the gatekeeper, the community board teleport or by logging off, while newcomers arrive. Plans, timings, spots and typing style are random per character, so there is no visible pattern, and the town npcs and walkable spots are found in each town automatically (`FakeTownPlayers*` in `config/Custom/FakePlayers.ini`, `//faketown` to inspect them).
- Roaming PvP fake players are built with real class stats, gear, and skills, and fight players and monsters like a genuine character.
- They engage in PvP for several reasons — retaliation, opportunistic attacks on flagged/karma players, revenge after being killed — using real skill combos.
- Fake party members: invite a roaming fake player you meet on the hunting grounds; the closer its level is to yours, the likelier it joins (nobody comes over on request). In the party it shows in the party window, follows the leader, fights what the party fights, and healers and buffers heal, buff, recharge and resurrect the party. Roaming fake players also hunt in parties of 2-3 (`config/Custom/FakeParty.ini`).
- Clans of fake players: 9 real clans (configurable) with their own crest, level, leader, reputation, wars and alliances. Town, farming and party fake players are sometimes members and show the clan name, crest and a random title. Two of them are at war and their members fight when they meet. Players can declare war on them (they declare back and attack the players of that clan on sight, with no karma for war kills), invite them into their alliance, or invite a fake player that isn't in a clan into their own clan, where it stays for good: saved with its name, class and looks, it logs off and on, shows offline in the clan window, may level up or leave each day (`config/Custom/FakeClans.ini`, `//fakeclans`).
- Castles of fake clans: a castle without a lord is taken by a fake clan (one castle each; 9 clans by default, so all 9 castles have a lord). The lord shows its crest on the castle npcs, sets the tax rate, spends the treasury, accepts or refuses clans that register to defend, and runs the manor on its own: every period it sells all the castle's seeds and buys all its crops at prices of its own, refilled every hour, so the manor always works. Players siege it against the castle's npc guards, and a clan that engraves the artifact keeps the castle. A castle left without a lord is taken again after 30 minutes (`FakeClanCastle*` and `FakeClanManor*` in `config/Custom/FakeClans.ini`, `//fakeclans`).
- PvP spots: three open-world areas made for fighting (Plains of Dion for levels 20-51, Ancient Battleground 52-75, Dragon Valley Entrance 76-85), reached with `.pvp` or the Community Board gatekeeper page. Everyone inside is flagged, kills there are PvP kills and never give karma. Fake players of the spot's levels keep coming there only to fight (no healers or buffers): they fight anyone of another clan, players and each other, drink CP potions, come back after dying to look for their killer, and the one with the longest kill streak becomes the PvP leader of the spot (`config/Custom/PvpSpots.ini`, `data/zones/pvp_spots.xml`, `//fakepvp_spots`).
- Visually and mechanically indistinguishable from real players (same info packets, PvP-flag behavior, generated names), though still NPCs internally. Like a player, a roaming fake player that heals or buffs a flagged player (or a PK) is flagged too.

### Rotating Hot Zones

A rotating set of bonus hunting zones that periodically change location and grant temporary buffs to whoever hunts there.

- A fixed pool of hand-placed zones, grouped by level bracket, with one zone per bracket "active" at a time on a schedule.
- Active zones buff both players and monsters, plus apply a randomly-rolled modifier each rotation (bonus loot, tougher monsters, drain effects, etc.).
- Many modifiers drive the server's other systems instead of plain stats. They can:
  - flood a zone with Thieves (who flee once their bag is full), wave challenges, Mages or rival fake players;
  - make monsters evolve into champions or rage from the first stun;
  - speed up Luck stacks;
  - mark a roaming bounty target;
  - build zone-wide Heat as the kill count climbs;
  - make every monster aggressive;
  - split slain monsters in two;
  - reward parties that hunt together;
  - give a "last stand" buff at low HP.
- Kills inside a hot zone can drop bonus currency and, once enough accumulate, spawn an empowered miniboss.
- Players are notified via announcements and a dedicated teleporter NPC.

### Night Cycle

The retail clock is kept (night is game hours 0:00-5:59, about one real hour in four), but the night now runs through announced phases with its own events, built on the systems above.

- **Phases**: Dusk (the last 10 real minutes of the day) tells tonight's Omen, then Night, the Witching Hour (the last 10 minutes of the night) and Dawn. Each phase is shown on screen and in the announcement chat, and the sky is set with the sunset/sunrise packets.
- **Night Omens**: each night rolls one Hot Zone modifier (Blood Moon, Restless Dead, Hornet's Nest, Coven, Thieves' Den, Metamorphosis, Lucky Stars, Splitting Ground, Champion Surge) or a night-only one (New Moon: monsters notice you from half as far; Full Moon: beasts and animals hit harder and monsters rage from the first stun; Starfall: Luck and jackpots). It covers the open world: not towns, instances, sieges, arenas, PvP spots, jail or raid bosses, and an active hot zone keeps its own modifier. Hot zone coins, minibosses and the flat hot zone champion bonus stay in hot zones. A Blood Moon turns the sky red outside towns.
- **Nightlords**: soon after nightfall one monster in each level bracket rises as a Nightlord in a hot zone the rotation hasn't made active: a tier 3 champion with the Rager archetype that must be beaten in 4 Wave Challenge waves. Everyone who fought it gets a Sealed Cache on top of the wave coins, and its killer is announced. At dawn the Nightlords still alive flee once their fight is over, and the monster comes back as an ordinary one.
- **The Witching Hour**: the Omen gives way to the dead: slain monsters may rise again (20%), undead hit 30% harder, wave challenges are three times as common, XP/SP +20% and the sky turns red. A kill may call a wave challenger nearby. At dawn the sun burns the undead still fighting.
- **Night Watch**: kills in the open world at night (and Nightlords and Shadow Raiders) earn points. At dawn the best 3 online players with enough points are announced and get hot zone coins and Sealed Caches.
- **The Night Market**: at nightfall Varro the Moonmonger (NPC 900360) opens his stalls in Giran next to the Master Blacksmith, and packs up at dawn. He sells Blessed Scrolls of Resurrection and the low and high grade luminous (night) lures that fishermen don't stock, for Gold Dragons (hot zone coins), trades Sealed Caches up a tier (5 Common for a Rare, 4 Rare for an Epic; he never sells caches, since they hold Gold Dragons), and changes Gold Dragons into Ancient Adena at 22 per coin - a little less than the standard way (a coin sells to a shop for 100 Adena, and the Black Marketeer of Mammon sells Ancient Adena at 4 Adena each, 25 per coin), but with no daily limit, level or hour requirement.
- **Black markets in town**: towns keep half their fake players at night (the ones who leave aren't replaced), and two more fake player stores open in each town. Their sellers fence goods for adena - a rare find well below its usual price, Sealed Caches, night lures - and pack up at dawn.
- **Thieves' Night**: in the open world at night Thief monsters are twice as common, and a Thief with a full bag runs off into the dark; players nearby see it on their radar until it is caught or gone.
- **Midnight hidden quests**: four hidden quests whose messenger only comes at night (see [Hidden Quests](#hidden-quests)).
- **Shadow Raids**: twice a night, a warband of 4-8 fake players of one fake clan (one at war with another fake clan if there is one) lands near a player hunting in the open world, and the raid is announced with the nearest town. For up to 20 minutes the raiders attack every player and roaming fake player near them who isn't of their clan or alliance and is within their level range. Killing a raider is never a PK, and pays 300 Gold Dragons, a Sealed Cache and Night Watch points; whoever kills the last one is announced. At dawn the raiders still standing melt into the night.
- **Moonlit Melee**: at night the PvP spots draw 1.5 times their usual fighters. At dawn the leader of each spot (the longest kill streak) is announced as its Night King; a player Night King gets 1,000 Gold Dragons and a Sealed Cache.
- **Children of the Night**: each race feels the night, like the Dark Elves' Shadow Sense: Humans (Watchfire) HP regeneration +20% and Accuracy +3, Elves (Starlight) MP regeneration +25% and Casting speed +5%, Orcs (Blood Moon Fury) P.Atk +6%, Dwarves (Forgelight) P.Def +8% and Accuracy +3, Kamael (Dusk Wings) Speed +8. The Passive Tree gains two keystones on branches of their own: Nocturne in Starfall (at night +12% P.Atk and M.Atk and +4 Evasion, by day -12% P.Def and M.Def) and Dawnbringer in Hallowmere (by day +12% P.Atk and M.Atk and 30% faster HP regeneration, at night -12% P.Atk and M.Atk). Night conditions (`<game night="true"/>`, the tree's `@NIGHT`/`@DAY`) now follow the Night Cycle, so a night a GM forces counts too.
- Players see all of it with `.night` or on the Community Board (`_bbsnight`, with a radar button for each Nightlord and Shadow Raid). GMs step through the phases with `//night day|dusk|night|witching|dawn` (until the clock reaches its next phase, or `//night auto`), set the Omen with `//night omen <name>` raise the Nightlords with `//night nightlord` and start a Shadow Raid with `//night raid` (near the targeted player). Scripts can listen to `ON_NIGHT_PHASE_CHANGE`. Settings are in `config/Custom/NightCycle.ini`.
- The night raid boss Eilhalder von Hellmann now also appears when the server boots during the night.

### Town Life

The towns follow the day and the night. Town life npcs (templates 900400-900446) go about their business and only think while a player is around, so an empty town costs nothing. Each town's streets, shops and squares are worked out once from the geodata and spawns, the same way the town fake players find theirs, so a town needs no coordinates.

- **By day**: townsfolk stroll the streets, look at the shops and stop to talk to each other. Porters and errand runners carry between the warehouse and the shops, a sweeper keeps the streets clean, and guards walk patrols in pairs. Children play tag in the square: whoever is "it" chases the nearest child and tags them, and the rest run away. A town crier shouts the news of the server: raid bosses that rose or fell, the next siege, tonight's Omen, the heroes, the festival. Fishermen stand on the shore at the five harbors facing the water, and dock workers carry along the quay.
- **Dusk**: the children are called home first, then everyone else walks into a house and is gone for the night. A house is a spot in front of a town npc that is walled in on nearly every side. Some retail npcs with nothing to sell, teach or give a quest for go home too: the Schuttgart children Adolph, Linda and old Edwin, the dwarven carrier and deliveryman, the elven bard, Agnes' followers in Goddard, and a few others. The ones on a walking route take it up again in the morning. The lamplighter makes his round.
- **Night**: night watchmen walk the streets calling the hour ("Two o'clock, and all's well in Giran!"), or warning people to stay inside during the Witching Hour. A crowd gathers outside the tavern (Valentine the Brewer in Goddard, otherwise the grocer) and goes home at the Witching Hour.
- **Dawn**: everyone comes back out of their house, each at their own time, and gets on with their day.
- **Players**: townsfolk greet players who walk by. Now and then a child runs up to a player and asks for a sweet. Once a day a character can give a child a sweet (500 adena) and gets a Firework, a Large Firework or Star Shards as thanks. Talking to the crier shows the news of the day.
- **Festival day** (Saturday by default): banners around the square, a bonfire at the tavern, half again as many townsfolk and children, and fireworks over the square at dusk.
- The towns, their size, which ones have children, an optional square or tavern, the retail npcs that go home and the harbors are listed in `data/TownLife.xml`. Every part can be turned off or scaled in `config/Custom/TownLife.ini`. GMs see what each town is doing with `//townlife` (or what the targeted npc is doing), set festival day with `//townlife festival on|off|auto`, and step through the phases with `//night`.

### Arena Challenges & Arena Shop

A solo PvE endurance mode and a matching reward shop, linked through a shared currency.

- Players fight an escalating series of waves against a single boss that gets stronger each wave and can never be permanently defeated — only the player's own death ends the run.
- Rewards scale with waves cleared, with milestone bonuses and a tracked personal best.
- A "Wave Challenge" variant happens spontaneously on ordinary world monsters, rewarding everyone who helped kill its final wave.
- The Arena Shop is a crafting-material store that accepts the currency earned from both challenge types, with a built-in item search.

### Buff Skill Purchase (Player Buff Shops)

Lets players sell their own buffs to other players for a fee, similar to a private store.

- A seller builds a personal list from their own known skills (limited to a server-approved whitelist) and sets a price for each.
- Going live visually reuses the normal private-store display, so sellers are easy to spot in town.
- Buyers browse the list and purchase a buff for themselves or their pet with one click; it's cast immediately and automatically.
- Restricted to normal, non-combat situations (no Olympiad, duels, fishing, transformation, etc.), matching the rules for a normal store.

### Buff Templates (Community Board Self-Buffing)

Lets a player save their own buffs as reusable templates and re-apply a whole set from the Community Board instead of casting each buff by hand.

- Each character has a few named template slots, filled from buffs in their own class skill tree (plus buffs learned from Custom Buff books) — the board never offers buffs of its own.
- A template is applied instantly to the character or to its summon in one click, with no MP, item or reuse requirements.
- Every use is re-validated server-side: each buff must still be known by the character (at its current level) and still pass the template filter, and only the character or its own summon is ever buffed.
- Only buffs the operator lists in the extended skill-duration list qualify, and a strict filter keeps anything that isn't a plain timed buff out of templates (heals, invincibility, Ultimate Defense, Noblesse Blessing, stealth, transformations, toggles, item- or GM-granted skills), and use is limited to out-of-combat, non-PvP situations.

### Tiered Champion Monsters

Rare, significantly stronger monster variants with better rewards.

- Three champion tiers, each with its own stat and reward multipliers, rolled independently on spawn (highest tier tried first).
- Champions hit harder and have far more HP, and reward more XP/SP and better drops, with a chance at bonus items.
- Marked with a distinct title and colored name/aura so they stand out in the world.
- Spawn chance increases in active hot zones, linking this system to the Hot Zone feature above.

### Thief & Mage Monster Variants

Two special monster variants that change how an ordinary monster plays out in combat.

- **Thief** monsters fight normally, but track how many nearby monsters have died while they're alive; killing a "fuller" Thief pays out more bonus adena.
- **Mage** monsters fight as genuine spellcasters — kiting at range and casting instead of meleeing, switching to melee only once their mana runs low.
- Mage monsters drop more loot on death and always yield a bonus Sealed Cache.
- Both are rolled independently on spawn and are mutually exclusive with each other and with the Champion/Hot Zone Miniboss/Wave Challenge states.

### Treasure Chests & Sealed Caches

Two unrelated loot systems that both involve opening something for a reward.

- Treasure chests disguise themselves as identical-looking "mimics" — attacking the wrong one either destroys the real chest's reward or wakes an aggressive mimic.
- Opening a real chest correctly requires a specific key/skill and yields random crafting materials from a Common/Rare/Epic tier.
- Sealed Caches are a separate, simpler system: a chance-based bonus item drop on any kill, one of three possible outcomes (alongside a "Lucky Streak" drop-rate bonus and a "Jackpot" loot reroll) from the same kill-reward roll.
- A Sealed Cache is just an ordinary lootbox item, opened from the inventory — no key or NPC involved.

### Alternative Class Transfer Challenges

A short, solo, instanced trial at the Class Master NPC that stands in for the long class transfer quest chains: clearing it unlocks that tier's Class Master transfer.

- Each tier (1st, 2nd, 3rd class) has a fighter trial and a mage trial, picked by a fallback chain (class, parent class, race, fighter/mage, generic), so more can be added per class without touching code.
- Every race can use it, Kamael included. Kamael take the fighter trials, except Soul Breakers, who are about to become Soul Hounds and take the Archmage trial.
- Entering is an instant teleport from the Class Master into a private copy of a small hall; clearing it sends the player straight back. No one else can enter or affect the trial.
- Objectives run in order and show on screen with a countdown: kill, collect (only marks won inside the trial count), reach a spot, talk, activate seals, protect a ward, survive, defeat a boss, use an item, clear waves, and duel.
- Built from the server's own systems:
  - **Omens** — every attempt rolls a Hot Zone modifier that applies to the whole trial (Kill Streak, Glass Cannon, Restless Dead, Fragile Ground, Last Stand, Metamorphosis, Splitting Ground...), shown before entering.
  - **Wave/Arena Champions** — bosses come back stronger each phase through the Wave Challenge engine; the toughest also get the Survival Arena buffs and enrage.
  - **Mark Thief and Arcane Skirmishers** — forced Thief and Mage monsters: the Thief pockets marks won near it and pays them back (with a bonus for a full bag) when killed; Mage monsters kite and cast.
  - **Rival Shade and Invaders** — the duel is against a fake player built as one of the classes the player is about to become, at their level; a rival may also invade mid-trial.
  - **Rewards** — Survival Arena currency (more for a fast clear) and one Sealed Cache per clear, while the trials themselves drop no hotzone coins and their Mage monsters no caches.
- Falling doesn't kill: the player is knocked out and returns to the entrance with progress kept (or, if configured, the trial fails). A disconnect keeps the trial for a grace period; a relog after it, or after a restart, lands the player back where they entered.
- The requirement is enforced right where the Class Master changes the class, and a clear only counts for the class and class slot that earned it; the transfer uses it up. GMs have `//challenge_status`, `//challenge_start`, `//challenge_complete`, `//challenge_abort` and `//challenge_reset`; players have `.trial`.

### Hidden Quests

Quests no player can look up. When a character quietly meets a secret condition, a messenger NPC walks up to them with a quest mark and offers a one-off quest. Only the server knows what caused the visit.

- **Secret conditions**: some are read from the character (PK count, PvP kills, fame, adena carried, towns visited, quests completed) and some are counted by the server from the day the feature is turned on (deaths, levels gained in a row without dying, orcs, undead, night kills, solo kills, raid bosses and karma players killed, kills at 10% HP or less, kills with no weapon, kills while swimming, times murdered by a PK, Olympiad wins, failed enchantments, fish caught, hours spent sitting in the wild and distance travelled on foot). Nothing is shown when a condition is met, and the messenger comes a few minutes later so the visit doesn't point at the cause. The Night Cycle adds four more: nights survived (hunting outside towns through the night without dying), Nightlords slain, fish caught at night with a night lure, and kills in the Witching Hour.
- **The visit**: the messenger only comes when the player is safe (not fighting, flagged, in an instance, Olympiad, siege, arena or store). It appears nearby, walks up and talks. Only that player can see it. The H5 client has no quest icon the server can put over an NPC's head, so the mark is a `[ ! ]` title, a visual effect on the NPC and the blinking tutorial question mark on screen (clicking it opens the offer). The player can accept, ask the messenger to come back later, or refuse the quest forever.
- **One task per quest (about 15-30 minutes)**, from eight kinds: a pilgrimage to shrines in the wild (pray by sitting, sometimes under vows), a gauntlet of "echo" duelists with their own tricks (wards, blinking, regenerating, splitting), being hunted by ambushes and then their leader (who may flee to a lair), chasing wisps or couriers on the radar, escorting an NPC between stops, holding a vigil at a totem, riddles that point to famous places, and following a will-o'-the-wisp before dawn to a cache its guardians keep. Enemies come from retail monster sets that fit the player's level.
- **Midnight quests** (`nightOnly="true"`): their messenger only comes at night, with at least 15 minutes of it left, and their task ends at dawn - most fail, but a vigil until dawn is won once its boss falls. Four ship: The Long Night (keep the Last Fire burning until dawn), Crown of the Night (the echoes of four Nightlords), Moonlit Waters and The Witching Bell (follow a wisp to the Sunken Cache or the Witch's Grave). They pay hot zone coins, Sealed Caches, night lures and a title.
- **Twenty-seven quests** ship in `data/HiddenQuests.xml`, which holds the conditions, tasks, dialog and rewards and is the place to add more. Rewards depend on the quest: enchant scrolls (blessed ones from the ghost smith), life stones, adena and SP for the player's grade, titles, title and name colours, a wolf pet, or clearing karma and a PK.
- Failing, dying (for most tasks), logging out or running out of time costs nothing: the messenger comes back later. Each quest can be completed once per character.
- GMs have `//hiddenquest` (every quest, its condition and a player's progress), `//hiddenquest_trigger`, `//hiddenquest_send`, `//hiddenquest_complete`, `//hiddenquest_abort` and `//hiddenquest_reset`. Settings are in `config/Custom/HiddenQuests.ini`.

### Master Blacksmith

One NPC in Giran (next to the Arena and the Class Master) that does the work of every blacksmith and explains soul crystals and special abilities (SA).

- **Every blacksmith service**: bestow an SA (C/B, A and S grade), remove an SA, craft dualswords (D/C, B, A, S), upgrade a weapon or swap its type, unseal/reseal armor and accessories, finish Foundation items, craft ingredients, augment and remove augments, Accessory Life Stones and remove attributes. It opens the same lists as the town blacksmiths and the Blacksmith of Mammon, at the same prices.
- **SA search**: type an ability or a weapon ("rsk haste", "focus", "dragon slayer", or initials like "sod") to see which weapons get it, which crystal color and stage each needs, the fee and the exact effect on that weapon (from the retail item descriptions).
- **Weapon and SA pages**: a weapon shows its three abilities side by side; an ability shows what it does and every weapon that gets it. The detail page checks what you carry (weapon unequipped, crystal, gemstones, adena) and bestows the SA in one click, keeping the enchant level.
- **Leveling plan**: from your current crystal to the stage the weapon needs, step by step. Each step lists the monsters and raid bosses that raise it, with level, chance (server rate included), how the soul is shared (last hit, whole party, one random member), raid alive/dead, where they are and a radar mark.
- **My Weapons / My Soul Crystals**: what your weapons can get, what your crystal can make, warnings when the quest isn't taken or you carry more than one crystal, and where to get a free Stage 0 crystal.
- The data comes from the server's own files (SA multisells, `LevelUpCrystalData.xml`, `custom/MasterBlacksmith/sa_effects.xml`), so it matches what the server really does.

### Dwarven Trades

The passive tree lets any character take Spoil, Sweeper Festival, Crystallize and Dwarven Craft, so the dwarven classes get what the tree doesn't give. Spoiling and crafting stay a dwarf's trade.

- **Spoilers** (Scavenger, Bounty Hunter, Fortune Seeker): their Spoil, Spoil Festival, Spoil Crush and Spoil Bomb never miss (Spoil from the tree can). A monster they spoiled gives more when swept, whoever kills it: each sweep item's chance is multiplied by 1.25 / 1.5 / 1.75, and its amount by 1 / 1.2 / 1.4 (1st / 2nd / 3rd class).
- **Crafters** (Artisan, Warsmith, Maestro): +5 / +10 / +15 success rate on recipes below 100% (a 60% recipe works 75% of the time for a Maestro), the masterwork chance ×1.25 / ×1.5 / ×2, and a 5 / 10 / 15% chance to make twice as many of a stackable item (shots, arrows, potions, materials). This works in their private workshops too, so customers come to dwarves.
- **Crystallize**: every dwarven class gets 20% more crystals, and crafters another 10 / 20 / 30% (a Maestro gets 50% more).
- **The tree's Dwarven Craft is the only way to craft for everyone but Artisans, Warsmiths and Maestros**: Create Item is no longer in the other classes' skill trees (the Scavenger line keeps the Dwarven Fighter's level 1, as in retail). The node gives the recipe book and Create Item at your level, like a dwarf learns it, but no higher than level 5 (D and most C grade recipes); it raises a lower Create Item of your own and gives it back when the node goes. B grade and up can only be crafted by the Artisan line. Dwarven crafting needs both Dwarven Craft and Create Item, so refunding the node ends it, but the recipe book is kept and comes back with the node.
- **Combat**: dwarves fight with blunts, polearms, stuns and golems, and the fighting ties into the trade.
  - **Skullcrusher** (both lines): their stun skills (Stun Attack, Hammer Crush, Rush Impact...) land ×1.1 / ×1.2 / ×1.3 as often, still capped by the skill's own highest chance.
  - **Plunderer's Mark** (spoilers): +8 / 12 / 16% damage from their attacks and physical skills (Backstab and Lucky Blow too) on a monster they spoiled themselves.
  - **Spoils of War** (spoilers): every corpse they sweep with loot on it restores 2 / 3 / 4% of their max HP and MP, so a Sweeper Festival over a pack is a breather.
  - **Golem Engineering** (crafters): their golems and cannons get +10 / 20 / 30% P.Atk, M.Atk, P.Def, M.Def and max HP, on top of the tree's summon nodes.
  - **Forged Gear** (crafters): every enchant level above +3 on their weapon gives +0.5 / 0.75 / 1% P.Atk, and on each armour piece (shield too) +0.1 / 0.15 / 0.2% P.Def, up to +15% each. It shows in the character window.
- Everything follows the active class: a dwarven subclass counts, a dwarf playing a non-dwarven subclass doesn't. Settings in `config/Custom/DwarvenTrades.ini`.
- **Fake players**: the Fortune Seeker and Maestro fake players get the combat perks of their class too (Skullcrusher, a Spoil that never misses, Plunderer's Mark, Golem Engineering on their golem, Forged Gear on their gear). A Fortune Seeker spoils the monster it fights first, and the players of its party can sweep it, with its spoiler bonus.

### Performers

Glittering Medals from champions teach every class the songs and dances, so the Swordsinger and Bladedancer lines get what a medal can't give: auras that only a performer can keep up, a combat stance each, a 3rd class finale, and better songs and dances of their own. The Swordsinger line keeps the party standing; the Bladedancer line tears the enemy down.

- **Performances** (toggles, one at a time; switching replaces the old one). While on, everyone in range gets an "echo" every few seconds, and it fades a few seconds after they walk away. Echoes have their own buff slots (the triggered-buff bar), so they stack with every retail buff and medal song and never push one out. The same echo from two performers doesn't stack. Level 1 and 2 at the 2nd class, 3 at the 3rd.

  | Performance | Class line | Who | Effect (level 1 / 2 / 3) |
  |---|---|---|---|
  | Ballad of the Bulwark | Swordsinger | Party within 900 | P. Def. and M. Def. +8 / 10 / 12% |
  | Hymn of Renewal | Swordsinger | Party within 900 | HP regen +20 / 30 / 40%, MP regen +10 / 15 / 20%, heals received +5 / 8 / 10% |
  | Anthem of Valor | Swordsinger | Party within 900 | P. Atk. and M. Atk. +6 / 8 / 10% |
  | Dance of Ruin | Bladedancer | Enemies within 400 | P. Def. and M. Def. −8 / 10 / 12% |
  | Dance of Torment | Bladedancer | Enemies within 400 | Atk. Spd. and Casting Spd. −8 / 10 / 12%, Speed −10 / 15 / 20% |
  | Dance of Frenzy | Bladedancer | Party within 900 | Atk. Spd. and Casting Spd. +6 / 8 / 10% |

  The enemy dances (hexes) land like a dance debuff, then hold while the enemy stays close; they reach monsters and the players the performer could attack without forcing it, never the performer's own party, clan or alliance. A stunned, sleeping or paralyzed performer stops performing until it ends.
- **Stances** (toggles, one at a time, alongside a performance):
  - **Battle Hymn** (Swordsinger line): P. Def. +5%, and each attack has a 15% chance to sing Healing Verse, healing the party within 600 for 120 / 200 / 280 / 360 HP.
  - **Blade Waltz** (Bladedancer line): Atk. Spd. +8%, Evasion +3, and each dual sword attack has a 20% chance to unleash Whirling Edge on every enemy within 200 (300 / 550 / 850 / 1150 power), which turns a Bladedancer into a pack farmer.
- **Finales** (level 79): **Sanctuary Chorus** (Sword Muse) heals the party within 1000 for 20% of its HP and gives P. Def. and M. Def. +30% for 15 seconds (5 minute reuse). **Danse Macabre** (Spectral Dancer) strikes up to 15 enemies within 300 with 3500 power and may cut their P. Def. by 20% and Speed by 30% for 10 seconds (2 minute reuse).
- **Virtuoso**: songs and dances a performer casts last ×1.5 (Swordsinger, Bladedancer) or ×2 (Sword Muse, Spectral Dancer), and singing one more doesn't cost them the extra MP everyone else pays, so a performer's songs outlast anyone's medal songs.
- Performances drain MP for as long as they are on (about 3 MP per second at 76, a stance about 2), proportionate to the level like Vicious Stance.
- The skills are learned from the Grand Masters like any class skill (data/stats/skills/custom/performer_skills.xml). Everything follows the active class. The client needs the rows in `client/Performers` to show their names and icons. Settings in `config/Custom/Performers.ini`.
- **Fake players**: Battle Bards and War Dancers (fake players of the two lines) hunt with their stance on and a performance going, switch performance when they fight a player (a War Dancer from Dance of Ruin to Dance of Torment, a Battle Bard from Anthem of Valor to Ballad of the Bulwark) and save their finale for players. The Sword Muse and Spectral Dancer that join parties play for the party. A fake performer's auras reach its party, players and fake players alike, and its hexes reach what its area skills reach; a player's auras reach the fake players of its party. Virtuoso counts for them.

### Other Custom Features at a Glance

| Area | Features |
|---|---|
| **Automation & convenience** | Client auto-play, auto-potion use, offline shop/offline play, a bank/deposit system, warehouse sorting, a savable buff-"scheme" system, free (item-less) mount use |
| **Progression & balance** | Per-class balance adjustments, per-NPC stat multipliers, a "de-level" system, a Nobless-status grant NPC, transmogrification (cosmetic item appearance) |
| **Economy** | A premium-account system, zero-price merchant selling, configurable private-store range |
| **PvP & social** | PvP kill announcements and reward items, PvP-title name coloring, a "find PvP" locator, boss-kill announcements, a faction (open-world team) system, an in-game wedding/couple system |
| **World & spawns** | Randomized spawn variation, monster enrage-on-crowd-control, Hellbound zone status progression, configurable starting location and allowed player races |
| **Anti-bot & moderation** | Image CAPTCHA challenges, dual-box detection, bot/macro-client ("walker") detection, automated chat moderation, account-side secondary password change |
| **Server identity** | A configurable login welcome screen message, adjustable in-game server time, an online-player-count/info display, a custom starting title, and **multilingual support** — serving server messages and NPC dialogue in multiple languages from the same data pack |

---

## Architecture at a Glance

The system is split into two independently deployable Java server processes that share a common infrastructure library and a MySQL/MariaDB database:

```
                     ┌──────────────────┐
   game client  ───▶ │   LoginServer    │  authenticates accounts, hands out
                     │  (LoginServer.jar)│  session keys, lists game servers
                     └─────────┬────────┘
                               │ private control channel
                               │ (server registration, session hand-off,
                               │  bans, password/access changes)
                     ┌─────────▼────────┐
   game client  ───▶ │    GameServer    │  world simulation, combat, quests,
                     │  (GameServer.jar) │  economy, sieges, everything else
                     └─────────┬────────┘
                               │
                     ┌─────────▼────────┐
                     │  MySQL / MariaDB │  accounts, characters, clans,
                     │   (via HikariCP) │  items, castles, olympiad, ...
                     └──────────────────┘
```

- **`commons`** — infrastructure shared by both servers: the async network engine, configuration/XML-reader framework, database connection pooling, thread-pool management, cron-like scheduling, and shared Swing console utilities.
- **`loginserver`** — account authentication, session issuance, and the registry of connected game servers.
- **`gameserver`** — the actual MMO simulation: world, combat, AI, economy, sieges, quests, and every player-facing system.
- **`dist/`** — the runtime "datapack": per-subsystem `.ini`/`.xml` configuration, XML game-design data, compiled-at-startup Java content scripts, geodata, HTML dialog pages, SQL schema, and third-party libraries.
- The LoginServer and GameServer never share character data directly — the LoginServer only vouches that a session key belongs to an authenticated account; all gameplay state lives in the GameServer and the database.

## Repository Layout

```
java/org/l2jmobius/
├── commons/       shared network, config, database, threading, crypto, UI infrastructure
├── loginserver/   authentication server
├── gameserver/    the MMO game server
├── tools/         standalone admin utilities (DatabaseInstaller, AccountManager, GameServerRegister, Search)
└── log/           logging manager glue

dist/
├── game/          GameServer runtime tree (config/, data/, launch scripts)
├── login/         LoginServer runtime tree (config/, data/, launch scripts)
├── db_installer/  standalone database-installer runtime tree + SQL schema
├── libs/          bundled third-party jars
└── images/        icons / splash art

launcher/          IDE run configurations for the two servers and the search tool
build.xml          Ant build script
```

The `java/` tree currently holds roughly **1,950 Java source files**. Content and configuration are deliberately kept out of the compiled core and live entirely under `dist/`, so an operator can reshape huge parts of the server (quests, AI, drop lists, spawns, zones, item/skill definitions) without recompiling anything.

---

## Login & Authentication System

Authentication is a three-party handshake between the game client, the LoginServer, and the target GameServer.

**Client ↔ LoginServer.** On connect, the LoginServer draws a scrambled RSA keypair and a Blowfish session key from small pre-generated pools (avoiding per-connection key-generation cost) and sends them to the client in an `Init` packet, itself wrapped in a bootstrap cipher. From then on every packet is Blowfish-encrypted with an XOR checksum trailer for integrity. The client authenticates through a legacy GameGuard-style handshake, then submits an RSA-encrypted login/password block; the LoginServer decrypts it, hashes the password, and compares it against the stored hash. A single-active-session policy is enforced: an account already logged in (in memory on the LoginServer or reported online by any connected GameServer) has its stale session kicked before the new one proceeds. Per-account IP allow/deny lists are checked at the same step. On success the client receives a server list (filtered by its access level, and reflecting each GameServer's live status/population) and, on selecting one, a session key.

**Client ↔ GameServer hand-off.** The client connects directly to the chosen GameServer and presents its session key. The GameServer forwards that key to the LoginServer over their private control channel; the LoginServer confirms it matches the session it issued and the GameServer admits the player. The LoginServer never transmits character data — it only vouches for the session key — and it is kept informed of who is online/offline so future login attempts see accurate state.

**LoginServer ↔ GameServer control channel.** This is architecturally distinct from the client-facing network stack: a small number of GameServers connect over a length-prefixed blocking-socket protocol (rather than the async client engine), authenticated with its own separate RSA/Blowfish handshake. A GameServer registers with a numeric server id plus a secret "hex id" (originally provisioned by the `GameServerRegister` tool) and a list of host/subnet pairs, so the LoginServer can hand different clients the appropriate IP for their subnet. Once authenticated, the channel carries live server-status pushes, and administrative packets for access-level changes, password changes, and player kicks.

**Account protection is layered:**
- Permanent bans (negative access level) and time-boxed temporary bans (evaluated live at login time, no background sweep needed).
- Per-account IP allow/deny lists.
- Brute-force protection: failed login attempts are counted per source IP and escalate to a temporary IP ban.
- A static, reloadable operator ban list matched with subnet-prefix fallback (exact IP → progressively wider subnet), so one entry can ban a whole range.
- Server-wide lockdown (enabled / disabled / GM-only) independent of individual account state.
- An optional in-game secondary "PIN" authentication step at character selection (see [Security & Anti-Cheat](#security--anti-cheat)).

---

## Network Engine

**Transport layer** (`commons.network`) is a bespoke asynchronous engine built on true Java NIO.2 async channels (`AsynchronousSocketChannel`/`AsynchronousServerSocketChannel`/`AsynchronousChannelGroup` with `CompletionHandler` callbacks) rather than a classic selector loop:
- Reads are framed as a length header followed by exactly that many payload bytes, driven entirely by chained completion callbacks so no thread blocks on I/O.
- Once a packet is fully buffered and decrypted, execution of its business logic is handed off to a dedicated packet-executor thread pool, keeping the small I/O completion-thread pool free to keep servicing sockets.
- Outbound packets are queued per client and drained through a fairness scheduler so one chatty connection cannot starve others' traffic.
- Buffers are pooled, size-classed, direct `ByteBuffer`s to minimize garbage-collector pressure under many concurrent connections; a "broadcast" packet mode serializes once and reuses/re-encrypts the same buffer per recipient instead of re-serializing identical broadcast traffic.

**Game client protocol** (`gameserver.network`) layers the actual Lineage II wire protocol on top of that transport. Opcodes are declared in Java enums together with the connection states each is legal in (`CONNECTED`, `AUTHENTICATED`, `ENTERING`, `IN_GAME`, …); a packet sent in the wrong state is silently dropped rather than acted on. A reserved opcode byte escapes into an extended "Ex" opcode space (itself two-level in places, e.g. teleport bookmarks nest a further sub-id) so the protocol isn't limited to 256 message types. In total the client protocol spans on the order of **300 inbound** and **480+ outbound** packet types — the full High Five client protocol surface, roughly 800 distinct message types — backed by enormous localization tables for server-driven system messages and NPC dialogue strings (on the order of 10,000 and 18,000 entries respectively). Session encryption uses a stateful XOR stream cipher keyed per-session (handed to the client in the initial key packet); the very first packet (protocol version negotiation) is sent unencrypted and validated against a configurable accepted-version list, and a legacy GameGuard probe/response exchange is retained in the protocol for client compatibility.

**Flood protection** sits on top of the packet dispatcher: each connected client owns roughly fifteen independent counters, one per sensitive action category (item use, dice rolls, chat, multisell, mail, item drop, character selection, auctions, generic transactions, etc.). Exceeding a category's threshold can automatically kick, temp-ban, or jail the account, with GM accounts exempt and violations logged separately for review.

**Inter-server protocol.** A separate packet family implements the private GameServer↔LoginServer link described above (authentication, player-session hand-off, access-level/password changes, kicks, server-status pushes), independent of the client-facing stack.

---

## World & Entity Model

Every placeable object in the world derives from a common `WorldObject` base (identity, position, world region, event-listener wiring). The living/combat-capable branch is `Creature`, from which the full actor taxonomy fans out:

- **Playable actors** — `Player` (the player character), and `Summon` (pet/servitor base) specialized into `Pet` (→ `BabyPet`), `Servitor`, `Decoy`, and `TamedBeast`.
- **NPCs** (`Npc`) — split into `Attackable` (anything that builds threat and can be fought: `Monster` → `RaidBoss`, `GrandBoss`, `Chest`, treasure/event/festival variants; `Guard`; siege `Defender` → `FortCommander`) and `Folk` (non-aggressive town NPCs: merchants, teleporters, fishermen, warehouse keepers, trainers, village masters per race/class, doormen, auctioneers, class/scheme/Olympiad managers, and more).
- **Vehicles** — `Boat` and `AirShip` (→ `ControllableAirShip`), each with their own movement AI.
- **Siege/world furniture** — destructible siege `Door` objects, `Tower` (→ `ControlTower`, `FlameTower`), inert `StaticObject` decorations, and non-creature `Fence` obstacles consumed by the geodata layer.

Each actor type is backed by a template (`CreatureTemplate` → `NpcTemplate`/`PlayerTemplate`/`DoorTemplate`) carrying its XML-declared base attributes, and by a parallel `Stat`/`Status` class hierarchy that mirrors the actor tree — `Stat` classes own computed combat values, `Status` classes own regeneration and death bookkeeping.

The **data holder layer** (`gameserver.data`) that backs all of this is broad and consistently patterned — roughly ninety dedicated loader/holder classes turning XML or database tables into typed in-memory singletons at startup, covering NPCs, items, skills, spawns, doors/fences, teleports, multisell, buylists, recipes, enchanting, item sets, elemental attributes, experience curves, classes, hennas, fishing, pets, transformations, static objects, category metadata, map regions, siege scheduling, initial equipment/shortcuts, karma, admin access, fake players, localization, and more.

---

## Stat & Combat Calculation

Character and NPC combat power is not computed by one formula but by a **composable pipeline**: six root attributes (`STR`/`INT`/`DEX`/`WIT`/`CON`/`MEN`) each feed a per-level bonus curve; derived combat stats (max HP/MP/CP, P./M. Atk & Def, speed, critical rate, resistances, etc.) are each backed by a `Calculator` — an ordered table of pluggable `Func` objects (add/subtract/multiply/divide/set/enchant-scaling functions) executed in priority order. Equipment, skills, active effects, hennas, and armor sets each contribute their own functions into a creature's calculator set, which is recomputed on demand whenever any of those change. Higher-level combat resolution (hit/damage/critical outcomes) is centralized on top of these calculated stats.

---

## Skill System

Skills are data-defined (id, level, costs, ranges, abnormal timing, etc., loaded from XML) but their **behavior is pluggable**:

- A skill's operation mode — active, passive, toggle, channeled, continuous/self-continuous, dance/song, transformation, triggered, static-reuse, PvP-only, clan, hero/GM/Seven-Signs-restricted, and more — is expressed through classifier flags on the core `Skill` object.
- **Effects** (what a skill actually does) implement a common lifecycle contract (`canStart` / `onStart` / per-tick `onActionTime` / `onExit`, plus success-chance and stat-function contribution hooks) and are resolved **by name at load time** through a reflective effect-handler registry, rather than being compiled into the core engine. The concrete vocabulary — buffs/debuffs, damage-over-time, heals, the full crowd-control set (paralyze, root, sleep, fear, mute), dispel variants, resurrection, physical/magical attacks, stat drains, and more — lives as roughly **165 independent effect implementations** in the content pack.
- **Conditions** (whether a skill/effect may apply at all) are a similarly pluggable library of ~90 classes covering logical composition, player-state checks (level, CP, charges, castle/fort/clan-hall ownership, transformation/flight state), inventory/weapon checks, and game-state checks (chance rolls, time-of-day, distance).
- **Targeting** is two-axis: *who* can be selected (single, area, aura, clan/clan-member, command channel, corpse variants, enemy summon, ground-targeted, holy, flagpole, …) and *how broadly* the effect then applies (fan, point-blank, ring/range, party/party-pledge, and boss-specific scopes) — both resolved through their own pluggable handler registries (~34 target-type implementations).
- **Buff/debuff bookkeeping**: an aggregate per-creature effect list enforces stacking/replacement rules by abnormal category, tracks per-tick scheduling for each active effect, and distinguishes normal expiry from cancellation.
- **Channeling and toggles** are first-class: channeled skills track every simultaneous channeler for effects that scale with participation; toggles are persistent until explicitly turned off.
- **Learning and progression**: skill trees drive normal class learning, subclass certification, and class-transfer routes; a separate skill-enchant system lets a learned skill itself be leveled up further, with its own per-route success/cost tables and bonus items.

---

## Item & Economy System

Item **definitions** (`Weapon`, `Armor`, `EtcItem`, `Henna`) are separate from live, ownable **instances**. Built on top of that base:

- **Enchanting** — normal and blessed scroll types, per-grade enchant ceilings, named success-rate curves, and enchant-level-gated bonus "options" that unlock extra passive effects on specific equipment.
- **Augmentation** — life-stone-style item augmentation, persisted on the item instance.
- **Elemental attributes** — items can carry elemental attack/defense assignments, fed by a dedicated mapping of crafting materials to element and tier.
- **Item sets** — full/partial equipment-set bonuses applied through the same stat-function pipeline used elsewhere.
- **Item auctions** — a dedicated auction subsystem (auction/bid/state model) driving NPC-run scheduled auctions.
- **Storage** — player and pet inventories, player and clan warehouses, personal freight, in-game mail with attachments, and buy-back (refund) history all share a common item-container abstraction.
- **Private stores** — player-run buy/sell shops are a capability attached directly to the player character, with their own listing and request/response packet family.
- **Recipes & manufacturing** — a recipe/ingredient model run through a dedicated crafting manager.
- **Multisell & NPC shops** — exchange-style multisell lists and per-NPC buy lists, both XML-driven and both with scheduled restocking.
- **Regional pricing** — named price zones with their own tax rate, optionally tied to castle ownership so siege outcomes affect local merchant prices.

---

## Zone System

World geometry is organized into **zones**: a common base type carries pluggable geometry (cuboid, cylinder, or arbitrary polygon) and entry/exit hooks, spatially indexed for efficient broadcast. Roughly three dozen concrete zone types are implemented, including:

- **Combat framing** — peace zones, no-PvP zones, arena zones, siege zones, siegable-hall zones, Olympiad stadium zones, boss zones.
- **Residence geography** — castle, fort, and clan-hall zones, residence teleport zones, headquarters/outpost flag zones.
- **Environment/hazard** — water, swamp, damage, periodic-effect, and jail zones.
- **Movement control** — landing/no-landing zones, no-summon-friend zones.
- **Spawn control** — respawn zones, NPC spawn territories, no-restart zones.
- **Commerce/utility** — no-store zones, town zones.
- **Special-purpose** — generic script-hook zones, conditional zones, rotating "hot zones," fishing zones, derby-track zones, event zones (e.g. capture-the-flag).

---

## Instance Dungeon System

Instanced content gets its own isolated world copy: a live instance tracks its own player and NPC sets, per-instance-cloned doors, multiple entry points and a single exit, an optional PvP flag, a UI countdown timer, dead-player ejection timing, and automatic cleanup once empty. Reentry policy is modeled explicitly (the *shape* of the reset rule — fixed schedule vs. time-since-completion — is defined per instance template, independent of any specific duration), and instances can optionally strip buffs on entry/exit. A lighter "instance world" session object lets scripted dungeon logic track boss/quest-specific state without bloating the core instance object. The content pack ships instance families including **Kamaloka**, **Pailaka**, castle and fortress dungeons, Seven-Signs-quest dungeons, the **Seed of Destruction**, and numerous standalone raid/quest instances.

---

## AI System

AI is a single-inheritance state machine built around one high-level intention enum shared by every actor type: idle, active (alert/scan), rest, attack, cast, move-to, follow, pick-up, interact.

- A generic per-tick "thinking" base class specializes into **`AttackableAI`** (NPC/monster combat brain — aggro/threat accumulation, fear handling, attack/cast decision-making), **`PlayerAI`**, **`SummonAI`** (pet/servitor follow-and-assist behavior with obstacle avoidance), **`DoorAI`**, vehicle AI, and a dedicated siege-guard AI family.
- NPC combat behavior is governed by two independent, orthogonal layers: a coarse **AI type** (fighter, archer, balanced, mage, healer, "corpse") governing low-level targeting/range mechanics, and a **monster archetype** governing higher-level decision style — how readily a given monster flees, self-heals, supports allies, holds a grudge, or rages as it loses health. An archetype can be declared per-NPC or, left unset, is rolled and cached the first time it's needed, weighted by the NPC's own template (whether it has healing/buff skills, is a raid boss, its AI type, etc.).
- **Threat/aggro** is tracked per-aggressor as two independent counters (hate, for target selection, and damage, for contribution tracking); leader/minion group behavior lets minions spawn with and defend a leader monster.
- **Boss and quest-scripted behavior** plugs into this system through the same script hook API described in [Scripting / Quest Engine](#scripting--quest-engine) — every major raid and grand boss (Antharas, Valakas, Baium, Beleth, Core, Orfen, Queen Ant, Frintezza, Sailren, and others) has a dedicated AI script driving its actor directly, alongside hundreds of area- and utility-NPC AI scripts.

---

## Geodata & Pathfinding Engine

The geodata engine handles geodata loading, movement validation, line-of-sight, and spatial queries over a custom binary geodata format, stored as a grid of regions subdivided into blocks of three kinds — flat (uniform height), complex (per-cell height plus directional movement flags), and multilayer (multiple stacked height layers per cell, for true multi-floor geometry like bridges and buildings). Per-cell movement flags encode which of the four cardinal directions are physically open from that cell, the primitive that both line-of-movement checks and pathfinding are built on.

- **Line of sight** checks support object-to-object and object-to-location queries, including tolerance for elevated terrain seeing over short obstructions.
- **Line of movement** checks validate straight-line traversal, used both for direct movement and as a post-processing step in pathfinding.
- **Z-axis awareness** — height queries, nearest/next-lower/next-higher Z lookups, and spawn-height resolution let the engine handle multi-level terrain correctly.
- **Obstacles** — closed doors and world fences are consulted directly during region load and during LOS/movement checks, so they behave as real geodata obstacles.
- **Pathfinding** uses node-based **A\*** search over the geodata grid, with pooled, reusable search buffers to avoid per-call allocation, followed by a post-filtering ("string-pulling") pass that greedily collapses the raw grid path down to its real turning points wherever a direct line-of-movement check proves two non-adjacent waypoints are directly walkable.
- Regions with no loaded geodata resolve to a uniform "null region" object rather than requiring null-checks throughout calling code.

---

## Clan, Residence & Siege Systems

- **Clan** — membership, per-rank privilege bitflags, crests, and clan-wide bookkeeping. **Alliances** are modeled as a shared ally id across member clans (with an ally-dissolution penalty concept) rather than as a separate entity.
- **Castles and Forts** both extend a shared residence abstraction (ownership, upkeep, treasury-style capabilities) while keeping their distinct mechanics separate — castles add a full manor/seed production economy; forts add their own spawn and teleport-access rules.
- **Clan Halls** come in two acquisition flavors implemented as distinct subtypes: auction-acquired halls (bid-based) and siege-acquired ("conquerable") halls, captured through combat.
- **Sieges** — castle sieges, fort sieges, conquerable-hall sieges, and territory wars all implement one common "siegable" contract, giving a single polymorphic model for attacker/defender/owner clan participation, siege towers, combat flags, territory wards, and siege scheduling, despite their gameplay differences.
- Dedicated managers handle siege guard spawning for castles and forts, mercenary-ticket-based siege preparation, clan-hall auctions, and conquerable-hall siege orchestration independently of the castle/fort siege path.

---

## Olympiad, Heroes & Seven Signs

- **Olympiad** — a ranked 1v1/team combat metagame with its own participant tracking and match orchestration, supporting classed, non-classed, normal, and team match modes, run out of dedicated Olympiad stadium zones and an Olympiad-manager NPC.
- **Heroes** — Olympiad performance feeds a hero-title system with its own hero-only skills, a dedicated hero chat channel, and monuments/commemoration NPCs.
- **Seven Signs** — the classic Dawn-vs-Dusk faction competition system, including its recurring festival sub-game, with dedicated Dawn/Dusk priest NPCs and Seven-Signs-restricted skills and instance dungeons.

---

## Minigames & Competitive Systems

Beyond the core combat loop, the server implements a number of self-contained side systems: **Kratei's Cube** and the **Underground Coliseum** (level-bracketed PvP arenas with their own curator NPCs and reward tiers), **Handy's Block Checker**, **Monster Derby** racing with betting, a server-wide **lottery**, a **fishing** system (rods, bait, fish, and monsters, with its own zone type) including a recurring **fishing championship**, the **Dimensional Rift** (tiered team-based instanced combat), and the classic **cursed weapon** world-drop mechanic (a powerful, cursed item that a player can wield only temporarily before being forced to relinquish it).

---

## Social & Grouping Systems

**Parties** and **command channels** (multi-party raid groups) share a common player-group abstraction that other systems — targeting scopes, loot rules, experience sharing — build on. Beyond grouping, the server implements **duels** (1v1 and party duels with their own ready/result flow), a **GM petition** (help-ticket) queue, **in-game mail** with item attachments, a **friends list**, and an in-game **wedding/couple** system.

---

## Community Board

The in-client Community Board (the ".bbs" interface) is backed by a lightweight forum/topic/post data model (with clan, memo, and mail-flavored forum types alongside normal ones) and a set of interactive board pages served as cached HTML and routed through the same bypass-handling pipeline used for NPC dialogs: clan management, regional teleport/info, a home page with favorites/bookmarks, a friends list, personal memos, in-client mail, an item drop-source search utility, personal buff templates (see [Custom Feature Set](#custom-non-retail-feature-set)), a PvP ranking of the top 20 players and fake players by PvP kills (fake players' kills are kept by name in `fake_player_pvp`: kills of flagged or karma targets, or of anyone in a PvP spot), and management of the passive skill tree feature (see [Custom Feature Set](#custom-non-retail-feature-set)).

---

## Scripting / Quest Engine

Nearly all narrative and NPC-reactive content — quests, boss and area AI, seasonal events, instance logic, and the implementations behind every handler category below — is written as **plain Java source** under `dist/game/data/scripts` and compiled **in-process at server startup** using the JDK's own compiler API (so a full JDK, not just a JRE, is required to run the server), honoring an include/exclude filter that lets an operator disable whole script folders without touching the content itself. A load failure in one script is caught and logged individually without aborting the rest of the load.

Almost all of this content extends one large base class that acts as the integration seam between content and engine, exposing:
- A **registration API** — dozens of `addXxxId(...)` methods that opt specific NPC, item, or zone IDs into an event category (talk, first-talk, kill, attack, spawn, skill-see, spell-finished, trap-action, faction-call, aggro-range-enter, creature-see, zone-enter/exit, event-received, move-finished/route-finished, NPC-hate, summon-spawn/talk, item-bypass/talk, teleport, class-condition, and more) without touching core engine code.
- **Lifecycle callbacks** — `onTalk`, `onAttack`, `onKill`, `onDeath`, `onSpawn`, `onSkillSee`, `onSpellFinished`, `onAcquireSkill` (and related skill-teaching hooks), `onEnterZone`/`onExitZone`, `onOlympiadMatchFinish`, scripted-route callbacks, and a generic event hook for HTML-bypass-triggered logic — plus a self-contained quest-timer facility for delayed or repeating callbacks.
- **Runtime services** — direct access to cached NPC dialog HTML, database persistence helpers, the full manager layer, and every outbound packet type, so a script can drive dialog, cutscenes, rewards, and world-state changes entirely from content code.

The content pack compiled through this pipeline is large: on the order of **540 quest scripts**, **240 AI scripts** (boss and area behavior), **30 instance-dungeon scripts**, **25 custom-feature scripts**, **10 seasonal event scripts**, plus the class-transfer, vehicle-route, and conquerable-hall-siege scripts — roughly **1,300 Java source files** in total once the handler implementations below are included, all sharing the same base-class API.

**The handler system** (`gameserver.handler` defines the interfaces; the datapack under `handlers/` supplies the implementations, all registered at boot) is the other major pluggability layer:

| Category | Purpose | Approx. implementations |
|---|---|---|
| Admin commands | GM `//` commands (teleport, spawns, item/currency grants, server control, debugging) | ~85 |
| User commands | Client-UI-triggered player shortcuts (mount, `/loc`, `/time`, unstuck, party/siege/Olympiad info) | ~15 |
| Voiced commands | Player-typed `.command`s (banking, autoplay, offline shop, password change, premium status, wedding, DPS meter, PvP spot teleport) | ~25 |
| Chat handlers | Delivery/range logic per chat channel (general, shout, trade, party, clan, alliance, whisper, hero, petition, battlefield) | 14 |
| Bypass handlers | Server-side link commands from NPC/HTML dialogs (shops, multisell, warehouse, freight, Olympiad, augmenting, observation) | ~30 |
| Community Board handlers | `.bbs` page logic (see [Community Board](#community-board)) | 12 |
| Item handlers | "What happens when this item is used" (shots, scrolls, dice, recipe books, teleport bookmarks, enchant items) | 30 |
| Skill effects | The buff/debuff/damage/heal/CC/transform/summon vocabulary every skill is built from | ~165 |
| Skill target types | Target-selection algorithms | ~34 |
| Action / shift-click handlers | Left-click and shift-click behavior per world-object type | ~16 |
| Punishment handlers | Executes ban / chat-ban / party-ban / jail | 3 |

Because scripts compile against the core engine (never the reverse), a handful of features that both sides need share are implemented as core managers with thin script-side wrappers rather than being split across the boundary.

---

## Manager & Background Task Subsystems

**Managers** (roughly 65 classes) own the live, in-memory state of nearly every subsystem discussed above, grouped loosely as:
- **World/spawn/environment** — day/night spawn and skill swapping, zone ownership, scripted NPC patrol routes, town lookups, raid/grand-boss spawn state, "hot zone" bonus-farming mechanics, special monster variants, monster-rage escalation, fake-player world population.
- **Siege/castle/fortress/territory** — castle and fort management, castle manor economy, siege and siege-guard orchestration, conquerable clan-hall sieges, territory wars, mercenary-ticket tracking.
- **Instances/minigames/events** — instance-template orchestration, Dimensional Rift, Seed of Destruction/Infinity, Kratei's Cube, Underground Coliseum, Monster Derby, lottery, Handy's Block Checker, fishing championship, temporary global drop-rate events.
- **Economy/items/progression** — item lifecycle and ground items, item auctions, recipes, the passive skill tree, raid-boss point tracking, PC-café point rewards, premium accounts, player-run buff shops, kill-reward jackpot mechanics, cursed weapons, unique ID allocation, a persistent global key/value variable store.
- **Social** — duels, GM petitions, mail, CAPTCHA challenges, anti-multibox/anti-feed tracking, weddings.
- **Vehicles** — airship and boat route/state management.
- **Server lifecycle** — daily resets, precautionary and scheduled restarts, and the central punishment dispatcher used uniformly by GM commands, flood protection, and bot detection.

**Task managers** (18 classes) are the periodic/background jobs layered on top: a central in-game clock driving day/night transitions, combat-stance expiry, batched AI think-cycles, follow-target and movement interpolation updates, mutual-visibility/perception checks feeding aggro, corpse/NPC decay, NPC respawn timers, idle social animations, PvP-flag expiry, item lifetime/auto-destroy/mana-drain timers, mail expiry, periodic autosave of online characters, scheduled shop restocking, and the server-side implementations of client auto-play, auto-potion, and generalized auto-use/macro behavior.

---

## Security & Anti-Cheat

- **Secondary (PIN) authentication** — an optional in-game numeric PIN independent of the account password, hashed before storage, with an attempt-limit lockout that triggers a login-server-issued temporary ban and an automated notification.
- **CAPTCHA** — image-based challenge/response for suspected automation, feeding into the punishment system on failure.
- **Anti-multibox / anti-feed tracking** — per-IP and per-character counters across regular play, Olympiad, and event contexts to detect dual-boxing and kill-feeding abuse.
- **Bot/macro-client detection** — chat-content heuristics that recognize known third-party bot/macro client signatures and report them directly to the punishment system, alongside cumulative chat-spam scoring that auto-applies chat bans.
- **Hardware fingerprinting** — the client's hardware profile (MAC address, OS version, CPU, GPU, memory layout) is captured and tied to account variables for ban-evasion tracking.
- **Flood protection** — see [Network Engine](#network-engine).
- **Central punishment engine** — a single dispatcher and a small set of pluggable handlers (ban, chat-ban, party-ban, jail) used uniformly by admin commands, flood protection, CAPTCHA failure, and bot detection alike.
- **Legacy anti-cheat handshake** — the original GameGuard-style probe/response exchange is retained in the protocol for client compatibility.
- **GM accountability** — every admin/GM command invocation is written to a dedicated, per-operator audit log independent of general server logs.
- **Protocol-level hardening** — per-opcode connection-state whitelisting (an out-of-state packet is silently dropped), a session cipher that only activates post-handshake, and a configurable accepted-client-protocol-version list.
- **Account-level protections** — see [Login & Authentication System](#login--authentication-system) for ban tiers, IP allow/deny lists, and brute-force protection.

---

## Caching

A deliberately small, focused cache layer: all NPC dialog and Community Board HTML pages are loaded into an in-memory map keyed by path (avoiding per-request disk I/O), can be hot-reloaded from the admin console without a restart, and gracefully fall back to live reads if disabled; a second small cache holds computed pairwise relationship state (PvP/clan/ally status and auto-attackable flags) between characters so it isn't recomputed on every status packet. Other subsystems (e.g. passive-tree stat bonuses) maintain their own local caches close to where they're used rather than in a shared cache package.

---

## Admin Tools & Console

Each server process can run a local Swing-based operator console in place of a plain stdout log, offering: graceful shutdown/restart with an operator-supplied delay and an abort option; hot-reload of configuration, GM access levels, HTML cache, multisell lists, buy lists, and the cash-shop catalog, without a full restart; server-wide announcements; a log viewer; and a status panel showing uptime, build info, and live online-player count. This is a local operator console, not a remote/web admin panel — remote and in-game administration instead goes through the GM admin-command handler system and the Community Board described above.

---

## Standalone Utilities

Four small dual-mode (GUI-or-console) tools sit alongside the two servers:
- **DatabaseInstaller** — creates the target database if missing (optionally backing up and dropping an existing one first) and runs the login and/or game SQL schema scripts in order, with per-file progress and error handling.
- **AccountManager** — creates, searches, edits (password reset, access-level change), and deletes accounts directly against the database, with a paginated account browser.
- **GameServerRegister** — registers or unregisters a GameServer identity against the login database, generating the secret "hex id" credential a GameServer presents during its authentication handshake.
- **Search** — a developer productivity tool for regex file-content search across the working tree, filterable by file extension.

---

## Database Layer

Database access is centralized through a **HikariCP** connection pool (self-tuning pool sizing, leak detection, connection validation, and optional startup self-tests that probe whether the database can really serve the configured pool size) talking to **MySQL/MariaDB** over the standard MySQL JDBC driver. SQL is written directly against MySQL syntax rather than through a vendor-abstracted ORM. A backup routine can shell out to `mysqldump` for timestamped, age-pruned dumps, invoked around login-server shutdown when enabled.

The shipped schema spans roughly **115 game-server tables** — covering accounts' game-side data, characters and every per-character subsystem (skills, subclasses, recipes, macros, shortcuts, hennas, friends, contacts, premium items, instance timers, item reuse state, offline trade/play), clans and every clan subsystem (privileges, notices, subpledges, wars, crests), castles/forts/clan-halls and their sieges and functions, item auctions, the manor economy, grand bosses and raid points, heroes and their diaries, the Olympiad, Seven Signs and its festival, cursed weapons, the Dimensional Rift, fishing championship, lottery, Monster Derby, wedding/couple data, custom mail, the passive skill tree, class transfer trial completions, and global server variables — plus roughly **4 login-server tables** for accounts, per-account IP authentication rules, and registered game servers.

---

## Data-Driven Content Pack

Everything under `dist/game/data` and `dist/game/config` is loaded through a shared XML-reader framework that validates every file against a companion XSD schema (roughly 80 schemas ship with the pack) before parsing, so malformed content fails fast at load time rather than misbehaving at runtime. The pack includes:

- **Geodata** — the binary pathing/height data consumed by the [geodata engine](#geodata--pathfinding-engine), covering the game world region by region.
- **Spawns** — NPC spawn lists, organized per region/area.
- **Zones** — the geometry and parameters for every [zone type](#zone-system).
- **HTML** — every NPC dialog window and Community Board page, served through the HTML cache.
- **Instances** — per-dungeon templates for the [instance system](#instance-dungeon-system), organized by dungeon family.
- **Multisell & buylists** — exchange shops and NPC sell lists.
- **Teleporters** — NPC-driven teleport destination lists.
- **Map regions** — town/region boundaries used for respawn-point resolution and the world map.
- **Passive tree** — the node graph for the custom passive skill tree feature.
- **Stats** — base experience/HP/MP growth and related per-level tables.
- **Localization (`lang/`)** — server-message and NPC-string translations layered on top of the base client strings, enabling the [multilingual support](#custom-non-retail-feature-set) feature.

Configuration itself is split between the main `dist/game/config` (and `dist/login/config`) directories — one `.ini`/`.xml` file per core subsystem (server/network identity, database, threading, rates, PvP, player rules, floodprotection, NPCs, geo engine, grand bosses, Olympiad, sieges, territory wars, underground coliseum, access levels, admin command permissions, bot-report punishments, class-master, script include/exclude rules, secondary auth, siege scheduling, dynamic per-level XP/SP curves) — and a **`Custom/` overlay directory** of roughly 60 additional files for the non-retail feature set described next, keeping stock-chronicle configuration cleanly separated from added features.

---

## Build System & Requirements

- **Build tool**: Apache Ant (≥ 1.8.2).
- **Language/runtime**: Java 25 — the build hard-fails if a JDK25+ toolchain isn't detected. A full **JDK** (not just a JRE) is required at runtime as well, because game content scripts are compiled in-process at server startup.
- **Database**: MySQL or MariaDB, accessed through HikariCP connection pooling.
- **Garbage collector**: the shipped IDE run configurations target the Z Garbage Collector (`-XX:+UseZGC`).
- **Third-party dependencies** are deliberately minimal: a MySQL JDBC driver, the HikariCP connection pool, and an SLF4J logging facade/backend pairing (used mainly to quiet HikariCP's own logging) — no bundled scripting or bytecode-manipulation library, since the runtime script compiler is the JDK's own.
- A single `javac` pass compiles the entire `java/` source tree, which the `jar` target then partitions via include/exclude filesets into three independently runnable artifacts: **`LoginServer.jar`** (login server plus the account-manager and game-server-registration tools), **`GameServer.jar`** (the game server plus the developer search tool), and a minimal, mostly self-contained **`DatabaseInstaller.jar`**. A final build step assembles a complete `build/server` tree combining the compiled jars with the full `dist/` datapack, ready to deploy.

---

## License

This program is free software, licensed under the **GNU General Public License, version 3** (or, at your option, any later version). See the license header in `build.xml` for the full notice.

---

## Recent Updates

Changes from 28 September – 9 October 2026.

### Fake Players: gear
- **Every piece is enchanted on its own**: the weapon, shield, each armor piece, each earring and ring, the necklace, shirt and belt get their own enchant, so a +7 weapon can come with +0 boots and a +3 helmet. Before, all armor and jewels shared one roll. A piece was enchanted at all, or is +0; an enchanted one is at its safe level (+3, +4 for a full body armor, read from `data/EnchantItemGroups.xml`), now and then below it, and each step past it is rarer than the one before. Weapons are pushed furthest, jewels next and armor least. At level 85 about 11% of weapons are +0, 26% +3, 30% +4 or +5 and 5% +10 or more. Each fake player has its own wealth, so some enchant nearly everything and others very little, and some went for a full +6 armor set (about a quarter at 84-85, for the set's +6 bonus). New settings in `config/Custom/FakePlayerPvp.ini`: `FakePvpWeaponEnchantOdds`, `FakePvpArmorEnchantOdds`, `FakePvpJewelEnchantOdds`, `FakePvpArmorSetEnchantChance`, `FakePvpGearWealthMin`/`Max`. `FakePvpWeaponEnchant`/`FakePvpArmorEnchant` now only bound the result (no-grade gear and cloaks are never enchanted).
- **Cloaks**: a fake player whose full armor set opens the cloak slot (Dynasty, Moirai, Vesper Noble, like players; plain Vesper doesn't) wears a cloak `FakePvpCloakChance` % of the time: the Ancient, Holy Spirit's, Zaken, Frintezza and Freya cloaks and their soul cloaks, the ones the Weavers unseal in High Five (the `cloak` list in `data/FakePlayerPvp.xml`). Its P. Def. and attribute resistances count. Town fake players wear them too.
- **Shirts and belts**: from level 20 (shirts) and 40 (belts), D to S grade (`ACCESSORIES` kit, `FakePvpShirtChance`, `FakePvpBeltChance`).
- **Vesper Noble**: level 84+ builds now wear Vesper or Vesper Noble (2 in 5), so they can have a cloak. Kit tiers with the same `minLevel` are alternatives picked by `weight`. The strong fake players of the PvP spots wear the last one listed.
- The inspect window shows the cloak, shirt and belt with the enchant of each piece, a cloak a player couldn't drop never drops from a fake player, and Forged Gear counts each armor piece's own enchant. Fake clan members get their gear rolled again from their saved seed under the new rules, once.

### Fake Players: dwarves and performers
- **Dwarven builds**: Fortune Seeker and Maestro roam, hunt, fight in the PvP spots and live in the towns (the Dwarven Village had no dwarves), with one-handed blunts and shields, or two-handed blunts (`Fortune Seeker Crusher`, `Maestro Crusher`), and a polearm for monster packs. They are also the Rival Shades of the dwarven class transfers.
  - Fortune Seekers spoil the monster they fight first (Spoil, Spoil Crush at 76), stun with Hammer Crush and Armor Crush and hit with Crushing Strike, Fatal Strike and, from 83, Lucky Strike on packs.
  - Maestros fight next to their Mechanic Golem, open with Rush or Rush Impact, heal with Battle Cry and put on Golem Armor when a PvP gets serious.
  - The Dwarven Trades work for them like for players: Skullcrusher, a Spoil that never misses, Plunderer's Mark, Golem Engineering on their golem and Forged Gear on their enchanted gear. A fake player's spoil can be swept by the players of its party, and its spoiler bonus counts.
- **Performer builds**: Battle Bard (Sword Muse line) and War Dancer (Spectral Dancer line) hunt and fight on their own. Their stance is always on (Battle Hymn, Blade Waltz), they play a hunting performance and switch to another one against players, and they save their finale for players. The party-only Sword Muse and Spectral Dancer now keep up their own performance and stance too, and the Sword Muse heals the party with Sanctuary Chorus.
- **Performances with fake players**: a fake performer's party auras reach its party (players and fake players), and its hexes reach what its area skills reach. A player's performance now reaches the fake players of its party too. Virtuoso counts for fake performers. `HexAffectsPlayers = False` now spares fake players as well.
- New `perform` and `spoil` skill lists in `data/FakePlayerPvp.xml`.

### Performers
- **Swordsinger and Bladedancer lines reworked**: Glittering Medals teach everyone the songs and dances, so the performers now get toggle auras nobody else has, a combat stance, a level 79 finale and longer-lasting songs and dances. See [Performers](#performers).
- **Performances** (one at a time): Ballad of the Bulwark, Hymn of Renewal and Anthem of Valor (Swordsinger line, party auras), Dance of Ruin and Dance of Torment (Bladedancer line, enemy hexes) and Dance of Frenzy (Bladedancer line, party aura). They stack with every buff and medal song.
- **Stances**: Battle Hymn (attacks may heal the party) and Blade Waltz (dual sword attacks may slash every enemy around the dancer).
- **Finales**: Sanctuary Chorus (Sword Muse) and Danse Macabre (Spectral Dancer).
- **Virtuoso**: a performer's songs and dances last ×1.5 / ×2 and stacking them costs no extra MP.
- New skills 27500-27528 in the class skill trees; client rows in `client/Performers`. Settings in `config/Custom/Performers.ini`.

### Dwarven Trades
- **Dwarven classes reworked**: Spoil, Sweeper Festival, Crystallize and Dwarven Craft on the passive tree had made them pointless, so the dwarves now do these trades better than anyone with the tree, and fight in their own way. See [Dwarven Trades](#dwarven-trades).
- **Spoilers** (Scavenger, Bounty Hunter, Fortune Seeker): Spoil never misses, and what they spoil gives more when swept (chance up to ×1.75 and amount up to ×1.4 for a Fortune Seeker).
- **Crafters** (Artisan, Warsmith, Maestro): a higher success rate (up to +15), more masterworks (up to ×2) and a chance to make twice as many stackable items (up to 15%), in their workshops too.
- **Crystallize**: dwarves get 20% more crystals, and crafters up to another 30%.
- **Combat**: stun skills of every dwarven class land more often (Skullcrusher). Spoilers hit the monsters they spoiled harder (Plunderer's Mark) and restore HP and MP for every corpse they sweep (Spoils of War). Crafters' golems and cannons are up to 30% stronger (Golem Engineering), and enchant levels above +3 on their gear add P.Atk and P.Def (Forged Gear).
- **Tree's Dwarven Craft is the only source of crafting outside the Artisan line**: it gives Create Item at your level, up to level 5 (D and most C grade). Create Item was taken out of the non-dwarven and Scavenger skill trees, and dwarven crafting now needs Dwarven Craft as well, so refunding the node ends it (recipes are kept). On an existing database run `dist/db_installer/sql/game/updates/2026-10-09_create_item_from_tree.sql` once with the server stopped, or `SkillCheckRemove` strips the old Create Item at login and reports each character.
- Settings in `config/Custom/DwarvenTrades.ini`.

### Town Life
- **New system**: npcs walk the town streets by day, go into their houses at dusk and come back at dawn. It covers townsfolk, workers, patrols, children playing tag, a town crier, harbor fishermen and dock workers, the lamplighter, the night watch, a tavern crowd at night, sweets for the children and a weekly festival day. See [Town Life](#town-life).
- Settings in `config/Custom/TownLife.ini`, towns and harbors in `data/TownLife.xml`, npc templates 900400-900446, the dialogs in `custom/TownLife` and the GM command `//townlife`.

### Night Cycle
- **New system**: the night runs through announced phases (dusk, night, the Witching Hour, dawn) with a world-wide Omen, Nightlords in every level bracket, the Witching Hour and the Night Watch rewards. See [Night Cycle](#night-cycle).
- `.night`, the Community Board page `_bbsnight` and the GM command `//night`. Settings in `config/Custom/NightCycle.ini`.
- Hot zone modifiers gained three night-only Omens (New Moon, Full Moon, Starfall) and the Witching Hour modifier, which the hot zone rotation never rolls.
- **Night Market**: Varro the Moonmonger (NPC 900360, multisells 900360-900362) sells in Giran from nightfall to dawn, trades Sealed Caches up a tier and changes hot zone coins into Ancient Adena (22 per coin); black market fake player stores open in the towns, which empty out at night; Thieves are more common at night and run off into the dark with a radar ping.
- **Midnight hidden quests** (ids 24-27, messengers 900350-900353): night-only messengers, four new secret conditions (nights survived, Nightlords slain, moonlit fish, Witching Hour kills), the new BEFORE_DAWN wisp trail task and VIGIL `untilDawn`.
- **Shadow Raids, Moonlit Melee and Children of the Night**: fake clan warbands raid the open world at night (`//night raid`), the PvP spots fill up at night and crown a Night King at dawn, each race gets a night trait, and the Passive Tree gains the Nocturne (Starfall, 43070-43072) and Dawnbringer (Hallowmere, 45070-45072) keystones. Game-time skill conditions now follow the Night Cycle.

### Hidden Quests
- **New system**: secret conditions send a messenger NPC (ids 900300-900312) to the player with a one-off quest. The conditions stay on the server.
- **13 quests** with 7 task types (pilgrimage, echo gauntlet, hunted, chase, escort, vigil, riddle) and rewards from enchant scrolls to titles, name colours and karma cleansing. All of it is data in `data/HiddenQuests.xml`.
- GM commands `//hiddenquest*` to see progress and to trigger, send, complete, abort or reset a quest.
- **10 more quests** (ids 14-23, messengers 900330-900339), each with a new kind of condition:

  | Quest | Condition | Task |
  |---|---|---|
  | The Laurel of Ash | 30 Olympiad wins | Echoes of four old Heroes |
  | The Smith's Lament | 20 failed enchantments (item lost or reset to +0) | Guard a ghost smith's anvil from Rustborn golems; pays blessed enchant scrolls |
  | The One That Got Away | 200 fish caught | Catch five silver shadows before they fade, while poachers interfere |
  | The Still Mountain | 3 hours sitting outside towns | Meditate at three Stones of Stillness under a vow of peace; pays SP |
  | Dance on the Edge | 100 kills at 10% HP or less | Four "partners" sent by Death |
  | The Empty Hand | 300 kills with no weapon equipped | Hunted by the Iron Fist school, then its grandmaster |
  | The Drowned Choir | 200 kills while swimming | Guard a water spirit's Tide Stone from the drowned |
  | The Wronged | Murdered 10 times by PKs | Catch three Red Hand couriers |
  | The Archivist's Burden | 30 quests completed | Escort an archivist to four hidden vaults |
  | The Road Goes Ever On | 2,500,000 units travelled on foot | Riddles about ten far-apart places |

  Kills by a summon don't count for the HP, weapon and water conditions. Clan war and siege kills don't count as murder, and teleports, boats, mounts and flying don't count as distance. Enchant failures and fish are counted by hooks in the enchant packet and in fishing.

### Master Blacksmith
- **New NPC** in Giran (id 900010, by the Arena Master) with every blacksmith service in one window: SAs, dualswords, Mammon's weapon upgrades and swaps, seals, Foundation items, augments, Life Stones, attribute removal and crafting.
- **Soul crystal and SA guide**: search an ability or weapon ("rsk haste"), compare a weapon's three SAs with their exact effect, see the crystal color and stage it needs, and bestow it in one click (the enchant level is kept).
- **Leveling plan**: each crystal stage lists the monsters and raid bosses that raise it, with chance, party rule, raid status, location and a radar mark, sorted by chance and your level.
- The window is 470x760. The custom NPC is added to the blacksmith and Mammon multisells it opens.

### Passive Skill Tree
- **Eleven new keystones on the outer edge**, each at the end of a two-node branch running straight out from a notable of an outer region:
  - **Whirling Steel** (Ironmarch, 40080-40082, past Wallbreaker): normal attacks with any melee weapon also hit up to 2 more enemies in front of you for 50% damage, and a polearm hits 2 more enemies than it would. -15% Attack Speed.
  - **Riposte** (Duskwood, 42070-42072, past Unseen): after you block an attack or skill with a shield, your next normal attack or physical skill within 5 seconds is a critical hit, and a blow skill lands. Your critical rate is halved otherwise.
  - **Guardian's Oath** (Hallowmere, 45080-45082, past Lamplight): you take 25% of the damage dealt to party members within 900 range in their place, never enough to kill you. +20% P.Def and M.Def, -20% P.Atk and M.Atk.
  - **Arc Conduit** (Starfall, 43080-43082, past Calm Before): single-target damage spells jump to the nearest enemy within 300 of the target for 50% of the damage, then to one more for 25%. Each jump comes 0.2 seconds after the one before and shows the spell's own animation flying from one enemy to the next. Spells cost 30% more MP.
  - **Chaos Weave** (Starfall, 43090-43092, past Starfire): each damage spell has a 20% chance to Surge for 2.5x damage and a 10% chance to Fizzle and deal none. No magic critical hits.
  - **Ley Anchor** (Ley Expanse, 44080-44082, past Overflowing Well): once you have stood still for 2 seconds, +25% M.Atk, +20% Casting Speed and +15% M.Def; until then -15% M.Atk. Node effects can use the new `STILL` and `MOBILE` conditions (`MATK_PCT@STILL`).
  - **Battlemage** (Ley Expanse, 44090-44092, past Living Conduit): 40% of your M.Atk is added to your P.Atk, and 5% of your melee damage restores MP. -25% Casting Speed, and -20 Speed in heavy armour.
  - **Phoenix Heart** (Red Wastes, 41070-41072, past Scarred Veteran): once every 5 minutes, a blow that would kill you leaves you alive with 40% of your HP. -15% max HP and -20% healing received.
  - **Relentless Assault** (Red Wastes, 41080-41082, past Frenzied): each normal hit you land on your target gives +3% Attack Speed, up to +30% after 10 hits. Switching target or 3 seconds without a hit starts over. -10% Attack Speed.
  - **Bloodletter** (Duskwood, 42080-42082, past Midnight Sight): physical critical hits (normal attacks and physical skills, not blows) deal no extra damage up front. Instead the target bleeds for 150% of the critical bonus over 6 seconds, and more critical hits add to the bleed. Purity of Flesh stops it.
  - **Spell Echo** (Ironmarch, 44100-44102, past Tower Shield, on the far side of the tree from Chaos Weave so one build rarely takes both): single-target damage spells have a 25% chance to hit their target again 0.5 seconds later, free, for 60% of the damage, with the spell's animation. -15% Casting Speed.
  - `layout_tree.py` lays out these branches, and Nocturne's and Dawnbringer's, instead of stopping on them.
- **Route choices**: each archetype now has several ways to the same place, and each way costs a different amount.
  - **Start**: each Origin has three roads to its first Crossroads. The middle road costs 3 points. The two side roads cost 4 and pass the entry of that Crossroads' two clusters, which used to hang off the Crossroads.
  - **First stretch (a wheel)**: between the first and second Crossroads there are three ways through: the plain road (3 points), or round either side through a whole cluster (5 points, its notable on the way). The active skill sits inside the wheel.
  - **Next two stretches**: on one side a lane runs through a notable (small, notable, small: 1 point more than the plain road). The cluster's third small no longer dead-ends: it leads to the stretch's keystone or active skill, which is also reachable from the plain road. So you can take it for 3 points over the road, or for 5 through the notable. On the other side the two clusters are joined into one loop between the two Crossroads, so either notable can be reached from either end.
  - **Large HP and MP clusters by the Nexus**: Vanguard and Juggernaut (HP), and Arcanist and Hierophant (MP), each have a large cluster between their last Crossroads and the Nexus. It is shaped like an eye: two arcs either side of the road through the Master, each small, small, notable, small, small. You can enter from the Crossroads or from the Nexus ring, so players from any archetype can reach them. They use existing HP and MP values.
  - **Attributes and summons, past each Rim Gate**: straight out from every Rim Gate there is now an attribute shrine. An eye from the gate to the shrine has attack on one arc and defence on the other, and a capstone ring sits behind the shrine. Earth is Juggernaut's, Dark Shadowblade's, Wind Deadeye's and Holy Hierophant's. Vanguard gets elemental resistances, and Arcanist gets Fire and Water. Past Arcanist's shrine a second eye leads to the Summoner's Circle and the summon capstone. Those summon nodes raise your pet's or servitor's P. Atk., M. Atk., P. Def., M. Def., max HP and attack/casting speed. Attribute points work like attribute stones (a weapon stone adds 5, an armour stone 6). Caps are in `Custom/PassiveTree.ini`.
  - **Outer Rim**: each Origin has two more roads to the rim, landing one node either side of the Rim Gate. They save 1 point towards the outer regions but skip the gate.
  - Node stats are unchanged. The 60 new road nodes copy existing Pathway and Outbound Road values. Allocated nodes that no longer connect are dropped on login, and their points are returned.
- **Active skills fixed**: Earthshatter, Volley and Arcane Nova now deal their intended damage (Arcane Nova did none), Earthshatter's stun can land, and Shadow Lunge's critical damage bonus applies.
- **Scale with level**: the damage, heal and debuff actives have 9 levels. You get the level that matches your character level, it moves up as you level, and the cooldown carries over. Before, they failed against high-level targets.
- **Hybrid actives**: each hybrid sector has an active skill node: Crimson Bulwark (Warlord), Reaving Strike (Reaver), Hunter's Mark (Stalker), Binding Rune (Spellbow), Temporal Flow (Mystic), Purifying Aegis (Templar).
- **Renamed** to avoid clashing with retail skills: War Cry is Battle Fervor, Shadow Step is Shadow Lunge, Benediction is Sacred Chorus, Sanctuary is Hallowed Ward.
- **Utility nodes that do something**: Field Salvage (was Field Repairs) grants Crystallize for every grade, Tracker's Guile grants Silent Move, Scholar's Insight gives +5% XP and SP, and Pilgrim's Provisions (was Sacred Artisan) gives +12 inventory slots and +30% weight limit.
- **Settings**: `Custom/PassiveTree.ini` is now loaded, with the same values as before. Single-node refunds are charged in Adena.
- **Tree editor for GMs**: `//passivetree` opens a web editor on the planner's port (the link only works while that GM is online and allowed to use the command).
  - **Move**: drag nodes, Shift+drag to select a box of them, or drag a cluster ring to move the whole ring. Arrow keys nudge the selection. An optional snap aligns nodes to a 10-unit grid.
  - **Link**: Alt+click a node to link or unlink it to the selected one, or use the Link tool to click a path node by node. Links are always saved both ways.
  - **Edit**: name, sector, file, type, cost, tier, effects (with the conditional `KEY@CONDITION` keys), granted skill and level (`auto` too), icon, description, position and orbit centre. With several nodes selected, set a field on all of them or multiply their effect values.
  - **Add and remove**: the Add tool places a copy of the selected node, linked to it. Ctrl+D duplicates the selection. Deleting a node that players have allocated asks first. New nodes never reuse the id of a removed node.
  - **Undo and redo** for every change. Unsaved work is kept in the browser and offered again if the page is closed.
  - **Saving** first checks the tree on the server and lists what is wrong, what you should know and which files change: unreachable nodes, unknown effect keys and conditions, missing skills, and how many characters lose allocated nodes. Those nodes are refunded, at once for online players and at the next login for the rest. The old files are copied to `data/passivetree_backup/<time>/` (the last 30 saves are kept). Then online players' trees are rebuilt and fake players' prepared trees are grown again. A save is refused if the files changed since the editor loaded them.
  - `//passivetree_reload` loads the files again after editing them by hand. Re-running the layout generator overwrites edits made in the editor.
  - The editor link lasts `PassiveTreeWebAdminTokenLifetime` seconds (2 hours by default).
  - **Without the game server**: open `dist/game/data/html/custom/passive-tree-admin.html` straight from disk in Chrome or Edge, click **Open the tree folder...** and pick `dist/game/data` (or `dist/game`, `dist` or the repository folder). The editor works the same, and saving writes `data/passivetree/*.xml` in that folder, exactly as the server would, after a backup in `data/passivetree_backup/`. It checks skills against `data/stats/skills`, but players' allocations are unknown, so it only says how many nodes and links a save removes. A running server picks the files up after a restart or `//passivetree_reload`. Picking `data/passivetree` itself also works, without the backup, the planner's icons or the skill check. Browsers that can't write to a folder (Firefox, Safari) download the changed files instead.
- **Client**: the custom skills need Skillname-e.dat / Skillgrp.dat entries to show their names and icons.

### Alternative Class Transfer Challenges
- **One Class Master**: the three Class Masters in Giran (900001/900002/900003) are now a single Class Master (900001) that offers the 1st, 2nd or 3rd class transfer (and its trial) based on your current class.
- **New**: the Class Masters now offer a short solo trial for each class transfer. Clearing it unlocks the transfer at that Class Master; the village-master quests are unchanged.
- **Six trials**: a fighter and a mage trial per tier, each 4-6 objectives in a private hall reached by teleport — no walking across the world.
- **Built from existing systems**: a rolled Hot Zone Omen per attempt, multi-phase Wave/Arena Champion bosses, the Mark Thief, Mage skirmishers, champion elites, a fake-player Rival Shade of your future class, and occasional fake-player Invaders.
- **Forgiving**: falling sends you back to the entrance with your progress; disconnects are held for a while.
- **Rewards**: Survival Arena currency (bonus for a fast clear) and a Sealed Cache.
- **Commands**: `.trial` shows your progress (`.trial abandon` gives up); GMs get `//challenge_status|start|complete|abort|reset` and `//reload classtransferchallenge`.
- **Kamael**: the Class Masters now transfer Kamael too (Trooper/Warder, then Berserker, Soul Breaker or Arbalester, then Doombringer, Soul Hound, Trickster, or Judicator for Inspectors). Soul Breakers take the Archmage trial, other Kamael the fighter trials, and the Rival Shade uses the Kamael fake-player builds.
- **Setup**: run `class_transfer_challenge_completion.sql`, and on an existing database the Kamael block at the end of `class_transfer_tree.sql` (it only adds missing rows). Settings are in `Custom/ClassTransferChallenge.ini`, trials in `data/ClassTransferChallenges/`.

### PvP Spots
- **New**: three open-world PvP spots, one per gear bracket: **Plains of Dion** (levels 20-51), **Ancient Battleground** (52-75) and **Dragon Valley Entrance** (76-85). They were picked against the High Five spawn and geodata files: open ground, no NPC and almost no monsters inside, and no gatekeeper drop point inside, so hunters arriving by teleport are never caught in them (`data/zones/pvp_spots.xml`, zone type `PvpSpotZone`).
- **No PK**: everyone inside is flagged from the moment they step in until they leave (attacked without Ctrl, like any flagged player), so a kill there is always a PvP kill (PvP count, PvP reward items and announcements) and never gives karma, whoever kills whom. Leaving, the flag runs out like after any fight. Dying there costs no exp and gives no death penalty, and nothing drops unless you have karma (`PvpSpotNoDeathPenalty`).
- **Teleport**: `.pvp` (or the "PvP Spots" button of the Community Board gatekeeper page and of `.help`) lists the spots with their levels, how many fight there and their leader, with a teleport button for each. Like a gatekeeper, not in a fight, the Olympiad, a duel, an event, a siege, an instance or jail, and not with karma; optional adena price or towns only.
- **Fake players come to fight**: 10-16 per spot (drifting; below 10 alive, newcomers arrive every few seconds), arriving like players by teleport, alone or as a group of 2-3 of the same clan that fights side by side, faster while a spot fills up. They are the spot's levels, and often close to the level of a player who is there, so players find opponents of their level. Only builds that fight on their own come: no healers or buffers. They don't hunt, talk or taunt; they leave after 15-45 minutes, and 75% of the ones killed come back 20-60 seconds later (like a restart in town and a teleport back), fully buffed, and go after their killer first. Fake players of the spots give no exp, monster loot or equipment.
- **The strong one and the PvP leader**: now and then a stronger fake player comes (one per spot at most): the best gear of its level, enchanted above the usual roll, every subclass in its passive tree, skilled and aggressive, and it never runs away. Whoever has the longest kill streak in a spot (from 3 kills, fake player or player) is its **PvP leader**: a fake player leader shows a "PvP Leader" title and the hero aura, the players there are told when a leader emerges, at killing sprees and when someone ends a leader's reign, and the fake players talk about it ("focus ...", "finally ... down"). Aggressive fake players gang up on the leader, cautious ones keep away from it.
- **PvP more like real Lineage II**: in a spot the fake players play free-for-all like players in a PvP zone:
  - they pick their opponent like a player: close, low on CP/HP, going for them or their clan mates, busy with someone else (a free hit), their killer, a clan war enemy, about their level; few pile on someone that several of them already fight;
  - in the middle of a fight they look around and switch to a clearly better opponent: finishing off someone almost dead for the kill, helping a clan mate, answering someone hitting them;
  - they drink CP potions all fight long (Greater CP Potion, 200 CP, from level 52), which only refill the CP part of their HP bar;
  - area skills catch every flagged opponent around, so a crowd is worth an area skill;
  - the ones that run from a losing fight stay in the spot: once they got away they drink potions, rest only when nobody is around to come for them, and come back; nobody reads a Scroll of Escape in the middle of a spot;
  - they chase a little way out of the spot (600) and then turn back, and with nobody in reach they walk over to the nearest fight.
- **Admin**: `//fakepvp_spots` (or "Spots" in `//fakeplayers`) shows the fighters, players and leader of each spot, and about a targeted spot fighter; `//fakepvp_spots_clear` sends them all off (new ones come).
- **Settings**: `config/Custom/PvpSpots.ini`. More spots: a `PvpSpotZone` in `data/zones/pvp_spots.xml` with `spotMinLevel`/`spotMaxLevel`.

### Fake Players
- **New builds**: Dreadnought, Dominator, Soultaker, Hell Knight, and the Kamael classes (Doombringer, Male/Female Soul Hound, Trickster, Judicator). Every class also gets a second gear variant. Kamael wear light armor only, like players (heavy armor and robes showed untextured on them).
- **Dwarves and performers**: Fortune Seeker and Maestro builds (blunts, stuns, Spoil, the Mechanic Golem and Golem Armor, with their Dwarven Trades perks), and Battle Bard and War Dancer, a Sword Muse and a Spectral Dancer that hunt on their own with their performances, stances and finales. See [Dwarven Trades](#dwarven-trades) and [Performers](#performers).
- **Servitors**: Necromancers, Hell Knights and Maestros summon their servitors. Necromancers link theirs with Transfer Pain and re-summon it during PvP.
- **Kamael mechanics**: fake players use souls, Final Form, Soul Cleanse and Warp. Disarm now works on fake players.
- **Passive tree keystones**: fake players now take keystones: one from about 30 spent points, a second from about 90, from level 40 (`FakePvpKeystonePoints`, `FakePvpKeystoneMinLevel`). Each role picks from its own list (`FakePvpKeystones.<ROLE>`), only ones that work for a fake player and its gear (Riposte and Deflection need a shield, Far Shot a bow). The keystone mechanics now work for fake players, including Unending Fury, Relentless Assault and Arcane Plating. Trees are grown for the fake player's gear, so a `@HEAVY` or `@SHIELD` bonus only counts if it wears one, and a stat that has reached its `PassiveTreeCap` stops pulling more points.
- **Personality**: each fake player rolls its own aggression, skill use, chattiness and roaming. The spread around the config values is set by `FakePvpPersonality*` options.
- **Smarter play**:
  - They teleport and log off only when no player can see them.
  - They notice defensive buffs (UD, Guts, Zealot, mirrors, Angelic Icon) and wait them out.
  - Warriors switch to a polearm when many monsters surround them.
  - They can taunt and fight each other. At the hunting grounds they also meet like players: they say hello, walk over and talk in general chat with social actions, argue over the spot and fight for it, steal each other's monsters (and take revenge for it), and join the fights of others (`FakePvpMeet*`, `FakePvpRivalry*`, `FakePvpFakeKillStealChance`, `FakePvpJoinFightChance`, `FakePvpFake*Scale`).
  - Some carry a Blessed SoE, and they leave hotzones that have rotated out.
- **Loot and exp**: fake player damage now counts toward drop ownership and the exp/sp split.
- **Fake party members** (`Custom/FakeParty.ini`):
  - Invite a roaming fake player you find hunting (by name or target, within 2000 range). It accepts with a chance set by your levels: 80% at the same level, 10% less per level you are below it, 6% less per level you are above it, never past 10 levels; +20% for a member of your clan or alliance. It keeps to its answer for 5 minutes, and tells you when the level gap is why. Nobody comes over on request ("lf" in chat is gone).
  - Party fake players show in the party window with their HP/MP, follow the party leader (and come after it when it teleports), assist the leader's target and fight monsters attacking the party. Their damage and kills count for the party (exp/sp with their share, drops, quests), party skills reach them, and area skills spare the party.
  - New support builds (Cardinal, Hierophant, Eva's Saint, Shillien Saint, Doomcryer, Sword Muse, Spectral Dancer) heal, group heal, buff, recharge and resurrect; the Dominator keeps up its Pa'agrio buffs in a party. They don't roam on their own (`weight="0"`).
  - Monsters can kill a party fake player. A party healer or a player can resurrect it; otherwise it comes back from town a minute later. Dismiss it or leave the party to let it go; it goes back to hunting where it is.
  - Roaming fake players sometimes hunt in parties of 2-3, often with a healer or buffer, and fight together.
- **Clans of fake players** (`Custom/FakeClans.ini`):
  - 9 clans run by fake players (Valhalla, IronLegion, Nightshade, DragonGuard, SilverWolves, CrimsonDawn, Eclipse, Phoenix, Sovereign by default). They are real clans with a level, a leader, reputation and a crest of their own (drawn heraldic designs, or your own 16x12 picture in `data/fakeclans/<clan name>.bmp|.png|.dds`). A clan of fake players is marked by a leader id equal to its clan id, and the database cleanup at start keeps it.
  - About a third of new fake players (town, farming and party ones) are members, and friends that arrive or hunt together are often in the same clan. Members show the clan name, crest and alliance, and a random title. Fake players no longer show their subclass count as a title (`FakePvpPassiveTreeTitle` is gone).
  - IronLegion and CrimsonDawn are at war: their members fight when they meet on the hunting grounds.
  - Players can declare war on a fake clan. It declares war back after a while, and its members then attack that clan's players on sight. Kills in a war both clans declared give no karma, move clan reputation and give the reduced death penalty, and players see the clan war icons over fake players. When the players stop their war, the fake clan stops too. A fake clan whose members a players' clan keeps killing declares war on it.
  - An alliance leader can invite a fake clan by targeting one of its members (`/allyinvite`). The clan answers after a few seconds.
  - A fake player that isn't in a clan can be invited into a player's clan. If it accepts, it is a member for good, kept in the `fake_clan_members` table: its name (reserved, also against new characters), class line, looks and title never change. It logs off like a player (after its hunting time) and shows as offline in the clan window, then logs in again 30-240 minutes later on a hunting ground of about its level. Each day cycle (24 h by default, missed ones rolled at the next start) it has a 5% chance to leave the clan and, if it stays, a 5% chance to gain a level; reaching 20, 40, 52, 61, 76, 80, 82 or 84 rolls its gear and passive tree again, otherwise it keeps the same ones (made from a saved seed, so also after a restart). It can be dismissed from the clan window, online or not. The clan's member limit counts it, its level requirements don't.
  - Members never taunt or attack their own clan or alliance, and a fake player of a clan at war with yours won't join your party.
- **Castles of fake clans** (`FakeClanCastle*`, `FakeClanManor*` in `Custom/FakeClans.ini`):
  - A castle without a lord is taken by a fake clan of `FakeClanNames` that has no castle: right away at the server start, and 30 minutes after a siege draw or a disbanded lord's clan otherwise (never during its siege or a territory war). A 9th default clan, Sovereign, is added so all 9 castles have a lord.
  - The lord keeps the tax rate at 10% (within the Seal of Strife limit), shows its crest on the castle npcs and spends what the castle earns, so its treasury stays empty like an npc castle's. It accepts 80% of the clans that register to defend, 1-10 minutes after they register.
  - Manor: every period the lord sells every seed of its castle (its whole limit, at 100-130% of the base price) and buys every crop (its whole limit, at 100-150%, for one of the two rewards at random). It sets the next period during each modifiable period, sets the current period at once when it takes a castle, and refills the seeds and crops of the current period every 60 minutes. It pays nothing for its manor, and the crops it buys don't fill the clan warehouse.
  - In a siege of its castle the castle's npc guards defend it (it hires no mercenaries). A players' clan that engraves the artifact keeps the castle; the manor the fake lord set stays until the new lord sets the next period.
  - Turning `FakeClanCastles` off gives their castles back to the npcs at the next start. `//fakeclans` also lists each castle's lord, tax, manor and next siege.
- **Admin and UI**:
  - `//fakeplayers` opens an admin menu with all fake player commands.
  - `//fakeclans` lists the clans of fake players: level, leader, members online, reputation, alliance and wars, and how many fake members players' clans have.
  - Shift-clicking a fake player shows its equipment and stats.
  - Shift-clicking a monster opens a redesigned NPC info window.
  - There are now 20 PvP taunt chat lines.

### Hotzones
- The level 71+ brackets are split into 71-79, 80+, 81+, 82+, 83+ and 84+. The Stakato Nest and Antharas' Lair locations are fixed.
- The teleporter windows are redesigned with a card layout and a larger window.
- Minibosses, coin drops, champion boosts and the spawn multiplier now apply only to zones that are active in the current rotation.
- 13 new rotation modifiers:
  - Thieves' Den, Proving Grounds, Metamorphosis, Hair Trigger, Lucky Stars, Contested Ground and Coven reuse the Thief, Wave Challenge, Champion, Rage, Luck, roaming fake player and Mage systems.
  - Bounty Hunt and Rising Heat are timed and kill-count zone events.
  - Hornet's Nest makes every monster aggressive.
  - Kinship rewards parties hunting together.
  - Last Stand is a new buff: skill 27005, with client rows in `client/PassiveTree`.
  - Splitting Ground splits slain monsters into two weaker copies.

### Augmentation
- **Life Stones**: using one opens a list of equipment it can augment and a cost confirmation page. It can replace an existing augment, and no Blacksmith is needed.
- **Stronger options**: augment option values are raised. Weapon rolls are weighted by stone grade and weapon type, and higher-grade stones roll more blue options.
- **Faster skills**: active augment skills reuse faster. Damage skills reuse 10% slower than their class versions.
- **Wild Magic**: the passive Wild Magic augment is halved (+4 → +2).

### Community Board Buff Templates
- Players can save up to 3 templates of their own class buffs and apply one to themselves or their summon.
- Buffs are limited to the Player.ini `SkillDurationList` and checked server-side.
- Templates apply instantly, and the editor fits on one page without scrolling.

### Fixes & Misc
- `AdminFakePlayers` failed to compile under the script engine's Java 8 source level, which disabled all handlers. This is fixed.
- `PassiveTreeApiServer` (the passive tree web editor, started by `MasterHandler`) used Java 16 pattern matching, which fails under the same Java 8 source level and takes the handlers down with it. This is fixed; every script now compiles at `-source 1.8`.
- The passive tree XSD validation errors and the missing skill 90302 are fixed. The passive skill tree page now has a search box.
- Attribute stones no longer open an empty window when no item can take the attribute.
- The champion buff medal now follows auto-loot rules.
