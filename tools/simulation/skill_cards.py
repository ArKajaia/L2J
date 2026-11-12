"""
Skill cards: the skill kit of one fake player build, one short card per skill, read straight from the data, so whoever
tunes a class's AI sees at a glance what each skill really does at a given level. A tool of the class clinic
(tools/simulation/class_clinic.py, docs/class-clinic.md).

    # The kit of a build at level 85, grouped like its <build> in dist/game/data/FakePlayerPvp.xml, then its combos.
    python3 tools/simulation/skill_cards.py "Female Soul Hound"

    # Each skill at the level a character of level 61 has it (class skill tree, parents included); the rest is left out.
    python3 tools/simulation/skill_cards.py "Female Soul Hound" --level 61

    # Just these skills, at their highest level (or id:level for another one).
    python3 tools/simulation/skill_cards.py --skills 1436,1439,1447:3

    # The build names.
    python3 tools/simulation/skill_cards.py --builds

A card is two lines: what the skill costs and reaches, then its effects with their parameters at that level
(name(k=v, ...); stat changes as stat*factor or stat+value). Cast is hitTime (+coolTime) and reuse is reuseDelay, both
before casting or attack speed. MP is mpConsume + mpInitialConsume. A debuff's land line is its base activateRate, the
stat that resists it (basicProperty), its magic level and level bonus rate, its trait and how long it lasts. A skill an
effect triggers (skillId) gets its own card under it. The Notes at the end are found mechanically: a debuff with a low
magic level for the character's level, a weak attack skill, skills that overwrite each other (same abnormalType).

Reads dist/game/data/FakePlayerPvp.xml, dist/game/data/stats/skills (custom included, as with CustomSkillsLoad) and
dist/game/data/stats/players/skillTrees. Standard library only. Enchant routes (enchant1..., skill levels 100+) are
not shown.
"""
import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATA = os.path.join(ROOT, "dist", "game", "data")
BUILDS_FILE = os.path.join(DATA, "FakePlayerPvp.xml")
SKILLS_DIR = os.path.join(DATA, "stats", "skills")
TREES_DIR = os.path.join(DATA, "stats", "players", "skillTrees")

ELEMENTS = {"0": "Fire", "1": "Water", "2": "Wind", "3": "Earth", "4": "Holy", "5": "Dark"}
KINDS = {"0": "physical", "1": "magic", "2": "static", "3": "dance"}
OPERATE = {"P": "passive", "T": "toggle", "CA1": "continuous", "CA5": "channeled", "DA1": "dash", "DA2": "dash"}
# Effect sections of a skill and the prefix of their line ("effects" has none).
SECTIONS = [("effects", ""), ("selfEffects", "self"), ("pvpEffects", "pvp"), ("pveEffects", "pve"), ("startEffects", "start"),
            ("channelingEffects", "channel"), ("endEffects", "end")]
NOT_CATEGORIES = {"combo", "variant"}
CATEGORY_HINTS = {"attack": "most preferred first", "peel": "the first that reaches is used", "perform": "hunting, then PvP"}
CARD_WIDTH = 28  # Width of "id name Llevel" before the facts.


# ---------------------------------------------------------------------------------------------------------------------
# Skills
# ---------------------------------------------------------------------------------------------------------------------

class Skill(object):
    def __init__(self, element):
        self.id = int(element.get("id"))
        self.levels = int(element.get("levels", "1"))
        self.name = element.get("name", "?")
        self.tables = {}
        self.fields = {}
        self.sections = []  # (section tag, element)
        self.conditions = []
        for child in element:
            if not isinstance(child.tag, str):
                continue
            if child.tag == "table":
                self.tables[child.get("name")] = (child.text or "").split()
            elif child.tag in ("conditions", "targetConditions"):
                self.conditions.append(child)
            elif child.tag in dict(SECTIONS):
                self.sections.append((child.tag, child))
            elif len(child) == 0 and not child.tag.startswith("enchant") and child.text is not None:
                self.fields[child.tag] = child.text.strip()

    def value(self, text, level):
        """A field or parameter value at a skill level: '#name' is read from the table (by level, clamped to the last of the first `levels` values)."""
        if text is None:
            return None
        return re.sub(r"#\w+", lambda m: self.lookup(m.group(0), level), text.strip())

    def lookup(self, name, level):
        values = self.tables.get(name)
        if not values:
            return name
        values = values[:self.levels]
        return values[max(1, min(level, len(values))) - 1]

    def get(self, field, level, default=None):
        value = self.value(self.fields.get(field), level)
        return default if value is None else value


