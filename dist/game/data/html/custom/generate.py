# -*- coding: utf-8 -*-
"""
PoE-style passive tree generator.

Three structural layers, which is what makes PoE's tree readable:

  HIGHWAY   sparse chains of weak "travel" nodes linking junctions.
            You path through these to get somewhere; they give almost nothing.
  JUNCTION  where highways meet. Clusters hang off these.
  CLUSTER   a pocket of 3-5 small nodes ALL OF ONE THEME that dead-ends in a
            NOTABLE of that same theme. This is the "accuracy group leading
            to a big accuracy notable" shape. You detour in if you want it.

Plus KEYSTONE / MASTER / ACTIVE_SKILL pods at the outer extremities.
"""
import math, xml.sax.saxutils as sx
from collections import deque, Counter

nodes, edges = {}, set()
_next = {}

def nid_for(prefix):
    _next[prefix] = _next.get(prefix, 0) + 1
    return prefix + _next[prefix]

def add(nid, name, sector, ntype, cost, x, y, effect=None, skill=None, desc=None, tier=0):
    assert nid not in nodes, f"dup {nid}"
    nodes[nid] = dict(id=nid, name=name, sector=sector, type=ntype, cost=cost,
                      x=round(x,1), y=round(y,1), tier=tier, effect=effect,
                      skill=skill, desc=desc)
    return nid

def link(a,b):
    if a!=b: edges.add((min(a,b),max(a,b)))

CENTER=(1600.0,1600.0)
SECTORS=["Vanguard","Juggernaut","Shadowblade","Deadeye","Arcanist","Hierophant"]

def pt(angle_deg, radius, origin=CENTER):
    r=math.radians(angle_deg)
    return origin[0]+radius*math.cos(r), origin[1]+radius*math.sin(r)

def pt_from(origin, angle_deg, dist):
    r=math.radians(angle_deg)
    return origin[0]+dist*math.cos(r), origin[1]+dist*math.sin(r)

