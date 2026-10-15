import struct, os
import xml.etree.ElementTree as ET
# Regenerates skillgrp_additions.txt and skillname-e_additions.txt for the Oracle and Totem Warchief
# skills from the server skill data. Run: python3 client/OraclesWarchiefs/generate_client_rows.py
# Same layout as client/Performers: both files in the L2ClientDat skill_begin ... skill_end key=value
# format, tab-separated (no header line).
ROOT=os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SKILLS=ROOT+'/dist/game/data/stats/skills/custom/'
skills=ET.parse(SKILLS+'oracle_skills.xml').getroot().findall('skill')+ET.parse(SKILLS+'warchief_skills.xml').getroot().findall('skill')
BY_ID={int(s.get('id')):s for s in skills}
# operate_type, from the server's operateType: 0 A1, 1 A2, 2 P (the client lists it with the passives), 3 T.
OPERATE={'A1':0,'A2':1,'P':2,'T':3}
# icon_type, following client/PassiveTree: 0 attack, 1 heal, 2 buff, 3 debuff (grp_line makes toggles 6).
OPER={27530:3,27531:0,27532:1,27533:3,27534:3,27535:3,27536:3,27537:2,27538:2,27539:2,27540:3,
      27545:2,27546:2,27547:2,27548:3,27549:3,27550:2,27551:2,27552:2,27553:0,27554:2,27555:2,27556:2,27557:2,27558:2,
      27559:0,27560:0,27561:2,27562:2,27563:0,27564:2}
TOGGLE=" Continuously consumes MP proportionately to the user's level."
TOTEM=" A totem pulses every 2 seconds to everyone within 600 and stands 30 seconds; it is as high level as you and can be broken. One of each kind, 3 at most."
DESC={
 27530:"Prophecy: after 5 seconds, the target is struck by holy magic with {power} Power ({still} Power if it is still within 300 of where it stood). Cancelled if the target dies first. A prophecy that comes true gives a Foresight.",
 27531:"The damage of a Prophecy of Doom that came true: {power} Power.",
 27532:"Prophecy: after 6 seconds, the target recovers 60% of the HP it lost while the prophecy was on. A prophecy that comes true gives a Foresight.",
 27533:"Prophecy: after 5 seconds, the target is stunned for {stun} seconds if it cast a skill while the prophecy was on, or silenced for {silence} seconds if it didn't. A prophecy that comes true gives a Foresight.",
 27534:"Stunned by a Prophecy of Ruin.",
 27535:"Silenced by a Prophecy of Ruin.",
 27536:"Prophecy: after 5 seconds, the target takes {share}% of the damage it dealt while the prophecy was on as holy magic damage (at most a quarter of its max HP). Reuse time is {reuse} seconds. A prophecy that comes true gives a Foresight.",
 27537:"A prophecy came true (not one spread by Inevitable Doom). M. Atk. +{matk}%. 5 Foresights allow Fulfilment.",
 27538:"Spends 5 Foresights: every prophecy you cast comes true at once, 50% stronger.",
 27539:"Sees the next blow coming: Evasion +4. A single hit taking 30% of your HP makes you step back 300, once a minute."+TOGGLE,
 27540:"Puts a Prophecy of Doom on every enemy (one more stack on an enemy that has one; they don't spread) and a Prophecy of Salvation on every party member within 600. Reuse time is 5 minutes.",
 27545:"Plants a Totem of Blood: party members near it recover {absorb}% of the damage they deal as HP."+TOTEM,
 27546:"Recovers {absorb}% of the damage dealt as HP while near the Totem of Blood.",
 27547:"Plants a Totem of the Horde: monsters near it that fight your party turn on the totem."+TOTEM,
 27548:"Plants a Totem of Frost-Teeth: enemies near it get Speed -{speed}% and Atk. Spd. -{atkspd}%."+TOTEM,
 27549:"Speed -{speed}%, Atk. Spd. -{atkspd}% while near the Totem of Frost-Teeth.",
 27550:"Plants a Totem of Ancestors: the first party member who lies dead near it gets up with 30% HP, and the totem is spent."+TOTEM,
 27551:"Stance: P. Atk. +5%. Each hit takes 1 second off the reuse of your Totems of Blood, the Horde, Frost-Teeth and Flames."+TOGGLE,
 27552:"Takes 1 second off the reuse of your Totems of Blood, the Horde, Frost-Teeth and Flames.",
 27553:"Needs 3 totems up. Each of your totems pulses once more and explodes, striking up to 10 enemies within 300 with {power} Power.",
 27554:"Needs a totem up. You swap places with your targeted totem, or the nearest one.",
 27555:"Max HP +6%. Near your own totem: P. Def. and M. Def. +8%.",
 27556:"P. Def. and M. Def. +8% while near your own totem.",
 27557:"Totems up: {lvl}. Shatter needs 3.",
 27558:"Plants the Great Totem of the Horde-Father for 20 seconds: it raises a Totem of Blood, a Totem of the Horde and a Totem of Frost-Teeth around it that reach 900, can't be broken and don't count towards your totems. Reuse time is {reuse_min} minutes.",
 27559:"Plants a Totem of Flames: every 1.67 seconds it burns up to {targets} enemies within 400 with {power} Power (fire); an enemy another of your Totems of Flames burns takes half from it."+TOTEM,
 27560:"Burned by a Totem of Flames: {power} Power.",
 27561:"Turns you to ice for 5 seconds while your totems fight: you can't move or act, P. Def. and M. Def. +300%, and you recover {heal}% of your max HP every second. Reuse time is 1 minute.",
 27563:"The damage of a Prophecy of Reckoning that came true: {share}% of the damage the target dealt.",
 27564:"Steal Essence absorbs half as much HP: 40% of the damage instead of 80%.",
 27562:"Prophecy of Doom: reuse time halved; cast again on the same target before it comes true, it gains a stack and its time starts again (up to 5, each adds its damage once more; once full its time runs on); it also spreads with 1 stack to up to 4 enemies within 400 of its target that have no Doom, who can resist it. A spread Doom gives no Foresight.",
}
def f32(x): return '%.8f' % struct.unpack('f', struct.pack('f', x))[0]

