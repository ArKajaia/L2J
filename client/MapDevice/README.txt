Maps of the Map Device - client additions (High Five)
=====================================================

New items (data/stats/items/custom/map_device_items.xml):
  50101 .. 50110  Map (Tier 1) .. Map (Tier 10)
  50111 .. 50115  Unique Map: The Crimson Coliseum, The Gilded Vault,
                  The Ossuary of Echoes, The Gambler's Den, The Primal Crucible
  50116           Primal Essence (shown as Blood of Eva without the patch)
  50117           Titan Scroll (shown as Transformation Scroll: Zaken without the patch)
  50118 .. 50119  Unique Map: The Ketra-Varka Front, The Tomb Raiders

Without these rows the server still works: the item XML gives each map
displayId 5915, so an unpatched client shows every tier as the retail
"Pirate's Treasure Map" (the server's own windows - the Map Device, the
drop messages - still name the tier). Patch the client so each tier
shows its own name and description:

  itemname-e_additions.txt  -> ItemName-e.dat (add these rows)
  add_etcitemgrp_rows.py    -> EtcItemgrp.dat

L2ClientDat key=value text format, tab-separated, one item per line, no
header line. These ids are new: add the rows, they replace nothing.

EtcItemgrp.dat
--------------
add_etcitemgrp_rows.py copies the retail Pirate's Treasure Map row
(object_id 5915) of your own EtcItemgrp.dat text export (L2ClientDat: open
EtcItemgrp.dat, save as text) ten times, as object_id 50101-50110:
  python add_etcitemgrp_rows.py EtcItemgrp.txt    -> EtcItemgrp.patched.txt
It prints the new rows. What it changes on the copies:
  icon              tiers 1-5 icon.etc_map_ivory_i00, tiers 6-10
                    icon.etc_map_yellow_i00 (the server XML uses the same)
  weight            0, the same as the server
  related_quest_id  cleared
Every other field stays as the retail row has it, so no texture files are
needed. Running it again replaces the rows instead of adding them twice.

The descriptions quote the default settings (8 medals, +20% per medal,
8 portals, the level ranges of data/MapDevice.xml). If those change,
change the text.

Applying them
-------------
1. Add the rows of itemname-e_additions.txt to ItemName-e.dat.
2. Run add_etcitemgrp_rows.py on your EtcItemgrp text and load the result.
3. Save & Encrypt, copy both .dat files into the client's system folder and
   restart the client.
4. On the server, remove the <set name="displayId" val="5915" /> lines
   from data/stats/items/custom/map_device_items.xml, so the patched client
   gets each map's own id. Every player needs the patched client from then on.

The NPCs (Map Device 900700, Map Portal 900701, Return Portal 900702) use
retail models (displayId) and server side names: no client change.
