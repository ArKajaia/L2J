"""
The class clinic: one build against every other build in 1v1 battles, read battle by battle, to find what its AI does
wrong against each kind of opponent and to prove a fix. The process is in docs/class-clinic.md.

    # 1. Fight: the build against all 39 others (a focused class matrix that keeps every battle log), then the report.
    python3 tools/simulation/class_clinic.py run "Female Soul Hound" --battles 30 --seed 4040

    # 2. Read the report again, or one group of opponents, or one fight second by second.
    python3 tools/simulation/class_clinic.py report <matrix folder> [--group tank] [--out report.md]
    python3 tools/simulation/class_clinic.py timeline <matrix folder>/battles/000018-Titan-vs-FemaleSoulHound

    # 3. After a change (rebuild, restart, run again with the same seed): did it work, per kind of opponent?
    python3 tools/simulation/class_clinic.py compare <baseline folder> <changed folder>

    # A quick look at one kind of opponent only, while working on a fix.
    python3 tools/simulation/class_clinic.py run "Female Soul Hound" --against tank,melee --battles 20 --seed 4040

Needs a running game server with SimulationWebOpenAccess (tools/simulation/local_server.sh), or --token. Standard
library only. A draw counts half a win. Intervals are 95% (Wilson for a rate, normal approximation for a difference).
"""
import argparse
import csv
import json
import math
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict

# The kinds of opponent a build is judged against. A build missing here (a new one) takes its kind from its role in run.json.
GROUPS = {
    "tank": ["Phoenix Knight", "Hell Knight", "Eva's Templar", "Shillien Templar"],
    "melee": ["Duelist", "Grand Khavatari", "Titan", "Dreadnought", "Doombringer", "Fortune Seeker", "Maestro", "Judicator", "War Dancer", "Battle Oracle", "Male Soul Hound Hybrid", "Female Soul Hound Hybrid"],
    "dagger": ["Adventurer", "Wind Rider", "Ghost Hunter"],
    "archer": ["Sagittarius", "Moonlight Sentinel", "Ghost Sentinel", "Trickster"],
    "nuker": ["Archmage", "Mystic Muse", "Storm Screamer", "Soultaker", "Male Soul Hound", "Female Soul Hound", "Flame Warchief", "Doom Oracle", "Dominator"],
    "healer": ["Cardinal", "Eva's Saint", "Shillien Saint"],
    "buffer": ["Sword Muse", "Spectral Dancer", "Doomcryer", "Hierophant", "Battle Bard"],
}
GROUP_ORDER = ["tank", "melee", "dagger", "archer", "nuker", "healer", "buffer"]
GROUP_OF = {build: group for group, builds in GROUPS.items() for build in builds}

MELEE_GAP = 200  # An enemy this close is "on it".
POSITION_STEP = 0.5  # Seconds between two position samples (SimulationPositionInterval).
# Decisions that mean "standing there on purpose or for lack of something to do". A sample with no cast, attack or move
# under any other decision is the short pause between two actions (FakePlayerPvpAI.SKILL_CHAIN_*), which every build has.
WAITING = {"MAGE_HOLD", "HOLD_OFF", "WAIT_INVUL", "SIM_WAIT", "SIM_ENGAGE", "GUARD_HOLD", "HOLD_FOR_TANK", "IDLE", "KITE_CORNERED", "DROP_TARGET_INVALID", "DROP_TARGET_STUCK", "DROP_TARGET_LEASH", "STAND_UP", "REST"}
STATES = ("casting", "attacking", "moving", "disabled", "waiting", "between")


# ---------------------------------------------------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------------------------------------------------

def wilson(points, n, z=1.96):
    if n == 0:
        return 0.0, 1.0
    p = points / n
    centre = (p + z * z / (2 * n)) / (1 + z * z / n)
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
    return max(0.0, centre - half), min(1.0, centre + half)


def delta_interval(p1, n1, p2, n2, z=1.96):
    if n1 == 0 or n2 == 0:
        return 0.0, -1.0, 1.0
    d = p2 - p1
    se = math.sqrt(p1 * (1 - p1) / n1 + p2 * (1 - p2) / n2)
    return d, d - z * se, d + z * se


def median(values):
    return statistics.median(values) if values else 0.0


def mean(values):
    return sum(values) / len(values) if values else 0.0


def skill_name(text):
    """'Soul Vortex (1512-1)' or 'Soul Vortex (1512)' -> 'Soul Vortex'."""
    return re.sub(r"\s*\(\d+(-\d+)?(, range -?\d+)?\)$", "", text) if text else "attack"


def group_of(build, role=None, build_role=None):
    if build in GROUP_OF:
        return GROUP_OF[build]
    if role == "TANK":
        return "tank"
    if role == "HEALER":
        return "healer"
    if role == "BUFFER":
        return "buffer"
    if role == "ARCHER":
        return "archer"
    if role == "NUKER":
        return "nuker"
    return "dagger" if build_role == "DAGGER" else "melee"


def read_lines(path):
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                yield json.loads(line)
            except ValueError:
                continue


# ---------------------------------------------------------------------------------------------------------------------
# Reading a matrix folder
# ---------------------------------------------------------------------------------------------------------------------

def read_settings(folder):
    settings = {}
    path = os.path.join(folder, "matrix.properties")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if "=" in line and not line.startswith("#"):
                    key, value = line.rstrip("\n").split("=", 1)
                    settings[key] = value.replace("\\:", ":").replace("\\ ", " ")
    return settings


def focus_build(folder, wanted=None):
    if wanted:
        return wanted
    focus = [b for b in read_settings(folder).get("focus", "").split("|") if b]
    if len(focus) != 1:
        sys.exit(f"{folder}: the matrix has {len(focus)} focus builds; name one with --build.")
    return focus[0]


