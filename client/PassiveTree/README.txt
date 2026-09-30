Passive tree skills - client additions (High Five)
===================================================

The passive tree grants custom skills 90400-90465 (server data:
dist/game/data/stats/skills/PassiveTreeActives.xml). Without these rows the
client shows them with no name and no icon.

  skillgrp_additions.txt      -> append to Skillgrp.dat
  skillname-e_additions.txt   -> append to Skillname-e.dat

Both are tab-separated in the same column layout as the L2ClientDat text
export (no header line). 82 rows each: 10 single-level skills and 8 skills
with 9 levels (Earthshatter, Volley, Arcane Nova, Mana Rift, Sacred Chorus,
Reaving Strike, Hunter's Mark, Binding Rune), whose level follows the
character level on the server.

Paste the rows at the end of each exported file (or in id order, if your
editor requires sorted ids), then re-encrypt the .dat and copy it to the
client's system folder.

Icons are existing retail icons, so no texture files are needed. MP/HP
costs, cast range and cast time match the server data; if the server skills
change, regenerate the rows with generate_client_rows.py rather than editing them
by hand.
