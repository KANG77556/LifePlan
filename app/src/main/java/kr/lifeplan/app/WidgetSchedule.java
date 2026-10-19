package kr.lifeplan.app;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Pure schedule calculations for glanceable home-screen widgets. */
public final class WidgetSchedule {
    private WidgetSchedule() {}
    public static final class Slot {
        public final JSONObject task;
        public final int start;
        public final int end;
        Slot(JSONObject task,int start,int end){this.task=task;this.start=start;this.end=end;}
    }
    public static final class Summary {
        public Slot current;
        public Slot next;
        public final List<JSONObject> today=new ArrayList<>();
    }
    private static int weekday(Calendar day){return day.get(Calendar.DAY_OF_WEEK)-1;}
    private static String iso(Calendar day){return new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(day.getTime());}
    static boolean active(JSONObject t,Calendar day){
        if("once".equals(t.optString("repeat")))return iso(day).equals(t.optString("date"));
        org.json.JSONArray a=t.optJSONArray("days");
        if(a!=null){for(int i=0;i<a.length();i++)if(a.optInt(i,-1)==weekday(day))return true;return false;}
        String r=t.optString("repeat","all");int w=weekday(day);
        return "weekday".equals(r)?w>=1&&w<=5:("weekend".equals(r)?w==0||w==6:true);
    }
    public static List<JSONObject> forDate(List<JSONObject> all,org.json.JSONObject specialPlans,Calendar day) {
        String key=iso(day);
        if(specialPlans!=null && specialPlans.has(key)) {
            org.json.JSONObject p=specialPlans.optJSONObject(key);
            ArrayList<JSONObject> dated=new ArrayList<>();
            org.json.JSONArray rows=p==null?null:p.optJSONArray("tasks");
            if(rows!=null)for(int j=0;j<rows.length();j++){
                JSONObject task=rows.optJSONObject(j);
                if(task!=null)dated.add(task);
            }
            return dated;
        }
        ArrayList<JSONObject> regular=new ArrayList<>();
        for(JSONObject t:all)if(active(t,day))regular.add(t);
        return regular;
    }
    public static Summary summarize(List<JSONObject> all,Calendar now) {
        return summarize(all,null,now);
    }
    public static Summary summarize(List<JSONObject> all,org.json.JSONObject specialPlans,Calendar now){
        Summary out=new Summary();
        int current=now.get(Calendar.HOUR_OF_DAY)*60+now.get(Calendar.MINUTE);
        List<Slot> slots=new ArrayList<>();
        for(int offset=-1;offset<=1;offset++){
            Calendar day=(Calendar)now.clone();
            day.add(Calendar.DAY_OF_MONTH,offset);
            for(JSONObject t:forDate(all,specialPlans,day)){
                int from=LifePlanDialRenderer.minute(t.optString("start","")),to=LifePlanDialRenderer.minute(t.optString("end",""));
                int dur=LifePlanDialRenderer.duration(from,to);
                if(dur==0)continue;
                int absolute=offset*1440+from;
                slots.add(new Slot(t,absolute,absolute+dur));
                if(offset==0)out.today.add(t);
            }
        }
        out.today.sort((a,b)->a.optString("start").compareTo(b.optString("start")));
        slots.sort((a,b)->Integer.compare(a.start,b.start));
        for(Slot s:slots){
            if(s.start<=current && current<s.end){
                if(out.current==null || s.start>out.current.start)out.current=s;
            } else if(s.start>current && (out.next==null || s.start<out.next.start))out.next=s;
        }
        return out;
    }
}