# ---------------------------------------------------------------------------
# THEMES - each is (theme name, small-node effect, (notable name, notable effect))
# A cluster is N copies of the small effect + the notable. THIS is the
# "small nodes of one kind leading to a big one of the same kind" structure.
# Small values are deliberately tiny; the notable is worth ~6-10 smalls.
# ---------------------------------------------------------------------------
THEMES = {
"Vanguard":[
  ("Iron Skin","PDEF_PCT:0.6",("Ironhide","PDEF_PCT:5;CON:1")),
  ("Vitality","MAXHP:14",("Robust Frame","MAXHP:90;CON:1")),
  ("Shield Work","SHIELD_RATE_PCT:0.6",("Aegis Training","SHIELD_RATE_PCT:5;PDEF_PCT:2")),
  ("Recovery","HP_REGEN_PCT:0.8",("Wellspring","HP_REGEN_PCT:6;MAXHP:40")),
  ("Warding","MDEF_PCT:0.6",("Spellbreaker","MDEF_PCT:5;MEN:1")),
  ("Thorns","REFLECT_PCT:0.6",("Spiked Bulwark","REFLECT_PCT:7;PDEF_PCT:2")),
],
"Juggernaut":[
  ("Brute Force","PATK_PCT:0.6",("Heavy Blows","PATK_PCT:5;STR:1")),
  ("Battle Rhythm","ATK_SPD_PCT:0.5",("Relentless Assault","ATK_SPD_PCT:4;PATK_PCT:1.5")),
  ("Savagery","CRIT_DMG_PCT:0.8",("Executioner","CRIT_DMG_PCT:8;STR:1")),
  ("Conditioning","MAXHP:12",("Iron Brawn","MAXHP:80;STR:1")),
  ("Weapon Training","ACCURACY_ADD:0.4",("Perfect Form","ACCURACY_ADD:4;PATK_PCT:2")),
  ("Bulwark","PDEF_PCT:0.5",("Armoured Fury","PDEF_PCT:4;MAXHP:40")),
],
"Shadowblade":[
  ("Precision","CRIT_RATE_ADD:4",("Lethality","CRIT_RATE_ADD:32;DEX:1")),
  ("Footwork","EVASION_ADD:0.4",("Shadow Dance","EVASION_ADD:4;MOVE_SPEED_ADD:1.8")),
  ("Deep Cuts","CRIT_DMG_PCT:0.8",("Exsanguinate","CRIT_DMG_PCT:8;DEX:1")),
  ("Swiftness","ATK_SPD_PCT:0.5",("Flurry","ATK_SPD_PCT:4;DEX:1")),
  ("Stride","MOVE_SPEED_ADD:0.7",("Silent Tread","MOVE_SPEED_ADD:5;EVASION_ADD:1")),
  ("Opportunism","PATK_PCT:0.5",("Backstabber","PATK_PCT:4;CRIT_DMG_PCT:3")),
],
"Deadeye":[
  ("Aim","ACCURACY_ADD:0.4",("Keen Eyesight","ACCURACY_ADD:4;DEX:1")),
  ("Draw Strength","PATK_PCT:0.6",("Longshot","PATK_PCT:5;DEX:1")),
  ("Ballistics","CRIT_DMG_PCT:0.8",("Broadhead","CRIT_DMG_PCT:8;ACCURACY_ADD:1")),
  ("Skirmish","EVASION_ADD:0.4",("Elusive Hunter","EVASION_ADD:3;MOVE_SPEED_ADD:1.8")),
  ("Quickdraw","ATK_SPD_PCT:0.5",("Rapid Fire","ATK_SPD_PCT:4;DEX:1")),
  ("Steady Hand","CRIT_RATE_ADD:4",("Bullseye","CRIT_RATE_ADD:28;PATK_PCT:2")),
],
"Arcanist":[
  ("Spellcraft","MATK_PCT:0.6",("Spell Surge","MATK_PCT:5;INT:1")),
  ("Mana Pool","MAXMP:12",("Deep Reservoir","MAXMP:120;INT:1")),
  ("Channelling","CAST_SPD_PCT:0.5",("Quickened Mind","CAST_SPD_PCT:4;WIT:1")),
  ("Arcane Insight","MCRIT_RATE_ADD:4",("Arcane Fury","MCRIT_RATE_ADD:32;INT:1")),
  ("Meditation","MP_REGEN_PCT:0.8",("Font of Power","MP_REGEN_PCT:6;MAXMP:60")),
  ("Ward","MDEF_PCT:0.5",("Runic Shell","MDEF_PCT:4;MAXMP:50")),
],
"Hierophant":[
  ("Devotion","MAXMP:14",("Devout Disciple","MAXMP:120;MEN:1")),
  ("Meditation","MP_REGEN_PCT:0.8",("Font of Mana","MP_REGEN_PCT:7;MEN:1")),
  ("Sanctity","MDEF_PCT:0.6",("Spell Ward","MDEF_PCT:5;MEN:1")),
  ("Litany","CAST_SPD_PCT:0.5",("Blessed Rites","CAST_SPD_PCT:4;WIT:1")),
  ("Constitution","HP_REGEN_PCT:0.8",("Shepherd's Grace","HP_REGEN_PCT:6;MAXHP:50")),
  ("Faith","PDEF_PCT:0.5",("Templar Training","PDEF_PCT:4;CON:1")),
],
}

# Travel/highway filler - near-worthless, exists to cost points and connect things
HIGHWAY_EFF = {
"Vanguard":"MAXHP:6","Juggernaut":"PATK_PCT:0.2","Shadowblade":"EVASION_ADD:0.2",
"Deadeye":"ACCURACY_ADD:0.2","Arcanist":"MATK_PCT:0.2","Hierophant":"MAXMP:6"}

