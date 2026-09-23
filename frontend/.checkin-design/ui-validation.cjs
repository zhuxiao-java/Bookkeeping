const { app, BrowserWindow, session } = require('electron')
const http = require('node:http')
const fs = require('node:fs/promises')
const path = require('node:path')
const root = path.resolve(__dirname, '../dist')
const output = process.env.UI_OUTPUT_DIR || path.join(require('node:os').tmpdir(), 'bookkeeping-ui-validation-output-' + Date.now())
const profile = path.join(require('node:os').tmpdir(), 'bookkeeping-ui-validation-' + Date.now())
require('node:fs').mkdirSync(profile, { recursive: true })
app.setPath('userData', profile)
const sleep = ms => new Promise(r => setTimeout(r, ms))
const date = new Date(), year = date.getFullYear(), month = date.getMonth() + 1
const day = d => `${year}-${String(month).padStart(2,'0')}-${String(d).padStart(2,'0')}`
const accounts = [
  {id:1,name:'日常银行卡 · 家庭共同储蓄账户',type:'bank',currency:'CNY',initialBalance:20000,currentBalance:1234567890.12,archived:0},
  {id:2,name:'随身现金',type:'cash',currency:'CNY',initialBalance:1000,currentBalance:234.56,archived:0},
  {id:3,name:'旅行备用美元账户',type:'ali_pay',currency:'DOLLAR',initialBalance:200,currentBalance:1234.56,archived:1}
]
const categories = [
  {id:1,name:'餐饮与生活日常消费',parentId:null,type:'expense',color:'#ee8b3c',icon:'Food',sortOrder:1,archived:0},
  {id:2,name:'周末聚餐',parentId:1,type:'expense',color:'#d9772b',icon:'Food',sortOrder:2,archived:0},
  {id:3,name:'家庭聚餐',parentId:2,type:'expense',color:'#aa642a',icon:'Food',sortOrder:3,archived:0},
  {id:4,name:'交通出行',parentId:null,type:'expense',color:'#3e8fa8',icon:'Van',sortOrder:4,archived:0},
  {id:5,name:'工资收入',parentId:null,type:'income',color:'#2fb57c',icon:'Wallet',sortOrder:5,archived:0}
]
const tags=[{id:1,name:'家庭共同开支',color:'#ee8b3c'},{id:2,name:'值得纪念的一天',color:'#3e8fa8'}]
const transactions=Array.from({length:24},(_,i)=>({id:i+1,type:i%5===0?'income':i%7===0?'transfer':'expense',amount:i%5===0?12000:168.8+i,fee:0,accountId:1,toAccountId:i%7===0?2:null,categoryId:i%5===0?5:i%2===0?3:4,transactionDate:`${day(Math.min(date.getDate(),i+1))}T12:30:00`,note:i===0?'很长的流水备注，用于验证多标签、大金额和长账户名称能够自然换行，保留每一条完整信息。':'日常记录 '+(i+1),tags:'[1,2]'}))
const budgets=[{id:1,categoryId:null,year,month,amount:5000,amountUsed:5800,main:true},{id:2,categoryId:1,year,month,amount:1000,amountUsed:1680}]
const checkins=Array.from({length:Math.min(7,date.getDate())},(_,i)=>({id:i+1,checkDate:day(date.getDate()-i),expReward:12,baseExp:10,bonusExp:2,streakDays:7-i}))
const level={level:3,leveName:'从容记录者',description:'把每一笔生活，记录成自己的成长。',experience:320,totalEarned:360,totalSpent:40,currentThreshold:200,nextThreshold:500,nextLevel:4,nextLevelName:'生活规划师',birthday:null}
const messages=[
 {id:1,title:'本月预算提醒：为接下来的生活留一些余地',content:'本月餐饮预算已超支，请留意后续支出。\n你可以查看预算和关联的交易流水。',type:'budget',status:0,createTime:`${day(1)}T09:00:00`,bizType:'budget',bizId:2},
 {id:2,title:'今天也是值得记录的一天',content:'愿你的每一步，都能走向更从容的生活。\n记下小小日常，积累长久的力量。',type:'greeting',status:0,createTime:`${day(1)}T08:00:00`,cardImage:'/greeting/birthday.svg'},
 {id:3,title:'今日天气',content:JSON.stringify({city:'杭州',description:'晴',tempC:'26',feelsLikeC:'27',minTempC:'22',maxTempC:'29',humidity:'65',windKmph:'12'}),type:'weather',status:1,createTime:`${day(1)}T07:00:00`}
]
let empty=false,unavailable=false,delay=0
let saveDelay=0,saveFailures=0,saveReward=5
const storagePaths=['/Users/示例用户/Library/Application Support/记账本/data','C:\\Users\\示例用户\\AppData\\Roaming\\记账本\\'+'家庭长期账本目录'.repeat(16)+'\\data']
let storageDirectory=storagePaths[0],storageUnavailable=false,storageEmpty=false,storageDelay=0
const requests=[],checks=[],interactions=[],errors=[],warnings=[],knownIssues=[]
let printTransition=false
function api(url,body){
 const p=url.pathname.replace(/^\/api/,'')
 requests.push({path:p,body})
 const resources={account:accounts,category:categories,tag:tags,transaction:transactions,budget:budgets,check_in:checkins,message:messages}
 let data=[],extra={}
 if(p==='/backup/storagePath')return storageUnavailable?{code:'B_TEST_UNAVAILABLE',msg:'模拟路径读取失败'}:{code:'S0806',data:storageEmpty?'':storageDirectory}
 if(p.includes('/stats/'))return {code:'B_TEST_FALLBACK',msg:'隔离测试使用本地聚合'}
 if(p==='/level/currentLevel')return unavailable?{code:'B_TEST_UNAVAILABLE',msg:'模拟不可用'}:{code:'S0806',data:level}
 if(p==='/level/configs')data=Array.from({length:10},(_,i)=>({id:i+1,level:i+1,name:['初来乍到','认真记账','从容记录者','生活规划师'][i]||'持续成长 '+(i+1),expThreshold:i*100,description:'每一步都算数'}))
 else if(p==='/level/logs')data=empty?[]:[{id:1,year,month,budgetAmount:5000,actualAmount:5800,diffAmount:-800,expChange:-40}]
 else if(p==='/message/unreadCount')data=empty?0:2
 else if(p==='/budget/searchBudget')data=empty?[]:budgets
 else if(p==='/transaction/recent')data=empty?[]:transactions.slice(0,5)
 else if(p.endsWith('/selectAll'))data=empty?[]:resources[p.split('/')[1]]||[]
 else if(p.endsWith('/page')){data=empty?[]:resources[p.split('/')[1]]||[];extra={total:data.length,pageNum:1,pageSize:20}}
 else if(p.includes('/detail/'))data=(resources[p.split('/')[1]]||[]).find(x=>x.id===Number(p.split('/').pop()))||null
 else if(p==='/transaction/saveWithReward'){
  if(saveFailures>0){saveFailures--;return {code:'B_TEST_SAVE',msg:'模拟保存失败，请重试'}}
  data=saveReward
 }
 else if(p.endsWith('/preview'))data={total:3,validCount:1,duplicateCount:1,invalidCount:1,rows:['valid','duplicate','invalid'].map((status,i)=>({line:i+2,date:day(1),type:'支出',amount:'168.80',accountName:'随身现金',categoryName:'交通出行',status,reason:status==='invalid'?'未找到匹配分类':''}))}
 else if(p==='/backup/csv/transactions')data={total:3,imported:1,skipped:2,duplicates:1,failures:[{line:4,reason:'未找到匹配分类'}]}
 return {code:'S0806',msg:'隔离模拟',data,...extra}
}
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png'}
const server=http.createServer(async(req,res)=>{
 try{
  const url=new URL(req.url,'http://localhost')
  if(url.pathname.startsWith('/api/')){let body='';for await(const chunk of req)body+=chunk;if(delay)await sleep(delay);if(url.pathname==='/api/transaction/saveWithReward'&&saveDelay)await sleep(saveDelay);if(url.pathname==='/api/backup/storagePath'&&storageDelay)await sleep(storageDelay);res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(api(url,body)));return}
  if(url.pathname==='/setup'){res.writeHead(200,{'Content-Type':'text/html'});res.end('<html><body>隔离验证</body></html>');return}
  const file=path.resolve(root,'.'+(url.pathname==='/'?'/index.html':decodeURIComponent(url.pathname)))
  if(!file.startsWith(root+'/'))throw Error('无效路径')
  let content=await fs.readFile(file)
  if(path.extname(file)==='.html')content=content.toString().replace('<head>', '<head><script>window.electronAPI={apiBase:location.origin+"/api",onOpenQuickRecord:()=>()=>{}}</script>')
  res.writeHead(200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream'});res.end(content)
 }catch{res.writeHead(404);res.end()}
})
let win,base
const js=code=>win.webContents.executeJavaScript(`(()=>{const visible=e=>e&&!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length)&&getComputedStyle(e).visibility!=='hidden';const all=s=>[...document.querySelectorAll(s)].filter(visible);const one=s=>all(s)[0];const field=label=>all('.bk-dialog .el-form-item').find(e=>e.querySelector('label')?.textContent.trim()===label);${code}})()`,true)
async function click(selector,text){
 const ok=await js(`const e=${text?`all(${JSON.stringify(selector)}).find(e=>e.textContent.trim()===${JSON.stringify(text)})`:`one(${JSON.stringify(selector)})`};if(!e)return false;e.click();return true`)
 if(!ok)throw Error(`未找到控件 ${selector} ${text||''}`);await sleep(320)
}
async function navigate(route){await js(`location.hash=${JSON.stringify('#/'+route)};`);await sleep(550)}
async function input(selector,value){
 await js(`const e=one(${JSON.stringify(selector)});if(!e)throw Error('输入框不存在');e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));`)
 await sleep(100)
}
async function selectField(label,option){
 await js(`const row=all('.bk-dialog .el-form-item').find(e=>e.querySelector('label')?.textContent.trim()===${JSON.stringify(label)});row.querySelector('.el-select__wrapper').click();`)
 await sleep(200);await click('.el-select-dropdown__item .acc-option__name',option)
}
async function scrollShot(selector,name){
 await js(`one(${JSON.stringify(selector)}).scrollIntoView({block:'center'})`);await sleep(200);await snapshot(name)
}
async function snapshot(name){
 await js(`all('.el-notification__closeBtn').forEach(e=>e.click())`);await sleep(450)
 const image=await win.webContents.capturePage();await fs.writeFile(path.join(output,name+'.png'),image.resize({width:win.getContentSize()[0]}).toPNG())
}
async function audit(name,shot=false){
 const styleIssues=await js(`
  const issues=[];
  const check=(e,key,actual,expected)=>{if(Math.abs(actual-expected)>1)issues.push({element:e.className,key,actual,expected})};
  const css=e=>getComputedStyle(e),num=(e,key)=>parseFloat(css(e).getPropertyValue(key));
  for(const e of all('.page--comfortable')){
    check(e,'阅读宽度',num(e,'max-width'),960);
    check(e,'页面间距',num(e,'row-gap'),num(e,'--bk-page-gap'));
  }
  for(const scroller of all('.main-layout__main,.settings__body')){
    const page=scroller.querySelector('.page--comfortable');
    if(!page||!['auto','scroll'].includes(css(scroller).overflowY))continue;
    const before=page.getBoundingClientRect(),value=scroller.style.getPropertyValue('overflow-y'),priority=scroller.style.getPropertyPriority('overflow-y');
    try{
      scroller.style.setProperty('overflow-y','scroll');
      const after=page.getBoundingClientRect();
      check(page,'滚动条出现时的水平位置',after.left,before.left);
      check(page,'滚动条出现时的内容宽度',after.width,before.width);
    }finally{
      if(value)scroller.style.setProperty('overflow-y',value,priority);
      else scroller.style.removeProperty('overflow-y');
    }
  }
  for(const e of all('.surface,.panel,.el-card__body'))check(e,'面板留白',num(e,'padding-left'),num(e,'--bk-panel-padding'));
  for(const e of all('.split-row,.surface-block,.preference-block,.theme-picker,.tag-row,.cat-row__main,.tx-row,.recent-row,.ci__row'))check(e,'列表行距',num(e,'padding-top'),num(e,'--bk-row-padding'));
  for(const e of all('.el-button:not(.is-link)')){
    const h=e.getBoundingClientRect().height,expected=num(e,'--bk-button-height');
    if(h<expected-1)issues.push({element:e.className,key:'按钮高度',actual:h,expected});
    if(e.classList.contains('is-circle'))check(e,'图标按钮宽度',e.getBoundingClientRect().width,h);
  }
  for(const e of all('.segment,.decimal-options'))check(e,'分段控件高度',e.getBoundingClientRect().height,num(e,'--bk-control-height'));
  for(const e of all('.quiet-controls .el-select__wrapper,.quiet-controls .el-input__wrapper')){
    const size=e.closest('.el-select--small,.el-input--small')?'--bk-control-height-small':e.closest('.el-select--large,.el-input--large')?'--bk-control-height-large':'--bk-control-height';
    const expected=num(e,size),actual=e.getBoundingClientRect().height;
    if(actual<expected-1)issues.push({element:e.className,key:'输入控件高度',actual,expected});
  }
  for(const e of all('.dialog-footer,.page-head__actions,.tx-row__actions,.cat-row__actions,.tag-row__actions,.msg-toolbar__ops,.total-card__actions')){
    if(e.matches('.el-button')||!['flex','inline-flex'].includes(css(e).display))continue;
    check(e,'操作组间距',num(e,'column-gap'),num(e,'--bk-action-gap'));
    for(const b of [...e.children].filter(x=>x.matches('.el-button')))check(b,'按钮额外边距',num(b,'margin-left'),0);
  }
  return issues;
 `)
 const state=await js(`const main=one('.main-layout__main');return {name:${JSON.stringify(name)},title:one('.page-head__title')?.textContent,mainWidth:main?.clientWidth,mainScroll:main?.scrollWidth,pageOverflow:document.documentElement.scrollWidth>innerWidth+1||(main&&main.scrollWidth>main.clientWidth+1),nestedButtons:!!document.querySelector('button button'),layers:all('.bk-dialog,.bk-drawer,.el-tour__content,.screensaver').map(e=>{const r=e.getBoundingClientRect(),f=e.querySelector('.el-dialog__footer');return {width:r.width,height:r.height,within:r.left>=0&&r.right<=innerWidth+1&&r.top>=0&&r.bottom<=innerHeight+1,footerVisible:!f||f.getBoundingClientRect().bottom<=innerHeight+1}})};`)
 state.styleIssues=styleIssues;
 checks.push(state);if(styleIssues.length||state.pageOverflow||state.nestedButtons||state.layers.some(x=>!x.within||!x.footerVisible)){console.log('布局异常',state);await snapshot('FAIL-'+name)}
 if(shot)await snapshot(name);return state
}
async function closeDialog(){await click('.el-dialog__headerbtn');await sleep(250)}
async function assertUI(name,code){
 const passed=await js(`return !!(()=>{${code}})()`);interactions.push({name,passed});if(!passed)throw Error('交互断言失败：'+name)
 console.log('交互通过',name)
}
async function setup(theme='light',extra={}){
 await win.loadURL(base+'/setup')
 await js(`localStorage.clear();localStorage.setItem('bookkeeping-settings',JSON.stringify({theme:${JSON.stringify(theme)},screensaverMinutes:0,...${JSON.stringify(extra)}}));localStorage.setItem('bookkeeping-guide',JSON.stringify({version:1,done:Object.fromEntries(['global','dashboard','account','transaction','report','budget','level','settings'].map(k=>[k,true]))}));`)
 await win.loadURL(base+'/#/dashboard');await sleep(850)
}
async function run(){
 await fs.mkdir(output,{recursive:true});await new Promise(r=>server.listen(0,'127.0.0.1',r));base=`http://127.0.0.1:${server.address().port}`
 await app.whenReady();const isolated=session.fromPartition('ui-check-'+Date.now())
 isolated.webRequest.onBeforeRequest((d,cb)=>cb({cancel:!d.url.startsWith(base+'/')&&!d.url.startsWith('data:')&&!d.url.startsWith('blob:')}))
 win=new BrowserWindow({width:1440,height:900,show:false,webPreferences:{session:isolated,offscreen:true,backgroundThrottling:false}})
 win.webContents.on('console-message',(_,level,message)=>{
   if(level<2||message.includes('Content-Security-Policy'))return
   if(printTransition&&message.includes('ResizeObserver loop completed with undelivered notifications'))warnings.push(message)
   else errors.push(message)
  })
 await setup()
 await assertUI('模拟账本已载入',`return one('.checkin-card') && !document.body.textContent.includes('无法连接后端')`)
 if(!requests.some(r=>r.path==='/account/selectAll'))throw Error('模拟 API 未接入')
 if(!process.env.UI_DASHBOARD_ONLY)await backfillChecks()
 if(process.env.UI_BACKFILL_ONLY){
  const failures=checks.filter(c=>c.pageOverflow||c.nestedButtons||c.styleIssues.length||c.layers.some(x=>!x.within||!x.footerVisible))
  console.log('补流水验证完成',{checks:checks.length,interactions:interactions.length,failures:failures.length,errors,output})
  if(failures.length||errors.length)throw Error('补流水界面验证未通过')
  return
 }
 await dashboardChecks()
 if(process.env.UI_DASHBOARD_ONLY){
  const failures=checks.filter(c=>c.pageOverflow||c.nestedButtons||c.styleIssues.length||c.layers.some(x=>!x.within||!x.footerVisible))
  console.log('总览验证完成',{checks:checks.length,interactions:interactions.length,failures:failures.length,errors,output})
  if(failures.length||errors.length)throw Error('总览界面验证未通过')
  return
 }
 await storageChecks()
 const routes=['dashboard','account','transaction','budget','report','level',...['preference','category','tag','data','help','about'].map(m=>'settings?menu='+m)]
 for(const [width,height]of (process.env.UI_QUICK ? [] : [[1920,1080],[1440,900],[1180,800],[960,720]])){
  win.setContentSize(width,height)
  for(const theme of ['light','dark']){
   if(await js(`return document.documentElement.classList.contains('dark')`)!==(theme==='dark'))await click('[data-guide="theme"]')
   for(const collapsed of [false,true]){
    if(await js(`return one('.aside-footer').getAttribute('aria-expanded')==='false'`)!==collapsed)await click('.aside-footer')
    for(const route of routes){await navigate(route);await audit(`${width}-${theme}-${collapsed?'collapsed':'expanded'}-${route.replace('?menu=','-')}`,!collapsed&&((width===1440&&theme==='light')||(width===960&&theme==='dark')))}
   }
  }
 }
 win.setContentSize(960,720)
 if(!await js(`return document.documentElement.classList.contains('dark')`))await click('[data-guide="theme"]')
 await navigate('dashboard');await click('[data-guide="quick-record"]');await audit('quick-dark',true)
 await click('.el-dialog__footer button','保存');await audit('quick-validation',true)
 await click('.record-tab','收入');await audit('quick-income');await click('.record-tab','转账');await audit('quick-transfer',true);await closeDialog()
 await navigate('transaction');await click('.tx-row__actions button[aria-label="编辑流水"]');await audit('transaction-edit',true)
 await assertUI('编辑分类回填不被类型监听清空',`return !!one('.bk-dialog .el-cascader input')?.value`)
 await closeDialog()
 await click('.tx-row__actions button[aria-label="复制流水"]');await audit('transaction-copy');await closeDialog()
 await navigate('account');await click('[data-guide="account-create"]');await audit('account-dialog',true);await closeDialog()
 await navigate('settings?menu=category');await click('.cat-manage .page-head button');await audit('category-dialog',true);await closeDialog()
 await navigate('settings?menu=tag');await click('.tag-manage .page-head button');await audit('tag-dialog',true);await closeDialog()
 await click('[aria-label="打开消息中心"]');await audit('messages-dark',true);await click('.msg-item__open');await audit('message-detail',true);await closeDialog()
 await js(`all('.msg-item__open')[1].click()`);await sleep(350);await audit('greeting-dark',true);await closeDialog()
 await js(`all('.msg-item__open')[2].click()`);await sleep(350);await audit('weather-dark',true);await closeDialog();await click('.el-drawer__close-btn')
 await click('[data-guide="help"]');await audit('help-drawer',true)
 await js(`const e=one('input[aria-label="搜索使用说明"]');e.value='预算';e.dispatchEvent(new Event('input',{bubbles:true}));`);await sleep(300);await audit('help-search',true);await click('.el-drawer__close-btn')
 await navigate('report');printTransition=true;win.webContents.debugger.attach('1.3');await win.webContents.debugger.sendCommand('Emulation.setEmulatedMedia',{media:'print'});await sleep(500);await assertUI('打印隐藏导航',`return matchMedia('print').matches && getComputedStyle(document.querySelector('.main-layout__aside')).display==='none'`);await audit('report-print',true);await win.webContents.debugger.sendCommand('Emulation.setEmulatedMedia',{media:''});await sleep(700);win.webContents.debugger.detach();printTransition=false
 await extendedChecks()
 empty=true;await setup('dark');await audit('empty-dashboard',true);await navigate('transaction');await audit('empty-transactions',true)
 unavailable=true;await setup('dark');await navigate('level');await audit('level-unavailable',true)
 await assertUI('等级不可用提示',`return document.body.textContent.includes('暂时无法获取等级信息')`)
 unavailable=false;
 delay=1500;await setup('light');await audit('loading-state',true);delay=0
 const layoutFailures=checks.filter(c=>c.pageOverflow||c.nestedButtons||c.layers.some(x=>!x.within||!x.footerVisible)).length
 const styleFailures=checks.filter(c=>c.styleIssues.length).length
 console.log('验证完成',{checks:checks.length,interactions:interactions.length,layoutFailures,styleFailures,errors,warnings,knownIssues,output})
 if(layoutFailures||styleFailures||errors.length)process.exitCode=1
}
async function dashboardChecks(){
 const originalRecord={...transactions[1]},originalBudget={...budgets[0]},originalArchived=accounts[2].archived
 try{
  accounts[2].archived=0
  Object.assign(transactions[1],{type:'transfer',categoryId:null,toAccountId:2,amount:1234567890.12,note:'账户之间的资金转移，不应作为支出显示负号'})
  for(const [width,height]of (process.env.UI_DASHBOARD_INTERACTIONS ? [] : [[1440,900],[960,720],[720,800]])){
   win.setContentSize(width,height)
   for(const theme of ['light','dark']){
    await setup(theme)
    for(const collapsed of [false,true]){
     if(await js(`return one('.aside-footer').getAttribute('aria-expanded')==='false'`)!==collapsed)await click('.aside-footer')
     const name=`dashboard-polish-${width}-${theme}-${collapsed?'collapsed':'expanded'}`
     await audit(name,true)
     await assertUI(name+'金额与图例不溢出',`return all('.hero,.tile,.recent-row,.expense-legend button,.budget-overview').every(e=>e.scrollWidth<=e.clientWidth+1)`)
     await assertUI(name+'币种分列',`return all('.tile__currency').length===2&&!one('.tile__value').textContent.includes(' + ')`)
     await assertUI(name+'转账语义',`const row=all('.recent-row').find(e=>e.querySelector('.recent-row__type')?.textContent==='转账');const amount=row?.querySelector('.recent-row__amount')?.textContent.trim();return row?.tagName==='A'&&row.textContent.includes('→')&&amount==='¥1,234,567,890.12'`)
     await assertUI(name+'预算超支金额',`return one('.budget-balance')?.textContent==='¥800.00'&&one('.budget-badge')?.textContent==='已超支'&&one('.budget-usage strong')?.textContent==='116%'&&one('.budget-overview .el-progress')?.getAttribute('aria-valuenow')==='100'`)
     await scrollShot('.chart-row',name+'-charts')
     await scrollShot('.bottom-row',name+'-records')
     await js(`one('.main-layout__main').scrollTop=0`)
    }
   }
  }
  win.setContentSize(1440,900);await setup('light')
  await assertUI('父分类图例显示聚合名',`return all('.expense-legend__name').some(e=>e.textContent==='餐饮与生活日常消费')`)
  await click('.expense-card .el-radio-button','子分类')
  await assertUI('子分类切换后同步图例',`return all('.expense-legend__name').some(e=>e.textContent==='家庭聚餐')&&!all('.expense-legend__name').some(e=>e.textContent==='餐饮与生活日常消费')`)
  const before=requests.length
  await js(`const e=one('.expense-legend button:not(:disabled)');e.focus();`)
  await assertUI('分类图例可获取键盘焦点',`return document.activeElement?.matches('.expense-legend button')`)
  win.webContents.debugger.attach('1.3')
  try{
   await win.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled',{enabled:true})
   await win.webContents.debugger.sendCommand('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,text:'\r',unmodifiedText:'\r'})
   await win.webContents.debugger.sendCommand('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13})
  }finally{win.webContents.debugger.detach()}
  await sleep(650)
  await assertUI('键盘进入分类流水',`return location.hash.startsWith('#/transaction')`)
  const queries=requests.slice(before).filter(r=>r.path==='/transaction/page').flatMap(r=>JSON.parse(r.body).queryList||[])
  if(!queries.some(q=>q.key==='type'&&q.value==='expense')||!queries.some(q=>q.key==='categoryId')||!queries.some(q=>q.key==='date'))throw Error('图例跳转缺少收支类型、分类或日期筛选')
  await navigate('dashboard');const recentBefore=requests.length;await click('.recent-row')
  const recentQueries=requests.slice(recentBefore).filter(r=>r.path==='/transaction/page').flatMap(r=>JSON.parse(r.body).queryList||[])
  if(!recentQueries.some(q=>q.key==='id'&&q.value===transactions[0].id))throw Error('最近流水没有按 ID 定位')
  await assertUI('最近流水进入定位页',`return location.hash.startsWith('#/transaction')`)
  for(const [used,label,amount]of [[1000,'预算充足','¥4,000.00'],[4200,'临近上限','¥800.00'],[4999.99,'临近上限','¥0.01'],[5000,'已用完','¥0.00'],[5800,'已超支','¥800.00']]){
   budgets[0].amountUsed=used;await setup('light')
   await assertUI('预算边界 '+used,`return one('.budget-badge')?.textContent===${JSON.stringify(label)}&&one('.budget-balance')?.textContent===${JSON.stringify(amount)}`)
  }
  budgets[0].amount=0;budgets[0].amountUsed=0;await setup('dark')
  await assertUI('零预算无无效百分比',`return one('.budget-usage strong')?.textContent==='—'&&!one('.budget-overview').textContent.includes('NaN')&&one('.budget-overview .el-progress')?.getAttribute('aria-valuenow')==='0'`)
  empty=true;await setup('dark')
  await assertUI('空账本隐藏图例与预算金额',`return !one('.expense-breakdown')&&!one('.budget-overview')&&one('.tile__value')?.textContent.trim()==='¥0.00'`)
  await audit('dashboard-polish-empty',true)
  empty=false;Object.assign(budgets[0],originalBudget);delay=1600;await setup('light')
  await assertUI('慢请求显示流水与预算骨架',`return all('.bottom-card .el-skeleton').length===2`)
  await audit('dashboard-polish-loading',true);await sleep(1700)
 }finally{
  delay=0;empty=false;Object.assign(transactions[1],originalRecord);Object.assign(budgets[0],originalBudget);accounts[2].archived=originalArchived
  win.setContentSize(1440,900);await setup('light')
 }
}
async function backfillChecks(){
 win.webContents.debugger.attach('1.3')
 await win.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled',{enabled:true})
 const saved=()=>requests.filter(r=>r.path==='/transaction/saveWithReward').map(r=>JSON.parse(r.body))
 const dismissPopups=async()=>{
  await js(`const e=one('.el-dialog__title');e.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));e.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));e.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));e.click()`)
  await sleep(200)
 }
 const fieldInput=async(label,value)=>{
  await js(`field(${JSON.stringify(label)})?.querySelector('.el-date-editor .el-input__wrapper')?.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));`)
  await sleep(100)
  await js(`const e=field(${JSON.stringify(label)})?.querySelector('input');if(!e)throw Error('未找到表单字段');e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}));`)
  await sleep(100)
  await js(`const e=field(${JSON.stringify(label)}).querySelector('input');e.dispatchEvent(new Event('change',{bubbles:true}));e.blur();`)
  await dismissPopups()
 }
 const pickCategory=async name=>{
  await click('.bk-dialog .el-cascader')
  await js(`const option=all('.el-cascader-node__label').find(e=>e.textContent.includes(${JSON.stringify(name)}));if(!option)throw Error('未找到分类选项');option.click()`)
  await sleep(200);await dismissPopups()
 }
 const expect=(name,passed)=>{interactions.push({name,passed:!!passed});if(!passed)throw Error('请求断言失败：'+name);console.log('交互通过',name)}
 try{
  for(const [width,height]of (process.env.UI_BACKFILL_INTERACTIONS?[]:[[1440,900],[720,800],[480,720]])){
   for(const theme of ['light','dark']){
    win.setContentSize(width,height);await setup(theme);await navigate('transaction');await click('[data-guide="tx-backfill"]')
    await audit(`backfill-${width}-${theme}`,true)
    await assertUI('补录说明与底部按钮 '+width+theme,`return one('.el-dialog__title')?.textContent==='补流水'&&one('.tf-backfill-note')?.textContent.includes('月结经验不重算')&&all('.el-dialog__footer button').length===3`)
    await click('.record-tab','转账');await audit(`backfill-transfer-${width}-${theme}`,true);await closeDialog()
   }
  }
  win.setContentSize(1440,900);await setup('light');await navigate(`transaction?type=expense&start=${day(1)}&end=${day(28)}`)
  const originalQuery=JSON.parse(requests.filter(r=>r.path==='/transaction/page').at(-1).body)
  await click('[data-guide="tx-backfill"]')
  await assertUI('默认昨天的本地时间且日期位于金额前',`const value=one('.tf-backfill-date input').value;const d=new Date();d.setDate(d.getDate()-1);return value.slice(0,10)===[d.getFullYear(),String(d.getMonth()+1).padStart(2,'0'),String(d.getDate()).padStart(2,'0')].join('-')&&Math.abs(new Date(value.replace(' ','T')).getTime()-d.getTime())<5000&&all('.bk-dialog .el-form-item')[0].classList.contains('tf-backfill-date')`)
  const originalTime=await js(`return one('.tf-backfill-date input').value.slice(11)`)
  await click('.tf-date-shortcuts button','前天')
  await assertUI('前天快捷日期保留时分秒',`const value=one('.tf-backfill-date input').value,d=new Date();d.setDate(d.getDate()-2);return value.slice(0,10)===[d.getFullYear(),String(d.getMonth()+1).padStart(2,'0'),String(d.getDate()).padStart(2,'0')].join('-')&&value.slice(11)===${JSON.stringify(originalTime)}`)
  await click('.tf-date-shortcuts button','昨天')
  const datePoint=await js(`const r=one('.tf-backfill-date input').getBoundingClientRect();return {x:Math.round(r.left+40),y:Math.round(r.top+r.height/2)};`)
  win.webContents.sendInputEvent({type:'mouseDown',button:'left',clickCount:1,...datePoint});win.webContents.sendInputEvent({type:'mouseUp',button:'left',clickCount:1,...datePoint});await sleep(450)
  await assertUI('日历禁选未来日期',`return !!one('.el-date-table td.disabled')&&!!one('.el-date-table td.today:not(.disabled)')`)
  await dismissPopups()
  await input('.record-amount input','23.45');await pickCategory('交通出行')
  const beforeFuture=saved().length
  const future=await js(`const d=new Date();d.setHours(23,59,59,0);return [d.getFullYear(),String(d.getMonth()+1).padStart(2,'0'),String(d.getDate()).padStart(2,'0')].join('-')+' 23:59:59'`)
  await fieldInput('发生时间',future);await click('.el-dialog__footer button','保存')
  await assertUI('提交拦截今天尚未发生的时间',`return !!one('.bk-dialog')&&one('.tf-backfill-date .el-form-item__error')?.textContent.includes('不能晚于当前时间')`)
  expect('未来时间不发送保存请求',saved().length===beforeFuture)
  await dismissPopups();await fieldInput('发生时间','2024-02-29 12:34:56')
  await fieldInput('备注','  保留原始备注  ')
  await js(`field('标签').querySelector('.el-select__wrapper').click()`);await sleep(200);await click('.el-select-dropdown__item','家庭共同开支');await dismissPopups()
  const start=requests.length
  await click('.el-dialog__footer button','保存并继续');await sleep(500)
  const first=saved().at(-1)
  expect('历史支出原样提交日期、金额、分类和备注',first.type==='expense'&&first.transactionDate==='2024-02-29T12:34:56'&&first.amount===23.45&&first.categoryId===4&&first.accountId===1&&first.note==='保留原始备注'&&first.tags==='[1]'&&!('id'in first))
  await assertUI('连续补录保留日期类型账户，清空金额分类备注标签并聚焦',`return one('.record-tab.is-active').textContent.trim()==='支出'&&one('.tf-backfill-date input').value==='2024-02-29 12:34:56'&&field('账户').textContent.includes('日常银行卡')&&one('.record-amount input').value===''&&one('.bk-dialog .el-cascader input').value===''&&field('备注').querySelector('input').value===''&&!field('标签').querySelector('.el-tag')&&document.activeElement===one('.record-amount input')&&!one('.el-form-item__error')`)
  await assertUI('成功提示包含补录日期和实际经验',`return all('.el-message--success').some(e=>e.textContent.includes('2024-02-29')&&e.textContent.includes('5 点经验'))`)
  const refreshes=requests.slice(start).filter(r=>r.path==='/transaction/page')
  expect('单次成功仅刷新一次流水且不改变筛选',refreshes.length===1&&JSON.stringify(JSON.parse(refreshes[0].body))===JSON.stringify(originalQuery))
  expect('成功触发等级刷新',requests.slice(start).some(r=>r.path==='/level/currentLevel'))
  await click('.el-dialog__footer button','保存并继续')
  await assertUI('下一笔重新校验金额及分类',`return field('金额').querySelector('.el-form-item__error')&&field('分类').querySelector('.el-form-item__error')`)
  await click('.record-tab','收入');await input('.record-amount input','1234.56');await pickCategory('工资收入')
  saveReward=0;await click('.el-dialog__footer button','保存并继续');saveReward=5
  expect('收入补录仍使用所选历史时间',saved().at(-1).type==='income'&&saved().at(-1).categoryId===5&&saved().at(-1).transactionDate===first.transactionDate)
  await assertUI('封顶时显示实际零奖励',`return all('.el-message--success').some(e=>e.textContent.includes('本次获得 0 点经验'))`)
  await click('.record-tab','转账');await selectField('转入账户','随身现金');await input('.record-amount input','50');await fieldInput('手续费','1.25')
  await click('.el-dialog__footer button','保存并继续')
  expect('转账补录提交双账户与手续费',saved().at(-1).type==='transfer'&&saved().at(-1).toAccountId===2&&saved().at(-1).fee===1.25&&saved().at(-1).categoryId===null)
  await assertUI('转账连续补录保留双账户并清空手续费',`return one('.record-tab.is-active').textContent.trim()==='转账'&&field('账户').textContent.includes('日常银行卡')&&field('转入账户').textContent.includes('随身现金')&&field('手续费').querySelector('input').value===''&&one('.record-amount input').value===''`)
  await input('.record-amount input','88.88');await fieldInput('手续费','0.25');await fieldInput('备注','失败后保留')
  await js(`field('标签').querySelector('.el-select__wrapper').click()`);await sleep(200);await click('.el-select-dropdown__item','值得纪念的一天');await dismissPopups()
  const beforeFailure=requests.length;saveFailures=1
  await click('.el-dialog__footer button','保存并继续');await sleep(250)
  await assertUI('失败保留全部输入与弹窗',`return one('.record-amount input').value==='88.88'&&field('手续费').querySelector('input').value==='0.25'&&field('备注').querySelector('input').value==='失败后保留'&&field('转入账户').textContent.includes('随身现金')&&field('标签').textContent.includes('值得纪念的一天')&&one('.tf-backfill-date input').value==='2024-02-29 12:34:56'&&!one('.el-dialog__footer button.is-loading')`)
  expect('失败不发出流水刷新或自动重试',requests.slice(beforeFailure).filter(r=>r.path==='/transaction/saveWithReward').length===1&&!requests.slice(beforeFailure).some(r=>r.path==='/transaction/page'))
  const beforeRetry=saved().length;saveDelay=1600
  await js(`const b=all('.el-dialog__footer button').find(e=>e.textContent.trim()==='保存');b.click();b.click();all('.el-dialog__footer button').find(e=>e.textContent.trim()==='保存并继续').click()`)
  await sleep(150)
  await assertUI('慢请求锁定表单、类型、日期快捷键及取消关闭',`return all('.bk-dialog .el-form input').every(e=>e.disabled)&&all('.record-tab,.tf-date-shortcuts button').every(e=>e.disabled)&&!one('.el-dialog__headerbtn')&&all('.el-dialog__footer button').find(e=>e.textContent.trim()==='取消').disabled`)
  await js(`document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true}));all('.el-dialog__footer button').find(e=>e.textContent.trim()==='取消').click()`)
  await assertUI('请求期间 Escape 和取消不能关闭',`return !!one('.bk-dialog')`)
  await sleep(1800);saveDelay=0
  expect('双击和交叉保存只提交一笔且可重试',saved().length===beforeRetry+1&&JSON.stringify(saved().at(-1))===JSON.stringify(saved().at(-2)))
  await assertUI('保存成功关闭弹窗，保留列表筛选',`return !one('.bk-dialog')&&location.hash.startsWith('#/transaction')`)
  await click('[data-guide="tx-backfill"]');await assertUI('重新打开补录清空旧金额',`return one('.record-amount input').value===''&&one('.record-tab.is-active').textContent.trim()==='支出'`)
  await fieldInput('发生时间',day(date.getDate())+' 00:00:00');await input('.record-amount input','10');await pickCategory('交通出行');await click('.el-dialog__footer button','保存')
  expect('允许补录今天已经发生的交易',saved().at(-1).transactionDate===day(date.getDate())+'T00:00:00')
  await click('[data-guide="tx-create"]')
  await assertUI('普通新增没有补录说明和连续保存按钮',`return !one('.tf-backfill-note')&&!!field('日期')&&all('.el-dialog__footer button').length===2&&field('日期').querySelector('input').value.slice(0,10)===${JSON.stringify(day(date.getDate()))}`)
  await input('.record-amount input','10');await pickCategory('交通出行');await fieldInput('日期','2099-01-01 12:34:56');await click('.el-dialog__footer button','保存')
  expect('普通新增保留既有日期行为',saved().at(-1).transactionDate==='2099-01-01T12:34:56')
  await navigate('transaction');await click('.filter-card button','重置')
  for(const type of ['income','transfer','expense']){
   const index=transactions.findIndex(r=>r.type===type),record=transactions[index]
   for(const action of ['编辑流水','复制流水']){
    await js(`all('.tx-row')[${index}].querySelector('[aria-label="${action}"]').click()`);await sleep(350)
    await assertUI(`${action} ${type} 正确回填`,type==='transfer'
     ?`return field('转入账户').textContent.includes('随身现金')&&one('.record-tab.is-active').textContent.trim()==='转账'`
     :`return one('.bk-dialog .el-cascader input').value.includes(${JSON.stringify(categories.find(c=>c.id===record.categoryId).name)})`)
    await assertUI(`${action} ${type} 保持原发生时间`, `return field('日期').querySelector('input').value===${JSON.stringify(record.transactionDate.replace('T',' '))}`)
    const before=requests.length;await click('.el-dialog__footer button','保存')
    const request=requests.slice(before).find(r=>r.path===(action==='编辑流水'?'/transaction/update':'/transaction/saveWithReward'))
    const payload=request&&JSON.parse(request.body)
    expect(`${action} ${type} 使用正确接口和记录身份`,payload&&payload.type===type&&payload.transactionDate===record.transactionDate&&(action==='编辑流水'?payload.id===record.id:!('id'in payload)))
   }
  }
 }catch(error){
  console.log('补录失败时表单状态',await js(`return {text:one('.bk-dialog')?.innerText,inputs:all('.bk-dialog input').map(e=>({value:e.value,disabled:e.disabled})),calendar:all('.el-date-table td').map(e=>e.className),pickers:[...document.querySelectorAll('.el-picker__popper')].map(e=>({html:e.outerHTML.slice(0,600),display:getComputedStyle(e).display})),active:document.activeElement?.outerHTML}`))
  await snapshot('backfill-failure');throw error
 }finally{
  win.webContents.debugger.detach()
  saveDelay=0;saveFailures=0;saveReward=5;win.setContentSize(1440,900);await setup('light')
 }
}
async function storageChecks(){
 if(requests.some(r=>r.path==='/backup/storagePath'))throw Error('进入数据管理前不应查询存储路径')
 for(const [index,width,height,theme]of [[0,1440,900,'light'],[1,960,720,'dark']]){
  win.setContentSize(width,height)
  if(await js(`return document.documentElement.classList.contains('dark')`)!==(theme==='dark'))await click('[data-guide="theme"]')
  await navigate('settings?menu=category');storageDirectory=storagePaths[index];await navigate('settings?menu=data')
  await assertUI('数据目录完整展示 '+theme,`const e=one('.storage-path');return e?.textContent===${JSON.stringify(storageDirectory)}&&getComputedStyle(e).userSelect==='text'&&e.scrollWidth<=e.clientWidth+1`)
  await audit('storage-path-'+theme,true)
 }
 // 替换剪贴板接口以验证复制行为，避免覆盖用户的系统剪贴板。
 await js(`window.__clipboardDescriptor=Object.getOwnPropertyDescriptor(navigator,'clipboard');Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async value=>{window.__copiedStoragePath=value}}});`)
 try{
  await click('[aria-labelledby="data-storage-heading"] button','复制路径')
  await assertUI('复制完整路径（隔离剪贴板）',`return window.__copiedStoragePath===${JSON.stringify(storageDirectory)}&&all('.el-message--success').some(e=>e.textContent.includes('存储路径已复制'))`)
  await js(`navigator.clipboard.writeText=async()=>{throw Error('模拟剪贴板权限拒绝')}`)
  await click('[aria-labelledby="data-storage-heading"] button','复制路径')
  await assertUI('复制失败提供手动复制提示',`return all('.el-message--warning').some(e=>e.textContent.includes('手动复制'))&&!!one('.storage-path')`)
 }finally{
  await js(`if(window.__clipboardDescriptor)Object.defineProperty(navigator,'clipboard',window.__clipboardDescriptor);else delete navigator.clipboard;delete window.__clipboardDescriptor;delete window.__copiedStoragePath;`)
 }
 storageUnavailable=true;await navigate('settings?menu=category');await navigate('settings?menu=data')
 await assertUI('路径读取失败不展示猜测路径',`return !one('.storage-path')&&one('[aria-labelledby="data-storage-heading"] [role="status"]')?.textContent.includes('暂时无法读取')`)
 await audit('storage-path-unavailable',true)
 storageUnavailable=false;await click('[aria-labelledby="data-storage-heading"] button','重新读取')
 await assertUI('路径读取失败后可重试',`return one('.storage-path')?.textContent===${JSON.stringify(storageDirectory)}`)
 storageEmpty=true;await navigate('settings?menu=category');await navigate('settings?menu=data')
 await assertUI('空路径响应显示重试入口',`return !one('.storage-path')&&one('[aria-labelledby="data-storage-heading"] button')?.textContent.trim()==='重新读取'`)
 storageEmpty=false;await click('[aria-labelledby="data-storage-heading"] button','重新读取')
 storageDelay=1600;await navigate('settings?menu=category');await navigate('settings?menu=data')
 await assertUI('读取期间禁用复制并提示加载',`return one('[aria-labelledby="data-storage-heading"] button')?.disabled&&one('[aria-labelledby="data-storage-heading"] [role="status"]')?.textContent.includes('正在读取')`)
 await audit('storage-path-loading',true);await sleep(1700);storageDelay=0
 await assertUI('加载后恢复路径展示',`return one('.storage-path')?.textContent===${JSON.stringify(storageDirectory)}`)
 storageDirectory=storagePaths[0];win.setContentSize(1440,900);await setup('light')
}
async function extendedChecks(){
 await setup('light')
 await navigate('dashboard');await scrollShot('.checkin-card','checkin-card-light')
 await click('[data-guide="quick-record"]');await audit('quick-light',true)
 await input('.qr-amount__input','36.50')
 await click('.qr-cat__name','餐饮与生活日常消费');await click('.qr-cat__name','周末聚餐');await click('.qr-cat__name','家庭聚餐')
 await assertUI('三级分类选择',`return one('.qr-cat.is-selected')?.textContent.includes('家庭聚餐')`)
 await click('.el-dialog__footer button','存为模板');await input('.el-message-box input','家庭聚餐模板');await click('.el-message-box__btns button','保存')
 await assertUI('模板保存',`return !!one('.qr-tpl__name')`)
 await click('.el-dialog__footer button','保存并记下一笔')
 await assertUI('连续记账保持弹窗并清空金额',`return !!one('.bk-dialog') && one('.qr-amount__input').value===''`)
 await click('.qr-tpl__name');await assertUI('模板回填',`return one('.qr-amount__input').value==='36.50'`)
 await closeDialog();await click('[data-guide="quick-record"]')
 await assertUI('草稿恢复',`return one('.qr-amount__input').value==='36.50'`)
 await click('.qr-tpl__del');await assertUI('模板删除不触发套用',`return !one('.qr-tpl__name') && one('.qr-amount__input').value==='36.50'`)
 await click('.record-tab','收入');await click('.qr-cat__name','工资收入');await input('.qr-amount__input','1200');await click('.el-dialog__footer button','保存并记下一笔')
 await click('.record-tab','转账');await selectField('转入账户','随身现金');await input('.qr-amount__input','100');await audit('transfer-ready',true);await click('.el-dialog__footer button','保存')
 const saved=requests.filter(r=>r.path==='/transaction/saveWithReward').map(r=>JSON.parse(r.body))
 if(!['expense','income','transfer'].every(type=>saved.some(r=>r.type===type)))throw Error('三类模拟记账未全部提交')
 console.log('交互通过 三类模拟记账请求')
 await navigate('transaction');await scrollShot('.tx-row','transaction-list-light')
 await click('.page-head button','批量管理');await assertUI('未选择时批量操作禁用',`return all('.batch-bar button').every(e=>e.disabled)`)
 await click('.tx-row__check input');await click('.batch-bar button','批量改分类');await audit('batch-category',true)
 await click('.bk-dialog .el-cascader');await click('.el-cascader-node__label','交通出行');await click('.el-dialog__footer button','确定')
 if(!requests.some(r=>r.path==='/transaction/batchUpdateCategory'))throw Error('未收到模拟批量分类请求')
 await navigate('settings?menu=category')
 const expanded=await js(`return one('.cat-row button[aria-expanded]')?.getAttribute('aria-expanded')`)
 await click('.cat-row button[aria-expanded]')
 await assertUI('分类展开收起',`return one('.cat-row button[aria-expanded]').getAttribute('aria-expanded')!==${JSON.stringify(expanded)}`)
 await click('.cat-row button[aria-label^="下移"]:not(:disabled)')
 if(!requests.some(r=>r.path==='/category/update'))throw Error('未收到模拟分类排序请求')
 await navigate('budget');const before=await js(`return one('.month-nav__label').textContent`)
 await click('.month-nav [aria-label="上个月"]');await assertUI('预算切换月份',`return one('.month-nav__label').textContent!==${JSON.stringify(before)}`);await click('.month-nav [aria-label="下个月"]')
 await click('.total-card__actions button','编辑');await audit('budget-edit',true);await closeDialog()
 await navigate('settings?menu=data');await click('button','导入流水 CSV');await audit('csv-pick',true)
 await js(`const dt=new DataTransfer();dt.items.add(new File(['日期,类型,金额\\n2026-01-01,支出,10'],'隔离测试.csv',{type:'text/csv'}));const e=document.querySelector('.csv-file');e.files=dt.files;e.dispatchEvent(new Event('change',{bubbles:true}));`)
 await sleep(400);await audit('csv-preview',true);await click('.csv-toolbar .el-radio-button','无法解析');await audit('csv-invalid',true)
 await click('.csv-footer .el-button--primary');await audit('csv-result',true);await assertUI('CSV 三步模拟导入',`return one('.el-result__title')?.textContent.includes('新增 1 条')`);await closeDialog()
 await click('[aria-label="打开消息中心"]');await click('.msg-item__open');await click('.el-dialog__footer button','查看预算管理')
 await assertUI('消息关联预算定位',`return location.hash.startsWith('#/budget') && !!one('.is-focused')`)
 await navigate('level');await scrollShot('[data-guide="lv-checkin"]','checkin-calendar-light')
 await navigate('report');await scrollShot('[data-guide="rp-pie"]','report-pie-light')
 const point=await js(`const r=one('[data-guide="rp-pie"] .chart-box').getBoundingClientRect();return {x:Math.round(r.left+r.width/2+Math.min(r.width,r.height)*0.25),y:Math.round(r.top+r.height*0.44)};`)
 win.webContents.sendInputEvent({type:'mouseMove',...point});win.webContents.sendInputEvent({type:'mouseDown',button:'left',clickCount:1,...point});win.webContents.sendInputEvent({type:'mouseUp',button:'left',clickCount:1,...point});await sleep(700)
 await assertUI('分类饼图钻取流水',`return location.hash.startsWith('#/transaction')`)
 await setup('auto',{backgroundImage:'data:image/svg+xml,'+encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="#84a89b"/></svg>')})
 win.webContents.debugger.attach('1.3')
 for(const scheme of ['dark','light']){
  await win.webContents.debugger.sendCommand('Emulation.setEmulatedMedia',{features:[{name:'prefers-color-scheme',value:scheme}]});await sleep(300)
  await assertUI('跟随系统主题 '+scheme,`return document.documentElement.classList.contains('dark')===${scheme==='dark'}`);await audit('custom-background-'+scheme,true)
 }
 await win.webContents.debugger.sendCommand('Emulation.setEmulatedMedia',{features:[]});win.webContents.debugger.detach()
 for(const [route,label,count]of [['dashboard','界面总览',7],['dashboard','总览页',5],['account','账户页',3],['transaction','流水页',4],['report','报表页',5],['budget','预算页',4],['level','等级页',4],['settings?menu=preference','设置页',4]]){
  await navigate(route);await click('[data-guide="help"]');await click('.help-block__links button',label);await sleep(600)
  await assertUI(label+'引导步骤完整',`return !!one('.el-tour__content') && all('.el-tour-indicator').length===${count}`)
  for(let step=0;step<count;step++){
   await audit('guide-'+label+'-'+(step+1),label==='界面总览'&&step<2)
   await click('.el-tour-buttons button:last-child');await sleep(150)
  }
  await assertUI(label+'引导完成关闭',`return !one('.el-tour__content')`)
 }
 await setup('dark',{screensaverMinutes:0.01});await sleep(5200)
 await assertUI('屏保进入',`return !!one('.screensaver')`);await audit('screensaver',true)
 await js(`window.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))`);await sleep(700)
 await assertUI('屏保按键退出',`return !one('.screensaver')`)
}
run().catch(async error=>{console.error(error);errors.push(String(error));process.exitCode=1;if(win)await snapshot('last-error')}).finally(async()=>{await fs.writeFile(path.join(output,'results.json'),JSON.stringify({checks,interactions,errors,warnings,knownIssues,requests},null,2));if(win)win.destroy();server.close();app.exit(process.exitCode||0)})
