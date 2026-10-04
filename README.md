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
- Visually and mechanically indistinguishable from real players (same info packets, PvP-flag behavior, generated names), though still NPCs internally.

### Rotating Hot Zones

A rotating set of bonus hunting zones that periodically change location and grant temporary buffs to whoever hunts there.

- A fixed pool of hand-placed zones, grouped by level bracket, with one zone per bracket "active" at a time on a schedule.
- Active zones buff both players and monsters, plus apply a randomly-rolled modifier each rotation (bonus loot, tougher monsters, drain effects, etc.).
- Kills inside a hot zone can drop bonus currency and, once enough accumulate, spawn an empowered miniboss.
- Players are notified via announcements and a dedicated teleporter NPC.

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

A short, solo, instanced trial at the Class Master NPCs that stands in for the long class transfer quest chains: clearing it unlocks that tier's Class Master transfer.

- Each tier (1st, 2nd, 3rd class) has a fighter trial and a mage trial, picked by a fallback chain (class, parent class, race, fighter/mage, generic), so more can be added per class without touching code.
- Every race can use it, Kamael included. Kamael take the fighter trials, except Soul Breakers, who are about to become Soul Hounds and take the Archmage trial.
- Entering is an instant teleport from the Class Master into a private copy of a small hall; clearing it sends the player straight back. No one else can enter or affect the trial.
- Objectives run in order and show on screen with a countdown: kill, collect (only marks won inside the trial count), reach a spot, talk, activate seals, protect a ward, survive, defeat a boss, use an item, clear waves, and duel.
- Built from the server's own systems:
  - **Omens** — every attempt rolls a Hot Zone modifier that applies to the whole trial (Kill Streak, Glass Cannon, Restless Dead, Fragile Ground...), shown before entering.
  - **Wave/Arena Champions** — bosses come back stronger each phase through the Wave Challenge engine; the toughest also get the Survival Arena buffs and enrage.
  - **Mark Thief and Arcane Skirmishers** — forced Thief and Mage monsters: the Thief pockets marks won near it and pays them back (with a bonus for a full bag) when killed; Mage monsters kite and cast.
  - **Rival Shade and Invaders** — the duel is against a fake player built as one of the classes the player is about to become, at their level; a rival may also invade mid-trial.
  - **Rewards** — Survival Arena currency (more for a fast clear) and one Sealed Cache per clear, while the trials themselves drop no hotzone coins and their Mage monsters no caches.
- Falling doesn't kill: the player is knocked out and returns to the entrance with progress kept (or, if configured, the trial fails). A disconnect keeps the trial for a grace period; a relog after it, or after a restart, lands the player back where they entered.
- The requirement is enforced right where the Class Master changes the class, and a clear only counts for the class and class slot that earned it; the transfer uses it up. GMs have `//challenge_status`, `//challenge_start`, `//challenge_complete`, `//challenge_abort` and `//challenge_reset`; players have `.trial`.

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

