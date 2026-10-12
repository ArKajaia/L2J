Oracle and Totem Warchief skills - client additions (High Five)
================================================================

Rows for the reworked Prophet and Warcryer lines:
  27530-27540  Oracles, the Prophet line          (data/stats/skills/custom/oracle_skills.xml)
  27562        Inevitable Doom (Hierophant)       (data/stats/skills/custom/oracle_skills.xml)
  27563        Reckoning (Prophecy of Reckoning)  (data/stats/skills/custom/oracle_skills.xml)
  27545-27561  Totem Warchiefs, the Warcryer line (data/stats/skills/custom/warchief_skills.xml)
  27564        Warchief's Restraint (Warcryer)    (data/stats/skills/custom/warchief_skills.xml)

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

Like client/Performers, 91 rows each: every level of the prophecies and
what they come true with, Foresight, Inevitable Doom, the totems and their
echoes, the stance, Spirit Trance, Totem Circle and the two finales.

Applying them
-------------
1. Paste the new rows into both files, keeping them sorted by skill id:
   they go after the 27528 rows of client/Performers. If an older version of
   these rows is in, replace all of them (27530-27564): the 4th column of
   Skillgrp.dat changed, Totem of Flames has 8 levels and Prophecy of
   Reversal is now Prophecy of Reckoning.
2. Save & Encrypt, copy both .dat files into the client's system folder and
   restart the client.

Icons are retail icons of the buffs and chants these classes no longer learn
(Prophecy of Fire, Divine Inspiration, Block Shield, Chant of Vampire, War
Chant, Magnus' Chant...), so no texture files are needed.

The totems use the models of NPCs 143-146 (Totems of Body, Spirit, Bravery and
Fortitude) and the Great Totem the Iron Giant Totem's (25260), with server-side
names, so the client needs no NPC rows.

icon_type is the group the client shows a skill in: 0 physical, 1 magic,
2 buff, 3 debuff, 6 toggle (Glimpse and War Drums). operate_type is the
server's operateType: 0 A1, 1 A2, 2 P, 3 T; the client lists a skill with the
passives only when it is 2.
icon_type 6 is what makes the client treat a skill as a toggle: it is listed
under Toggle in the skill window and its icon is lit while it is on. With 2,
as these toggles had before, it shows like a buff skill even though it works
as a toggle, so paste the Skillgrp rows in again.

MP cost, cast range and cast time match the server data; if the server skills
change, regenerate the rows with generate_client_rows.py rather
than editing them by hand.
