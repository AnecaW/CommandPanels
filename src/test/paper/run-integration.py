"""Isolated real Paper + connected protocol client. Native fixture fires inventory events."""
import argparse, json, os, shutil, subprocess, tempfile, time, urllib.request, urllib.parse, urllib.error, zipfile
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--accept-eula',action='store_true',required=True);parser.add_argument('--paper',type=Path,required=True);parser.add_argument('--protocol-client',type=Path,required=True);parser.add_argument('--browser-wait',action='store_true');parser.add_argument('--browser-panel',type=Path);parser.add_argument('--existing-modules',action='store_true');args=parser.parse_args()
repo=Path(__file__).resolve().parents[5];module=repo/'Modules/CommandPanels';root=Path(tempfile.mkdtemp(prefix='commandpanels-paper-'));java=Path(os.environ['JAVA_HOME'])
core=root/'plugins/PlugModCore';(core/'modules').mkdir(parents=True);shutil.copy(repo/'PlugModCore/target/plugmodcore-1.0-SNAPSHOT.jar',root/'plugins');shutil.copy(module/'target/CommandPanels.jar',core/'modules');shutil.copy(args.paper,root/'paper.jar');token=os.urandom(32).hex()
existing=[]
if args.existing_modules:
 for directory in ['PlaceholderAPI','ScoreBoard','PluginManager','CustomCommands','TemplateModule']:
  artifacts=[p for p in (repo/'Modules'/directory/'target').glob('*.jar') if not p.name.startswith('original-')]
  assert len(artifacts)==1,(directory,artifacts)
  shutil.copy(artifacts[0],core/'modules');existing.append(artifacts[0].stem)
