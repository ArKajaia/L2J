import struct, os
import xml.etree.ElementTree as ET
# Regenerates skillgrp_additions.txt and skillname-e_additions.txt for the Oracle and Totem Warchief
# skills from the server skill data. Run: python3 client/OraclesWarchiefs/generate_client_rows.py
# Same column layout as client/Performers (L2ClientDat text export, no header line).
ROOT=os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SKILLS=ROOT+'/dist/game/data/stats/skills/custom/'
skills=ET.parse(SKILLS+'oracle_skills.xml').getroot().findall('skill')+ET.parse(SKILLS+'warchief_skills.xml').getroot().findall('skill')
BY_ID={int(s.get('id')):s for s in skills}
# oper_type, following client/PassiveTree: 0 attack, 1 heal, 2 buff (toggles too), 3 debuff.
OPER={27530:3,27531:0,27532:1,27533:3,27534:3,27535:3,27536:2,27537:2,27538:2,27539:2,27540:3,
      27545:2,27546:2,27547:2,27548:3,27549:3,27550:2,27551:2,27552:2,27553:0,27554:2,27555:2,27556:2,27557:2,27558:2}
TOGGLE=" Continuously consumes MP proportionately to the user's level."
TOTEM=" A totem pulses every 2 seconds to everyone within 600 and stands 30 seconds; it can be broken. One of each kind, 3 at most."
DESC={
 27530:"Prophecy: after 5 seconds, the target is struck by holy magic with {power} Power ({still} Power if it is still within 300 of where it stood). Cancelled if the target dies first. A prophecy that comes true gives a Foresight.",
 27531:"The damage of a Prophecy of Doom that came true: {power} Power.",
 27532:"Prophecy: after 6 seconds, the target recovers all the HP it lost while the prophecy was on. A prophecy that comes true gives a Foresight.",
 27533:"Prophecy: after 5 seconds, the target is stunned for {stun} seconds if it cast a skill while the prophecy was on, or silenced for {silence} seconds if it didn't. A prophecy that comes true gives a Foresight.",
 27534:"Stunned by a Prophecy of Ruin.",
 27535:"Silenced by a Prophecy of Ruin.",
 27536:"Prophecy: the target's HP, MP, CP and place are written down. After 6 seconds it comes back to them (never lower than it is). Reuse time is {reuse} seconds.",
 27537:"A prophecy came true. M. Atk. +{matk}%. 5 Foresights allow Fulfilment.",
 27538:"Spends 5 Foresights: every prophecy you cast comes true at once, 50% stronger.",
 27539:"Sees the next blow coming: Evasion +4. A single hit taking 30% of your HP makes you step back 300, once a minute."+TOGGLE,
 27540:"Puts a Prophecy of Doom on every enemy and a Prophecy of Salvation on every party member within 600. Reuse time is 5 minutes.",
 27545:"Plants a Totem of Blood: party members near it recover {absorb}% of the damage they deal as HP."+TOTEM,
 27546:"Recovers {absorb}% of the damage dealt as HP while near the Totem of Blood.",
 27547:"Plants a Totem of the Horde: monsters near it that fight your party turn on the totem."+TOTEM,
 27548:"Plants a Totem of Frost-Teeth: enemies near it get Speed -{speed}% and Atk. Spd. -{atkspd}%."+TOTEM,
 27549:"Speed -{speed}%, Atk. Spd. -{atkspd}% while near the Totem of Frost-Teeth.",
 27550:"Plants a Totem of Ancestors: the first party member who lies dead near it gets up with 30% HP, and the totem is spent."+TOTEM,
 27551:"Stance: P. Atk. +5%. Each hit takes 1 second off the reuse of your totem skills."+TOGGLE,
 27552:"Takes 1 second off the reuse of your totem skills.",
 27553:"Needs 3 totems up. Each of your totems pulses once more and explodes, striking up to 10 enemies within 300 with {power} Power.",
 27554:"Needs a totem up. You swap places with your targeted totem, or the nearest one.",
 27555:"Max HP +200. Near your own totem: P. Def. and M. Def. +8%.",
 27556:"P. Def. and M. Def. +8% while near your own totem.",
 27557:"Totems up: {lvl}. Shatter needs 3.",
 27558:"Plants the Great Totem of the Horde-Father for 20 seconds: Totem of Blood, Totem of the Horde and Totem of Frost-Teeth at once, within 900. It can't be targeted. Reuse time is 5 minutes.",
}
def f32(x): return '%.8f' % struct.unpack('f', struct.pack('f', x))[0]
def table(sk, name, lvl):
    for t in sk.findall('table'):
        if t.get('name')==name: return t.text.split()[lvl-1]
    return None
def val(sk, text, lvl):
    return table(sk, text, lvl) if text.startswith('#') else text
def pct(x, down=False):
    return str(round((1-float(x))*100 if down else (float(x)-1)*100))
grp=[]; names=[]
for sk in skills:
    sid=int(sk.get('id')); levels=int(sk.get('levels')); name=sk.get('name')
    icon=sk.find('icon').text.strip()
    for lvl in range(1, levels+1):
        def g(s, tag, default=None):
            e=s.find(tag); return val(s, e.text.strip(), lvl) if e is not None else default
        mp=int(g(sk,'mpConsume','0')) or int(g(sk,'mpInitialConsume','0'))
        rng=int(g(sk,'castRange','-1')); hit=int(g(sk,'hitTime','0'))/1000.0
        ismagic=int(g(sk,'isMagic','0')); ismagic=ismagic if ismagic in (0,1) else 0
        hit=hit if hit>0 else 1.0
        attack = OPER[sid] in (0,3)
        tpl = ('0','3','S','9','11') if attack else ('1','1','X','8','10')
        row=[str(sid),str(lvl),str(OPER[sid]),tpl[0],str(mp),str(rng & 0xFFFFFFFF),tpl[1],f32(hit),str(ismagic),tpl[2],str(sid),icon,'','0','0','0','a,none\\0','0',tpl[3],tpl[4],'0','a,none\\0']
        assert len(row)==22
        grp.append('\t'.join(row))
        args=dict(lvl=lvl)
        if sid in (27530,27531):
            power=float(table(BY_ID[27531],'#power',lvl)); args.update(power=int(power), still=int(power*1.5))
        if sid==27533:
            args.update(stun=table(BY_ID[27534],'#abnormalTime',lvl), silence=table(BY_ID[27535],'#abnormalTime',lvl))
        if sid==27536: args['reuse']=int(table(sk,'#reuseDelay',lvl))//1000
        if sid==27537: args['matk']=pct(table(sk,'#mAtk',lvl))
        if sid in (27545,27546): args['absorb']=table(BY_ID[27546],'#absorb',lvl)
        if sid in (27548,27549): args.update(speed=pct(table(BY_ID[27549],'#speed',lvl),True), atkspd=pct(table(BY_ID[27549],'#atkSpd',lvl),True))
        if sid==27553: args['power']=table(sk,'#power',lvl)
        d=DESC[sid].format(**args)
        assert '{' not in d and 'None' not in d, (sid,lvl,d)
        names.append('\t'.join([str(sid),str(lvl),'a,%s\\0'%name,'a,%s\\0'%d,'a,none\\0','a,none\\0']))
out=ROOT+'/client/OraclesWarchiefs'
open(out+'/skillgrp_additions.txt','w',encoding='utf-8',newline='\r\n').write('\n'.join(grp)+'\n')
open(out+'/skillname-e_additions.txt','w',encoding='utf-8',newline='\r\n').write('\n'.join(names)+'\n')
print('wrote %d skillgrp and %d skillname rows' % (len(grp), len(names)))
