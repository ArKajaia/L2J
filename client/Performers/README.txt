Performer skills - client additions (High Five)
===============================================

Rows for the Swordsinger / Bladedancer line skills:
  27500-27508  Swordsinger line  (data/stats/skills/custom/performer_skills.xml)
  27520-27528  Bladedancer line  (same file)

Without these rows the client shows the skills with no name and no icon.

  skillgrp_additions.txt      -> Skillgrp.dat
  skillname-e_additions.txt   -> Skillname-e.dat

Both are in the L2ClientDat key=value text format, tab-separated, one skill
level per line, no header line:
  Skillgrp.dat     skill_begin  skill_id=..  skill_level=..  icon=[..]
                   icon_panel=[]  icon_type=..  operate_type=..  is_magic=..
                   mp_consume=..  cast_range=..  hit_time=..  animation=[..]
                   cast_style=..  skill_visual_effect=[..]  debuff=0  enchanted=0
                   enchant_skill_level=0  enchant_icon=[none]  hp_consume=..
                   rumble_self=..  rumble_target=..  GaugeTime=0.0
                   AdditionalTag=[none]  skill_end
  Skillname-e.dat  skill_begin  skill_id=..  skill_level=..  name=[..]  desc=[..]
                   enchant_name=[none]  enchant_desc=[none]  skill_end

Like client/PassiveTree, 54 rows each: every level
of the performances, their echoes (the buff or debuff people near the
performer get), the stances and their triggered skills, and the two
finales.

Applying them
-------------
1. Paste the new rows into both files, keeping them sorted by skill id:
   they go after the 27465 rows of client/PassiveTree.
2. Save & Encrypt, copy both .dat files into the client's system folder and
   restart the client.

Icons are retail icons of performer skills the Champion Medals don't
teach (Spirit Barrier, Song of Purification, Battle Whisper, Sword
Symphony, Hex, Freezing Strike, Dance of Berserker, Demonic Blade Dance,
Dance of Blade Storm, Dance of Medusa...), so no texture files are needed
and an echo is never mistaken for a medal song in the buff bar.

icon_type is the group the client shows a skill in: 0 physical, 1 magic,
2 buff, 3 debuff, 6 toggle (the performances and stances). operate_type is
the server's operateType: 0 A1, 1 A2, 2 P, 3 T.
icon_type 6 is what makes the client treat a skill as a toggle: it is listed
under Toggle in the skill window and its icon is lit while it is on. With 2,
as these toggles had before, it shows like a buff skill even though it works
as a toggle, so paste the Skillgrp rows in again.

MP cost, cast range and cast time match the server data; if the server skills
change, regenerate the rows with
generate_client_rows.py rather than editing them by hand.
