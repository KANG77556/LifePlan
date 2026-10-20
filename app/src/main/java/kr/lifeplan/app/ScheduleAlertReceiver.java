package kr.lifeplan.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;

public final class ScheduleAlertReceiver extends BroadcastReceiver {
    private static final String PREF="lifeplan_schedule_alerts";
    private static final String ACTION="kr.lifeplan.app.SCHEDULE_ALERT";
    private static final String CHANNEL="lifeplan_schedule_start";
    static void sync(Context c,String json){
        if(json==null||json.length()>1500000)return;
        try{
            JSONObject src=new JSONObject(json);
            int lead=src.optInt("lead",0);
            if(lead!=0&&lead!=5&&lead!=10&&lead!=30)return;
            if(src.optJSONArray("tasks")==null)return;
            c.getSharedPreferences(PREF,0).edit().putString("payload",json).apply();
            schedule(c);
        }catch(Exception ignored){}
    }
    static void schedule(Context c){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        android.content.SharedPreferences pref=c.getSharedPreferences(PREF,0);
        HashSet<Integer> old=new HashSet<>();
        String saved=pref.getString("ids","");
        for(String x:saved.split(","))try{old.add(Integer.parseInt(x));}catch(Exception ignored){}
        for(int id:old){
            PendingIntent pi=pending(c,id,null,0);
            am.cancel(pi);pi.cancel();
        }
        HashSet<Integer> ids=new HashSet<>();
        try{
            JSONObject data=new JSONObject(pref.getString("payload","{}"));
            int lead=data.optInt("lead",0);
            if(lead==0){pref.edit().putString("ids","").apply();return;}
            List<JSONObject> all=new ArrayList<>();
            JSONArray tasks=data.optJSONArray("tasks");
            if(tasks!=null)for(int i=0;i<tasks.length();i++){JSONObject t=tasks.optJSONObject(i);if(t!=null)all.add(t);}
            JSONObject special=data.optJSONObject("specialPlans");
            Calendar now=Calendar.getInstance();
            int planned=0;
            for(int offset=0;offset<8;offset++){
                Calendar day=(Calendar)now.clone();
                day.add(Calendar.DATE,offset);
                for(JSONObject t:WidgetSchedule.forDate(all,special,day)){
                    int start=LifePlanDialRenderer.minute(t.optString("start",""));
                    if(start<0)continue;
                    Calendar fire=(Calendar)day.clone();
                    fire.set(Calendar.HOUR_OF_DAY,start/60);fire.set(Calendar.MINUTE,start%60);fire.set(Calendar.SECOND,0);fire.set(Calendar.MILLISECOND,0);
                    fire.add(Calendar.MINUTE,-lead);
                    if(!fire.after(now))continue;
                    String date=new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(day.getTime());
                    String title=t.optString("name","");
                    if(title.isEmpty())continue;
                    int id=("schedule:"+date+":"+t.optString("id","")+":"+start).hashCode();
                    if(ids.contains(id))continue;
                    ids.add(id);
                    PendingIntent pi=pending(c,id,title,lead);
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,fire.getTimeInMillis(),pi);
                    if(++planned>=100)break;
                }
                if(planned>=100)break;
            }
            StringBuilder out=new StringBuilder();
            for(int id:ids){if(out.length()>0)out.append(',');out.append(id);}
            pref.edit().putString("ids",out.toString()).apply();
        }catch(Exception ignored){}
    }
    private static PendingIntent pending(Context c,int id,String title,int lead){
        Intent i=new Intent(c,ScheduleAlertReceiver.class).setAction(ACTION).putExtra("title",title).putExtra("lead",lead);
        return PendingIntent.getBroadcast(c,id,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public void onReceive(Context c,Intent i){
        String action=i.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_TIME_CHANGED.equals(action)||Intent.ACTION_TIMEZONE_CHANGED.equals(action)){schedule(c);return;}
        if(!ACTION.equals(action))return;
        String title=i.getStringExtra("title");
        if(title==null||title.isEmpty())return;
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(CHANNEL,"라이프플랜 일정 시작",NotificationManager.IMPORTANCE_DEFAULT));
        int lead=i.getIntExtra("lead",10);
        Intent launch=new Intent(c,MainActivity.class);
        PendingIntent open=PendingIntent.getActivity(c,990,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,CHANNEL):new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("라이프플랜 · 다음 일정")
         .setContentText(title+(lead>0?" 시작 "+lead+"분 전입니다.":" 시작할 시간입니다."))
         .setContentIntent(open).setAutoCancel(true);
        try{nm.notify((title+System.currentTimeMillis()).hashCode(),b.build());}catch(SecurityException ignored){}
        schedule(c);
    }
}
