package kr.lifeplan.app;
import android.app.*;
import android.content.*;
import android.os.Build;
import org.json.*;
import java.util.*;

public class ReminderReceiver extends android.content.BroadcastReceiver {
  public static final String CHANNEL="lifeplan_reminders";
  static void channel(Context c) {
    if(Build.VERSION.SDK_INT>=26) {
      NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
      nm.createNotificationChannel(new NotificationChannel(CHANNEL,"라이프플랜 생활관리",NotificationManager.IMPORTANCE_DEFAULT));
    }
  }
  static void schedule(Context c,String json) {
    c.getSharedPreferences("lifeplan_alerts",0).edit().putString("items",json).apply();
    scheduleSaved(c);
  }
  static void scheduleSaved(Context c) {
    channel(c);
    AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
    String json=c.getSharedPreferences("lifeplan_alerts",0).getString("items","[]");
    try {
      JSONArray items=new JSONArray(json);
      for(int i=0;i<items.length();i++) {
        JSONObject t=items.optJSONObject(i);if(t==null)continue;
        int id=t.optString("id","").hashCode();if(id==0)continue;
        Intent intent=new Intent(c,ReminderReceiver.class).setAction("lifeplan.ALERT").putExtra("id",id);
        PendingIntent pi=PendingIntent.getBroadcast(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        Calendar next=nextTime(t);
        if(next!=null)am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.getTimeInMillis(),pi);
      }
    }catch(Exception ignored){}
  }
  static Calendar nextTime(JSONObject t) {
    Calendar now=Calendar.getInstance(),cal=Calendar.getInstance();
    String[] hh=t.optString("time","19:00").split(":");
    if(hh.length!=2)return null;
    try{cal.set(Calendar.HOUR_OF_DAY,Integer.parseInt(hh[0]));cal.set(Calendar.MINUTE,Integer.parseInt(hh[1]));}catch(Exception e){return null;}
    cal.set(Calendar.SECOND,0);cal.set(Calendar.MILLISECOND,0);
    String mode=t.optString("mode","weekly");
    if("weekly".equals(mode)){
      int weekday=t.optInt("weekday",0)+1;
      for(int i=0;i<=7;i++){if(cal.after(now)&&cal.get(Calendar.DAY_OF_WEEK)==weekday)break;cal.add(Calendar.DAY_OF_MONTH,1);}
    }else if("monthly".equals(mode)){
      for(int i=0;i<32;i++){
        int day=Math.min(cal.getActualMaximum(Calendar.DAY_OF_MONTH),Math.max(1,t.optInt("monthDay",1)));
        if(cal.get(Calendar.DAY_OF_MONTH)==day&&cal.after(now))break;
        cal.add(Calendar.DAY_OF_MONTH,1);
      }
    }else{
      String last=t.optString("lastDone","");
      if(!last.isEmpty()){
        try {String[] d=last.split("-");cal.set(Calendar.YEAR,Integer.parseInt(d[0]));cal.set(Calendar.MONTH,Integer.parseInt(d[1])-1);cal.set(Calendar.DAY_OF_MONTH,Integer.parseInt(d[2]));cal.add(Calendar.DAY_OF_MONTH,Math.max(1,t.optInt("days",7)));}catch(Exception ignored){}
      }
      if(!cal.after(now))cal.add(Calendar.DAY_OF_MONTH,1);
    }
    return cal;
  }
  @Override public void onReceive(Context c,Intent intent){
    if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())||Intent.ACTION_TIME_CHANGED.equals(intent.getAction())||Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())){scheduleSaved(c);return;}
    if(!"lifeplan.ALERT".equals(intent.getAction()))return;
    int id=intent.getIntExtra("id",0);String title="반복 생활관리";
    try{
      JSONArray a=new JSONArray(c.getSharedPreferences("lifeplan_alerts",0).getString("items","[]"));
      for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&o.optString("id","").hashCode()==id){title=o.optString("name",title);break;}}
    }catch(Exception ignored){}
    channel(c);
    Intent launch=c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
    PendingIntent open=PendingIntent.getActivity(c,id,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder nb=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,CHANNEL):new Notification.Builder(c);
    nb.setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("라이프플랜 · 생활관리").setContentText(title+" 할 시간입니다.").setAutoCancel(true).setContentIntent(open);
    NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
    try{nm.notify(id,nb.build());}catch(SecurityException ignored){}
    scheduleSaved(c);
  }
}
