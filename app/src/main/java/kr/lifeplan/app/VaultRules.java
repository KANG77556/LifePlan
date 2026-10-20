package kr.lifeplan.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Filename validation and bounded chronological snapshots, free from Android dependencies. */
public final class VaultRules {
    public static final int KEEP=7;
    public static final long REPLACE_WINDOW_MILLIS=15L*60L*1000L;
    private static final Pattern FILE=Pattern.compile("^backup-[0-9]{13}\\.lpb$");
    private VaultRules(){}
    public static boolean trustedName(String name){return name!=null&&FILE.matcher(name).matches();}
    public static String fileName(long stamp){return "backup-"+stamp+".lpb";}
    public static long timestamp(String name){
        if(!trustedName(name))return -1;
        try{return Long.parseLong(name.substring(7,20));}catch(Exception e){return -1;}
    }
    public static List<String> newest(List<String> filenames){
        ArrayList<String> names=new ArrayList<>();
        if(filenames!=null)for(String name:filenames)if(trustedName(name))names.add(name);
        names.sort(Collections.reverseOrder());
        return names;
    }
    public static String preferredName(List<String> filenames,long current,boolean forceNew){
        List<String> names=newest(filenames);
        if(!forceNew&&!names.isEmpty()){
            long last=timestamp(names.get(0));
            if(last>0 && last<=current && current-last<REPLACE_WINDOW_MILLIS)return names.get(0);
        }
        return fileName(current);
    }
}
