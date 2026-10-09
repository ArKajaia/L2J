Oracle and Totem Warchief skills - client additions (High Five)
================================================================

Rows for the reworked Prophet and Warcryer lines:
  27530-27540  Oracles, the Prophet line          (data/stats/skills/custom/oracle_skills.xml)
  27545-27558  Totem Warchiefs, the Warcryer line (data/stats/skills/custom/warchief_skills.xml)

Without these rows the client shows the skills with no name and no icon.

  skillgrp_additions.txt      -> Skillgrp.dat
  skillname-e_additions.txt   -> Skillname-e.dat

Both are tab-separated in the same column layout as the L2ClientDat text
export (no header line), like client/Performers. 68 rows each: every level
of the prophecies and what they come true with, Foresight, the totems and
their echoes, the stance, Totem Circle and the two finales.

Applying them
-------------
1. Paste the new rows into both files, keeping them sorted by skill id:
   they go after the 27528 rows of client/Performers.
2. Save & Encrypt, copy both .dat files into the client's system folder and
   restart the client.

Icons are retail icons of the buffs and chants these classes no longer learn
(Prophecy of Fire, Divine Inspiration, Block Shield, Chant of Vampire, War
Chant, Magnus' Chant...), so no texture files are needed.

The totems use the models of NPCs 143-146 (Totems of Body, Spirit, Bravery and
Fortitude) and the Great Totem the Iron Giant Totem's (25260), with server-side
names, so the client needs no NPC rows.

oper_type follows client/Performers (2 buff, 3 debuff, 0 attack, 1 heal);
toggles use 2. MP cost, cast range and cast time match the server data; if the
server skills change, regenerate the rows with generate_client_rows.py rather
than editing them by hand.