class Battle:
    """One fight of the focus build, with what its logs say."""

    def __init__(self):
        self.number = 0
        self.folder = None
        self.opponent = None
        self.group = None
        self.points = 0.0  # 1 win, 0.5 draw, 0 loss
        self.reason = None
        self.duration = 0.0  # seconds of fighting
        self.hp_left = 0.0
        self.enemy_hp_left = 0.0
        self.logged = False
        # From the logs:
        self.dealt = 0
        self.taken = 0
        self.enemy_healed = 0
        self.healed = 0
        self.alive = 0.0
        self.casts = Counter()  # skill -> casts started
        self.aborts = Counter()
        self.cast_time = Counter()  # skill -> ms of casting started
        self.cast_gap = defaultdict(list)
        self.damage = Counter()  # skill -> damage dealt
        self.hits = Counter()
        self.landed = Counter()  # debuffs of the focus build that landed
        self.resisted = Counter()
        self.enemy_damage = Counter()  # enemy skill -> damage taken
        self.enemy_landed = Counter()  # enemy debuff -> times it landed on the focus build
        self.enemy_landed_seconds = Counter()
        self.enemy_casts = Counter()
        self.into_invul = 0
        self.samples = 0
        self.state = Counter()  # samples by STATES
        self.melee_samples = 0
        self.gaps = []
        self.rooted = 0
        self.enemy_disabled = 0
        self.enemy_rooted = 0
        self.enemy_samples = 0
        self.min_mp = 1.0
        self.end_mp = 1.0
        self.first_hit = None  # "focus" or "enemy"
        self.opener = []
        self.killing_blow = None
        self.kill_blow = None
        self.reasons = Counter()  # decision reason -> ms
        self.anomalies = Counter()
        self.kit = {}  # category -> [skill names]
        self.stats = {}
        self.traits = []  # ("keystone", name), ("chest", name), ("weapon", name), ("skill", low/mid/high)


def battle_folders(folder):
    folders = {}
    battles = os.path.join(folder, "battles")
    if os.path.isdir(battles):
        for name in os.listdir(battles):
            match = re.match(r"(\d+)-", name)
            if match:
                folders[int(match.group(1))] = os.path.join(battles, name)
    return folders


