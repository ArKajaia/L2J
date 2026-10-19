Currencies and caches - client additions (High Five)
====================================================

Replacement rows for retail items this server gives a new purpose:
  3481  Dragon Coin        hot zone / Survival Arena coin (retail: Gold Dragon) (Rates.ini ArenaCurrencyItemId)
  6393  Champion Medal     (retail: Event - Glittering Medal) champion drop: learns buffs (CustomBuffSystem.ini ItemId)
                           and pays the Survival Arena entry fee
  4674  Defence Medal      (retail: Medal of Honor, an unused event item) Village Under Siege
                           contribution, exchanged for gear at the Defence Force Board
                           (VillageSiege.ini VillageSiegeMedalItemId)
  6492  Sealed Cache (Common)   \
  6499  Sealed Cache (Rare)      > LuckyLoot.ini SealedCache*ItemId, contents in
  6509  Sealed Cache (Epic)     /  data/stats/items/custom/064xx and 065xx

  itemname-e_additions.txt  -> ItemName-e.dat

L2ClientDat key=value text format, tab-separated, one item per line, no
header line. These ids already exist in ItemName-e.dat: replace the
existing rows with these, don't add a second row with the same id.

The descriptions quote the default config values (cache contents,
ChampionRewardMaxLevelDifference = 12, SealedCacheMaxLevelDifference = 8,
22 Ancient Adena per Dragon Coin, 5 Common -> 1 Rare, 4 Rare -> 1 Epic,
1 medal per skill and per arena run). If those change, change the text.

EtcItemgrp.dat
--------------
patch_etcitemgrp.py edits these six rows in your own EtcItemgrp.dat text
export (L2ClientDat: open EtcItemgrp.dat, save as text) and leaves every
other field as retail has it:
  python patch_etcitemgrp.py EtcItemgrp.txt    -> EtcItemgrp.patched.txt
It prints the patched rows. What it changes:
  icon              3481  icon.etc_magic_coin_gold_i00 -> icon.etc_coins_gold_i00
                          (a pile of gold coins: the old icon is shared by
                          every Coins of Magic coin)
                    4674  icon.etc_magic_coin_gold_i00 -> icon.etc_badge_gold_i00
                          (a gold badge, like the server's item XML)
                    6393  icon.etc_event_glitter_medal_i00 (kept)
                    6492/6499/6509  the green, blue and purple chests (kept:
                          they already read as Common, Rare, Epic)
  weight            0 for the coin and the medals, 100 for the caches, the
                    same as the server, so the client's weight gauge agrees
  related_quest_id  cleared: they are no longer quest or event items
  drop look/sound   3481 copies drop_type, drop_anim_type, drop_radius,
                    drop_height, drop_mesh, drop_texture, droptexnum and
                    drop_sound from the Adena row (object_id 57), so a coin
                    drop on the ground looks and sounds like money
All icons and meshes are retail ones, so no texture files are needed. The
server's item XML uses the same icons, so NPC windows that draw the item
icon match the inventory.

Applying them
-------------
1. Replace the six rows in ItemName-e.dat with itemname-e_additions.txt.
2. Run patch_etcitemgrp.py on your EtcItemgrp text and load the result.
3. Save & Encrypt, copy both .dat files into the client's system folder and
   restart the client.
