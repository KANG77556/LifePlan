(function(root,factory){
  'use strict';
  const analytics=factory();
  root.LifePlanAnalytics=analytics;
  if(typeof module!=='undefined'&&module.exports)module.exports=analytics;
})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  const DATE=/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/;
  const TIME=/^([01][0-9]|2[0-3]):[0-5][0-9]$/;
  function isoDate(dt){
    return dt.getFullYear()+'-'+String(dt.getMonth()+1).padStart(2,'0')+'-'+String(dt.getDate()).padStart(2,'0');
  }
  function parseDay(value){
    if(typeof value!=='string'||!DATE.test(value))return null;
    const [y,m,d]=value.split('-').map(Number);
    if(y<1970||y>2099)return null;
    const dt=new Date(y,m-1,d);
    return dt.getFullYear()===y&&dt.getMonth()+1===m&&dt.getDate()===d?dt:null;
  }
  function addDay(value,step){
    const dt=parseDay(value);
    if(!dt||!Number.isInteger(step))return null;
    dt.setDate(dt.getDate()+step);
    return isoDate(dt);
  }
  function weekDays(value){
    const dt=parseDay(value);
    if(!dt)return[];
    dt.setDate(dt.getDate()-(dt.getDay()+6)%7);
    const start=isoDate(dt);
    return Array.from({length:7},(_,i)=>addDay(start,i));
  }
  function monthDays(value){
    const dt=parseDay(value);
    if(!dt)return[];
    let y=dt.getFullYear(),m=dt.getMonth(),n=new Date(y,m+1,0).getDate();
    return Array.from({length:n},(_,i)=>isoDate(new Date(y,m,i+1)));
  }
  function taskRow(t){
    if(!t||typeof t!=='object'||(typeof t.id!=='number'&&typeof t.id!=='string'))return null;
    const id=String(t.id);
    if(!id||id.length>80||!TIME.test(t.start||'')||!TIME.test(t.end||'')||t.start===t.end)return null;
    return {
      id,
      name:typeof t.name==='string'?t.name.slice(0,100):'일정',
      start:t.start,end:t.end,
      color:typeof t.color==='string'&&/^#[a-fA-F0-9]{6}$/.test(t.color)?t.color:'#91a6ee',
      completedAt:typeof t.completedAt==='string'&&t.completedAt.length<=40?t.completedAt:null
    };
  }
  function rowsFor(tasks){
    const seen=new Set(),result=[];
    if(!Array.isArray(tasks))return result;
    for(const t of tasks){
      const row=taskRow(t);
      if(!row||seen.has(row.id))continue;
      seen.add(row.id);
      result.push(row);
      if(result.length>=200)break;
    }
    result.sort((a,b)=>a.start.localeCompare(b.start));
    return result;
  }
  function snapshot(tasks,day,stamp){
    if(!parseDay(day))return null;
    const items=rowsFor(tasks);
    for(const item of items){
      const old=tasks.find(t=>String(t.id)===item.id);
      if(old&&old.done===day)item.completedAt='legacy';
    }
    return {capturedAt:typeof stamp==='string'?stamp:'',items};
  }
  function normalizeRecord(record){
    if(!record||typeof record!=='object')return null;
    return {capturedAt:typeof record.capturedAt==='string'?record.capturedAt.slice(0,40):'',
      items:rowsFor(record.items)};
  }
  function normalizeLog(raw){
    const out=Object.create(null);
    if(!raw||typeof raw!=='object'||Array.isArray(raw))return out;
    let count=0;
    for(const [day,record] of Object.entries(raw)){
      if(!parseDay(day))continue;
      const cleaned=normalizeRecord(record);
      if(!cleaned)continue;
      Object.defineProperty(out,day,{value:cleaned,enumerable:true,writable:true,configurable:true});
      if(++count>=3650)break;
    }
    return out;
  }
  // Keep history unchanged when a weekday default is edited later. Only explicitly
  // reconcile an edited calendar day (e.g. today's schedule or a dated exception).
  function reconcile(record,tasks){
    const previous=normalizeRecord(record)||{capturedAt:'',items:[]};
    const byId=new Map(previous.items.map(t=>[t.id,t]));
    const items=rowsFor(tasks).map(t=>({
      ...t,completedAt:byId.has(t.id)?byId.get(t.id).completedAt:t.completedAt
    }));
    return {capturedAt:previous.capturedAt,items};
  }
  function setChecked(record,id,checked,at){
    const norm=normalizeRecord(record);
    if(!norm)return null;
    const row=norm.items.find(t=>t.id===String(id));
    if(!row)return null;
    row.completedAt=checked?(typeof at==='string'&&at?at:new Date().toISOString()):null;
    return norm;
  }
  function dayStats(record){
    const norm=normalizeRecord(record);
    if(!norm)return null;
    const total=norm.items.length,done=norm.items.filter(t=>!!t.completedAt).length;
    return {total,done,rate:total?Math.round(100*done/total):null};
  }
  function periodStats(log,days,today){
    const results=[],validLog=log&&typeof log==='object'?log:{};
    let tracked=0,done=0,total=0;
    for(const day of days||[]){
      if(!parseDay(day))continue;
      if(today&&day>today){results.push({date:day,state:'future',stats:null});continue}
      const stats=dayStats(validLog[day]);
      if(stats){tracked++;done+=stats.done;total+=stats.total}
      results.push({date:day,state:stats?'tracked':'unrecorded',stats});
    }
    return {trackedDays:tracked,done,total,rate:total?Math.round(100*done/total):null,days:results};
  }
  return {isoDate,parseDay,addDay,weekDays,monthDays,rowsFor,snapshot,normalizeRecord,normalizeLog,
    reconcile,setChecked,dayStats,periodStats};
});