KEYSTONES={
"Vanguard":[("Bastion of Thorns","REFLECT_PCT:14;SHIELD_RATE_PCT:7;PATK_PCT:-7","Reflect and block far more; you hit much softer."),
            ("Immovable","PDEF_PCT:11;MDEF_PCT:11;MOVE_SPEED_ADD:-8","Far tougher, notably slower.")],
"Juggernaut":[("Colossal Cleaver","PATK_PCT:14;ATK_SPD_PCT:-6","Huge attack power, slower swings."),
              ("Blood Frenzy","ATK_SPD_PCT:9;PDEF_PCT:-9","Much faster, far more fragile.")],
"Shadowblade":[("Ambush Specialist","CRIT_DMG_PCT:12;EVASION_ADD:-1.8","Devastating crits, easier to hit."),
               ("Ghost Walker","EVASION_ADD:4;MOVE_SPEED_ADD:5;MAXHP:-140","Evasive and fast, but frail.")],
"Deadeye":[("Far Shot","PATK_PCT:12;PDEF_PCT:-8","Great ranged power, poor defences."),
           ("Steady Aim","ACCURACY_ADD:4;CRIT_DMG_PCT:6;MOVE_SPEED_ADD:-7","Superb accuracy, slow-footed.")],
"Arcanist":[("Arcane Overload","MATK_PCT:15;MAXMP:-160","Immense spell power, tiny mana pool."),
            ("Mind Over Matter","MAXMP:200;MAXHP:-120","Far deeper mana, less health.")],
"Hierophant":[("Divine Benevolence","MP_REGEN_PCT:6;MDEF_PCT:6;PATK_PCT:-11;MATK_PCT:-11","Superb sustain, very weak offence."),
              ("Zealot's Oath","HP_REGEN_PCT:6;MP_REGEN_PCT:-5","Health recovery at mana's expense.")],
}
MASTERS={
"Vanguard":("Avatar of the Phalanx","CON:2;SHIELD_RATE_PCT:7;PDEF_PCT:5;MDEF_PCT:5"),
"Juggernaut":("Titan's Apotheosis","STR:2;PATK_PCT:8;ATK_SPD_PCT:2"),
"Shadowblade":("Executioner's Mark","DEX:2;CRIT_RATE_ADD:18;CRIT_DMG_PCT:6"),
"Deadeye":("Rain of Needles","DEX:2;PATK_PCT:6;ACCURACY_ADD:2.8"),
"Arcanist":("Elemental Singularity","INT:2;MATK_PCT:8;CAST_SPD_PCT:2"),
"Hierophant":("Avatar of Salvation","MEN:2;WIT:1;MP_REGEN_PCT:5;MDEF_PCT:5"),
}
ACTIVES={
"Vanguard":[("Vanguard Aegis",90400),("Rallying Shout",90401)],
"Juggernaut":[("War Cry",90410),("Earthshatter",90411)],
"Shadowblade":[("Shadow Step",90420),("Smoke Veil",90421)],
"Deadeye":[("Disengage",90430),("Volley",90431)],
"Arcanist":[("Arcane Nova",90440),("Mana Rift",90441)],
"Hierophant":[("Benediction",90450),("Sanctuary",90451)],
}
FUNCTIONS={"Vanguard":("Field Repairs",90302),"Juggernaut":("Common Craft Mastery",1322),
"Shadowblade":("Master Spoil",248),"Deadeye":("Tracker's Guile",None),
"Arcanist":("Scholar's Insight",None),"Hierophant":("Sacred Artisan",None)}

# ---------------------------------------------------------------------------
def make_cluster(sector, base_id, attach_id, origin, out_angle, theme, size):
    """
    A PoE-style pocket: a short stalk of same-theme smalls that dead-ends in
    a matching NOTABLE. Smalls cross-link so the pocket has internal loops
    (you can enter from either side) but the notable sits at the far end.
    """
    tname, teff, (nname, neff) = theme
    made = []
    prev = attach_id
    # stalk: 1 approach node
    x,y = pt_from(origin, out_angle, 95)
    a = add(nid_for(base_id), tname, sector, "SMALL", 1, x, y, effect=teff)
    link(prev, a); made.append(a); prev = a

    # blob: `size` smalls fanned around the stalk direction
    blob=[]
    for k in range(size):
        spread = (k-(size-1)/2) * 30
        d = 205 + (26 if k%2 else 0)
        x,y = pt_from(origin, out_angle+spread, d)
        s = add(nid_for(base_id), tname, sector, "SMALL", 1, x, y, effect=teff)
        link(prev, s); blob.append(s); made.append(s)
    for k in range(len(blob)-1):
        link(blob[k], blob[k+1])          # internal loop across the blob

    # notable at the far end, reachable from the blob
    x,y = pt_from(origin, out_angle, 330)
    nb = add(nid_for(base_id), nname, sector, "NOTABLE", 2, x, y, effect=neff,
             desc=f"{tname} mastery.")
    for s in blob: link(s, nb)
    made.append(nb)
    return made, nb

