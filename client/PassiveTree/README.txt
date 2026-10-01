Custom skills - client additions (High Five)
============================================

Rows for the server's custom skills:
  27000-27004  Hot Zone buffs        (data/stats/skills/custom/hotzone_skills.xml)
  27400-27465  Passive tree actives  (data/stats/skills/PassiveTreeActives.xml)

Without these rows the client shows the skills with no name and no icon.

  skillgrp_additions.txt      -> Skillgrp.dat
  skillname-e_additions.txt   -> Skillname-e.dat

Both are tab-separated in the same column layout as the L2ClientDat text
export (no header line). 96 rows each: the Hot Zone buffs (Kill Streak has
10 levels), 10 single-level tree skills and 8 tree skills with 9 levels
(Earthshatter, Volley, Arcane Nova, Mana Rift, Sacred Chorus, Reaving
Strike, Hunter's Mark, Binding Rune), whose level follows the character
level on the server.

Applying them
-------------
1. These skills used to have ids 90000-90004 and 90400-90465. The client does
   not resolve skill ids that high (they never show in the skill window and
   buffs get a black icon), so delete every 90000-90499 row from BOTH .dat
   files first.
2. Paste the new rows. Keep the file sorted by skill id: they go after the
   last 25xxx row.
3. Save & Encrypt, copy both .dat files into the client's system folder and
   restart the client.
4. On the server, run once (game server stopped):
   dist/db_installer/sql/game/updates/2026-10-01_custom_skill_ids.sql
   so hotbar shortcuts and saved cooldowns move to the new ids.

Keep any future custom skill ids below 32768.

Icons are existing retail icons, so no texture files are needed. MP/HP
costs, cast range and cast time match the server data; if the server skills
change, regenerate the rows with generate_client_rows.py rather than editing
them by hand.