class SkillDB(object):
    def __init__(self):
        self.skills = {}
        files = sorted(glob.glob(os.path.join(SKILLS_DIR, "*.xml")))
        files += sorted(glob.glob(os.path.join(SKILLS_DIR, "custom", "*.xml")))  # Loaded last by the server: wins.
        for path in files:
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as e:
                print("warning: %s: %s" % (os.path.relpath(path, ROOT), e), file=sys.stderr)
                continue
            for element in root.iter("skill"):
                if element.get("id"):
                    self.skills[int(element.get("id"))] = Skill(element)

    def get(self, skill_id):
        return self.skills.get(skill_id)


# ---------------------------------------------------------------------------------------------------------------------
# Class skill trees
# ---------------------------------------------------------------------------------------------------------------------

def load_trees():
    """classId -> (parentClassId or None, [(skillId, skillLevel, getLevel)])."""
    trees = {}
    for path in sorted(glob.glob(os.path.join(TREES_DIR, "**", "*.xml"), recursive=True)):
        root = ET.parse(path).getroot()
        for tree in root.iter("skillTree"):
            if tree.get("type") not in ("classSkillTree", "commonSkillTree"):
                continue
            class_id = int(tree.get("classId", "-1")) if tree.get("type") == "classSkillTree" else "common"
            parent = tree.get("parentClassId")
            parent = int(parent) if parent is not None and int(parent) >= 0 and int(parent) != class_id else None
            entry = trees.setdefault(class_id, [None, []])
            if parent is not None and entry[0] is None:
                entry[0] = parent
            for skill in tree.iter("skill"):
                entry[1].append((int(skill.get("skillId")), int(skill.get("skillLevel")), int(skill.get("getLevel", "1"))))
    return trees


def class_chain(trees, class_id):
    chain = []
    while class_id is not None and class_id in trees and class_id not in chain:
        chain.append(class_id)
        class_id = trees[class_id][0]
    return chain


def learned_skills(trees, class_id, level):
    """skillId -> (highest skill level learned at character level <= level, or 0; the level it is first learned at).
    Like FakePlayerPvpFactory.getLearnedSkills: the complete class tree (common, parents, the class), enchants (100+) left out."""
    learned = {}
    entries = list(trees.get("common", [None, []])[1])
    for cid in class_chain(trees, class_id):
        entries += trees[cid][1]
    for skill_id, skill_level, get_level in entries:
        if skill_level >= 100:
            continue
        have, first = learned.get(skill_id, (0, 999))
        first = min(first, get_level)
        if get_level <= level:
            have = max(have, skill_level)
        learned[skill_id] = (have, first)
    return learned


# ---------------------------------------------------------------------------------------------------------------------
# Builds
# ---------------------------------------------------------------------------------------------------------------------

ID_LIST = re.compile(r"^\s*\d+(\s*[|,]\s*\d+)*\s*$")


def load_builds():
    builds = []
    for element in ET.parse(BUILDS_FILE).getroot().iter("build"):
        categories = []
        combos = []
        for child in element:
            if not isinstance(child.tag, str) or child.tag in NOT_CATEGORIES:
                if child.tag == "combo":
                    steps = [dict(step.attrib) for step in child if step.tag == "step"]
                    combos.append((dict(child.attrib), steps))
                continue
            if child.text and ID_LIST.match(child.text):
                groups = [[int(x) for x in group.split("|")] for group in re.sub(r"\s", "", child.text).split(",")]
                categories.append((child.tag, groups))
        builds.append({"attrib": dict(element.attrib), "categories": categories, "combos": combos,
                       "variants": [v.get("name") for v in element.findall("variant")]})
    return builds


def find_build(builds, name):
    wanted = name.strip().lower()
    for build in builds:
        if build["attrib"]["name"].lower() == wanted:
            return build, None
    for build in builds:
        if wanted in [v.lower() for v in build["variants"] if v]:
            return build, "%s is a variant (other gear) of %s: same skills" % (name, build["attrib"]["name"])
    matches = [b for b in builds if wanted in b["attrib"]["name"].lower()]
    if len(matches) == 1:
        return matches[0], None
    if matches:
        sys.exit("'%s' matches several builds: %s" % (name, ", ".join(b["attrib"]["name"] for b in matches)))
    sys.exit("no build '%s' (see --builds)" % name)


