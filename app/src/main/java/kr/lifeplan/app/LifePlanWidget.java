package kr.lifeplan.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/** Two lightweight Android home widgets. Data is synced from the existing WebView local DB. */
public final class LifePlanWidget extends AppWidgetProvider {
    public static final String PREFS = "lifeplan_widgets";
    private static final String SNAPSHOT = "snapshot";
    public static final String ACTION_REFRESH = "kr.lifeplan.app.WIDGET_REFRESH";
    public static final String ACTION_OPEN = "kr.lifeplan.app.WIDGET_OPEN";

    public static class Dial extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { LifePlanWidget.updateAll(c); }
        @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, android.os.Bundle opts) { LifePlanWidget.updateAll(c); }
        @Override public void onReceive(Context c, Intent i) {
            super.onReceive(c, i);
            if (ACTION_REFRESH.equals(i.getAction()) || Intent.ACTION_DATE_CHANGED.equals(i.getAction())
                    || Intent.ACTION_TIME_CHANGED.equals(i.getAction()) || Intent.ACTION_TIMEZONE_CHANGED.equals(i.getAction())) LifePlanWidget.updateAll(c);
        }
    }
    public static class Recurring extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { LifePlanWidget.updateAll(c); }
        @Override public void onReceive(Context c, Intent i) {
            super.onReceive(c, i);
            if (ACTION_REFRESH.equals(i.getAction()) || Intent.ACTION_DATE_CHANGED.equals(i.getAction())
                    || Intent.ACTION_TIME_CHANGED.equals(i.getAction()) || Intent.ACTION_TIMEZONE_CHANGED.equals(i.getAction())) LifePlanWidget.updateAll(c);
        }
    }

    public static void sync(Context c, String json) {
        if (json == null || json.length() > 1500000) return;
        try {
            JSONObject data = new JSONObject(json);
            if (data.optJSONArray("tasks") == null || data.optJSONArray("recurring") == null) return;
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SNAPSHOT, json).apply();
            updateAll(c);
        } catch (Exception ignored) { }
    }

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { updateAll(c); }
    @Override public void onEnabled(Context c) { updateAll(c); }
    @Override public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (ACTION_REFRESH.equals(i.getAction()) || Intent.ACTION_DATE_CHANGED.equals(i.getAction())
                || Intent.ACTION_TIME_CHANGED.equals(i.getAction()) || Intent.ACTION_TIMEZONE_CHANGED.equals(i.getAction())) updateAll(c);
    }

    private static PendingIntent open(Context c, String page, int requestCode) {
        Intent i = new Intent(c, MainActivity.class);
        i.setAction(ACTION_OPEN);
        i.putExtra("page", page);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, requestCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static boolean active(JSONObject t, int weekday, String today) {
        if ("once".equals(t.optString("repeat"))) return today.equals(t.optString("date"));
        JSONArray days = t.optJSONArray("days");
        if (days != null) {
            for (int j = 0; j < days.length(); j++) if (days.optInt(j, -1) == weekday) return true;
            return false;
        }
        String rep = t.optString("repeat", "all");
        return !"weekday".equals(rep) && !"weekend".equals(rep)
            || "weekday".equals(rep) && weekday >= 1 && weekday <= 5
            || "weekend".equals(rep) && (weekday == 0 || weekday == 6);
    }

    private static String iso(Calendar c) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime()); }

    private static boolean due(JSONObject t, Calendar now, String today) {
        String created = t.optString("createdAt", today), last = t.optString("lastDone", "");
        String mode = t.optString("mode", "weekly");
        String scheduled = today;
        if ("after".equals(mode)) {
            if (!last.isEmpty()) {
                Calendar d = Calendar.getInstance();
                try {
                    d.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(last));
                    d.add(Calendar.DAY_OF_YEAR, Math.max(1, t.optInt("days",7)));
                    scheduled = iso(d);
                } catch (Exception e) { return false; }
            } else scheduled = created;
        } else if ("weekly".equals(mode)) {
            Calendar d=(Calendar)now.clone();
            d.add(Calendar.DAY_OF_MONTH,-(now.get(Calendar.DAY_OF_WEEK)-1-t.optInt("weekday",0)+7)%7);
            scheduled=iso(d);
        } else if ("monthly".equals(mode)) {
            Calendar d=(Calendar)now.clone();
            d.set(Calendar.DAY_OF_MONTH,Math.min(d.getActualMaximum(Calendar.DAY_OF_MONTH), Math.max(1,t.optInt("monthDay",1))));
            if(d.after(now)) {
                d.add(Calendar.MONTH,-1);
                d.set(Calendar.DAY_OF_MONTH,Math.min(d.getActualMaximum(Calendar.DAY_OF_MONTH),Math.max(1,t.optInt("monthDay",1))));
            }
            scheduled=iso(d);
        }
        return scheduled.compareTo(today)<=0 && scheduled.compareTo(created)>=0 &&
            (last.isEmpty() || last.compareTo(scheduled)<0);
    }

    public static void updateAll(Context c) {
        AppWidgetManager m=AppWidgetManager.getInstance(c);
        int[] ids=m.getAppWidgetIds(new ComponentName(c,LifePlanWidget.class));
        int[] recurringIds=m.getAppWidgetIds(new ComponentName(c,Recurring.class));
        int[] dialIds=m.getAppWidgetIds(new ComponentName(c,Dial.class));
        if(ids.length==0 && recurringIds.length==0 && dialIds.length==0)return;
        JSONObject data;
        try {data=new JSONObject(c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(SNAPSHOT,"{}"));}catch(Exception e){data=new JSONObject();}
        Calendar now=Calendar.getInstance();
        String today=iso(now);
        int weekday=now.get(Calendar.DAY_OF_WEEK)-1,minute=now.get(Calendar.HOUR_OF_DAY)*60+now.get(Calendar.MINUTE);
        ArrayList<JSONObject> tasks=new ArrayList<>();
        JSONArray source=data.optJSONArray("tasks");
        org.json.JSONObject specials=data.optJSONObject("specialPlans");
        ArrayList<JSONObject> allTasks=new ArrayList<>();
        if(source!=null)for(int i=0;i<source.length();i++){
            JSONObject t=source.optJSONObject(i);
            if(t!=null)allTasks.add(t);
        }
        for(JSONObject t:WidgetSchedule.forDate(allTasks,specials,now)){
            if(!t.optString("start").equals(t.optString("end")))tasks.add(t);
        }
        Collections.sort(tasks,Comparator.comparing(t->t.optString("start","99:99")));
        ArrayList<JSONObject> dueItems=new ArrayList<>();
        JSONArray rec=data.optJSONArray("recurring");
        if(rec!=null)for(int i=0;i<rec.length();i++) {
            JSONObject t=rec.optJSONObject(i);
            if(t!=null && due(t,now,today))dueItems.add(t);
        }
        String dateLabel=new SimpleDateFormat("M월 d일 EEEE",Locale.KOREAN).format(now.getTime());
        if(dialIds.length>0) {
            WidgetSchedule.Summary summary=WidgetSchedule.summarize(allTasks,specials,now);
            android.graphics.Bitmap dial=LifePlanDialRenderer.render(now,tasks);
            for(int id:dialIds) {
                RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.widget_dial);
                v.setTextViewText(R.id.widget_dial_count,"일정 "+tasks.size()+"개");
                v.setTextViewText(R.id.widget_dial_date,dateLabel);
                v.setImageViewBitmap(R.id.widget_dial_image,dial);
                v.setTextViewText(R.id.widget_dial_current,summary.current==null?"현재 일정 없음":summary.current.task.optString("name"));
                v.setTextViewText(R.id.widget_dial_current_time,summary.current==null?"":summary.current.task.optString("start")+"–"+summary.current.task.optString("end"));
                v.setTextViewText(R.id.widget_dial_next,summary.next==null?"다음 일정 없음":summary.next.task.optString("name"));
                v.setTextViewText(R.id.widget_dial_next_time,summary.next==null?"":summary.next.task.optString("start")+"–"+summary.next.task.optString("end"));
                int[] rows={R.id.widget_dial_row1,R.id.widget_dial_row2,R.id.widget_dial_row3,R.id.widget_dial_row4};
                for(int row=0;row<rows.length;row++) {
                    if(row<summary.today.size()) {
                        JSONObject t=summary.today.get(row);
                        v.setTextViewText(rows[row],t.optString("name")+"   "+t.optString("start")+"–"+t.optString("end"));
                    } else v.setTextViewText(rows[row],row==0?"오늘 일정 없음":"");
                }
                if(summary.today.size()>4)v.setTextViewText(R.id.widget_dial_footer,"외 "+(summary.today.size()-4)+"개 · 탭하여 일정 열기");
                int h=m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,0);
                if(h==0)h=m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,0);
                v.setViewVisibility(R.id.widget_dial_agenda,h>=310?android.view.View.VISIBLE:android.view.View.GONE);
                v.setOnClickPendingIntent(R.id.widget_dial_root,open(c,"daily",102));
                m.updateAppWidget(id,v);
            }
        }
        for(int id:ids){
            RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.widget_today);
            v.setTextViewText(R.id.widget_date,dateLabel);
            v.setTextViewText(R.id.widget_today_count,"일정 "+tasks.size()+"개");
            for(int row=0;row<3;row++) {
                int name=row==0?R.id.widget_task1:row==1?R.id.widget_task2:R.id.widget_task3;
                if(row<tasks.size()){
                    JSONObject t=tasks.get(row);
                    String prefix=t.optString("start")+"–"+t.optString("end")+"   ";
                    v.setTextViewText(name,prefix+t.optString("name"));
                } else if(row==0) v.setTextViewText(name,"오늘 등록된 일정이 없습니다.");
                else v.setTextViewText(name,"");
            }
            v.setTextViewText(R.id.widget_more,tasks.size()>3?"외 "+(tasks.size()-3)+"개 일정 · 앱에서 보기":"일정 보기");
            v.setOnClickPendingIntent(R.id.widget_today_root,open(c,"daily",100));
            m.updateAppWidget(id,v);
        }
        for(int id:recurringIds){
            RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.widget_recurring);
            v.setTextViewText(R.id.widget_recurring_date,dateLabel);
            v.setTextViewText(R.id.widget_due_count,dueItems.size()+"개 미완료");
            v.setTextViewText(R.id.widget_due1,dueItems.size()>0?"• "+dueItems.get(0).optString("name"):"확인할 일이 없습니다.");
            v.setTextViewText(R.id.widget_due2,dueItems.size()>1?"• "+dueItems.get(1).optString("name"):"");
            v.setTextViewText(R.id.widget_due3,dueItems.size()>2?"• "+dueItems.get(2).optString("name"):"");
            v.setOnClickPendingIntent(R.id.widget_recurring_root,open(c,"recurring",101));
            m.updateAppWidget(id,v);
        }
    }
}