The in-client Community Board (the ".bbs" interface) is backed by a lightweight forum/topic/post data model (with clan, memo, and mail-flavored forum types alongside normal ones) and a set of interactive board pages served as cached HTML and routed through the same bypass-handling pipeline used for NPC dialogs: clan management, regional teleport/info, a home page with favorites/bookmarks, a friends list, personal memos, in-client mail, an item drop-source search utility, personal buff templates (see [Custom Feature Set](#custom-non-retail-feature-set)), and management of the passive skill tree feature (see [Custom Feature Set](#custom-non-retail-feature-set)).

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
| Voiced commands | Player-typed `.command`s (banking, autoplay, offline shop, password change, premium status, wedding, DPS meter) | ~25 |
| Chat handlers | Delivery/range logic per chat channel (general, shout, trade, party, clan, alliance, whisper, hero, petition, battlefield) | 14 |
| Bypass handlers | Server-side link commands from NPC/HTML dialogs (shops, multisell, warehouse, freight, Olympiad, augmenting, observation) | ~30 |
| Community Board handlers | `.bbs` page logic (see [Community Board](#community-board)) | 10 |
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

Changes from 28 September – 4 October 2026.

### Passive Skill Tree
- **Route choices**: each archetype now has several ways to the same place, and each way costs a different amount.
  - **Start**: each Origin has three roads to its first Crossroads. The middle road costs 3 points. The two side roads cost 4 and pass the entry of that Crossroads' two clusters, which used to hang off the Crossroads.
  - **Lanes**: beside each of the three middle stretches of the main road there is a pass-through cluster (small, notable, small, plus one side small). It costs 1 point more than the plain road and gives you the notable on the way. The keystone or active skill of that stretch now hangs off the lane's first small.
  - **Outer Rim**: each Origin has two more roads to the rim, landing one node either side of the Rim Gate. They save 1 point towards the outer regions but skip the gate.
  - Node stats are unchanged. The 60 new road nodes copy existing Pathway and Outbound Road values. Allocated nodes that no longer connect are dropped on login, and their points are returned.
- **Active skills fixed**: Earthshatter, Volley and Arcane Nova now deal their intended damage (Arcane Nova did none), Earthshatter's stun can land, and Shadow Lunge's critical damage bonus applies.
- **Scale with level**: the damage, heal and debuff actives have 9 levels. You get the level that matches your character level, it moves up as you level, and the cooldown carries over. Before, they failed against high-level targets.
- **Hybrid actives**: each hybrid sector has an active skill node: Crimson Bulwark (Warlord), Reaving Strike (Reaver), Hunter's Mark (Stalker), Binding Rune (Spellbow), Temporal Flow (Mystic), Purifying Aegis (Templar).
- **Renamed** to avoid clashing with retail skills: War Cry is Battle Fervor, Shadow Step is Shadow Lunge, Benediction is Sacred Chorus, Sanctuary is Hallowed Ward.
- **Utility nodes that do something**: Field Salvage (was Field Repairs) grants Crystallize for every grade, Tracker's Guile grants Silent Move, Scholar's Insight gives +5% XP and SP, and Pilgrim's Provisions (was Sacred Artisan) gives +12 inventory slots and +30% weight limit.
- **Settings**: `Custom/PassiveTree.ini` is now loaded, with the same values as before. Single-node refunds are charged in Adena.
- **Client**: the custom skills need Skillname-e.dat / Skillgrp.dat entries to show their names and icons.

### Alternative Class Transfer Challenges
- **New**: the Class Masters now offer a short solo trial for each class transfer. Clearing it unlocks the transfer at that Class Master; the village-master quests are unchanged.
- **Six trials**: a fighter and a mage trial per tier, each 4-6 objectives in a private hall reached by teleport — no walking across the world.
- **Built from existing systems**: a rolled Hot Zone Omen per attempt, multi-phase Wave/Arena Champion bosses, the Mark Thief, Mage skirmishers, champion elites, a fake-player Rival Shade of your future class, and occasional fake-player Invaders.
- **Forgiving**: falling sends you back to the entrance with your progress; disconnects are held for a while.
- **Rewards**: Survival Arena currency (bonus for a fast clear) and a Sealed Cache.
- **Commands**: `.trial` shows your progress (`.trial abandon` gives up); GMs get `//challenge_status|start|complete|abort|reset` and `//reload classtransferchallenge`.
- **Kamael**: the Class Masters now transfer Kamael too (Trooper/Warder, then Berserker, Soul Breaker or Arbalester, then Doombringer, Soul Hound, Trickster, or Judicator for Inspectors). Soul Breakers take the Archmage trial, other Kamael the fighter trials, and the Rival Shade uses the Kamael fake-player builds.
- **Setup**: run `class_transfer_challenge_completion.sql`, and on an existing database the Kamael block at the end of `class_transfer_tree.sql` (it only adds missing rows). Settings are in `Custom/ClassTransferChallenge.ini`, trials in `data/ClassTransferChallenges/`.

### Fake Players
- **New builds**: Dreadnought, Dominator, Soultaker, Hell Knight, and the Kamael classes (Doombringer, Male/Female Soul Hound, Trickster, Judicator). Every class also gets a second gear variant. Kamael wear light armor only, like players (heavy armor and robes showed untextured on them).
- **Servitors**: Necromancers and Hell Knights summon their servitors. Necromancers link theirs with Transfer Pain and re-summon it during PvP.
- **Kamael mechanics**: fake players use souls, Final Form, Soul Cleanse and Warp. Disarm now works on fake players.
- **Personality**: each fake player rolls its own aggression, skill use, chattiness and roaming. The spread around the config values is set by `FakePvpPersonality*` options.
- **Smarter play**:
  - They teleport and log off only when no player can see them.
  - They notice defensive buffs (UD, Guts, Zealot, mirrors, Angelic Icon) and wait them out.
  - Warriors switch to a polearm when many monsters surround them.
  - They can taunt and fight each other. At the hunting grounds they also meet like players: they say hello, walk over and talk in general chat with social actions, argue over the spot and fight for it, steal each other's monsters (and take revenge for it), and join the fights of others (`FakePvpMeet*`, `FakePvpRivalry*`, `FakePvpFakeKillStealChance`, `FakePvpJoinFightChance`, `FakePvpFake*Scale`).
  - Some carry a Blessed SoE, and they leave hotzones that have rotated out.
- **Loot and exp**: fake player damage now counts toward drop ownership and the exp/sp split.
- **Admin and UI**:
  - `//fakeplayers` opens an admin menu with all fake player commands.
  - Shift-clicking a fake player shows its equipment and stats.
  - Shift-clicking a monster opens a redesigned NPC info window.
  - There are now 20 PvP taunt chat lines.

### Hotzones
- The level 71+ brackets are split into 71-79, 80+, 81+, 82+, 83+ and 84+. The Stakato Nest and Antharas' Lair locations are fixed.
- The teleporter windows are redesigned with a card layout and a larger window.
- Minibosses, coin drops, champion boosts and the spawn multiplier now apply only to zones that are active in the current rotation.

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
- The passive tree XSD validation errors and the missing skill 90302 are fixed. The passive skill tree page now has a search box.
- Attribute stones no longer open an empty window when no item can take the attribute.
- The champion buff medal now follows auto-loot rules.
