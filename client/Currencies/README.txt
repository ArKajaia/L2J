Currencies and caches - client additions (High Five)
====================================================

Replacement rows for retail items this server gives a new purpose:
  3481  Gold Dragon        hot zone / Survival Arena coin (Rates.ini ArenaCurrencyItemId)
  6393  Glittering Medal   champion drop: learns buffs (CustomBuffSystem.ini ItemId)
                           and pays the Survival Arena entry fee
  6492  Sealed Cache (Common)   \
  6499  Sealed Cache (Rare)      > LuckyLoot.ini SealedCache*ItemId, contents in
  6509  Sealed Cache (Epic)     /  data/stats/items/custom/064xx and 065xx

  itemname-e_additions.txt  -> ItemName-e.dat

L2ClientDat key=value text format, tab-separated, one item per line, no
header line. These ids already exist in ItemName-e.dat: replace the
existing rows with these, don't add a second row with the same id.

The descriptions quote the default config values (cache contents,
ChampionRewardMaxLevelDifference = 12, SealedCacheMaxLevelDifference = 8,
22 Ancient Adena per Gold Dragon, 5 Common -> 1 Rare, 4 Rare -> 1 Epic,
1 medal per skill and per arena run). If those change, change the text.

Icons
-----
Icons are not in ItemName-e.dat but in EtcItemgrp.dat (the first entry of
icon={...} in the row with the same object_id). Retail textures only, so
no .utx files are needed:
  3481  icon.etc_magic_coin_gold_i00 -> icon.etc_coins_gold_i00
        (a pile of gold coins; the old icon is shared with every
        Coins of Magic coin, so the currency is easy to tell apart)
  6393  keep icon.etc_event_glitter_medal_i00
  6492/6499/6509  keep the treasure chest icons (green, blue, purple
        already read as Common, Rare, Epic)
The server's own item XML uses the same icons, so NPC windows that draw
the item icon match the inventory.

Applying them
-------------
1. Replace the five rows in ItemName-e.dat, and the 3481 icon in
   EtcItemgrp.dat.
2. Save & Encrypt, copy both .dat files into the client's system folder and
   restart the client.
