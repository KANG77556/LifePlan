package kr.lifeplan.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.function.Consumer;

/** Activity access gate. PIN never crosses WebView / JS bridge. */
public final class PrivacyLock {
    private static final String PREF="lifeplan_privacy";
    private static final String PIN="pin_hash";
    private static final String TRIES="failed_attempts";
    private static final String UNTIL="locked_until";
    private final Activity activity;
    private final WebView web;
    private final SharedPreferences preferences;
    private boolean unlocked;
    private AlertDialog lockDialog;

    public PrivacyLock(Activity activity,WebView web) {
        this.activity=activity;
        this.web=web;
        preferences=activity.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    public boolean enabled(){return !preferences.getString(PIN,"").isEmpty();}
    public void initialize() {
        applySecurity();
        unlocked=!enabled();
        if(enabled())web.setVisibility(View.INVISIBLE);
    }
    private void applySecurity(){
        if(enabled())activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    public void onResume(){
        applySecurity();
        if(enabled()&&!unlocked){
            web.setVisibility(View.INVISIBLE);
            showUnlock();
        } else web.setVisibility(View.VISIBLE);
    }
    public void onPause(){
        if(enabled()){
            unlocked=false;
            web.setVisibility(View.INVISIBLE);
            if(lockDialog!=null)lockDialog.dismiss();
            lockDialog=null;
        }
    }
    public void onDestroy(){
        if(lockDialog!=null)lockDialog.dismiss();
        lockDialog=null;
    }
    public void showSettings(){
        if(enabled()&&!unlocked){
            showUnlock();
            return;
        }
        if(!enabled()) {
            newPin();
            return;
        }
        new AlertDialog.Builder(activity).setTitle("라이프플랜 앱 잠금")
            .setMessage("PIN은 앱을 다시 열 때 필요합니다. 잠금 설정 시 화면 캡처도 제한됩니다.")
            .setItems(new String[]{"PIN 변경","PIN 잠금 해제"},(dialog,which)->{
                requireOldPin(which==0?"기존 PIN 확인 · 변경":"기존 PIN 확인 · 잠금 해제",
                    ()->{
                        if(which==0)newPin();
                        else new AlertDialog.Builder(activity).setTitle("PIN 잠금 해제")
                            .setMessage("앱 잠금을 해제할까요? 홈 화면 위젯 내용에는 영향을 주지 않습니다.")
                            .setNegativeButton("취소",null)
                            .setPositiveButton("해제",(d,w)->{
                                preferences.edit().remove(PIN).remove(TRIES).remove(UNTIL).apply();
                                unlocked=true;applySecurity();sendStatus();
                            }).show();
                    });
            }).setNegativeButton("닫기",null).show();
    }
    private EditText pinInput(){
        EditText field=new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setHint("숫자 4~8자리");
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
        field.setPadding(35,26,35,26);
        return field;
    }
    private void newPin(){
        EditText first=pinInput();
        new AlertDialog.Builder(activity).setTitle("새 PIN 설정")
            .setMessage("숫자 4~8자리로 앱 잠금 PIN을 설정합니다. PIN을 잊으면 앱 내 잠금을 해제하기 어려울 수 있으므로 기억해 두세요.")
            .setView(first)
            .setNegativeButton("취소",null)
            .setPositiveButton("다음",(d,w)->{
                String pin=first.getText().toString();
                if(!PinHash.valid(pin)){toast("PIN은 숫자 4~8자리로 설정하세요.");return;}
                EditText again=pinInput();
                new AlertDialog.Builder(activity).setTitle("PIN 다시 입력")
                    .setMessage("설정할 PIN을 다시 입력하세요.")
                    .setView(again).setNegativeButton("취소",null)
                    .setPositiveButton("설정",(d2,w2)->{
                        if(!pin.equals(again.getText().toString())){
                            toast("PIN이 일치하지 않습니다. 다시 설정하세요.");return;
                        }
                        try{
                            String hash=PinHash.create(pin);
                            preferences.edit().putString(PIN,hash).remove(TRIES).remove(UNTIL).apply();
                            unlocked=true;applySecurity();sendStatus();
                            toast("PIN 잠금을 설정했습니다.");
                        }catch(Exception ex){toast("PIN을 저장하지 못했습니다.");}
                    }).show();
            }).show();
    }
    private boolean throttled(){
        long until=preferences.getLong(UNTIL,0L);
        if(until>System.currentTimeMillis()){
            long remaining=(until-System.currentTimeMillis()+999)/1000;
            toast("PIN 입력 제한 중입니다. "+remaining+"초 후 다시 시도하세요.");
            return true;
        }
        return false;
    }
    private boolean verify(String pin){
        if(throttled())return false;
        boolean ok=PinHash.verify(preferences.getString(PIN,""),pin);
        if(ok){
            preferences.edit().remove(TRIES).remove(UNTIL).apply();
            return true;
        }
        int failures=preferences.getInt(TRIES,0)+1;
        SharedPreferences.Editor editor=preferences.edit().putInt(TRIES,failures);
        if(failures>=5)editor.putInt(TRIES,0).putLong(UNTIL,System.currentTimeMillis()+60000L);
        editor.apply();
        toast(failures>=5?"5회 오류로 1분간 입력이 제한됩니다.":"PIN이 일치하지 않습니다.");
        return false;
    }
    private void requireOldPin(String title,Runnable success){
        if(throttled())return;
        EditText field=pinInput();
        new AlertDialog.Builder(activity).setTitle(title).setView(field)
            .setNegativeButton("취소",null)
            .setPositiveButton("확인",(d,w)->{
                if(verify(field.getText().toString()))success.run();
            }).show();
    }
    private void showUnlock(){
        if(!enabled()||unlocked||activity.isFinishing()||lockDialog!=null)return;
        if(throttled()){
            long remaining=Math.max(1000L,preferences.getLong(UNTIL,0L)-System.currentTimeMillis()+300L);
            web.postDelayed(this::showUnlock,remaining);
            return;
        }
        EditText field=pinInput();
        lockDialog=new AlertDialog.Builder(activity).setTitle("라이프플랜 잠금")
            .setMessage("개인 기록을 보려면 PIN을 입력하세요.")
            .setView(field)
            .setPositiveButton("잠금 해제",(d,w)->{
                lockDialog=null;
                if(verify(field.getText().toString())){
                    unlocked=true;
                    web.setVisibility(View.VISIBLE);
                    sendStatus();
                }else{
                    web.postDelayed(this::showUnlock,450);
                }
            }).create();
        lockDialog.setCancelable(false);
        lockDialog.setCanceledOnTouchOutside(false);
        lockDialog.show();
    }
    public void sendStatus(){
        web.evaluateJavascript("window.LifePlanPrivacyStatus && window.LifePlanPrivacyStatus("+enabled()+");",null);
    }
    private void toast(String text){Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
}
