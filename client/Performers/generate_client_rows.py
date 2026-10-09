import struct, os
import xml.etree.ElementTree as ET
# Regenerates skillgrp_additions.txt and skillname-e_additions.txt for the performer skills
# from the server skill data. Run: python3 client/Performers/generate_client_rows.py
# Same column layout as client/PassiveTree (L2ClientDat text export, no header line).
ROOT=os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
skills=ET.parse(ROOT+'/dist/game/data/stats/skills/custom/performer_skills.xml').getroot().findall('skill')
# oper_type, following client/PassiveTree: 0 physical attack, 1 heal, 2 buff (toggles too), 3 debuff.
OPER={27500:2,27501:2,27502:2,27503:2,27504:2,27505:2,27506:2,27507:1,27508:2,
      27520:2,27521:3,27522:2,27523:3,27524:2,27525:2,27526:2,27527:0,27528:0}
TOGGLE=" Only one performance can be on at a time. Continuously consumes MP proportionately to the user's level."
STANCE=" Only one stance can be on at a time. Continuously consumes MP proportionately to the user's level."
DESC={
 27500:"Performance: while on, all party members within 900 get Ballad of the Bulwark (P. Def. and M. Def. +{p}%)."+TOGGLE,
 27501:"P. Def. and M. Def. +{p}% while near the performer.",
 27502:"Performance: while on, all party members within 900 get Hymn of Renewal (HP Recovery Bonus +{hp}%, MP Recovery Bonus +{mp}%, HP recovered from healing +{heal}%)."+TOGGLE,
 27503:"HP Recovery Bonus +{hp}%, MP Recovery Bonus +{mp}%, HP recovered from healing +{heal}% while near the performer.",
 27504:"Performance: while on, all party members within 900 get Anthem of Valor (P. Atk. and M. Atk. +{p}%)."+TOGGLE,
 27505:"P. Atk. and M. Atk. +{p}% while near the performer.",
 27506:"Stance: P. Def. +5%. Each attack has a 15% chance to sing Healing Verse, recovering {power} HP of all party members within 600."+STANCE,
 27507:"Recovers {power} HP of all party members within 600.",
 27508:"Recovers 20% of the HP of all party members within 1000 and increases their P. Def. and M. Def. by 30% for 15 seconds. Reuse time is 5 minutes.",
 27520:"Performance: while on, enemies within 400 get Dance of Ruin (P. Def. and M. Def. -{p}%) while they stay near."+TOGGLE,
 27521:"P. Def. and M. Def. -{p}% while near the dancer.",
 27522:"Performance: while on, enemies within 400 get Dance of Torment (Atk. Spd. and Casting Spd. -{p}%, Speed -{run}%) while they stay near."+TOGGLE,
 27523:"Atk. Spd. and Casting Spd. -{p}%, Speed -{run}% while near the dancer.",
 27524:"Performance: while on, all party members within 900 get Dance of Frenzy (Atk. Spd. and Casting Spd. +{p}%)."+TOGGLE,
 27525:"Atk. Spd. and Casting Spd. +{p}% while near the dancer.",
 27526:"Stance: Atk. Spd. +8%, Evasion +3. Each attack with dual swords has a 20% chance to unleash Whirling Edge, striking all enemies within 200 with {power} Power added to P. Atk."+STANCE,
 27527:"Strikes all enemies within 200 with {power} Power added to P. Atk.",
 27528:"Strikes up to 15 enemies within 300 with 3500 Power added to P. Atk. They may also get P. Def. -20% and Speed -30% for 10 seconds. Reuse time is 2 minutes.",
}
def f32(x): return '%.8f' % struct.unpack('f', struct.pack('f', x))[0]
def table(sk, name, lvl):
    for t in sk.findall('table'):
        if t.get('name')==name: return t.text.split()[lvl-1]
    return None
def val(sk, text, lvl):
    return table(sk, text, lvl) if text.startswith('#') else text
def pct(x, down=False):
    v=round((1-float(x))*100 if down else (float(x)-1)*100)
    return str(v)
grp=[]; names=[]
for sk in skills:
    sid=int(sk.get('id')); levels=int(sk.get('levels')); name=sk.get('name')
    def g(tag, lvl, default=None):
        e=sk.find(tag); return val(sk, e.text.strip(), lvl) if e is not None else default
    icon=sk.find('icon').text.strip()
    for lvl in range(1, levels+1):
        mp=int(g('mpConsume',lvl,'0')) or int(g('mpInitialConsume',lvl,'0')); hp=int(g('hpConsume',lvl,'0'))
        rng=int(g('castRange',lvl,'-1')); hit=int(g('hitTime',lvl,'0'))/1000.0
        ismagic=int(g('isMagic',lvl,'0')); ismagic=ismagic if ismagic in (0,1) else 0
        hit=hit if hit>0 else 1.0
        attack = OPER[sid] in (0,3)
        tpl = ('0','3','S','9','11') if attack else ('1','1','X','8','10')
        row=[str(sid),str(lvl),str(OPER[sid]),tpl[0],str(mp),str(rng & 0xFFFFFFFF),tpl[1],f32(hit),str(ismagic),tpl[2],str(sid),icon,'','0','0',str(hp),'a,none\\0','0',tpl[3],tpl[4],'0','a,none\\0']
        assert len(row)==22
        grp.append('\t'.join(row))
        down = sk.find('isDebuff') is not None
        p=None
        for key in ('#def','#atk','#spd'):
            if table(sk,key,lvl): p=pct(table(sk,key,lvl), down)
        # A toggle describes its echo: read the echo's tables (the next id).
        if sid in (27500,27502,27504,27520,27522,27524):
            echo=[s for s in skills if int(s.get('id'))==sid+1][0]
            edown = echo.find('isDebuff') is not None
            for key in ('#def','#atk','#spd'):
                if table(echo,key,lvl): p=pct(table(echo,key,lvl), edown)
            src=echo
        else:
            src=sk
        args=dict(p=p, power=None, hp=None, mp=None, heal=None, run=None)
        if table(src,'#regHp',lvl): args.update(hp=pct(table(src,'#regHp',lvl)), mp=pct(table(src,'#regMp',lvl)), heal=pct(table(src,'#heal',lvl)))
        if table(src,'#run',lvl): args['run']=pct(table(src,'#run',lvl), True)
        if sid in (27506,27526):
            trig=[s for s in skills if int(s.get('id'))==sid+1][0]
            args['power']=table(trig,'#power',lvl)
        elif table(sk,'#power',lvl): args['power']=table(sk,'#power',lvl)
        d=DESC[sid].format(**args)
        assert '{' not in d and 'None' not in d, (sid,lvl,d)
        names.append('\t'.join([str(sid),str(lvl),'a,%s\\0'%name,'a,%s\\0'%d,'a,none\\0','a,none\\0']))
out=ROOT+'/client/Performers'
open(out+'/skillgrp_additions.txt','w',encoding='utf-8',newline='\r\n').write('\n'.join(grp)+'\n')
open(out+'/skillname-e_additions.txt','w',encoding='utf-8',newline='\r\n').write('\n'.join(names)+'\n')
print('wrote %d skillgrp and %d skillname rows' % (len(grp), len(names)))
