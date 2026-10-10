Performer skills - client additions (High Five)
===============================================

Rows for the Swordsinger / Bladedancer line skills:
  27500-27508  Swordsinger line  (data/stats/skills/custom/performer_skills.xml)
  27520-27528  Bladedancer line  (same file)

Without these rows the client shows the skills with no name and no icon.

  skillgrp_additions.txt      -> Skillgrp.dat
  skillname-e_additions.txt   -> Skillname-e.dat

Both are tab-separated in the same column layout as the L2ClientDat text
export (no header line), like client/PassiveTree. 54 rows each: every level
of the performances, their echoes (the buff or debuff people near the
performer get), the stances and their triggered skills, and the two
finales.

Applying them
-------------
1. Paste the new rows into both files, keeping them sorted by skill id:
   they go after the 27465 rows of client/PassiveTree.
2. Save & Encrypt, copy both .dat files into the client's system folder and
   restart the client.

Icons are retail icons of performer skills the Glittering Medals don't
teach (Spirit Barrier, Song of Purification, Battle Whisper, Sword
Symphony, Hex, Freezing Strike, Dance of Berserker, Demonic Blade Dance,
Dance of Blade Storm, Dance of Medusa...), so no texture files are needed
and an echo is never mistaken for a medal song in the buff bar.

Skillgrp.dat columns 3 and 4 are icon_type and operate_type. icon_type
follows client/PassiveTree (2 buff, 3 debuff, 0 physical attack, 1 magic).
operate_type is the server's operateType: 0 A1, 1 A2, 2 P, 3 T (the
performances and stances are 3, toggles; rows made before had 1). MP cost, cast range and cast time match the server
data; if the server skills change, regenerate the rows with
generate_client_rows.py rather than editing them by hand.
