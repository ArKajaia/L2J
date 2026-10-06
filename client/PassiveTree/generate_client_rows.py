import re, struct, os
import xml.etree.ElementTree as ET
# Regenerates skillgrp_additions.txt and skillname-e_additions.txt from the
# server skill data. Run: python3 client/PassiveTree/generate_client_rows.py
ROOT=os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SKILL_FILES=['/dist/game/data/stats/skills/PassiveTreeActives.xml','/dist/game/data/stats/skills/custom/hotzone_skills.xml']
skills=[sk for f in SKILL_FILES for sk in ET.parse(ROOT+f).getroot().findall('skill')]
ICON={27000:3123,27001:3123,27002:3123,27003:3123,27004:3123,27005:3123,
      27400:110,27401:121,27410:78,27411:347,27420:821,27421:922,27430:4,27431:772,27440:1417,27441:1157,
      27450:1027,27451:1010,27460:1268,27461:96,27462:101,27463:1201,27464:1085,27465:1409}
OPER={27000:2,27001:2,27002:2,27003:2,27004:2,27005:2,
      27400:2,27401:2,27410:2,27411:0,27420:0,27421:2,27430:2,27431:0,27440:1,27441:1,27450:1,27451:2,
      27460:2,27461:0,27462:3,27463:3,27464:2,27465:2}
DESC={
 27000:"Hot zone monster buff: Speed +50%, Max HP +50%, Atk. Spd. and Casting Spd. +30%.",
 27001:"Hunting in an active hot zone: XP and SP +50%, Atk. Spd. +40%, Casting Spd. +20%, drop amount +25%.",
 27002:"Hot zone kill streak: XP and SP +{bonus}%. Kill again within 15 seconds to raise it.",
 27003:"Hot zone omen: P. Atk. and M. Atk. +25%, but P. Def. and M. Def. -20%.",
 27004:"Hot zone omen: skill reuse time -20% and MP consumption -30%.",
 27005:"Hot zone omen: while your HP is at 30% or less, P. Atk. and M. Atk. +30% and 10% of the damage you deal is restored as HP.",
 27400:"Increases P. Def. and M. Def. by 30% and Shield Block Rate by 20% for 20 seconds. Reuse time is 90 seconds.",
 27401:"Increases the P. Atk., M. Atk., P. Def. and M. Def. of all party members by 8% for 60 seconds. Reuse time is 180 seconds.",
 27410:"Increases P. Atk. by 15% and Atk. Spd. by 10%, but decreases P. Def. by 5% for 30 seconds. Reuse time is 60 seconds.",
 27411:"Strikes all nearby enemies with {power} Power added to P. Atk. and may stun them for 2 seconds. Reuse time is 30 seconds.",
 27420:"Instantly moves behind the target and increases Critical Damage by 20% for 5 seconds. Reuse time is 30 seconds.",
 27421:"Increases Evasion by 8 and Speed by 20 for 8 seconds. Reuse time is 40 seconds.",
 27430:"Increases Speed by 25 and Evasion by 6 for 6 seconds. Reuse time is 35 seconds.",
 27431:"Strikes all enemies within 500 range with {power} Power added to P. Atk. Reuse time is 25 seconds.",
 27440:"Blasts all nearby enemies with a magic nova of {power} Power. Reuse time is 30 seconds.",
 27441:"Consumes {hp} HP to instantly restore {amount} MP. Reuse time is 60 seconds.",
 27450:"Restores {amount} HP to all party members. Reuse time is 60 seconds.",
 27451:"Increases the P. Def. and M. Def. of all party members by 10% for 60 seconds. Reuse time is 180 seconds.",
 27460:"For 20 seconds, 6% of the damage dealt by your melee attacks is restored as HP and P. Def. is increased by 15%. Reuse time is 120 seconds.",
 27461:"Strikes the target with {power} Power added to P. Atk. and may cause bleeding for 10 seconds, draining HP over time. Reuse time is 20 seconds.",
 27462:"Marks the target, decreasing its Evasion by 6 and P. Def. by 10% for 20 seconds. Reuse time is 30 seconds.",
 27463:"Holds the target in place for 4 seconds. Reuse time is 30 seconds.",
 27464:"Decreases the reuse time of magic skills by 25% and increases Casting Spd. by 10% for 15 seconds. Reuse time is 180 seconds.",
 27465:"Removes up to 3 debuffs and increases HP recovered from healing by 20% for 15 seconds. Reuse time is 150 seconds.",
}
def f32(x): return '%.8f' % struct.unpack('f', struct.pack('f', x))[0]
def val(sk, text, lvl):
    if text.startswith('#'):
        for t in sk.findall('table'):
            if t.get('name')==text: return t.text.split()[lvl-1]
        raise KeyError(text)
    return text