def make_highway(sector, base_id, a_id, a_pos, b_id, b_pos, segments):
    """Chain of weak travel nodes between two junctions."""
    prev=a_id; made=[]
    for k in range(1, segments+1):
        f=k/(segments+1)
        x=a_pos[0]+(b_pos[0]-a_pos[0])*f
        y=a_pos[1]+(b_pos[1]-a_pos[1])*f
        h=add(nid_for(base_id),"Pathway",sector,"SMALL",1,x,y,effect=HIGHWAY_EFF[sector])
        link(prev,h); made.append(h); prev=h
    link(prev,b_id)
    return made

# ---------------------------------------------------------------------------
# Build sectors
# ---------------------------------------------------------------------------
JUNCTION_RADII=[520, 900, 1280, 1660]
junction_at={}   # (sector_i, ring) -> (id, pos)

for si,sector in enumerate(SECTORS):
    base=1000+si*1000
    spine=si*60.0
    themes=THEMES[sector]
    t_i=0; k_i=0; a_i=0

    # START at the outer rim
    sx_,sy_=pt(spine,1900)
    start=add(base, f"{sector} Origin", sector,"START",0,sx_,sy_,
              effect="MAXHP:8",desc="Starting point for this archetype.")
    _next[base]=0

    prev_id, prev_pos = start,(sx_,sy_)
    for ring,R in enumerate(reversed(JUNCTION_RADII)):   # outer -> inner
        jx,jy=pt(spine,R)
        j=add(nid_for(base),"Crossroads",sector,"SMALL",1,jx,jy,
              effect=HIGHWAY_EFF[sector],desc="Junction.")
        junction_at[(si,ring)]=(j,(jx,jy))
        make_highway(sector,base,prev_id,prev_pos,j,(jx,jy),2)
        prev_id,prev_pos=j,(jx,jy)

        # hang 2-3 clusters off this junction, alternating sides of the spine
        for c,off in enumerate([-64, 64, 0][: (3 if ring in (1,2) else 2)]):
            theme=themes[t_i % len(themes)]; t_i+=1
            size=3 if ring==0 else 4
            make_cluster(sector,base,j,(jx,jy),spine+off+(0 if off else 0),theme,size)

        # keystone pod off ring 1 and 3
        if ring in (1,3) and k_i<len(KEYSTONES[sector]):
            kn,ke,kd=KEYSTONES[sector][k_i]; k_i+=1
            px,py=pt_from((jx,jy),spine+100,265)
            stalk=add(nid_for(base),"Approach",sector,"SMALL",1,
                      *pt_from((jx,jy),spine+100,120),effect=HIGHWAY_EFF[sector])
            link(j,stalk)
            kid=add(nid_for(base),kn,sector,"KEYSTONE",3,px,py,effect=ke,desc=kd)
            link(stalk,kid)

        # active-skill pod off ring 0 and 2
        if ring in (0,2) and a_i<len(ACTIVES[sector]):
            an,ask=ACTIVES[sector][a_i]; a_i+=1
            stalk=add(nid_for(base),"Approach",sector,"SMALL",1,
                      *pt_from((jx,jy),spine-100,120),effect=HIGHWAY_EFF[sector])
            link(j,stalk)
            px,py=pt_from((jx,jy),spine-100,265)
            aid=add(nid_for(base),an,sector,"ACTIVE_SKILL",2,px,py,skill=ask,
                    effect="MAXHP:15",
                    desc="PLACEHOLDER - grants an active skill (not yet authored).")
            link(stalk,aid)

    # MASTER at the innermost junction, pushed slightly inward
    mn,me=MASTERS[sector]
    jid,jpos=junction_at[(si,3)]
    mx,my=pt(spine,360)
    mid=add(nid_for(base),mn,sector,"MASTER",3,mx,my,effect=me,desc="Archetype capstone.")
    make_highway(sector,base,jid,jpos,mid,(mx,my),1)

    # FUNCTION hangs off the innermost junction
    fn,fsk=FUNCTIONS[sector]
    fx,fy=pt_from(jpos,spine+145,210)
    fid=add(nid_for(base),fn,sector,"FUNCTION",2,fx,fy,skill=fsk,effect="MAXHP:15",
            desc="Utility unlock.")
    link(jid,fid)

