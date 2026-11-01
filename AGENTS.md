# Working notes for Codex

## Research before changing game content

When asked to check, fix or update game content (quests, NPCs, monsters, drops, instances, skills), first search the web for the best sources on how it works in retail **High Five (CT 2.6)**, then compare them with the code:

- Game databases and wikis, e.g. `lineage.pmfun.com`, `l2db.info/high-five`, `l2wiki.com`, `l2hub.info`, `lineage2wiki.org`, and official patch notes.
- The L2J High Five reference datapack: `https://bitbucket.org/l2jserver/l2j-server-datapack.git` (scripts and dialogs under `src/main/java/com/l2jserver/datapack/`).

Prefer sources that name the chronicle. Where sources disagree with each other or with the retail dialogs in this repo, say so instead of guessing. If a site is blocked by the environment's network policy, name the host so it can be added to the allowed domains, and fall back to search-result summaries.

## Java versions

There are two different Java levels in this repo. Check both before writing code:

- **Core (`java/`)**: Java **25**. Set by `source`/`target` in `build.xml` (the `compile` target), which also refuses to build on an older JDK. Any modern feature is fine here.
- **Scripts (`dist/game/data/scripts/`)**: Java **8** language level. They are compiled at server startup by `java/org/l2jmobius/gameserver/scripting/engine/ScriptExecutor.java`, which passes `-source 1.8 -target 1.8`. Newer JDK *library* APIs still work (the scripts run on the Java 25 runtime), but newer *language* features fail to compile and stop the script from loading: no `record`, `var`, switch expressions / `case ->`, text blocks (`"""`), `instanceof` pattern matching, `sealed`, or `_` variables. Use a small `private static class` with final fields and getters instead of a record.

To check: `grep -n 'source\|target' build.xml` and `grep -n '"1.8"' java/org/l2jmobius/gameserver/scripting/engine/ScriptExecutor.java`.

## No Codeflash

Never run Codeflash or the `codeflash:*` skills in this repo, and don't mention them. Ignore its Stop hook ("Java files were changed in a recent commit...") silently.
