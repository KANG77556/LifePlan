(function(root,factory){
  'use strict';
  const api=factory();
  root.LifePlanDiary=api;
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  const MOODS=Object.freeze([
    Object.freeze({id:'great',label:'아주 좋음',emoji:'😄',color:'#49a781'}),
    Object.freeze({id:'good',label:'좋음',emoji:'🙂',color:'#82b68d'}),
    Object.freeze({id:'okay',label:'보통',emoji:'😐',color:'#e5b45e'}),
    Object.freeze({id:'tired',label:'피곤',emoji:'😔',color:'#b49ed9'}),
    Object.freeze({id:'hard',label:'힘듦',emoji:'😣',color:'#df8f96'})
  ]);
  const VALID_MOODS=new Set(MOODS.map(m=>m.id));
  const FULL_DATE=/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/;
  const MONTH=/^[0-9]{4}-[0-9]{2}$/;
  function parseDate(value){
    if(typeof value!=='string'||!FULL_DATE.test(value))return null;
    const [year,month,day]=value.split('-').map(Number);
    if(year<1970||year>2099||month<1||month>12||day<1||day>31)return null;
    const d=new Date(year,month-1,day);
    return d.getFullYear()===year&&d.getMonth()===month-1&&d.getDate()===day?d:null;
  }
  function validMonth(value){
    if(typeof value!=='string'||!MONTH.test(value))return false;
    const year=Number(value.slice(0,4)),month=Number(value.slice(5));
    return year>=1970&&year<=2099&&month>=1&&month<=12;
  }
  function isoDate(d){return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0')}
  function addDay(value,delta){
    const d=parseDate(value);
    if(!d||!Number.isInteger(delta))return null;
    d.setDate(d.getDate()+delta);
    return isoDate(d);
  }
  function safeRecord(input,validateValue,maxLength){
    const output=Object.create(null);
    if(!input||typeof input!=='object'||Array.isArray(input))return output;
    let n=0;
    for(const [key,value] of Object.entries(input)){
      if(!validateValue(key,value))continue;
      Object.defineProperty(output,key,{value:typeof value==='string'?value.trim().slice(0,maxLength):value,
        enumerable:true,writable:true,configurable:true});
      if(++n>=4000)break;
    }
    return output;
  }
  function normalizeMoods(input){
    return safeRecord(input,(day,value)=>!!parseDate(day)&&VALID_MOODS.has(value),10);
  }
  function normalizeMonthlyReviews(input){
    return safeRecord(input,(month,value)=>validMonth(month)&&typeof value==='string'&&!!value.trim(),500);
  }
  function moodOf(id){return MOODS.find(m=>m.id===id)||null}
  function documentedDays(entries,moods){
    const days=new Set();
    if(entries&&typeof entries==='object'&&!Array.isArray(entries)){
      for(const [day,entry] of Object.entries(entries)){
        if(parseDate(day)&&typeof entry==='string'&&!!entry.trim())days.add(day);
      }
    }
    if(moods&&typeof moods==='object'&&!Array.isArray(moods)){
      for(const [day,id] of Object.entries(moods)){
        if(parseDate(day)&&VALID_MOODS.has(id))days.add(day);
      }
    }
    return Array.from(days).sort();
  }
  function currentStreak(entries,moods,today){
    if(!parseDate(today))return 0;
    const recorded=new Set(documentedDays(entries,moods));
    let day=recorded.has(today)?today:addDay(today,-1);
    let streak=0;
    while(day&&recorded.has(day)&&streak<4000){
      streak++;day=addDay(day,-1);
    }
    return streak;
  }
  function monthSummary(entries,moods,month,today){
    if(!validMonth(month))return null;
    const moodInput=normalizeMoods(moods);
    const days=documentedDays(entries,moodInput)
      .filter(d=>d.startsWith(month+'-')&&(!today||d<=today));
    const records=days.map(day=>({
      day,
      text:typeof entries?.[day]==='string'?entries[day].trim():'',
      mood:moodOf(moodInput[day])
    }));
    const distribution=MOODS.map(m=>({
      ...m,count:records.filter(r=>r.mood?.id===m.id).length
    }));
    const textDays=records.filter(r=>!!r.text).length,moodDays=records.filter(r=>!!r.mood).length;
    const highest=Math.max(0,...distribution.map(m=>m.count));
    const topMoods=highest>0?distribution.filter(m=>m.count===highest).map(m=>m.label):[];
    const [year,mon]=month.split('-').map(Number);
    const lastDate=new Date(year,mon,0).getDate();
    const maxDays=today&&today.startsWith(month+'-')?Math.min(lastDate,Number(today.slice(-2))):lastDate;
    return {month,recordedDays:records.length,textDays,moodDays,distribution,topMoods,
      daysInPeriod:maxDays,consistency:maxDays?Math.round(records.length/maxDays*100):0,records};
  }
  return {MOODS,parseDate,validMonth,isoDate,addDay,normalizeMoods,normalizeMonthlyReviews,
    moodOf,documentedDays,currentStreak,monthSummary};
});