# ---------------------------------------------------------------------------------------------------------------------
# Cards
# ---------------------------------------------------------------------------------------------------------------------

def seconds(ms):
    s = float(ms) / 1000.0
    return number(s)


def number(x):
    x = float(x)
    return str(int(x)) if x == int(x) else ("%.2f" % x).rstrip("0").rstrip(".")


def duration(sec):
    sec = float(sec)
    if sec >= 120 and sec % 60 == 0:
        return "%d min" % (sec // 60)
    return "%s s" % number(sec)


def is_number(text):
    try:
        float(text)
        return True
    except (TypeError, ValueError):
        return False


def format_param(skill, element, level):
    """One parameter of an effect, compact: power=12, pDef*0.77, pAtk+3[DUALFIST], skillLevel per level..."""
    tag = element.tag
    text = (element.text or "").strip()
    values = [v for v in element if v.tag == "value"]
    using = [u.get("kind") for u in element.iter("using") if u.get("kind")]
    if values:
        if any(v.get("level") for v in values):
            chosen = None
            for v in values:
                if v.get("level") is None or int(v.get("level")) <= level:
                    chosen = v
            text = (chosen.text or "").strip() if chosen is not None else (values[0].text or "").strip()
        else:
            text = (values[0].text or "").strip()
    text = skill.value(text, level) if text else text
    if tag in ("mul", "add", "sub", "set", "div") and element.get("stat"):
        sign = {"mul": "*", "add": "+", "sub": "-", "set": "=", "div": "/"}[tag]
        return "%s%s%s%s" % (element.get("stat"), sign, text, "[%s]" % ",".join(using) if using else "")
    if len(element) and not values:
        inner = [format_param(skill, child, level) for child in element if isinstance(child.tag, str)]
        attrs = ",".join("%s=%s" % (k, skill.value(v, level)) for k, v in element.attrib.items())
        return "%s%s[%s]" % (tag, "(%s)" % attrs if attrs else "", " ".join(inner))
    attrs = ",".join("%s=%s" % (k, skill.value(v, level)) for k, v in element.attrib.items())
    if attrs and text:
        return "%s(%s)=%s" % (tag, attrs, text)
    if attrs:
        return "%s(%s)" % (tag, attrs)
    return "%s=%s" % (tag, text)


def format_effect(skill, effect, level):
    params = ["%s=%s" % (k, skill.value(v, level)) for k, v in effect.attrib.items() if k != "name"]
    params += [format_param(skill, child, level) for child in effect if isinstance(child.tag, str)]
    name = skill.value(effect.get("name", effect.tag), level)
    return "%s(%s)" % (name, ", ".join(params)) if params else name


def triggered(skill, effect, level):
    """(skillId, skillLevel) an effect triggers, or None."""
    sid = effect.find("skillId")
    if sid is None or not is_number(skill.value(sid.text, level)):
        return None
    lvl = effect.find("skillLevel")
    lvl_value = skill.value(lvl.text, level) if lvl is not None else "1"
    return int(skill.value(sid.text, level)), int(float(lvl_value)) if is_number(lvl_value) else 1


def format_condition(skill, element, level):
    tag = element.tag
    children = [c for c in element if isinstance(c.tag, str)]
    if tag in ("and", "or"):
        return "(" + (" & " if tag == "and" else " | ").join(format_condition(skill, c, level) for c in children) + ")"
    if tag == "not":
        return "not " + " & ".join(format_condition(skill, c, level) for c in children)
    if tag == "using" and element.get("kind"):
        return "using " + element.get("kind")
    attrs = " ".join("%s=%s" % (k, skill.value(v, level)) for k, v in element.attrib.items())
    return ("%s %s" % (tag, attrs)).strip()


def head(skill, level, note=""):
    return "%d %s L%d%s" % (skill.id, skill.name, level, note)


def facts(skill, level):
    """The first line of a card, after the name: kind, cast, reuse, range, power, cost, then the land line."""
    g = lambda field, default=None: skill.get(field, level, default)
    out = []
    kind = KINDS.get(g("isMagic", "0"), "isMagic " + g("isMagic", "0"))
    operate = g("operateType", "")
    out.append("%s %s" % (OPERATE[operate], kind) if operate in OPERATE else kind)
    target = g("targetType")
    if target and target != "ONE":
        out.append("target " + target)
    if g("hitTime"):
        cool = g("coolTime")
        out.append("cast %.1f%s s" % (float(g("hitTime")) / 1000.0, "+" + seconds(cool) if cool and float(cool) > 0 else ""))
    if g("reuseDelay") and float(g("reuseDelay")) > 0:
        out.append("reuse %s%s" % (duration(float(g("reuseDelay")) / 1000.0), " static" if g("staticReuse") == "true" else ""))
    if g("castRange") and float(g("castRange")) > 0:
        out.append("range " + g("castRange"))
    if g("power") and float(g("power")) != 0:
        out.append("power " + g("power"))
    if g("pvpPower"):
        out.append("pvp power " + g("pvpPower"))
    if g("element") is not None:
        out.append(ELEMENTS.get(g("element"), "element " + g("element")) + (" " + g("elementPower") if g("elementPower") else ""))
    if g("affectRange") and float(g("affectRange")) > 0:
        out.append("area " + g("affectRange") + (" fan " + g("fanRange") if g("fanRange") else ""))
    if g("affectLimit"):
        out.append("max targets " + g("affectLimit"))
    if g("flyRadius"):
        out.append("jump " + g("flyRadius"))
    mp = sum(float(g(f)) for f in ("mpConsume", "mpInitialConsume") if g(f) and is_number(g(f)))
    if mp:
        out.append("MP " + number(mp))
    if g("mpPerChanneling"):
        out.append("MP %s/tick" % g("mpPerChanneling"))
    if g("hpConsume"):
        out.append("HP " + g("hpConsume"))
    if g("soulMaxConsumeCount"):
        out.append("souls " + g("soulMaxConsumeCount"))
    if g("chargeConsume"):
        out.append("charges " + g("chargeConsume"))
    if g("itemConsumeId") and g("itemConsumeId") != "0":
        out.append("item %s x%s" % (g("itemConsumeId"), g("itemConsumeCount", "1")))
    if g("blowChance"):
        out.append("blow %s%%" % g("blowChance"))
    if g("baseCritRate"):
        out.append("crit %s" % g("baseCritRate"))
    if g("removedOnAnyActionExceptMove") == "true":
        out.append("ends on any action")
    line = ", ".join(out)
    land = []
    if is_number(g("activateRate")) and float(g("activateRate")) > 0:
        part = "lands %s%% base" % g("activateRate")
        if g("basicProperty") and g("basicProperty") != "NONE":
            part += " on " + g("basicProperty")
        bonus = ", +%s%%/level" % g("lvlBonusRate") if g("lvlBonusRate") and g("lvlBonusRate") != "0" else ""
        if g("magicLevel"):
            part += " (magic level %s%s)" % (g("magicLevel"), bonus)
        land.append(part)
    if g("trait") and g("trait") != "NONE":
        land.append("trait " + g("trait"))
    if g("abnormalType") and g("abnormalType") != "NONE" and g("abnormalTime") and float(g("abnormalTime")) > 0:
        land.append("lasts %s (%s%s)" % (duration(g("abnormalTime")), g("abnormalType"),
                                         " lvl " + g("abnormalLevel") if g("abnormalLevel") and g("abnormalLevel") not in ("1", "-1") else ""))
    elif g("abnormalType") and g("abnormalType") != "NONE":
        land.append("(%s)" % g("abnormalType"))
    if land:
        line += "; " + ", ".join(land)
    return line


def card(db, skill_id, level, indent, note="", depth=0, seen=None):
    """The lines of one card. level None: the skill's highest level."""
    skill = db.get(skill_id)
    pad = " " * indent
    if skill is None:
        return ["%s%d (no such skill in dist/game/data/stats/skills)%s" % (pad, skill_id, note)]
    level = skill.levels if level is None else max(1, min(level, skill.levels))
    lines = ["%s%-*s %s" % (pad, CARD_WIDTH, head(skill, level, note), facts(skill, level))]
    sub = " " * (indent + 5)
    seen = set(seen or ()) | {skill_id}
    for section, element in skill.sections:
        prefix = dict(SECTIONS)[section]
        effects = [e for e in element if e.tag == "effect"]
        if not effects:
            continue
        lines.append("%s%s: %s" % (sub, prefix + " effects" if prefix else "effects", ", ".join(format_effect(skill, e, level) for e in effects)))
        for effect in effects:
            trigger = triggered(skill, effect, level)
            if trigger and depth < 2 and trigger[0] not in seen:
                lines += [l for l in card(db, trigger[0], trigger[1], indent + 7, " (triggered by %s)" % effect.get("name"), depth + 1, seen)]
    if skill.conditions:
        conds = []
        for element in skill.conditions:
            conds += [format_condition(skill, c, level) for c in element if isinstance(c.tag, str)]
        if conds:
            lines.append("%sneeds: %s" % (sub, "; ".join(conds)))
    return lines


# ---------------------------------------------------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------------------------------------------------

def attack_power(skill, level):
    power = skill.get("power", level)
    if is_number(power) and float(power) > 0:
        return float(power)
    for section, element in skill.sections:
        for effect in element:
            name = effect.get("name", "")
            p = effect.find("power")
            if "Damage" in name and "OverTime" not in name and p is not None and is_number(skill.value(p.text, level)):
                return float(skill.value(p.text, level))
    return None


def print_build(db, trees, build, level, note=None):
    a = build["attrib"]
    class_id = int(a["classId"])
    learned = learned_skills(trees, class_id, level)
    extra = ", ".join("%s %s" % (k, v) for k, v in a.items() if k not in ("name", "classId", "role", "weapon", "armor", "weight"))
    print("%s (classId %d, role %s, weapon %s, armor %s%s), level %d" % (a["name"], class_id, a.get("role"), a.get("weapon"), a.get("armor"),
                                                                        ", " + extra if extra else "", level))
    if note:
        print(note)
    if not class_chain(trees, class_id):
        print("warning: no class skill tree for classId %d: every skill is shown at its highest level" % class_id)
    shown = []  # (category, skill id, level, alternative group or None)

    def level_of(skill_id):
        """(level to show or None if not learned yet, note)."""
        if skill_id in learned:
            have, first = learned[skill_id]
            return (have, "") if have else (None, " (learned at %d)" % first)
        skill = db.get(skill_id)
        return (skill.levels if skill else None), " (not in the class tree)"

    for category, groups in build["categories"]:
        hint = CATEGORY_HINTS.get(category)
        print()
        print(category.upper() + (" (%s)" % hint if hint else ""))
        missing = []
        for group in groups:
            if len(group) > 1:
                names = [(db.get(s).name if db.get(s) else str(s)) for s in group]
                print("  one of (the first learned one is used): %s" % " | ".join(names))
            for skill_id in group:
                lvl, note = level_of(skill_id)
                if lvl is None and skill_id in learned:
                    skill = db.get(skill_id)
                    missing.append("%d %s%s" % (skill_id, skill.name if skill else "?", note))
                    continue
                for line in card(db, skill_id, lvl, 4 if len(group) > 1 else 2, note if note.startswith(" (not") else ""):
                    print(line)
                shown.append((category, skill_id, lvl, (category, tuple(group)) if len(group) > 1 else None))
        if missing:
            print("  not learned at %d: %s" % (level, ", ".join(missing)))

    if build["combos"]:
        print()
        print("COMBOS ([..] = optional step)")
        for attrib, steps in build["combos"]:
            tags = []
            if attrib.get("pvp") == "true":
                tags.append("pvp")
            for k, v in attrib.items():
                if k not in ("name", "pvp"):
                    tags.append("%s=%s" % (k, v))
            parts = []
            unlearned = []
            for step in steps:
                ids = [int(x) for x in step.get("skill", "0").split("|")]
                names = []
                for sid in ids:
                    skill = db.get(sid)
                    names.append(skill.name if skill else str(sid))
                    if sid in learned and not learned[sid][0]:
                        unlearned.append(skill.name if skill else str(sid))
                text = "|".join(names)
                flags = [k for k, v in step.items() if k not in ("skill", "optional") and v == "true"]
                flags += ["%s=%s" % (k, v) for k, v in step.items() if k not in ("skill", "optional") and v not in ("true", "false")]
                if flags:
                    text += " {%s}" % ", ".join(flags)
                parts.append("[%s]" % text if step.get("optional") == "true" else text)
            print("  %s (%s): %s" % (attrib.get("name", "?"), ", ".join(tags), " > ".join(parts)) if tags else
                  "  %s: %s" % (attrib.get("name", "?"), " > ".join(parts)))
            if unlearned:
                print("      not learned at %d: %s" % (level, ", ".join(unlearned)))

    notes = build_notes(db, shown, level)
    if notes:
        print()
        print("Notes")
        for n in notes:
            print("  - " + n)


def build_notes(db, shown, level):
    notes = []
    seen = set()
    for category, skill_id, lvl, alt in shown:
        skill = db.get(skill_id)
        if skill is None or skill_id in seen:
            continue
        seen.add(skill_id)
        magic_level = skill.get("magicLevel", lvl)
        rate = skill.get("activateRate", lvl)
        if is_number(magic_level) and is_number(rate) and float(rate) > 0 and level - float(magic_level) >= 10:
            notes.append("%d %s L%d: magic level %s, %d below level %d, lands %s%% base: much less on a same-level target"
                         % (skill_id, skill.name, lvl, magic_level, level - int(float(magic_level)), level, rate))
    # Weak attack skills: under a third of the strongest one of the same list and kind (magic and physical powers differ).
    by_list = {}
    for category, skill_id, lvl, alt in shown:
        skill = db.get(skill_id)
        if skill is None or category != "attack":
            continue
        power = attack_power(skill, lvl)
        if power:
            kind = "magic" if skill.get("isMagic", lvl, "0") == "1" else "physical"
            by_list.setdefault(kind, []).append((skill, lvl, power))
    for kind, entries in by_list.items():
        best = max(entries, key=lambda e: e[2])
        for skill, lvl, power in entries:
            if power < best[2] / 3.0:
                notes.append("%d %s L%d: power %s, under a third of %s (%s, the strongest %s attack)"
                             % (skill.id, skill.name, lvl, number(power), best[0].name, number(best[2]), kind))
    # Same abnormalType: they overwrite each other (alternatives "a|b" of one list are never both used: not counted).
    by_type = {}
    for category, skill_id, lvl, alt in shown:
        skill = db.get(skill_id)
        if skill is None:
            continue
        abnormal = skill.get("abnormalType", lvl)
        if abnormal and abnormal != "NONE":
            entry = by_type.setdefault(abnormal, [])
            if skill_id not in [s.id for s, c, a in entry]:
                entry.append((skill, category, alt or skill_id))
    for abnormal, entries in sorted(by_type.items()):
        if len(set(a for s, c, a in entries)) > 1:
            notes.append("same abnormalType %s (they overwrite each other): %s"
                         % (abnormal, ", ".join("%d %s (%s)" % (s.id, s.name, c) for s, c, a in entries)))
    return notes


def parse_skill_list(text):
    out = []
    for part in re.split(r"[,\s]+", text.strip()):
        if not part:
            continue
        m = re.match(r"^(\d+)(?:[:-](\d+))?$", part)
        if not m:
            sys.exit("bad skill '%s' (id or id:level)" % part)
        out.append((int(m.group(1)), int(m.group(2)) if m.group(2) else None))
    return out


def main():
    p = argparse.ArgumentParser(description="The skill kit of a fake player build, one card per skill.",
                                formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    p.add_argument("build", nargs="?", help='a build name of dist/game/data/FakePlayerPvp.xml, e.g. "Female Soul Hound"')
    p.add_argument("--level", type=int, default=85, help="character level: each skill at the level its class tree gives (default 85)")
    p.add_argument("--skills", help="just these skill ids, comma separated (id:level for a level, default the highest)")
    p.add_argument("--builds", action="store_true", help="list the build names")
    args = p.parse_args()

    if args.builds:
        for build in load_builds():
            a = build["attrib"]
            print("%-26s classId %-4s %-8s %s" % (a["name"], a["classId"], a.get("role"), a.get("weapon")))
        return
    if args.skills:
        db = SkillDB()
        for skill_id, lvl in parse_skill_list(args.skills):
            for line in card(db, skill_id, lvl, 0):
                print(line)
        return
    if not args.build:
        p.error("name a build, or use --skills or --builds")
    build, note = find_build(load_builds(), args.build)
    print_build(SkillDB(), load_trees(), build, args.level, note)


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:  # Piped into head.
        sys.stderr.close()
