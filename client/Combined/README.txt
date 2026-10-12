All custom skills - client additions (High Five)
================================================

The skillgrp and skillname-e rows of client/OraclesWarchiefs,
client/PassiveTree and client/Performers merged into one pair of files,
sorted by skill id and level (242 rows each), so they can be pasted in one go:

  skillgrp_additions.txt      -> Skillgrp.dat
  skillname-e_additions.txt   -> Skillname-e.dat

Paste all rows into both files, replacing any older copies of these ids
(27000-27564), then Save & Encrypt and copy both .dat files into the
client's system folder. See the README in each source folder for details.

These files are generated: if a source folder's rows change, rebuild them with

  for n in skillgrp skillname-e; do
    cat OraclesWarchiefs/${n}_additions.txt PassiveTree/${n}_additions.txt \
        Performers/${n}_additions.txt \
      | sort -t"$(printf '\t')" -s -k2.10,2n -k3.13,3n > Combined/${n}_additions.txt
  done

run from the client folder.