(core/'core-config.yml').write_text('modules:\n  auto-load-on-startup: true\n  allow-hot-reload: true\nweb:\n  enabled: true\n  host: "127.0.0.1"\n  port: 18085\n  access-token: "'+token+'"\n');(root/'eula.txt').write_text('eula=true\n');(root/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25585\nonline-mode=false\nenforce-secure-profile=false\nview-distance=2\nsimulation-distance=2\ngenerate-structures=false\n')
classes=root/'probe';classes.mkdir();classpath=(module/'target/integration-classpath.txt').read_text().strip()+os.pathsep+str(repo/'PlugModCore/target/plugmodcore-1.0-SNAPSHOT.jar')
subprocess.run([str(java/'bin/javac.exe'),'--release','21','-cp',classpath,'-d',str(classes),str(Path(__file__).with_name('PanelProbe.java'))],check=True)
with zipfile.ZipFile(root/'plugins/PanelProbe.jar','w') as jar:
 for file in classes.rglob('*.class'):jar.write(file,file.relative_to(classes))
 jar.writestr('plugin.yml','name: PanelProbe\nversion: 1\nmain: org.wannes.integration.PanelProbe\napi-version: "1.21"\ndepend: [PlugModCore]\ncommands:\n  panelprobe:\n    permission: panelprobe.admin\n  fixtureaction:\n    permission: fixture.action\npermissions:\n  panelprobe.admin:\n    default: op\n  fixture.action:\n    default: op\n  fixture.panel:\n    default: false\n')
checks=[];server=None;bot=None;log=None;capability=''
def check(name,condition):assert condition,name;checks.append(name);print('PASS '+name,flush=True)
def wait(read,ready=lambda x:bool(x),seconds=120):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  try:
   value=read()
   if ready(value):return value
  except (OSError,ValueError,KeyError):pass
  time.sleep(.15)
 raise AssertionError('Timed out; last status '+str(read()))
def http(path,values=None,post=False):
 values=urllib.parse.urlencode(values or {});req=urllib.request.Request('http://127.0.0.1:18085'+path+('' if post or not values else '?'+values),data=values.encode() if post else None,headers={'Authorization':'Bearer '+token,'Content-Type':'application/x-www-form-urlencoded'})
 with urllib.request.urlopen(req,timeout=10) as response:
  body=response.read().decode();return json.loads(body) if response.headers.get_content_type()=='application/json' else body
def api(path,values=None,post=False):
 result=http('/api/modules/CommandPanels'+path,{**(values or {}),'capability':capability},post)
 if isinstance(result,dict) and 'job' in result:
  result=wait(lambda:api('/jobs',{'job':result['job']}),lambda r:r['status']!='running')
  assert result['status']=='done',result
 return result
def status():
 # Windows may briefly deny reads while the probe atomically replaces its snapshot.
 return wait(lambda:json.loads((root/'plugins/PanelProbe/status.json').read_text()),seconds=5)
def console(command):server.stdin.write(command+'\n');server.stdin.flush()
def probe(command):console('panelprobe '+command);time.sleep(.3);assert not status()['error'],status()
def save(panel,original=''):
 result=api('/save',{'definition':json.dumps(panel),'original':original,'revision':api('/panels')['state']['revision'],'consoleAuthorized':'true'},True)
 snapshot=root/'plugins/PanelProbe/status.json';modified=snapshot.stat().st_mtime_ns
 wait(lambda:snapshot.stat().st_mtime_ns>modified)
 return result
def start(name):
 global server,log
 log=(root/name).open('w',encoding='utf-8');server=subprocess.Popen([str(java/'bin/java.exe'),'-Xms256M','-Xmx1024M','-XX:ActiveProcessorCount=2','-jar','paper.jar','--nogui'],cwd=root,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True,creationflags=subprocess.CREATE_NO_WINDOW);wait(lambda:'Done (' in (root/name).read_text(encoding='utf-8',errors='replace'))
def client():
 global bot
 script=root/'bot.cjs';script.write_text('const mc=require('+json.dumps(str(args.protocol_client.resolve()))+');const c=mc.createClient({host:"127.0.0.1",port:25585,username:"PanelTest",auth:"offline",version:"1.21.11"});c.on("position",d=>c.write("teleport_confirm",{teleportId:d.teleportId}));let windowId=0,stateId=0;const fs=require("node:fs"),clickFile='+json.dumps(str(root/'wire-click.json'))+';c.on("open_window",d=>{windowId=d.windowId;console.log("WINDOW_TITLE "+JSON.stringify(d.windowTitle));});c.on("window_items",d=>{windowId=d.windowId;stateId=d.stateId;});setInterval(()=>{if(fs.existsSync(clickFile)){const task=JSON.parse(fs.readFileSync(clickFile));fs.unlinkSync(clickFile);c.write("window_click",{windowId,stateId,slot:task.slot,mouseButton:task.button,mode:task.mode,changedSlots:[],cursorItem:null});console.log("WIRE_CLICK_SENT");}},50);c.on("packet",(d,m)=>{if(["system_chat","open_window","window_items","sound_effect"].includes(m.name))console.log(m.name+" "+JSON.stringify(d,(k,v)=>typeof v==="bigint"?v.toString():v));});c.on("error",e=>console.error(e));c.on("end",()=>process.exit());')
 bot=subprocess.Popen(['node',str(script)],stdout=(root/'bot.log').open('a',encoding='utf-8'),stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW);wait(status,lambda s:s['online'],60)
def stop():
 global bot
 if bot and bot.poll() is None:bot.terminate();bot.wait(timeout=10)
 console('stop');server.wait(timeout=60);log.close()
try:
 start('first.log');http('/modules/enable/CommandPanels',post=True);capability=wait(lambda:http('/api/modules/CommandPanels/session'))['capability'];client()
 check('module discovered and enabled with shared service',status()['service'])
 check('empty first-run state',not api('/panels')['state']['panels'])
 catalogue=api('/catalogue');check('catalogue uses real valid materials and sounds',any(m['name']=='DIAMOND' for m in catalogue['materials']) and bool(catalogue['sounds']))
 import uuid
 panel={'id':str(uuid.uuid4()),'name':'Servers','command':'servers','title':'&6Servers','size':27,'enabled':True,'permission':'fixture.panel','items':{}}
 save(panel);panel=next(iter(api('/panels')['state']['panels'].values()));check('panel saved with stable identity',bool(panel['id']));check('configured command permission exposed to native registries',status()['commandPermissions'].get('commandpanels:servers')=='fixture.panel')
 probe('command servers');check('panel permission blocks opening',not status()['holder'].endswith('$Menu'))
 probe('grant true');probe('command servers');check('native opening command displays configured inventory',status()['size']==27 and status()['holder'].endswith('$Menu'))
 item={'material':'DIAMOND','name':'&aHello {player} %fixture_name%','lore':['&bServer selector','Second line'],'amount':3,'customModelData':42,'itemModel':'minecraft:emerald','glint':True,'flags':['HIDE_ENCHANTS'],'actions':[{'type':'console','value':'fixtureaction first {player}','click':'ANY','permission':'','authorized':True},{'type':'player','value':'fixtureaction left','click':'LEFT','permission':'','authorized':False},{'type':'player','value':'fixtureaction right','click':'RIGHT','permission':'','authorized':False}]}
 item['actions'] += [{'type':'player','value':'fixtureaction shift-left','click':'SHIFT_LEFT','permission':'','authorized':False},{'type':'player','value':'fixtureaction shift-right','click':'SHIFT_RIGHT','permission':'','authorized':False}]
 try:api('/save',{'definition':json.dumps({**panel,'items':{'0':item}}),'original':panel['id'],'revision':api('/panels')['state']['revision']},True);raise AssertionError('Unauthorized console action accepted')
 except urllib.error.HTTPError as error:check('console-action save requires explicit administrator authorization',error.code==400)
 panel['items']={'0':item};save(panel,panel['id']);check('live edit closes old menu safely',not status()['holder'].endswith('$Menu'));probe('command servers');snapshot=status()['items'][0]
 print('ITEM_SNAPSHOT '+json.dumps(snapshot),flush=True)
 check('material amount lore glint model and flags rendered natively',snapshot['material']=='DIAMOND' and snapshot['amount']==3 and len(snapshot['lore'])==2 and snapshot['glint'] and snapshot['customModelData']==42 and snapshot['itemModel']=='minecraft:emerald' and 'HIDE_ENCHANTS' in snapshot['flags'])
 check('colours and built-in player placeholder rendered', '"color":"green"' in snapshot['name'] and 'PanelTest' in snapshot['name'])
 check('unavailable placeholder does not crash rendering','%fixture_name%' in snapshot['name'])
 probe('placeholder true');probe('command servers');check('shared optional placeholder API resolves correct player context','%fixture_name%' not in status()['items'][0]['name'])
 probe('double LEFT 0');wait(status,lambda s:len(s['executed'])==2,seconds=10);check('ordered actions execute once per pending click',status()['executed']==['CONSOLE:first PanelTest','PanelTest:left'])
 probe('click RIGHT 0');check('right click selects correct actions',status()['executed'][-2:]==['CONSOLE:first PanelTest','PanelTest:right'])
 for click,value in [('SHIFT_LEFT','shift-left'),('SHIFT_RIGHT','shift-right')]:
  probe('click '+click+' 0');check('shift click selects correct actions: '+click,status()['cancelled'] and status()['executed'][-2:]==['CONSOLE:first PanelTest','PanelTest:'+value])
 for button,mode,label in [(0,2,'hotbar swap'),(0,0,'left click')]:
  before=status();(root/'wire-click.json').write_text(json.dumps({'slot':0,'button':button,'mode':mode}));wait(status,lambda s:s['observedClicks']>before['observedClicks'],seconds=10)
  check('actual client packet protected: '+label,status()['cancelled'] and status()['items'][0]['amount']==3 and status()['menuItemsInPlayerInventory']==0)
  if mode==0:wait(status,lambda s:len(s['executed'])==len(before['executed'])+2,seconds=10);check('actual client packet runs configured actions',status()['executed'][-1]=='PanelTest:left')
 for click,slotnum in [('NUMBER_KEY',0),('SHIFT_LEFT',32),('DROP',0),('DOUBLE_CLICK',0)]:
  before=len(status()['executed']);probe(f'click {click} {slotnum}');check('protected interaction '+click,status()['cancelled'] and len(status()['executed'])==before)
 probe('drag');check('drag into protected menu cancelled',status()['cancelled']);check('menu items not duplicated into player inventory',status()['menuItemsInPlayerInventory']==0)
 probe('grant false');before=len(status()['executed']);probe('click LEFT 0');check('permission changes enforced for an open menu',len(status()['executed'])==before and not status()['holder'].endswith('$Menu'));probe('grant true')
 location=status()['location'];location[2]+=1
 panel['items']['0']['actions']=[{'type':'message','value':'&bWelcome {player}','click':'ANY','permission':'','authorized':False},{'type':'sound','value':'minecraft:block.note_block.pling','click':'ANY','permission':'','authorized':False},{'type':'teleport','value':';'.join(map(str,location)),'click':'ANY','permission':'','authorized':False},{'type':'close','value':'','click':'ANY','permission':'','authorized':False}]
 save(panel,panel['id']);probe('command servers');probe('click LEFT 0');check('message sound teleport and close execute through native APIs','Welcome PanelTest' in (root/'bot.log').read_text() and 'sound_effect ' in (root/'bot.log').read_text() and not status()['holder'].endswith('$Menu') and abs(status()['location'][2]-location[2])<.01)
 target={**panel,'id':str(uuid.uuid4()),'name':'Next','command':'nextpanel','permission':'','items':{}};save(target);target=next(p for p in api('/panels')['state']['panels'].values() if p['command']=='nextpanel')
 panel['items']['0']['actions']=[{'type':'open','value':target['id'],'click':'ANY','permission':'','authorized':False}];save(panel,panel['id']);probe('command servers');probe('click LEFT 0');check('panel-to-panel navigation opens native inventory',status()['holder'].endswith('$Menu') and all(i is None for i in status()['items']))
 panel['items']['0']['actions']=[{'type':'server','value':'survival','click':'ANY','permission':'','authorized':False}];save(panel,panel['id']);probe('command servers');probe('click LEFT 0');check('missing proxy produces actionable player error','requires a configured compatible proxy' in (root/'bot.log').read_text())
 exported=api('/export',{'id':panel['id']});check('versioned export retains actions and identity',exported['schemaVersion']==1 and exported['panel']['id']==panel['id'])
 api('/duplicate',{'id':panel['id'],'revision':api('/panels')['state']['revision']},True);copy=next(p for p in api('/panels')['state']['panels'].values() if p['command']=='servers_2');check('duplicate gets independent ID and starts disabled',copy['id']!=panel['id'] and not copy['enabled'])
 api('/toggle',{'id':copy['id'],'revision':api('/panels')['state']['revision']},True);wait(status,lambda s:'commandpanels:servers_2' in s['registered']);check('enable registers duplicate opening command',True)
 api('/toggle',{'id':copy['id'],'revision':api('/panels')['state']['revision']},True);wait(status,lambda s:'commandpanels:servers_2' not in s['registered']);check('disable removes opening command',True)
 api('/delete',{'id':copy['id'],'confirmation':'DELETE:'+copy['id'],'revision':api('/panels')['state']['revision']},True);check('confirmed delete preserves unrelated panels',copy['id'] not in api('/panels')['state']['panels'] and len(api('/panels')['state']['panels'])==2)
 exported['panel']['command']='importedpanel';exported['panel']['enabled']=False
 api('/import',{'definition':json.dumps(exported),'revision':api('/panels')['state']['revision']},True);imported=next(p for p in api('/panels')['state']['panels'].values() if p['command']=='importedpanel');check('versioned import regenerates ID safely',imported['id']!=panel['id'])
 api('/delete',{'id':imported['id'],'confirmation':'DELETE:'+imported['id'],'revision':api('/panels')['state']['revision']},True)
 try:save({**panel,'command':'help'},panel['id']);raise AssertionError('Conflicting command accepted')
 except urllib.error.HTTPError as error:check('native command conflict rejected without changing state',error.code==400 and api('/panels')['state']['panels'][panel['id']]['command']=='servers')
 try:save({**panel,'items':{'0':{**item,'material':'NOT_AN_ITEM'}}},panel['id']);raise AssertionError('Invalid material accepted')
 except urllib.error.HTTPError as error:check('invalid item rejected server-side',error.code==400)
 owned={**target,'id':str(uuid.uuid4()),'name':'Owned permission','command':'ownedpanel','permission':'commandpanels.fixture'};save(owned);owned=next(p for p in api('/panels')['state']['panels'].values() if p['command']=='ownedpanel');check('new access permission is discoverable through Paper',status()['permissionNodes']==['commandpanels.fixture'])
 api('/delete',{'id':owned['id'],'confirmation':'DELETE:'+owned['id'],'revision':api('/panels')['state']['revision']},True);wait(status,lambda s:not s['permissionNodes']);check('unused module-owned permission removed without touching plugin permissions',status()['commandPermissions'].get('commandpanels:servers')=='fixture.panel')
 for cycle in range(3):
  probe('command servers');oldcap=capability;http('/modules/reload/CommandPanels',post=True);capability=wait(lambda:http('/api/modules/CommandPanels/session'),lambda r:r['capability']!=oldcap)['capability'];wait(status,lambda s:s['service'] and len(s['registered'])==2 and not s['holder'].endswith('$Menu'));check('reload cleanup cycle '+str(cycle),status()['listenerCount']==4)
 expected_commands=2
 if args.browser_panel:
  browser=json.loads(args.browser_panel.read_text());api('/import',{'definition':json.dumps(browser),'revision':api('/panels')['state']['revision'],'consoleAuthorized':'true'},True);expected_commands=3
 stop();start('restart.log');capability=wait(lambda:http('/api/modules/CommandPanels/session'))['capability'];client();probe('grant true');probe('command servers');check('full restart restores configuration and opening commands',status()['holder'].endswith('$Menu') and api('/panels')['state']['panels'][panel['id']]['items']['0']['material']=='DIAMOND')
 if args.browser_panel:
  definition=browser['panel'];probe('command '+definition['command']);item=status()['items'][0]
  check('actual browser-created panel export opens natively after restart',status()['size']==definition['size'] and item['material']==definition['items']['0']['material'])
  stored=next(p for p in api('/panels')['state']['panels'].values() if p['command']==definition['command']);check('browser-created item configuration persists',stored['items']==definition['items'])
  message=definition['items']['0']['actions'][0]['value'].replace('{player}','PanelTest')
  probe('click LEFT 0');check('browser-configured message action executes in Minecraft',message in (root/'bot.log').read_text())
 http('/modules/disable/CommandPanels',post=True);wait(status,lambda s:not s['service']);check('module disable releases commands listeners and inventories',not status()['registered'] and status()['listenerCount']==0 and not status()['holder'].endswith('$Menu'))
 http('/modules/enable/CommandPanels',post=True);capability=wait(lambda:http('/api/modules/CommandPanels/session'))['capability'];wait(status,lambda s:s['service']);check('reenable restores one set of listeners and commands',len(status()['registered'])==expected_commands and status()['listenerCount']==4)
 for name in existing:
  http('/modules/enable/'+name,post=True)
  ready=wait(lambda:next(m for m in http('/api/modules') if m['internalId']==name),lambda m:m['state'] in ['ENABLED','FAILED'])
  check('existing module enables: '+name,ready['state']=='ENABLED' and ready['loaded'])
 if args.browser_wait:
  (root/'browser.json').write_text(json.dumps({'root':str(root),'token':token,'capability':capability}));print('BROWSER_READY '+str(root),flush=True);wait(lambda:(root/'browser.done').exists(),seconds=1800)
  marker=(root/'browser.done').read_text().strip()
  if marker:
   verification=json.loads(marker);command=verification['verifyCommand']
   saved=next(p for p in api('/panels')['state']['panels'].values() if p['command']==command)
   probe('command '+command);check('fresh WebUI-created panel opens in Minecraft',status()['holder'].endswith('$Menu') and status()['items'][0]['material']==saved['items']['0']['material'])
   if 'titleText' in verification:
    native=status()['title'];check('formatted title component uses correct player context and styles',verification['titleText'] in native and '"color":"gold"' in native and '"bold":true' in native)
    packet=(root/'bot.log').read_text();check('client receives formatted inventory title', 'WINDOW_TITLE' in packet and verification['titleText'] in packet and 'gold' in packet)
   probe('click LEFT 0');check('fresh WebUI-created item action executes',verification['message'] in (root/'bot.log').read_text())
   stop();start('browser-restart.log');capability=wait(lambda:http('/api/modules/CommandPanels/session'))['capability'];client();probe('command '+command)
   check('fresh WebUI-created panel survives server restart',api('/panels')['state']['panels'][saved['id']]==saved and status()['holder'].endswith('$Menu'))
   if 'titleText' in verification:check('formatted title survives restart',verification['titleText'] in status()['title'] and '"color":"gold"' in status()['title'] and '"bold":true' in status()['title'])
finally:
 if server and server.poll() is None:stop()
 (root/'results.json').write_text(json.dumps(checks,indent=2));print('Artifacts: '+str(root),flush=True)