# A row in the L2ClientDat Skillgrp.dat key=value format, from the 22 values above (column order of the
# old tab-separated export). A toggle (operate_type 3) gets icon_type 6: the client only shows a skill
# as a toggle (Toggle tab of the skill window, lit while on) by its icon_type, not its operate_type.
def grp_line(r):
    (sid,lvl,icon_type,operate,mp,rng,cast_style,hit,ismagic,anim,effect,icon,panel,
     debuff,enchanted,hp,ench_icon,ench_lvl,rumble_self,rumble_target,gauge,tag)=r
    if operate=='3': icon_type='6'
    rng=int(rng); rng=rng-(1<<32) if rng>=(1<<31) else rng
    num=lambda x: (lambda s: s if '.' in s else s+'.0')('%.6g' % float(x))
    txt=lambda x: x[2:-2] if x.startswith('a,') and x.endswith('\\0') else x
    return '\t'.join(['skill_begin','skill_id='+sid,'skill_level='+lvl,'icon=[%s]'%icon,'icon_panel=[%s]'%panel,
        'icon_type='+icon_type,'operate_type='+operate,'is_magic='+ismagic,'mp_consume='+mp,'cast_range=%d'%rng,
        'hit_time='+num(hit),'animation=[%s]'%anim,'cast_style='+cast_style,'skill_visual_effect=[%s]'%effect,
        'debuff='+debuff,'enchanted='+enchanted,'enchant_skill_level='+ench_lvl,'enchant_icon=[%s]'%txt(ench_icon),
        'hp_consume='+hp,'rumble_self='+rumble_self,'rumble_target='+rumble_target,'GaugeTime='+num(gauge),
        'AdditionalTag=[%s]'%txt(tag),'skill_end'])
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
        tpl = ('3','S','9','11') if attack else ('1','X','8','10')
        operate=str(OPERATE[sk.find('operateType').text.strip()])
        row=[str(sid),str(lvl),str(OPER[sid]),operate,str(mp),str(rng & 0xFFFFFFFF),tpl[0],f32(hit),str(ismagic),tpl[1],str(sid),icon,'','0','0','0','a,none\\0','0',tpl[2],tpl[3],'0','a,none\\0']
        assert len(row)==22
        grp.append(grp_line(row))
        args=dict(lvl=lvl)
        if sid in (27530,27531):
            power=float(table(BY_ID[27531],'#power',lvl)); args.update(power=int(power), still=int(power*1.5))
        if sid==27533:
            args.update(stun=table(BY_ID[27534],'#abnormalTime',lvl), silence=table(BY_ID[27535],'#abnormalTime',lvl))
        if sid==27536: args['reuse']=int(table(sk,'#reuseDelay',lvl))//1000
        if sid in (27536,27563): args['share']=table(BY_ID[27563],'#power',lvl)
        if sid==27537: args['matk']=pct(table(sk,'#mAtk',lvl))
        if sid in (27545,27546): args['absorb']=table(BY_ID[27546],'#absorb',lvl)
        if sid in (27548,27549): args.update(speed=pct(table(BY_ID[27549],'#speed',lvl),True), atkspd=pct(table(BY_ID[27549],'#atkSpd',lvl),True))
        if sid==27553: args['power']=table(sk,'#power',lvl)
        if sid==27558: args['reuse_min']=int(val(sk,sk.find('reuseDelay').text.strip(),lvl))//60000
        if sid in (27559,27560): args.update(power=table(BY_ID[27560],'#power',lvl), targets=table(BY_ID[27560],'#targets',lvl).split('-')[0])
        if sid==27561: args['heal']=table(sk,'#heal',lvl)
        d=DESC[sid].format(**args)
        assert '{' not in d and 'None' not in d, (sid,lvl,d)
        names.append('\t'.join(['skill_begin','skill_id=%d'%sid,'skill_level=%d'%lvl,'name=[%s]'%name,'desc=[%s]'%d,'enchant_name=[none]','enchant_desc=[none]','skill_end']))
# Sorted by skill id and level, like the .dat files.
key=lambda row: tuple(int(x.split('=')[-1]) for x in row.split('\t')[:3] if x!='skill_begin')
grp.sort(key=key); names.sort(key=key)
out=ROOT+'/client/OraclesWarchiefs'
open(out+'/skillgrp_additions.txt','w',encoding='utf-8',newline='\r\n').write('\n'.join(grp)+'\n')
open(out+'/skillname-e_additions.txt','w',encoding='utf-8',newline='\r\n').write('\n'.join(names)+'\n')
print('wrote %d skillgrp and %d skillname rows' % (len(grp), len(names)))