def load(folder, build):
    """Every finished battle of the build in a matrix folder, with its logs read where they were kept."""
    results = os.path.join(folder, "results.csv")
    if not os.path.exists(results):
        sys.exit(f"No results.csv in {folder}.")
    folders = battle_folders(folder)
    battles = []
    with open(results, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["winner"] not in ("a", "b", "draw") or build not in (row["a"], row["b"]):
                continue
            battle = Battle()
            side = "a" if row["a"] == build else "b"
            battle.number = int(row["battle"])
            battle.opponent = row["b"] if side == "a" else row["a"]
            battle.points = 1.0 if row["winner"] == side else 0.5 if row["winner"] == "draw" else 0.0
            battle.reason = row["reason"]
            battle.duration = int(row["duration_ms"]) / 1000.0
            battle.hp_left = float(row["a_hp"] if side == "a" else row["b_hp"])
            battle.enemy_hp_left = float(row["b_hp"] if side == "a" else row["a_hp"])
            battle.group = group_of(battle.opponent)
            battle.folder = folders.get(battle.number)
            if battle.folder:
                read_battle(battle, build)
            battles.append(battle)
    return battles


def read_battle(battle, build):
    run = next(read_lines(os.path.join(battle.folder, "run.json")), None)
    if not run:
        return
    me = enemy = None
    for member in run.get("members", []):
        if member.get("build") == build and me is None:
            me = member
        else:
            enemy = member
    if not me or not enemy:
        return
    battle.logged = True
    battle.group = group_of(battle.opponent, enemy.get("role"), enemy.get("buildRole"))
    battle.kit = {category: [skill_name(s) for s in skills] for category, skills in me.get("skills", {}).items()}
    battle.stats = {key: me.get(key) for key in ("maxHp", "maxMp", "pAtk", "mAtk", "pDef", "mDef", "runSpeed", "attackRange")}
    battle.stats["enemyMaxHp"] = enemy.get("maxHp")
    for keystone in (me.get("passives") or {}).get("keystones") or []:
        battle.traits.append(("keystone", keystone))
    gear = me.get("gear") or {}
    battle.traits.append(("chest", gear.get("chest")))
    battle.traits.append(("weapon", gear.get("weapon")))
    match = re.search(r"skill ([-+]?[0-9.]+)", me.get("personality") or "")
    if match:
        skill = float(match.group(1))
        battle.traits.append(("player skill", "low (under -1/3)" if skill < -1 / 3 else "high (over 1/3)" if skill > 1 / 3 else "middle"))
    my_id, enemy_id = me["id"], enemy["id"]
    max_mp = max(1, me.get("maxMp") or 1)

    fight_start = None
    end = None
    for event in read_lines(os.path.join(battle.folder, "events.jsonl")):
        kind = event.get("type")
        t = event.get("t", 0)
        if kind == "fight_start":
            fight_start = t
        elif kind == "round_end":
            end = t
        elif kind == "cast_start":
            name = skill_name(event.get("skill"))
            if event.get("casterId") == my_id:
                battle.casts[name] += 1
                battle.cast_time[name] += event.get("castTime") or 0
                if event.get("targetId") == enemy_id:
                    if event.get("gap") is not None:
                        battle.cast_gap[name].append(event["gap"])
                    if event.get("targetInvul"):
                        battle.into_invul += 1
                    if fight_start is not None and len(battle.opener) < 4:
                        battle.opener.append(name)
            elif event.get("casterId") == enemy_id:
                battle.enemy_casts[name] += 1
        elif kind == "cast_abort":
            if event.get("casterId") == my_id:
                battle.aborts[skill_name(event.get("skill"))] += 1
        elif kind == "damage":
            name = skill_name(event.get("skill"))
            amount = event.get("amount", 0)
            if event.get("attackerTeam") == me["team"] and event.get("targetId") == enemy_id:
                # Its own hits and its servitor's.
                if event.get("attackerId") != my_id:
                    name = "servitor: " + name
                battle.damage[name] += amount
                battle.hits[name] += 1
                battle.dealt += amount
                if battle.first_hit is None:
                    battle.first_hit = "focus"
            elif event.get("targetId") == my_id:
                if event.get("attackerId") != enemy_id:
                    name = "servitor: " + name
                battle.enemy_damage[name] += amount
                battle.taken += amount
                if battle.first_hit is None:
                    battle.first_hit = "enemy"
        elif kind == "debuff_landed":
            name = skill_name(event.get("skill"))
            if event.get("casterId") == my_id and event.get("targetId") == enemy_id:
                battle.landed[name] += 1
            elif event.get("targetId") == my_id and event.get("casterId") != my_id:
                battle.enemy_landed[name] += 1
                battle.enemy_landed_seconds[name] += event.get("seconds") or 0
        elif kind == "resisted":
            if event.get("casterId") == my_id:
                battle.resisted[skill_name(event.get("skill"))] += 1
        elif kind == "heal":
            if event.get("targetId") == enemy_id:
                battle.enemy_healed += event.get("hp", 0)
            elif event.get("targetId") == my_id:
                battle.healed += event.get("hp", 0)
        elif kind == "death":
            if event.get("victimId") == my_id:
                battle.killing_blow = event.get("killingBlow")
            elif event.get("victimId") == enemy_id:
                battle.kill_blow = event.get("killingBlow")

    if fight_start is None:
        fight_start = 0
    for sample in read_lines(os.path.join(battle.folder, "positions.jsonl")):
        if sample.get("t", 0) < fight_start or not sample.get("alive"):
            continue
        if sample.get("id") == my_id:
            battle.samples += 1
            if sample.get("disabled"):
                state = "disabled"
            elif sample.get("casting"):
                state = "casting"
            elif sample.get("attacking"):
                state = "attacking"
            elif sample.get("moving"):
                state = "moving"
            elif sample.get("decision") in WAITING:
                state = "waiting"
            else:
                state = "between"
            battle.state[state] += 1
            if sample.get("rooted"):
                battle.rooted += 1
            gap = sample.get("gap")
            if gap is not None:
                battle.gaps.append(gap)
                if gap < MELEE_GAP:
                    battle.melee_samples += 1
            mp = (sample.get("mp") or 0) / max_mp
            battle.min_mp = min(battle.min_mp, mp)
            battle.end_mp = mp
        elif sample.get("id") == enemy_id:
            battle.enemy_samples += 1
            if sample.get("disabled"):
                battle.enemy_disabled += 1
            if sample.get("rooted"):
                battle.enemy_rooted += 1
    battle.alive = battle.samples * POSITION_STEP

    summary = next(read_lines(os.path.join(battle.folder, "summary.json")), None)
    if summary:
        for team in summary.get("teams", []):
            for member in team.get("members", []):
                if member.get("id") == my_id:
                    for reason, ms in (member.get("decisionTime") or {}).items():
                        if reason != "SIM_PREP":
                            battle.reasons[reason] += ms
                    for anomaly, count in (member.get("anomalies") or {}).items():
                        battle.anomalies[anomaly] += count


# ---------------------------------------------------------------------------------------------------------------------
# Aggregates
# ---------------------------------------------------------------------------------------------------------------------

class Stats:
    """The numbers of a set of battles (a kind of opponent, one opponent, or all)."""

    def __init__(self, battles):
        self.battles = battles
        self.n = len(battles)
        self.points = sum(b.points for b in battles)
        self.rate = self.points / self.n if self.n else 0.0
        self.low, self.high = wilson(self.points, self.n)
        self.wins = [b for b in battles if b.points == 1.0]
        self.losses = [b for b in battles if b.points == 0.0]
        self.draws = [b for b in battles if b.points == 0.5]
        logged = [b for b in battles if b.logged]
        self.logged = logged
        alive = sum(b.alive for b in logged)
        samples = sum(b.samples for b in logged)
        self.dps = sum(b.dealt for b in logged) / alive if alive else 0.0
        self.dps_taken = sum(b.taken for b in logged) / alive if alive else 0.0
        self.melee = sum(b.melee_samples for b in logged) / samples if samples else 0.0
        self.state = Counter()
        for b in logged:
            self.state.update(b.state)
        self.state_share = {key: self.state[key] / samples if samples else 0.0 for key in STATES}
        self.rooted = sum(b.rooted for b in logged) / samples if samples else 0.0
        enemy_samples = sum(b.enemy_samples for b in logged)
        self.enemy_disabled = sum(b.enemy_disabled for b in logged) / enemy_samples if enemy_samples else 0.0
        self.enemy_rooted = sum(b.enemy_rooted for b in logged) / enemy_samples if enemy_samples else 0.0
        self.gap = median([g for b in logged for g in b.gaps])
        self.first_hit = sum(1 for b in logged if b.first_hit == "focus") / len(logged) if logged else 0.0
        self.enemy_heal_share = sum(b.enemy_healed for b in logged) / max(1, sum(b.dealt for b in logged))
        self.out_of_mp = sum(1 for b in logged if b.min_mp < 0.05) / len(logged) if logged else 0.0

    def per_battle(self, field, skill=None):
        if not self.logged:
            return 0.0
        if skill is None:
            return sum(getattr(b, field) for b in self.logged) / len(self.logged)
        return sum(getattr(b, field)[skill] for b in self.logged) / len(self.logged)

    def total(self, field):
        counter = Counter()
        for b in self.logged:
            counter.update(getattr(b, field))
        return counter


def by_group(battles):
    groups = defaultdict(list)
    for battle in battles:
        groups[battle.group].append(battle)
    return [(group, groups[group]) for group in GROUP_ORDER if group in groups] + [(g, v) for g, v in groups.items() if g not in GROUP_ORDER]


def pct(value):
    return f"{100 * value:.0f}%"


def rate_cell(stats):
    return f"{100 * stats.rate:.1f}% ({100 * stats.low:.0f}-{100 * stats.high:.0f})"


def table(header, rows):
    lines = ["| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
    lines += ["| " + " | ".join(str(cell) for cell in row) + " |" for row in rows]
    return "\n".join(lines)


# ---------------------------------------------------------------------------------------------------------------------
# The report
# ---------------------------------------------------------------------------------------------------------------------

def category_of(kit, skill):
    for category in ("ATTACK", "DEBUFF", "PEEL", "BUFF", "HEAL", "EMERGENCY", "CLEANSE", "TRANSFORM", "RUSH", "MOVE", "CHARGE", "SUMMON", "TOGGLE"):
        if skill in kit.get(category, []):
            return category
    return "-"


def skill_rows(stats, kit, limit=30):
    casts = stats.total("casts")
    aborts = stats.total("aborts")
    damage = stats.total("damage")
    hits = stats.total("hits")
    landed = stats.total("landed")
    resisted = stats.total("resisted")
    cast_time = stats.total("cast_time")
    total_damage = max(1, sum(damage.values()))
    names = sorted(set(casts) | set(damage), key=lambda s: -(casts[s] + hits[s]))
    rows = []
    n = max(1, len(stats.logged))
    for name in names[:limit]:
        gaps = [g for b in stats.logged for g in b.cast_gap.get(name, [])]
        tried = landed[name] + resisted[name]
        seconds = cast_time[name] / 1000.0
        rows.append([
            name,
            category_of(kit, name),
            f"{casts[name] / n:.2f}",
            pct(aborts[name] / casts[name]) if casts[name] else "-",
            f"{pct(landed[name] / tried)} of {tried}" if tried and category_of(kit, name) != "ATTACK" else (f"{pct(landed[name] / tried)} of {tried}" if tried else "-"),
            f"{damage[name] / casts[name]:.0f}" if casts[name] and damage[name] else "-",
            f"{damage[name] / seconds:.0f}" if seconds and damage[name] else "-",
            pct(damage[name] / total_damage) if damage[name] else "-",
            f"{median(gaps):.0f}" if gaps else "-",
        ])
    return rows


def unused_kit(stats, kit):
    casts = stats.total("casts")
    n = max(1, len(stats.logged))
    unused = []
    for category, skills in kit.items():
        if category == "TOGGLE":
            continue
        for skill in skills:
            if casts[skill] / n < 0.05:
                unused.append(f"{skill} ({category.lower()}, {casts[skill]} casts)")
    return unused


def findings(build, battles, kit, stats_all):
    """Things worth a look, each with its evidence. They are questions to check in the logs, not conclusions."""
    found = []
    ranged = stats_all.gap > 250 or any(c in kit for c in ("PEEL",))
    for group, group_battles in by_group(battles):
        stats = Stats(group_battles)
        if not stats.logged:
            continue
        label = f"vs {group}"
        if stats.high < 0.5:
            found.append((1, f"**Loses {label}**: {rate_cell(stats)} over {stats.n} battles. Losses last a median {median([b.duration for b in stats.losses]):.0f} s; the enemy has a median {pct(median([b.enemy_hp_left for b in stats.losses]))} HP+CP left."))
        if stats.losses and median([b.enemy_hp_left for b in stats.losses]) < 0.25 and len(stats.losses) >= 5:
            found.append((2, f"**Close losses {label}**: the enemy ends with a median {pct(median([b.enemy_hp_left for b in stats.losses]))} HP+CP in {len(stats.losses)} losses. A small gain in damage or survival flips these."))
        if ranged and stats.melee > 0.35 and group in ("tank", "melee", "dagger"):
            found.append((2, f"**Can't keep its distance {label}**: an enemy is within {MELEE_GAP} of it {pct(stats.melee)} of the fight (median gap {stats.gap:.0f}). Its roots and slows: enemy rooted {pct(stats.enemy_rooted)} of the fight, disabled {pct(stats.enemy_disabled)}."))
        if stats.state_share["disabled"] > 0.12:
            top = ", ".join(f"{skill} {count / len(stats.logged):.1f}x" for skill, count in stats.total("enemy_landed").most_common(3))
            found.append((2, f"**Disabled {pct(stats.state_share['disabled'])} of the fight {label}** (stunned, paralyzed, asleep...). Landed on it per battle: {top}. Does it have a cleanse, a resist buff or a way to stop the cast?"))
        if stats.state_share["waiting"] > 0.05:
            reasons = stats.total("reasons")
            total = max(1, sum(reasons.values()))
            top = ", ".join(f"{reason} {pct(ms / total)}" for reason, ms in reasons.most_common(5))
            found.append((2, f"**Stands waiting {pct(stats.state_share['waiting'])} of the fight {label}** (no cast, attack or move, on a hold or wait decision). Decision time: {top}."))
        if stats.enemy_heal_share > 0.25:
            found.append((2, f"**The enemy heals back {pct(stats.enemy_heal_share)} of its damage {label}**. Silence or stun the heal, or save the burst for after it."))
        if stats.out_of_mp > 0.10:
            found.append((2, f"**Out of MP in {pct(stats.out_of_mp)} of battles {label}** (under 5% at some point)."))
        if stats.draws and len(stats.draws) / stats.n > 0.15:
            found.append((3, f"**{pct(len(stats.draws) / stats.n)} draws {label}**: it can't finish the fight in time."))
        casts = stats.total("casts")
        aborts = stats.total("aborts")
        landed = stats.total("landed")
        resisted = stats.total("resisted")
        for skill, count in casts.items():
            per_battle = count / len(stats.logged)
            if per_battle >= 0.3 and aborts[skill] / count >= 0.2:
                found.append((3, f"**{skill} is aborted {pct(aborts[skill] / count)} of the time {label}** ({per_battle:.1f} casts a battle): started where it can't finish."))
            tried = landed[skill] + resisted[skill]
            if tried >= 10 and category_of(kit, skill) in ("DEBUFF", "PEEL") and landed[skill] / tried < 0.3 and tried / len(stats.logged) >= 0.5:
                found.append((3, f"**{skill} lands {pct(landed[skill] / tried)} {label}** ({tried / len(stats.logged):.1f} tries a battle): time spent on a resist. Is there a better moment or a better skill against this kind?"))
    if stats_all.logged:
        unused = unused_kit(stats_all, kit)
        if unused:
            found.append((3, "**Skills of its kit it (almost) never casts**: " + ", ".join(unused) + ". Each is either a missed tool or belongs out of the build."))
        invul = stats_all.per_battle("into_invul")
        if invul > 0.3:
            found.append((3, f"**Casts into an invincible target {invul:.1f} times a battle.**"))
    found.sort(key=lambda item: item[0])
    return [text for _, text in found]


def report(folder, build, only_group=None):
    battles = load(folder, build)
    if not battles:
        sys.exit(f"No battle of {build} in {folder}.")
    if only_group:
        battles = [b for b in battles if b.group == only_group]
        if not battles:
            sys.exit(f"No battle of {build} against a {only_group} in {folder}.")
    settings = read_settings(folder)
    stats_all = Stats(battles)
    kit = next((b.kit for b in battles if b.kit), {})
    logged = len(stats_all.logged)
    out = []
    out.append(f"# Class clinic: {build}" + (f" against {only_group}s" if only_group else ""))
    out.append("")
    out.append(f"Matrix `{os.path.basename(os.path.normpath(folder))}`: level {settings.get('level', '?')}, brain {settings.get('brain', '?')}, seed {settings.get('seed', '?')}, {stats_all.n} battles ({logged} with logs), {settings.get('timeLimit', '?')} s limit, {settings.get('distance', '?')} apart.")
    if logged < stats_all.n:
        out.append(f"\n**Only {logged} of {stats_all.n} battles kept their logs**: run with `class_clinic.py run` (it keeps them all), or the numbers below the scorecard cover the newest battles only.")
    sample = next((b for b in stats_all.logged), None)
    if sample:
        s = sample.stats
        out.append(f"\nA {build} of this run: max HP {s.get('maxHp')}, max MP {s.get('maxMp')}, P. Atk {s.get('pAtk')}, M. Atk {s.get('mAtk')}, P. Def {s.get('pDef')}, M. Def {s.get('mDef')}, speed {s.get('runSpeed')}.")

    out.append("\n## 1. Scorecard\n")
    header = ["Against", "Battles", "Win % (95%)", "W-L-D", "Fight s (won / lost)", "HP left when it wins", "Enemy HP left when it loses", "Damage /s dealt / taken", f"Enemy within {MELEE_GAP}", "Disabled", "Waiting", "Hits first"]

    def score_row(label, stats):
        return [label, stats.n, rate_cell(stats), f"{len(stats.wins)}-{len(stats.losses)}-{len(stats.draws)}",
                f"{median([b.duration for b in stats.wins]):.0f} / {median([b.duration for b in stats.losses]):.0f}",
                pct(median([b.hp_left for b in stats.wins])) if stats.wins else "-",
                pct(median([b.enemy_hp_left for b in stats.losses])) if stats.losses else "-",
                f"{stats.dps:.0f} / {stats.dps_taken:.0f}", pct(stats.melee), pct(stats.state_share["disabled"]), pct(stats.state_share["waiting"]), pct(stats.first_hit)]

    rows = [score_row(f"**{group}**", Stats(group_battles)) for group, group_battles in by_group(battles)]
    rows.append(score_row("**all**", stats_all))
    out.append(table(header, rows))

    out.append("\n### By opponent, worst first\n")
    opponents = defaultdict(list)
    for battle in battles:
        opponents[battle.opponent].append(battle)
    rows = []
    for opponent, opponent_battles in sorted(opponents.items(), key=lambda item: Stats(item[1]).rate):
        stats = Stats(opponent_battles)
        ends = Counter(b.reason for b in opponent_battles)
        blows = Counter(b.killing_blow for b in stats.losses if b.killing_blow)
        rows.append([opponent, opponent_battles[0].group, rate_cell(stats), f"{len(stats.wins)}-{len(stats.losses)}-{len(stats.draws)}",
                     f"{stats.dps:.0f} / {stats.dps_taken:.0f}", pct(stats.melee), pct(stats.state_share["disabled"]),
                     ", ".join(f"{reason} {count}" for reason, count in ends.most_common(3)),
                     ", ".join(f"{blow} {count}" for blow, count in blows.most_common(2)) or "-"])
    out.append(table(["Opponent", "Kind", "Win % (95%)", "W-L-D", "Damage /s dealt / taken", f"Enemy within {MELEE_GAP}", "Disabled", "Ends", "Killed by"], rows))

    if stats_all.logged:
        out.append("\n## 2. Findings to check\n")
        out.append("Flagged from the numbers. Each one is a question to answer in the battle logs (section 7), not a conclusion.\n")
        for text in findings(build, battles, kit, stats_all) or ["Nothing flagged."]:
            out.append(f"- {text}")

        out.append("\n## 3. Its skills\n")
        out.append("Casts are per battle. \"Landed\" counts the debuff part of a skill (landed against resisted). \"Per cast second\" is damage divided by the time spent casting it: the skills that pay.\n")
        out.append(table(["Skill", "List", "Casts", "Aborted", "Landed", "Damage per cast", "Per cast second", "Share of damage", "Median gap at cast"], skill_rows(stats_all, kit)))
        unused = unused_kit(stats_all, kit)
        if unused:
            out.append("\nNever or almost never cast: " + ", ".join(unused) + ".")

        out.append("\n### Casts per battle by kind of opponent\n")
        groups = by_group(battles)
        top = [name for name, _ in stats_all.total("casts").most_common(18)]
        rows = []
        for name in top:
            rows.append([name] + [f"{Stats(group_battles).per_battle('casts', name):.1f}" for _, group_battles in groups])
        out.append(table(["Skill"] + [group for group, _ in groups], rows))

        out.append("\n### How it opens (first casts on the enemy)\n")
        rows = []
        for group, group_battles in groups:
            openers = Counter(" > ".join(b.opener[:3]) for b in group_battles if b.logged and b.opener)
            total = max(1, sum(openers.values()))
            rows.append([group, "; ".join(f"{opener} ({pct(count / total)})" for opener, count in openers.most_common(3))])
        out.append(table(["Against", "Most common openings"], rows))

        out.append("\n## 4. What the enemy does to it\n")
        rows = []
        for group, group_battles in groups:
            stats = Stats(group_battles)
            n = max(1, len(stats.logged))
            damage = stats.total("enemy_damage")
            total = max(1, sum(damage.values()))
            landed = stats.total("enemy_landed")
            seconds = stats.total("enemy_landed_seconds")
            rows.append([group,
                         ", ".join(f"{skill} {pct(amount / total)}" for skill, amount in damage.most_common(4)),
                         ", ".join(f"{skill} {count / n:.1f}x ({seconds[skill] / n:.0f} s)" for skill, count in landed.most_common(4)) or "-",
                         f"{pct(stats.enemy_heal_share)}",
                         ", ".join(f"{blow} {count}" for blow, count in Counter(b.killing_blow for b in stats.losses if b.killing_blow).most_common(3)) or "-"])
        out.append(table(["Against", "Damage it takes, by enemy skill", "Debuffs landed on it per battle (seconds)", "Enemy heals back", "Killing blows"], rows))

        out.append("\n## 5. Where its time goes\n")
        rows = []
        for group, group_battles in groups + [("all", battles)]:
            stats = Stats(group_battles)
            reasons = stats.total("reasons")
            total = max(1, sum(reasons.values()))
            rows.append([group] + [pct(stats.state_share[key]) for key in STATES]
                        + [pct(stats.rooted), f"{pct(stats.enemy_disabled)} / {pct(stats.enemy_rooted)}", f"{stats.gap:.0f}",
                           ", ".join(f"{reason} {pct(ms / total)}" for reason, ms in reasons.most_common(6))])
        out.append("\"Waiting\" is standing on a hold or wait decision; \"between\" is the short pause between two actions that every build has.\n")
        out.append(table(["Against", "Casting", "Attacking", "Moving", "Disabled", "Waiting", "Between", "Rooted", "Enemy disabled / rooted", "Median gap", "Decisions by time"], rows))
        anomalies = stats_all.total("anomalies")
        if anomalies:
            out.append("\nAnomalies the monitor flagged for it: " + ", ".join(f"{name} {count}" for name, count in anomalies.most_common()) + ".")

        out.append("\n## 6. Its own build\n")
        out.append("The same class with other keystones, gear or player skill. A big gap here is a build problem (passive tree, gear), not an AI one: note it, don't tune the AI around it.\n")
        traits = defaultdict(list)
        for battle in stats_all.logged:
            for trait in battle.traits:
                traits[trait].append(battle)
        rows = []
        for (kind, name), trait_battles in sorted(traits.items(), key=lambda item: (item[0][0], -len(item[1]))):
            if len(trait_battles) >= 20:
                rows.append([kind, name, len(trait_battles), rate_cell(Stats(trait_battles))])
        out.append(table(["", "Has", "Battles", "Win % (95%)"], rows))

        out.append("\n## 7. Battles to read\n")
        out.append("`class_clinic.py timeline <folder>` prints a fight second by second. A typical loss and a typical win per kind (the ones of median length):\n")
        rows = []
        for group, group_battles in groups:
            stats = Stats(group_battles)

            def typical(candidates):
                candidates = sorted((b for b in candidates if b.logged), key=lambda b: b.duration)
                return os.path.relpath(candidates[len(candidates) // 2].folder, folder) if candidates else "-"

            rows.append([group, typical(stats.losses), typical(stats.wins)])
        out.append(table(["Against", "A typical loss", "A typical win"], rows))
    return "\n".join(out) + "\n", battles


# ---------------------------------------------------------------------------------------------------------------------
# Comparing two runs
# ---------------------------------------------------------------------------------------------------------------------

def sign(low, high):
    return "**better**" if low > 0 else "**worse**" if high < 0 else "no clear change"


def compare(base_folder, changed_folder, build):
    base = load(base_folder, build)
    changed = load(changed_folder, build)
    out = [f"# Class clinic: {build}, before and after", ""]
    out.append(f"Before: `{os.path.basename(os.path.normpath(base_folder))}` ({len(base)} battles). After: `{os.path.basename(os.path.normpath(changed_folder))}` ({len(changed)} battles).")
    base_settings, changed_settings = read_settings(base_folder), read_settings(changed_folder)
    for key in ("seed", "level", "battles", "timeLimit", "distance", "brain"):
        if base_settings.get(key) != changed_settings.get(key):
            out.append(f"\n**The runs differ in {key}** ({base_settings.get(key)} against {changed_settings.get(key)}): compare runs with the same settings and seed.")
    out.append("\n## Win rate by kind of opponent\n")
    rows = []
    verdicts = []
    groups_base = dict(by_group(base))
    groups_changed = dict(by_group(changed))
    for group in [g for g in GROUP_ORDER if g in groups_base or g in groups_changed] + ["all"]:
        s1 = Stats(base if group == "all" else groups_base.get(group, []))
        s2 = Stats(changed if group == "all" else groups_changed.get(group, []))
        d, low, high = delta_interval(s1.rate, s1.n, s2.rate, s2.n)
        verdicts.append((group, low, high))
        rows.append([f"**{group}**", f"{100 * s1.rate:.1f}% ({s1.n})", f"{100 * s2.rate:.1f}% ({s2.n})", f"{100 * d:+.1f} ({100 * low:+.1f} to {100 * high:+.1f})", sign(low, high),
                     f"{s1.dps:.0f} -> {s2.dps:.0f}", f"{s1.dps_taken:.0f} -> {s2.dps_taken:.0f}", f"{pct(s1.melee)} -> {pct(s2.melee)}",
                     f"{pct(s1.state_share['disabled'])} -> {pct(s2.state_share['disabled'])}", f"{pct(s1.state_share['waiting'])} -> {pct(s2.state_share['waiting'])}"])
    out.append(table(["Against", "Before", "After", "Difference (95%)", "Verdict", "Damage /s dealt", "Damage /s taken", f"Enemy within {MELEE_GAP}", "Disabled", "Waiting"], rows))

    out.append("\n## By opponent, biggest change first\n")
    opponents = sorted({b.opponent for b in base} | {b.opponent for b in changed})
    rows = []
    for opponent in opponents:
        s1 = Stats([b for b in base if b.opponent == opponent])
        s2 = Stats([b for b in changed if b.opponent == opponent])
        if not s1.n or not s2.n:
            continue
        d, low, high = delta_interval(s1.rate, s1.n, s2.rate, s2.n)
        rows.append((abs(d), [opponent, group_of(opponent), f"{100 * s1.rate:.0f}% ({s1.n})", f"{100 * s2.rate:.0f}% ({s2.n})", f"{100 * d:+.0f} ({100 * low:+.0f} to {100 * high:+.0f})", sign(low, high) if (low > 0 or high < 0) else ""]))
    rows.sort(key=lambda row: -row[0])
    out.append(table(["Opponent", "Kind", "Before", "After", "Difference (95%)", ""], [row for _, row in rows]))

    s1, s2 = Stats(base), Stats(changed)
    if s1.logged and s2.logged:
        out.append("\n## Skill use, casts per battle\n")
        casts1, casts2 = s1.total("casts"), s2.total("casts")
        damage1, damage2 = s1.total("damage"), s2.total("damage")
        aborts1, aborts2 = s1.total("aborts"), s2.total("aborts")
        n1, n2 = len(s1.logged), len(s2.logged)
        rows = []
        for name in sorted(set(casts1) | set(casts2), key=lambda s: -abs(casts2[s] / n2 - casts1[s] / n1))[:20]:
            rows.append([name, f"{casts1[name] / n1:.2f}", f"{casts2[name] / n2:.2f}", f"{casts2[name] / n2 - casts1[name] / n1:+.2f}",
                         f"{pct(aborts1[name] / casts1[name]) if casts1[name] else '-'} -> {pct(aborts2[name] / casts2[name]) if casts2[name] else '-'}",
                         f"{damage1[name] / n1:.0f} -> {damage2[name] / n2:.0f}"])
        out.append(table(["Skill", "Before", "After", "Change", "Aborted", "Damage per battle"], rows))

    out.append("\n## Verdict\n")
    overall = verdicts[-1]
    better = [group for group, low, high in verdicts[:-1] if low > 0]
    worse = [group for group, low, high in verdicts[:-1] if high < 0]
    out.append(f"- Overall: {sign(overall[1], overall[2])}.")
    out.append(f"- Clearly better against: {', '.join(better) or 'none'}.")
    out.append(f"- Clearly worse against: {', '.join(worse) or 'none'}.")
    out.append("- \"Clearly\" means the 95% interval of the difference doesn't include zero. A kind with fewer than about 200 battles a side can't show a change under about 10 points: run more battles before calling it.")
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------------------------------------------------------------
# One fight, second by second
# ---------------------------------------------------------------------------------------------------------------------

def timeline(folder, limit):
    run = next(read_lines(os.path.join(folder, "run.json")), None)
    if not run:
        sys.exit(f"No run.json in {folder}.")
    names = {m["id"]: f"{m['build']}" for m in run.get("members", [])}
    lines = []
    for m in run.get("members", []):
        lines.append(f"{m['build']} ({m['name']}, brain {m.get('brain', 'A')}): HP {m.get('maxHp')}, MP {m.get('maxMp')}, P.Atk {m.get('pAtk')}, M.Atk {m.get('mAtk')}, P.Def {m.get('pDef')}, M.Def {m.get('mDef')}, speed {m.get('runSpeed')}; {m.get('personality')}")
    start = 0
    rows = []
    for event in read_lines(os.path.join(folder, "events.jsonl")):
        kind = event.get("type")
        t = event.get("t", 0)
        if kind == "fight_start":
            start = t
            rows.append((t, 0, "---- fight starts ----"))
        elif kind == "cast_start":
            who = names.get(event.get("casterId"), event.get("caster"))
            on = "" if event.get("targetId") == event.get("casterId") else f" on {names.get(event.get('targetId'), event.get('target'))}"
            gap = f", gap {event['gap']}" if event.get("gap") is not None else ""
            rows.append((t, 1, f"{who} casts {skill_name(event.get('skill'))}{on} ({event.get('castTime')} ms{gap})" + (" INTO INVUL" if event.get("targetInvul") else "")))
        elif kind == "cast_abort":
            rows.append((t, 1, f"{names.get(event.get('casterId'), event.get('caster'))}: {skill_name(event.get('skill'))} ABORTED"))
        elif kind == "damage":
            who = names.get(event.get("attackerId"), event.get("attacker"))
            rows.append((t, 2, f"{who} hits {names.get(event.get('targetId'), event.get('target'))} for {event.get('amount')} with {skill_name(event.get('skill'))}" + (" (crit)" if event.get("crit") else "") + (" (dot)" if event.get("dot") else "") + f" -> {100 * event.get('targetHp', 0):.0f}% HP"))
        elif kind == "debuff_landed":
            rows.append((t, 2, f"{skill_name(event.get('skill'))} LANDS on {names.get(event.get('targetId'), event.get('target'))} ({event.get('seconds')} s)"))
        elif kind == "resisted":
            rows.append((t, 2, f"{names.get(event.get('targetId'), event.get('target'))} resists {skill_name(event.get('skill'))}"))
        elif kind == "heal":
            rows.append((t, 2, f"{names.get(event.get('healerId'), event.get('healer'))} heals {names.get(event.get('targetId'), event.get('target'))} for {event.get('hp')} with {skill_name(event.get('skill'))} -> {100 * event.get('targetHp', 0):.0f}% HP"))
        elif kind == "death":
            rows.append((t, 3, f"**** {names.get(event.get('victimId'), event.get('victim'))} dies ({event.get('killingBlow')}) ****"))
        elif kind in ("weapon_swap", "flee_start", "standstill_push", "rout", "round_end"):
            rows.append((t, 3, f"[{kind}] " + ", ".join(f"{k}={v}" for k, v in event.items() if k not in ("t", "type", "id"))))
    last = {}
    scored = {}
    for decision in read_lines(os.path.join(folder, "decisions.jsonl")):
        reason = decision.get("reason")
        if reason in ("CASTING", "FIGHT_READ", "SIM_PREP", "CAST", "SCORED_SKILL", "KITE_MOVING"):
            if reason == "SCORED_SKILL":
                picked = (decision.get("kind") or "").split(";")[0]
                if scored.get(decision.get("id")) != picked:
                    scored[decision.get("id")] = picked
                    rows.append((decision.get("t", 0), 0, f"  ({names.get(decision.get('id'))} scores {decision.get('kind')})"))
            continue
        key = (reason, decision.get("kind"))
        if last.get(decision.get("id")) == key:
            continue
        last[decision.get("id")] = key
        gap = f", gap {decision.get('gap')}" if decision.get("gap") is not None else ""
        hp = f", HP {100 * decision.get('hp'):.0f}%" if decision.get("hp") is not None else ""
        rows.append((decision.get("t", 0), 0, f"  ({names.get(decision.get('id'))} decides {reason}" + (f" {decision.get('kind')}" if decision.get("kind") else "") + (f" {skill_name(decision.get('skill'))}" if decision.get("skill") else "") + f"{gap}{hp})"))
    rows.sort(key=lambda row: (row[0], row[1]))
    for t, _, text in rows[:limit] if limit else rows:
        if t >= start - 500 or start == 0:
            lines.append(f"{(t - start) / 1000.0:7.1f}s  {text}")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------------------------------------------------
# Running a focused matrix through the web API
# ---------------------------------------------------------------------------------------------------------------------

class Api:
    def __init__(self, url, token):
        self.url = url.rstrip("/")
        self.token = token

    def call(self, path, params=None, post=False):
        params = dict(params or {})
        if self.token:
            params["admin"] = self.token
        data = urllib.parse.urlencode(params)
        url = f"{self.url}/api/simulation/matrix/{path}"
        try:
            if post:
                request = urllib.request.Request(url, data=data.encode("utf-8"), method="POST")
            else:
                request = urllib.request.Request(url + ("?" + data if data else ""))
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.URLError as e:
            sys.exit(f"Can't reach the game server at {self.url} ({e}). Start it with tools/simulation/local_server.sh.")


def resolve_against(text, build):
    """--against tank,Titan -> the build names."""
    builds = []
    for name in text.split(","):
        name = name.strip()
        if not name:
            continue
        for found in GROUPS.get(name.lower(), [name]):
            if found != build and found not in builds:
                builds.append(found)
    return builds


def run(args):
    api = Api(args.url, args.token)
    status = api.call("status")
    if status.get("matrix"):
        sys.exit(f"A matrix is already running ({status['matrix']['name']}): wait for it, or stop it with POST /api/simulation/matrix/stop.")
    params = {"focus": args.build, "battles": args.battles, "parallel": args.parallel, "level": args.level, "time": args.time, "distance": args.distance, "brain": args.brain, "keep": 100000}
    if args.seed:
        params["seed"] = args.seed
    if args.against:
        params["builds"] = ",".join([args.build] + resolve_against(args.against, args.build))
    answer = api.call("start", params, post=True)
    print("\n".join(answer.get("lines", [])), flush=True)
    matrix = answer.get("matrix")
    if not matrix:
        sys.exit(1)
    folder = matrix["dir"]
    last = None
    while True:
        time.sleep(15)
        status = api.call("status")
        current = status.get("matrix")
        if not current or current["name"] != matrix["name"]:
            break
        if current["done"] != last:
            last = current["done"]
            print(f"  {current['done']}/{current['total']} battles", flush=True)
    text, _ = report(folder, args.build)
    path = os.path.join(folder, "clinic.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print(text)
    print(f"Folder: {folder}\nReport: {path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    p = commands.add_parser("run", help="fight a build against every other build, keep every log, write the report")
    p.add_argument("build")
    p.add_argument("--battles", type=int, default=30, help="battles per opponent (30: about 1,170 battles, 10 minutes)")
    p.add_argument("--against", help="only these opponents: kinds (tank, melee, dagger, archer, nuker, healer, buffer) and build names, separated by commas")
    p.add_argument("--parallel", type=int, default=300, help="battles at once")
    p.add_argument("--level", type=int, default=85)
    p.add_argument("--time", type=int, default=300, help="time limit of a battle, in seconds")
    p.add_argument("--distance", type=int, default=1000, help="how far apart the two spawn")
    p.add_argument("--brain", default="A")
    p.add_argument("--seed", help="the same seed for the run before and after a change")
    p.add_argument("--url", default="http://127.0.0.1:8788")
    p.add_argument("--token", help="the admin=... token of a //SimulationWeb link (not needed with SimulationWebOpenAccess)")

    p = commands.add_parser("report", help="the report of a run")
    p.add_argument("folder")
    p.add_argument("--build", help="the build (default: the matrix's focus build)")
    p.add_argument("--group", choices=GROUP_ORDER, help="only the battles against this kind of opponent")
    p.add_argument("--out", help="also write the report to this file")

    p = commands.add_parser("compare", help="a run before a change against a run after it")
    p.add_argument("baseline")
    p.add_argument("changed")
    p.add_argument("--build")
    p.add_argument("--out")

    p = commands.add_parser("timeline", help="one fight, second by second")
    p.add_argument("folder", help="a battle folder (<matrix folder>/battles/...)")
    p.add_argument("--limit", type=int, default=0, help="only the first lines")

    args = parser.parse_args()
    if args.command == "run":
        run(args)
        return
    if args.command == "report":
        text, _ = report(args.folder, focus_build(args.folder, args.build), args.group)
    elif args.command == "compare":
        text = compare(args.baseline, args.changed, focus_build(args.baseline, args.build))
    else:
        text = timeline(args.folder, args.limit)
    print(text)
    if getattr(args, "out", None):
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)


if __name__ == "__main__":
    main()
