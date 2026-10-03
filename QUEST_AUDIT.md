# Quest Audit Plan: Top 50 Quests by Level

This is the order for checking the 50 highest-level quests for issues, from high level to low level.

## How the list was built

- **Level**: the minimum level a player needs to start the quest. It comes from the quest's `MIN_LEVEL`/`MIN_LV` constant or, when there is no constant, from the `player.getLevel()` check in `onTalk`/`onEvent`.
- Quests with no level check are left out. These are the Lord paths (Q00708–Q00716), the mercenary paths (Q00147/Q00148), Q00173, Q00426, Q00504, Q00635, Q00639 and Q00655. The 34 Saga quests (Q00067–Q00100) need level 76, which is checked in `AbstractSagaQuest`, so they fall below the top 50.
- **Order inside a level**: prerequisite quests come before the quests that need them (found from `getQuestState(...)`/`isCompleted()` calls on other quests). Quests that share a start NPC are grouped so one test setup covers several quests.
- Prerequisites never cross level tiers inside this list, so checking strictly from high to low level never tests a quest before its prerequisite. Two chains start outside the list: Q10283 needs **Q00115** and Q10292 needs **Q00198**. Mark those as completed with a GM command before testing.
- The cut at 50 falls inside the level 81 Seven Signs chain. Q10293–Q10296 are listed as an optional extension (#51–#54) so the chain can be checked as one piece.

## Issues already found by a static scan

The scan compared every HTML file name in each quest's Java code with the files in its folder:

| Quest | Problem |
|---|---|
| ~~Q10286_ReunionWithSirra~~ ✅ fixed | `32781-02.html` and `32781-03.html` were referenced but missing. Both are added, using the reference datapack's Jinia text. `jinia_npc_q10286_10.htm` was a retail file name; `32760-08.html` already shows that text, so the TODO is removed. |
| ~~Q10287_StoryOfThoseLeft~~ ✅ fixed | `32760-06.html` (shown as Jinia sends the player out of the hideout) had no text, only "Jinia:". It now tells the player to meet Rafforty for the reward. The TODO naming the retail file `jinia_npc_q10287_06.htm` is removed. |
| ~~Q00309_ForAGoodCause~~ ✅ fixed | The reward exchange returns `32646-15.htm`/`32646-16.htm` (lines 233/237). These names were copied from Q00308's NPC. The files that exist are `32647-15.html`/`32647-16.html`, so the player gets an empty window after every exchange. |
| ~~Q00308_ReedFieldMaintenance~~ ✅ fixed | Line 161 looks up Q00238 with `.class.getName()` (`quests.Q00238_...`). Quest states are stored under `getSimpleName()`, so the lookup always returns `null`, and `claimreward` always shows `32646-12.html`, even to players who completed Q00238. |
| ~~Q10295_SevenSignsSolinasTomb~~ ✅ fixed *(extension)* | `32787-06.html` (Elcadia's line while Solina is partway through her story) was missing. Added. |

Possible problems that need a closer look:

- ~~**Q10288_SecretMission**~~ ✅ checked: the reference datapack also gates it on level 82 only, with no Q10287 prerequisite. No change needed.
- **Q00464_Oath** has no `addStartNpc`. It is started from an item, so test the item-use path.

## Retail comparison: all quests above level 75

All 128 quests with a minimum level above 75 (88 with their own level check plus the 40 Saga quests at level 76) were compared with the L2J High Five datapack (`bitbucket.org/l2jserver/l2j-server-datapack`), which is built from retail data. The comparison covered level gates, prerequisites, NPC/monster/item IDs, EXP/SP/adena/item rewards, drop chances, quest type and dialog text.

- **52 quests match exactly.** Most of the rest differ only in how drop chances are written (`36` percent vs `0.36` vs `360` per thousand), in party range (`1500` vs the config setting), in cutscene IDs written as constants, or in dialog links renamed for this server. All of those were checked and come out the same.
- **Intentional differences kept**: Q10282 also gives the retail 212,182 adena; Q10502 also counts Freya's hard mode; Q00311 adds the Varangka altar fight; Q00238 fixes a start-condition bug and an NPC ID typo that the reference still has. Q00423 was rewritten in this fork with renumbered dialogs but plays the same.
- **Shared Saga logic** (`AbstractSagaQuest`): same level gate (76) and rewards (2,299,404 EXP, 5,000,000 adena, item 6622).
- **Not comparable**: Q00727_HopeWithinTheDarkness is not in the reference, so it was reviewed by hand (see below).

Bugs found (most are shared with the reference, so comparing alone would not catch them):

| Quest | Problem | Status |
|---|---|---|
| Q00901_HowLavasaurusesAreMade | Line 194 returned `32049-02.html`, but the file is `32049-02.htm`, so a player under level 76 got an empty window there. | ✅ fixed |
| Q00309_ForAGoodCause | The "Back" link on both reward lists (`32647-10.html`, `32647-11.html`) went to Q00308's script, so it did nothing. | ✅ fixed |
| Q10286_ReunionWithSirra | `32781-02.html`/`32781-03.html` now use the reference text instead of the wording written earlier. | ✅ updated |

| Q00727_HopeWithinTheDarkness | The party check tested the leader's clan for every member, so players from other clans could enter. | ✅ fixed |
| Q00727_HopeWithinTheDarkness | If an investigator NPC landed the last hit, `onKill` never ran (it only fires for player kills), so the dungeon never completed and nobody could finish the quest. Completion is now also checked on the investigators' 5-second timer, and everyone in the instance is credited, not just the killer's party. | ✅ fixed |
| Q00727_HopeWithinTheDarkness | When an investigator died, the others were killed with `doDie(null)`, but `QuestGuard.doDie` read the killer without a null check and threw, so the failure cleanup never ran. Fixed in `QuestGuard`. | ✅ fixed |
| Q00727_HopeWithinTheDarkness | The death-line array has 3 entries for 4 investigators, so the Seduced Warrior's death threw an exception. A random line is used now. | ✅ fixed |
| Q00727_HopeWithinTheDarkness | `CastleWarden-07.html` said level 75; the quest requires 80. | ✅ fixed |

Checks that found nothing: missing dialog files (beyond the ones above), quest lookups by `getName()`, empty dialogs, and dialog links to missing files.

## High Five zone changes: Antharas' Lair and Dragon Valley (low-level quests)

High Five rebuilt Antharas' Lair and Dragon Valley for level 80+ players. The patch notes say three quests were removed: Power of Darkness (55), Whisper of Dreams Part 1 (56) and Whisper of Dreams Part 2 (60). Every other quest monster from those zones moved to the Watcher's Tomb (or nearby: Death Pass for Drakes and Thunder Wyrms, and the area outside the Watcher's Tomb for Maluk Succubi). The moved monsters have new NPC IDs.

- **Removed quests**: none of the three is in this datapack. Nothing to do.
- **New High Five quests for these zones** (Q00026 Kitzka text, Q00254, Q00456, Q00903–Q00905, Q10290, Q10504): all present, and their dialogs already use the new monsters.
- **Kill lists**: every quest that hunted the old monsters also counts the new IDs, same as the reference datapack. Old ID → new ID: Royal Cave Servant 20276 → 20240, Cave Keeper 20277 → 20246, Shackle 20279 → 20235, Headless Knight 20280 → 20146, Dustwind Gargoyle 20281 → 20242, Thunder Wyrm 20282 → 20243, Maluk Succubus 20283/20284 → 20244/20245, Drake 20285 → 20137, Hunter Gargoyle 20286 → 20241, Cave Maiden 20287 → 20134. This covers Q00214, Q00241, Q00336, Q00337, Q00344, Q00384, Q00426 and Q00503. Q00708's Headless Knight is spawned by the quest itself. The old IDs stay in the lists (as in the reference) but no longer spawn.
- **Dialogs updated**:

| Quest | Change |
|---|---|
| Q00337_AudienceWithTheLandDragon | Gilmore (`30754-02/03/04`) and Theodric (`30755-03`) now send the player to the Watcher's Tomb for the Cave Keepers and Cave Maidens that reveal the third Abyssal Jewel. Before, the dialogs only said "in this valley", and those monsters no longer live in the valley or the lair. |
| Q00241_PossessorOfAPreciousSoul1 | Kantabilon (`31042-02/04`) now points to the Watcher's Tomb area for the Maluk Succubi, not "the Dragon Valley". |
| Q00384_WarehouseKeepersPastime | Baxt's monster list (`30685-06`) no longer names the Dragon Bearer Chief/Warrior/Archer. They do not exist in High Five, and Cliff's list (`30182-05/06`) already left them out. |

### Other zones

High Five revamped only two hunting zones: Dragon Valley (outdoor) and Antharas' Lair (indoor). To catch anything else, every quest's kill and talk targets were checked against what actually spawns: zone spawn files, instance files, raid boss/fort/territory spawn lists, minions, zone AI scripts and the quest's own `addSpawn` calls.

- **No other low-level quest is broken.** Wherever a quest monster no longer spawns, a same-named monster that does spawn is also counted, or the quest spawns it itself. Examples: Forgotten Village (Q00022/Q00024/Q00633), Zaken's Pikemen/Archers (Q00426/Q00710), Jackhammer Golems (Q00463/Q00647), Cannibalistic Stakato (Q00240/Q00310/Q00640), Pirate Zombie Captain (Q00365), Four Sepulchers (Q00619/Q00620, spawned by the zone's AI).
- **Giant's Cave** became level 81–82 in an earlier update and isn't a High Five change. Q00376/Q00377 already use the new monsters and require level 79. Q00426 (fishing shot) still lists the old level 60–65 Giant's Cave IDs, same as the reference. The new level 81+ monsters are not added to it.
- **Every NPC a quest talks to** is either spawned or spawned by a quest/instance script.
- **Leftover monster names that no longer spawn** (the quests still work through their other monsters): Q00384's dialogs (`30182-05/06`, `30685-06`) no longer name Conjurer Bat and Nightmare Guide, which do not spawn; its code still lists them and Cadeine, Sanhidro, Connabi, Bartal, Luminun and Innersen (harmless). Q00296 counts Crimson Tarantula in code only. Hunter and Plunder Tarantulas spawn.

## Starter quests (levels 2–15)

Supply Check (174), Head for the Hills (281), the race quests for levels 10–15 (101–108 for each race, 175 for Kamael) and Get a Pet (419) were compared with the reference datapack. Their level gates, races, NPCs, monsters, items, drop chances, EXP/SP, adena and dialogs all match it, and every NPC and monster they use spawns.

| Quest | Start | Adena | EXP / SP | Other rewards |
|---|---|---|---|---|
| Q00174_SupplyCheck | Lv 2, Marcela (Isle of Souls) | 2,466 | 5,672 / 446 | Wooden Breastplate, Helmet, Gaiters, Gloves, Leather Shoes |
| Q00281_HeadForTheHills | Lv 6, Marcela | 23 per claw, +400 for 10 or more | – | 50 claws buy a random item; 6,000 NG soulshots or 3,000 NG spiritshots once, under level 25 |
| Q00101_SwordOfSolidarity | Lv 9, Human | 10,981 | 25,747 / 2,171 | Sword of Solidarity |
| Q00102_SeaOfSporesFever | Lv 12, Elf | 6,331 | 30,202 / 1,339 | Sword or Staff of Sentinel |
| Q00103_SpiritOfCraftsman | Lv 10, Dark Elf | 19,799 | 46,663 / 3,999 | Blood Saber |
| Q00104_SpiritOfMirrors | Lv 10, Human | 16,866 | 39,750 / 3,407 | Wand of Adept |
| Q00105_SkirmishWithOrcs | Lv 10, Elf | 17,599 | 41,478 / 3,555 | Red Sunset Sword or Staff |
| Q00106_ForgottenTruth | Lv 10, Dark Elf | 10,266 | 24,195 / 2,074 | Eldritch Dagger (Eldritch Staff for mages; the reference gives the dagger to everyone) |
| Q00107_MercilessPunishment | Lv 10, Orc | 14,666 | 34,565 / 2,962 | Butcher's Sword |
| Q00108_JumbleTumbleDiamondFuss | Lv 10, Dwarf | 14,666 | 34,565 / 2,962 | Silversmith Hammer |
| Q00175_TheWayOfTheWarrior | Lv 10, Kamael | 8,799 | 20,739 / 1,777 | Warrior's Sword |
| Q00419_GetAPet | Lv 15, Martin (Gludio) | – | – | Wolf Collar |

Bugs found and fixed:

| Quest | Problem |
|---|---|
| Q00103_SpiritOfCraftsman | A kill picks a random party member, but the drop was checked against the killer and given to the killer while the picked member's step was advanced. Party members could skip steps or lose drops. The picked member now gets the drop, and only members on the matching step (6 for Zombie Heads, 3 for Bone Fragments) are picked. The Blood Saber was given with `rewardItems`, so a quest reward rate above 1 gave several; it is now always one, like the other race weapons. |
| Q00281_HeadForTheHills | Claws dropped for anyone who had opened Marcela's quest dialog, even without accepting the quest. Now only for players on the quest. |
| Q00108_JumbleTumbleDiamondFuss | `giveItemRandomly` returns `true` only when the limit is reached, so gem drops made no sound, and the "item got" sound played on every kill once one gem type was full. The method now plays the sounds itself. |
| Q00419_GetAPet | A player who left the pet test before finishing (closed the window or walked away) was told to meet the Animal Lovers again, but those visits could never bring the state back, so the quest could only be restarted from the beginning. An unfinished test now restarts from the first question. Talking to Metty, Elice or Bella during the test also changed the test's answer count; it no longer does. |

## Levels 18–42: Kaneus, pets, Little Wing, Pailaka, soul crystals

Mutated Kaneus Gludio/Dion/Heine (10276–10278), Blood Fiend (164), Dangerous Seduction (170), Help the Uncle/Sister/Son (42–44), Little Wing (420), Pailaka – Song of Ice and Fire (128) and Enhance Your Weapon (350) were compared with the reference datapack. Their level gates, races, NPCs, monsters, items, drop chances and rewards match it. The dialogs match too, except for Enhance Your Weapon, which this server reorganized: its soul crystal data is loaded by the server (`LevelUpCrystalData.xml`) and it has an extra under-level dialog.

| Quest | Start | Reward |
|---|---|---|
| Q10276_MutatedKaneusGludio | Lv 18, Bathis (Gludio) | 8,500 adena (no EXP). Tissues from Tomlan Kamos and Ol Ariosh, the bosses of Kamaloka Hall of the Abyss lv 23 and 26 |
| Q10277_MutatedKaneusDion | Lv 28, Lukas (Dion) | 20,000 adena. Crimson Hatu Otis and Seer Flouros (Kamaloka lv 33 and 36) |
| Q10278_MutatedKaneusHeine | Lv 38, Gosta (Heine) | 50,000 adena. Blade Otis and Weird Bunei (Kamaloka lv 43 and 46) |
| Q00164_BloodFiend | Lv 21, not Dark Elf | 42,130 adena, 35,637 EXP / 1,854 SP |
| Q00170_DangerousSeduction | Lv 21, Dark Elf only | 102,680 adena, 38,607 EXP / 4,018 SP |
| Q00044_HelpTheSon | Lv 24, Lundy | Pet Exchange Ticket: Kookaburra |
| Q00042_HelpTheUncle | Lv 25, Waters | Pet Exchange Ticket: Buffalo |
| Q00043_HelpTheSister | Lv 26, Cooper | Pet Exchange Ticket: Cougar |
| Q00420_LittleWing | Lv 35, Cooper | Dragonflute of Wind, Star or Twilight (hatchling) |
| Q00128_PailakaSongOfIceAndFire | Lv 36–42, Adler | 810,000 EXP / 50,000 SP, Pailaka Ring, Pailaka Earring, Scroll of Escape, +10,000 vitality points (half the maximum) |
| Q00350_EnhanceYourWeapon | Lv 40, Jurek, Gideon or Winonin | A stage 0 soul crystal; needed for crystals to absorb souls |

Any pet manager swaps the pet tickets for the baby pet (`PetManager` "exchange"). Pailaka was already fixed in earlier commits (7aea3ad1, ba86264d); nothing new was found there.

Bugs found and fixed:

| Quest | Problem |
|---|---|
| Q10276/Q10277/Q10278 Mutated Kaneus | If the player who landed the last hit on the Kamaloka boss did not have the quest, no party member got the tissue, and the boss does not come back until the next Kamaloka run. Now any party member on the quest can get it. Party members also had to be nowhere near the boss; they now have to be within party range. |
| Q00420_LittleWing | If the Deluxe Fairy Stone broke on the way to Mimyu (cond 4), Byron sent the player to Cronos and Cronos sent them back to Byron, so the quest could not go on. Cronos now offers a new stone, as he already did at cond 5. |
| Q00420_LittleWing | Mimyu's "give back the Fairy Dust" choice needed more than one Fairy Dust, but the quest gives exactly one, so that choice (Hatchling Food or the 5% Hatchling Armor) did nothing. |
| Q00420_LittleWing | Maria's link moved the quest on even if the materials were gone by the time it was clicked, with no stone made. It now shows her "bring the materials" dialog instead. |
| Q00420_LittleWing | Toad Skin and drake egg drops went to a random party member who might be on another step, have enough already, or hunt another drake, so the drop was lost. Only members who still need it are picked now. |
| Q00420_LittleWing | When a new stone was made, Byron's lines depended on the old stone instead of the new one, so a new ordinary stone got the "pure white stone" warning. |
| Q00350_EnhanceYourWeapon | The "do you have a crystal" check only covered stages 0–10. A player holding a stage 11 or higher crystal was offered a new stage 0 one. With two crystals in the inventory, neither absorbs souls. |

## Checklist for each quest

1. **Start**: level gate, including any upper limit, plus race/class/prerequisite checks. The "too low" and "already done" dialogs must appear when they should.
2. **NPCs and monsters**: every NPC/monster ID in `addStartNpc`/`addTalkId`/`addKillId` exists in `stats/npcs` and is spawned.
3. **HTML**: every dialog the code returns exists, and every link in the HTML maps to an event the code handles.
4. **Progress**: the `cond`/memo state moves forward in the right order, the quest log updates, and relogging partway through doesn't break the quest.
5. **Items**: quest item drop chance and needed counts are right, party members also get credit where they should, and quest items are removed when the quest ends or is aborted.
6. **Rewards**: adena, EXP/SP and items match the HTML text and retail. Check level/class-based reward branches.
7. **Repeating**: `DAILY` quests reset at the right time and refuse a second run on the same day. `REPEATABLE` quests can be restarted.
8. **Spawns and instances**: event NPCs and instance entry/exit clean up after themselves.
9. **Server log**: no exceptions or "missing html" warnings during the whole run.

## Order of checks

Columns: **Lines** is the size of the Java code and **HTML** is the number of dialog files, both as a rough guide to effort. **Type** is `D` for daily, blank for one-time. ⚠ marks known issues from the scan above.

### Level 84

| # | Quest | Lines | HTML | Type | Start NPC | Notes |
|---|---|---|---|---|---|---|
| 1 | Q10282_ToTheSeedOfAnnihilation | 126 | 13 | | Kbaldir | Prerequisite for #2 |
| 2 | Q00453_NotStrongEnoughAlone | 354 | 14 | D | Klemis | Needs Q10282 |
| 3 | Q00454_CompletelyLost | 793 | 16 | D | Injured Soldier | Largest script in the list. Escort logic |
| 4 | Q00452_FindingtheLostSoldiers | 143 | 10 | D | Jakan | |
| 5 | Q10504_JewelOfAntharas | 170 | 10 | | Theodric | |
| 6 | Q00904_DragonTrophyAntharas | 175 | 9 | D | Theodric | |
| 7 | Q00907_DragonTrophyValakas | 175 | 9 | D | Klein | |

### Level 83

| # | Quest | Lines | HTML | Type | Start NPC | Notes |
|---|---|---|---|---|---|---|
| 8 | Q10290_LandDragonConqueror | 167 | 10 | | Theodric | |
| 9 | Q00903_TheCallOfAntharas | 197 | 8 | D | Theodric | |
| 10 | Q10291_FireDragonDestroyer | 163 | 10 | | Klein | |
| 11 | Q00906_TheCallOfValakas | 178 | 8 | D | Klein | |
| 12 | Q10505_JewelOfValakas | 170 | 10 | | Klein | |
| 13 | Q00905_RefinedDragonBlood | 217 | 12 | D | Separated Souls | |

### Level 82

**Freya chain (Rafforty)**

| # | Quest | Lines | HTML | Type | Start NPC | Notes |
|---|---|---|---|---|---|---|
| 14 | Q10283_RequestOfIceMerchant | 205 | 19 | | Rafforty | Needs Q00115 (outside the list) |
| 15 | Q10284_AcquisitionOfDivineSword | 333 | 44 | | Rafforty | Needs Q10283 |
| 16 | Q10285_MeetingSirra | 375 | 42 | | Rafforty | Needs Q10284 |
| 17 | Q10286_ReunionWithSirra ✅ | 263 | 22 | | Rafforty | Needs Q10285. Missing HTML added |
| 18 | Q10287_StoryOfThoseLeft ✅ | 243 | 20 | | Rafforty | Needs Q10286. Empty dialog filled in |
| 19 | Q10288_SecretMission | 184 | 18 | | Aquilani, Dominic | No Q10287 check (verify) |
| 20 | Q10289_FadeToBlack | 337 | 11 | | Greymore | Needs Q10288 |
| 21 | Q00270_TheOneWhoEndsSilence | 460 | 12 | | Greymore (fake) | Needs Q10288 |
| 22 | Q10502_FreyaEmbroideredSoulCloak | 146 | 6 | | Olf Adams | |

**Winds of Change and Reed Field**

| # | Quest | Lines | HTML | Type | Start NPC | Notes |
|---|---|---|---|---|---|---|
| 23 | Q00237_WindsOfChange | 336 | 46 | | Flauen | Leads to Q00238 or Q00239 |
| 24 | Q00238_SuccessFailureOfBusiness | 220 | 11 | | Helvetica | Branch of Q00237 |
| 25 | Q00239_WontYouJoinUs | 220 | 13 | | Athenia | Branch of Q00237 |
| 26 | Q00308_ReedFieldMaintenance ✅ | 280 | 16 | | Katensa | Needs Q00238. Cannot run together with Q00309. Q00238 lookup fixed |
| 27 | Q00309_ForAGoodCause ✅ | 287 | 18 | | Atra | Needs Q00239. Reward dialog names fixed |

**Mouen / Sally / Pinaps / Stan quests and their follow-ups**

| # | Quest | Lines | HTML | Type | Start NPC | Notes |
|---|---|---|---|---|---|---|
| 28 | Q00249_PoisonedPlainsOfTheLizardmen | 113 | 10 | | Mouen | |
| 29 | Q00423_TakeYourBestShot | 185 | 17 | | Johnny, Batracos | Needs Q00249 |
| 30 | Q00250_WatchWhatYouEat | 183 | 20 | | Sally | |
| 31 | Q00287_FiguringItOut | 212 | 14 | | Laki | Needs Q00250 |
| 32 | Q00251_NoSecrets | 150 | 7 | | Pinaps | |
| 33 | Q00290_ThreatRemoval | 219 | 10 | | Pinaps | Needs Q00251 |
| 34 | Q00252_ItSmellsDelicious | 178 | 8 | | Stan | |
| 35 | Q00289_NoMoreSoupForYou | 265 | 6 | | Stan | Needs Q00252. Random reward table |
| 36 | Q00461_RumbleInTheBase | 178 | 7 | D | Stan | Needs Q00252 |

**Vladimir / Tunatun quests**

| # | Quest | Lines | HTML | Type | Start NPC | Notes |
|---|---|---|---|---|---|---|
| 37 | Q00019_GoToThePastureland | 128 | 7 | | Vladimir | Ends at Tunatun |
| 38 | Q00020_BringUpWithLove | 144 | 16 | | Tunatun | |
| 39 | Q00278_HomeSecurity | 194 | 7 | | Tunatun | |
| 40 | Q00631_DeliciousTopChoiceMeat | 222 | 6 | | Tunatun | |

**Stand-alone quests**

| # | Quest | Lines | HTML | Type | Start NPC | Notes |
|---|---|---|---|---|---|---|
| 41 | Q00279_TargetOfOpportunity | 152 | 8 | | Jerian | |
| 42 | Q00288_HandleWithCare | 225 | 8 | | Ankumi | |
| 43 | Q00457_LostAndFound | 251 | 11 | D | Gumiel | Escort NPC spawned by chance |
| 44 | Q00458_PerfectForm | 344 | 25 | D | Kelleyia | |
| 45 | Q00464_Oath | 316 | 39 | D | *(item)* | Started from an item. Reward picked by the finishing NPC |

### Level 81

| # | Quest | Lines | HTML | Type | Start NPC | Notes |
|---|---|---|---|---|---|---|
| 46 | Q00109_InSearchOfTheNest | 160 | 12 | | Pierce | |
| 47 | Q00146_TheZeroHour | 135 | 7 | | Kahman | Needs Q00109 |
| 48 | Q00240_ImTheOnlyOneYouCanTrust | 152 | 12 | | Kintaijin | |
| 49 | Q00310_OnlyWhatRemains | 170 | 12 | | Kintaijin | Needs Q00240 |
| 50 | Q10292_SevenSignsGirlOfDoubt | 357 | 32 | | Wood | Needs Q00198 (outside the list) |

### Optional extension: rest of the Seven Signs chain (level 81)

| # | Quest | Lines | HTML | Notes |
|---|---|---|---|---|
| 51 | Q10293_SevenSignsForbiddenBookOfTheElmoreAdenKingdom | 404 | 55 | Needs Q10292 |
| 52 | Q10294_SevenSignsToTheMonasteryOfSilence | 451 | 76 | Needs Q10293. Instance (see the SSQMonasteryOfSilence TODOs in `TODO.md`) |
| 53 | Q10295_SevenSignsSolinasTomb ✅ | 568 | 69 | Needs Q10294. Missing Elcadia dialog added; Elcadia now points back to Eris after Solina's story |
| 54 | Q10296_SevenSignsOneWhoSeeksThePowerOfTheSeal | 294 | 28 | Needs Q10295 |

## Suggested passes

1. **Static pass, all 50 (fast)**: checklist items 1–3 by reading the code. Fix the ⚠ items first, since they are already confirmed.
2. **In-game pass, in the order above**: checklist items 4–9. Test each chain from its first quest to its last in one session, so the prerequisite state is real and not set by GM commands.
3. **Daily pass**: run all `D` quests a second time after the daily reset to check the reuse timer.