grp=[]; names=[]; icons_used={}
for sk in skills:
    sid=int(sk.get('id')); levels=int(sk.get('levels')); name=sk.get('name')
    def g(tag, lvl, default=None):
        e=sk.find(tag); return val(sk, e.text.strip(), lvl) if e is not None else default
    for lvl in range(1, levels+1):
        mp=int(g('mpConsume',lvl,'0')); hp=int(g('hpConsume',lvl,'0'))
        rng=int(g('castRange',lvl,'-1')); hit=int(g('hitTime',lvl,'0'))/1000.0
        ismagic=int(g('isMagic',lvl,'0'))
        # Client rows only use 0/1 (server-only types like 4 = no-animation buff become 0),
        # and a buff applied by script with no cast time still gets the 1 second the client expects.
        ismagic=ismagic if ismagic in (0,1) else 0
        hit=hit if hit>0 else 1.0
        attack = OPER[sid] in (0,1,3) and g('targetType',lvl,'SELF') not in ('SELF','PARTY')
        tpl = ('0','3','S','9','11') if attack else ('1','1','X','8','10')
        icon='icon.skill%04d'%ICON[sid]
        row=[str(sid),str(lvl),str(OPER[sid]),tpl[0],str(mp),str(rng & 0xFFFFFFFF),tpl[1],f32(hit),str(ismagic),tpl[2],str(sid),icon,'','0','0',str(hp),'a,none\\0','0',tpl[3],tpl[4],'0','a,none\\0']
        assert len(row)==22
        grp.append('\t'.join(row))
        # power/amount/dot per level for descriptions
        power=g('power',lvl)
        amount=dot=None
        for eff in sk.find('effects'):
            p=eff.find('power')
            if p is not None:
                v=val(sk,p.text.strip(),lvl)
                if eff.get('name')=='DamOverTime': dot=int(v)*5
                else: amount=v
        bonus=val(sk,'#bonus',lvl) if sk.find("table[@name='#bonus']") is not None else None
        d=DESC[sid].format(power=power,amount=amount,hp=hp,dot=dot,bonus=bonus)
        assert '{' not in d and 'None' not in d, (sid,d)
        names.append('\t'.join([str(sid),str(lvl),'a,%s\\0'%name,'a,%s\\0'%d,'a,none\\0','a,none\\0']))
    icons_used[sid]=ICON[sid]
assert len(grp)==97 and len(names)==97, (len(grp),len(names))
out=ROOT+'/client/PassiveTree'; os.makedirs(out,exist_ok=True)
# Column headers of the L2ClientDat export, for reference (not written to the files).
H1='skill_id\tskill_level\toper_type\tmp_consume\tcast_range\tcast_style\tUNK_0\thit_time\tis_magic\tani_char\tdesc\ticon_name\ticon_name2\tis_ench\tench_skill_id\thp_consume\tnonetext1\tUNK_1\tUNK_2\tUNK_3\tUNK_4\tnonetext2'
H2='id\tlevel\tname\tdescription\tdesc_add1\tdesc_add2'
open(out+'/skillgrp_additions.txt','w',encoding='utf-8',newline='\r\n').write('\n'.join(grp)+'\n')
open(out+'/skillname-e_additions.txt','w',encoding='utf-8',newline='\r\n').write('\n'.join(names)+'\n')
print('wrote %d skillgrp and %d skillname rows' % (len(grp), len(names)))
