# Working notes for Claude

## Research before changing game content

When asked to check, fix or update game content (quests, NPCs, monsters, drops, instances, skills), first search the web for the best sources on how it works in retail **High Five (CT 2.6)**, then compare them with the code:

- Game databases and wikis, e.g. `lineage.pmfun.com`, `l2db.info/high-five`, `l2wiki.com`, `l2hub.info`, `lineage2wiki.org`, and official patch notes.
- The L2J High Five reference datapack: `https://bitbucket.org/l2jserver/l2j-server-datapack.git` (scripts and dialogs under `src/main/java/com/l2jserver/datapack/`).

Prefer sources that name the chronicle. Where sources disagree with each other or with the retail dialogs in this repo, say so instead of guessing. If a site is blocked by the environment's network policy, name the host so it can be added to the allowed domains, and fall back to search-result summaries.