# --- circumferential highways between neighbouring sectors -----------------
bridges=0
for si in range(6):
    nxt=(si+1)%6
    for ring in (1,2,3):
        a,apos=junction_at[(si,ring)]
        b,bpos=junction_at[(nxt,ring)]
        make_highway(SECTORS[si],20000+si*1000+ring*100,
                     a,apos,b,bpos,3)
        bridges+=1

# --- Nexus core -------------------------------------------------------------
NEX=9000
_next[NEX]=0
core_ring=[]
for k in range(12):
    ang=k*30.0
    x,y=pt(ang,230)
    eff=["MAXHP:8","MAXMP:8","PATK_PCT:0.3","MATK_PCT:0.3","PDEF_PCT:0.3","MDEF_PCT:0.3"][k%6]
    n=add(nid_for(NEX),"Astral Filament","Nexus","SMALL",1,x,y,effect=eff)
    core_ring.append(n)
for k in range(12): link(core_ring[k],core_ring[(k+1)%12])
for si in range(6):
    mid_id=[n for n in nodes.values() if n["sector"]==SECTORS[si] and n["type"]=="MASTER"][0]["id"]
    link(mid_id, core_ring[(si*2)%12])

PILLARS=[("Pillar of Vitality","CON:1;MAXHP:90"),("Pillar of Force","STR:1;PATK_PCT:4"),
         ("Pillar of Grace","DEX:1;MOVE_SPEED_ADD:2.4"),("Pillar of Intellect","INT:1;MATK_PCT:4"),
         ("Pillar of Wisdom","WIT:1;CAST_SPD_PCT:3"),("Pillar of Will","MEN:1;MDEF_PCT:4")]
pids=[]
for k,(pn,pe) in enumerate(PILLARS):
    x,y=pt(k*60+30,125)
    p=add(nid_for(NEX),pn,"Nexus","NOTABLE",2,x,y,effect=pe)
    pids.append(p); link(p,core_ring[(k*2+1)%12]); link(p,core_ring[(k*2)%12])
for k in range(6): link(pids[k],pids[(k+1)%6])
cen=add(nid_for(NEX),"Prismatic Attunement","Nexus","MASTER",3,*CENTER,
        effect="STR:1;DEX:1;CON:1;INT:1;WIT:1;MEN:1",
        desc="TRANSCENDENT: +1 to every base attribute.")
for p in pids: link(p,cen)

# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Collision relaxation: nudge apart any nodes drawn too close together.
# Pure layout - topology is untouched, only x/y move. This is what stops
# labels and circles from piling on top of each other.
# ---------------------------------------------------------------------------
def relax(iterations=140, min_dist=112.0, strength=0.5):
    ids=list(nodes.keys())
    import math as _m
    for _ in range(iterations):
        moved=0.0
        # bucket into a grid so we only test nearby pairs
        cell=min_dist
        grid={}
        for i in ids:
            n=nodes[i]
            key=(int(n["x"]//cell), int(n["y"]//cell))
            grid.setdefault(key,[]).append(i)
        for i in ids:
            a=nodes[i]
            gx,gy=int(a["x"]//cell), int(a["y"]//cell)
            for dx in (-1,0,1):
                for dy in (-1,0,1):
                    for j in grid.get((gx+dx,gy+dy),()):
                        if j<=i: continue
                        b=nodes[j]
                        ddx=b["x"]-a["x"]; ddy=b["y"]-a["y"]
                        d2=ddx*ddx+ddy*ddy
                        if d2>=min_dist*min_dist or d2==0: continue
                        d=_m.sqrt(d2)
                        push=(min_dist-d)/2.0*strength
                        ux,uy=ddx/d, ddy/d
                        a["x"]-=ux*push; a["y"]-=uy*push
                        b["x"]+=ux*push; b["y"]+=uy*push
                        moved+=push
        if moved<1.0: break
    for i in ids:
        nodes[i]["x"]=round(nodes[i]["x"],1)
        nodes[i]["y"]=round(nodes[i]["y"],1)

relax()

# report worst remaining crowding
import math as _m
_ids=list(nodes.keys())
_worst=1e9
for _a in range(len(_ids)):
    for _b in range(_a+1,len(_ids)):
        n1,n2=nodes[_ids[_a]],nodes[_ids[_b]]
        _d=_m.hypot(n1["x"]-n2["x"], n1["y"]-n2["y"])
        if _d<_worst: _worst=_d
print(f"closest pair after relaxation: {_worst:.1f}px")

for n in nodes.values(): n["parents"]=[]
for a,b in edges:
    if nodes[b]["type"]!="START": nodes[b]["parents"].append(a)
    if nodes[a]["type"]!="START": nodes[a]["parents"].append(b)

errs=[(n['id'],p) for n in nodes.values() for p in n['parents'] if p not in nodes]
adj={}
for n in nodes.values():
    for p in n['parents']: adj.setdefault(p,[]).append(n['id'])
seen=set(); q=deque(n['id'] for n in nodes.values() if n['type']=='START'); seen.update(q)
while q:
    c=q.popleft()
    for nx in adj.get(c,[]):
        if nx not in seen: seen.add(nx); q.append(nx)

degs=[len(n['parents']) for n in nodes.values()]
print(f"nodes {len(nodes)}  edges {len(edges)}  avg conn {sum(degs)/len(degs):.2f}  max {max(degs)}")
print(f"broken refs {len(errs)}  unreachable {len(nodes)-len(seen)}")
print("types:",dict(Counter(n['type'] for n in nodes.values())))
print("total cost:",sum(n['cost'] for n in nodes.values()))

def write(sector):
    rows=sorted([n for n in nodes.values() if n['sector']==sector],key=lambda r:r['id'])
    out=['<?xml version="1.0" encoding="UTF-8"?>',
         f'<!-- {sector}: {len(rows)} nodes. x/y are authoritative layout coords. -->','<list>']
    for n in rows:
        a=(f'id="{n["id"]}" name="{sx.escape(n["name"])}" sector="{n["sector"]}" '
           f'type="{n["type"]}" tier="{n["tier"]}" cost="{n["cost"]}" x="{n["x"]}" y="{n["y"]}"')
        if n["effect"]: a+=f' effect="{sx.escape(n["effect"])}"'
        if n["skill"]:  a+=f' skillId="{n["skill"]}" skillLevel="1"'
        if n["desc"]:   a+=f' description="{sx.escape(n["desc"])}"'
        if n["parents"]:
            out.append(f'\t<node {a}>')
            for p in sorted(n["parents"]): out.append(f'\t\t<requires nodeId="{p}" />')
            out.append('\t</node>')
        else: out.append(f'\t<node {a} />')
    out.append('</list>')
    open(f"/mnt/user-data/outputs/tree-v5/data/{sector}.xml","w",encoding="utf-8").write("\n".join(out))
    return len(rows)

tot=0
for s in SECTORS+["Nexus"]:
    c=write(s); tot+=c; print(f"  {s}.xml {c}")
print("total written",tot)
