import re, struct, os
import xml.etree.ElementTree as ET
# Regenerates skillgrp_additions.txt and skillname-e_additions.txt from the
# server skill data. Run: python3 client/PassiveTree/generate_client_rows.py
ROOT=os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
tree=ET.parse(ROOT+'/dist/game/data/stats/skills/PassiveTreeActives.xml').getroot()
ICON={90400:110,90401:121,90410:78,90411:347,90420:821,90421:922,90430:4,90431:772,90440:1417,90441:1157,
      90450:1027,90451:1010,90460:1268,90461:96,90462:101,90463:1201,90464:1085,90465:1409}
OPER={90400:2,90401:2,90410:2,90411:0,90420:0,90421:2,90430:2,90431:0,90440:1,90441:1,90450:1,90451:2,
      90460:2,90461:0,90462:3,90463:3,90464:2,90465:2}
DESC={
 90400:"Increases P. Def. and M. Def. by 30% and Shield Block Rate by 20% for 20 seconds. Reuse time is 90 seconds.",
 90401:"Increases the P. Atk., M. Atk., P. Def. and M. Def. of all party members by 8% for 60 seconds. Reuse time is 180 seconds.",
 90410:"Increases P. Atk. by 15% and Atk. Spd. by 10%, but decreases P. Def. by 5% for 30 seconds. Reuse time is 60 seconds.",
 90411:"Strikes all nearby enemies with {power} Power added to P. Atk. and may stun them for 2 seconds. Reuse time is 30 seconds.",
 90420:"Instantly moves behind the target and increases Critical Damage by 20% for 5 seconds. Reuse time is 30 seconds.",
 90421:"Increases Evasion by 8 and Speed by 20 for 8 seconds. Reuse time is 40 seconds.",
 90430:"Increases Speed by 25 and Evasion by 6 for 6 seconds. Reuse time is 35 seconds.",
 90431:"Strikes all enemies within 500 range with {power} Power added to P. Atk. Reuse time is 25 seconds.",
 90440:"Blasts all nearby enemies with a magic nova of {power} Power. Reuse time is 30 seconds.",
 90441:"Consumes {hp} HP to instantly restore {amount} MP. Reuse time is 60 seconds.",
 90450:"Restores {amount} HP to all party members. Reuse time is 60 seconds.",
 90451:"Increases the P. Def. and M. Def. of all party members by 10% for 60 seconds. Reuse time is 180 seconds.",
 90460:"For 20 seconds, 6% of the damage dealt by your melee attacks is restored as HP and P. Def. is increased by 15%. Reuse time is 120 seconds.",
 90461:"Strikes the target with {power} Power added to P. Atk. and may cause bleeding for 10 seconds, draining HP over time. Reuse time is 20 seconds.",
 90462:"Marks the target, decreasing its Evasion by 6 and P. Def. by 10% for 20 seconds. Reuse time is 30 seconds.",
 90463:"Holds the target in place for 4 seconds. Reuse time is 30 seconds.",
 90464:"Decreases the reuse time of magic skills by 25% and increases Casting Spd. by 10% for 15 seconds. Reuse time is 180 seconds.",
 90465:"Removes up to 3 debuffs and increases HP recovered from healing by 20% for 15 seconds. Reuse time is 150 seconds.",
}
def f32(x): return '%.8f' % struct.unpack('f', struct.pack('f', x))[0]
def val(sk, text, lvl):
    if text.startswith('#'):
        for t in sk.findall('table'):
            if t.get('name')==text: return t.text.split()[lvl-1]
        raise KeyError(text)
    return text
grp=[]; names=[]; icons_used={}
for sk in tree.findall('skill'):
    sid=int(sk.get('id')); levels=int(sk.get('levels')); name=sk.get('name')
    def g(tag, lvl, default=None):
        e=sk.find(tag); return val(sk, e.text.strip(), lvl) if e is not None else default
    for lvl in range(1, levels+1):
        mp=int(g('mpConsume',lvl,'0')); hp=int(g('hpConsume',lvl,'0'))
        rng=int(g('castRange',lvl,'-1')); hit=int(g('hitTime',lvl,'0'))/1000.0
        ismagic=int(g('isMagic',lvl,'0'))
        attack = OPER[sid] in (0,1,3) and sk.find('targetType').text not in ('SELF','PARTY')
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
        d=DESC[sid].format(power=power,amount=amount,hp=hp,dot=dot)
        assert '{' not in d and 'None' not in d, (sid,d)
        names.append('\t'.join([str(sid),str(lvl),'a,%s\\0'%name,'a,%s\\0'%d,'a,none\\0','a,none\\0']))
    icons_used[sid]=ICON[sid]
assert len(grp)==82 and len(names)==82, (len(grp),len(names))
out=ROOT+'/client/PassiveTree'; os.makedirs(out,exist_ok=True)
# Column headers of the L2ClientDat export, for reference (not written to the files).
H1='skill_id\tskill_level\toper_type\tmp_consume\tcast_range\tcast_style\tUNK_0\thit_time\tis_magic\tani_char\tdesc\ticon_name\ticon_name2\tis_ench\tench_skill_id\thp_consume\tnonetext1\tUNK_1\tUNK_2\tUNK_3\tUNK_4\tnonetext2'
H2='id\tlevel\tname\tdescription\tdesc_add1\tdesc_add2'
open(out+'/skillgrp_additions.txt','w',encoding='utf-8',newline='\r\n').write('\n'.join(grp)+'\n')
open(out+'/skillname-e_additions.txt','w',encoding='utf-8',newline='\r\n').write('\n'.join(names)+'\n')
print('wrote %d skillgrp and %d skillname rows' % (len(grp), len(names)))
