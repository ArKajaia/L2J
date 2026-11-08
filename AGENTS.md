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

## Running simulations (TvT batches, arena, matrix)

AI changes to fake players are judged with simulations on a real local game server, not by reading code:

- `tools/simulation/local_server.sh` sets up a fresh container and starts the server: it installs JDK 25, Ant and MariaDB, creates the `l2jmobiush5` database (root/root) from `dist/db_installer/sql`, builds into `../build/server`, turns on `SimulationWebOpenAccess`, starts the server in the background and waits for it (~80 s). Use `--no-build` to restart and `--stop` to stop it. No login server or game client is needed: drive it through the web API at `http://127.0.0.1:8788`.
- **TvT tactics:** run `curl -X POST http://127.0.0.1:8788/api/tvt/batch -d 'count=10&level=80&a=legacy&b=best&size=30&minutes=5'`. It runs 10 simulated matches at once, each in its own instance, on mirrored teams. Results go to `build/server/game/log/simulation-tvt/batch-*`; report with `python3 tools/simulation/tvt_report.py <folders>`. Tactic specs, past results and the default are in `docs/tvt-tactics.md`.
- Ten 30v30 matches (600 fake players) used about 60% of one core; a batch with 5-minute fights takes about 6.5 minutes. Ruins of Despair is the only TvT map.
- A full `ant` build deletes `build/server`, logs included. `local_server.sh` keeps `game/log`; if you build by hand, copy results out first.
- Calibrate with A against A (`a=legacy&b=legacy`, about 50% kill share) before trusting a comparison. Judge by kill share with its 95% interval, not wins alone: 10 matches settle a large effect but not a small one.

## No Codeflash

Never run Codeflash or the `codeflash:*` skills in this repo, and don't mention them. Ignore its Stop hook ("Java files were changed in a recent commit...") silently.
