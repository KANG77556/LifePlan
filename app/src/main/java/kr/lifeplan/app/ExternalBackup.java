package kr.lifeplan.app;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.Context;
import android.webkit.WebView;
import android.app.AlertDialog;
import android.widget.EditText;
import android.text.InputType;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Portable encrypted archive in a user-selected Storage Access Framework document. */
public final class ExternalBackup {
  public static final int PICK_TARGET=409, PICK_RESTORE=410;
  private static final String PREF="portable_backup",URI_KEY="target_uri",PWD_KEY="password";
  private final Activity activity;private final WebView web;private final SharedPreferences prefs;
  private final ExecutorService io=Executors.newSingleThreadExecutor();
  private String pendingJson;private String pendingRestoreJson;
  public ExternalBackup(Activity activity,WebView web){this.activity=activity;this.web=web;this.prefs=activity.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
  private void dispatch(String fn,String message){activity.runOnUiThread(()->{if(!activity.isFinishing()&&!activity.isDestroyed())web.evaluateJavascript("window."+fn+" && window."+fn+"("+JSONObject.quote(message)+");",null);});}
  private void status(String value){dispatch("LifePlanExternalBackupStatus",value);}
  private boolean valid(String value){if(value==null||value.length()>800000)return false;try{JSONObject j=new JSONObject(value);return j.optJSONArray("tasks")!=null&&j.optJSONArray("goals")!=null&&j.optJSONArray("habits")!=null;}catch(Exception e){return false;}}
  private char[] storedPassword(){String s=prefs.getString(PWD_KEY,"");return s.isEmpty()?null:s.toCharArray();}
  public void selectDestination(String json){
    if(!valid(json)){status("백업 데이터가 올바르지 않습니다.");return;}
    pendingJson=json;
    EditText field=new EditText(activity);field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);field.setHint("10자 이상 백업 비밀번호");
    new AlertDialog.Builder(activity).setTitle("휴대용 백업 비밀번호").setMessage("앱 삭제 후에도 필요한 비밀번호입니다. 잊으면 복원이 불가능합니다. 기기에 저장되므로 기기 잠금을 사용하세요.").setView(field).setNegativeButton("취소",(d,w)->pendingJson=null).setPositiveButton("저장 위치 선택",(d,w)->{
      char[] pwd=field.getText().toString().toCharArray();if(!PortableArchive.validPassword(pwd)){Arrays.fill(pwd,'\0');status("비밀번호는 10자 이상이어야 합니다.");return;}
      prefs.edit().putString(PWD_KEY,new String(pwd)).apply();Arrays.fill(pwd,'\0');
      Intent pick=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"LifePlan-encrypted.lpbackup").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
      try{activity.startActivityForResult(pick,PICK_TARGET);}catch(Exception ex){status("저장 위치 선택기를 열 수 없습니다.");}
    }).show();
  }
  public void chooseRestore(String currentJson){
    pendingRestoreJson=valid(currentJson)?currentJson:null;
    Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    try{activity.startActivityForResult(pick,PICK_RESTORE);}catch(Exception ex){status("복원 파일 선택기를 열 수 없습니다.");}
  }
  public void onResult(int request,int result,Intent intent){
    if(result!=Activity.RESULT_OK||intent==null||intent.getData()==null)return;
    Uri uri=intent.getData();
    if(request==PICK_TARGET){
      String json=pendingJson;pendingJson=null;if(!valid(json))return;
      try{activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}
      prefs.edit().putString(URI_KEY,uri.toString()).apply();
      saveNow(json);
    } else if(request==PICK_RESTORE){
      try{activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
      EditText password=new EditText(activity);password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
      new AlertDialog.Builder(activity).setTitle("암호화 백업 복원").setMessage("백업 파일을 만들 때 설정한 비밀번호를 입력하세요.").setView(password).setNegativeButton("취소",null).setPositiveButton("복원",(d,w)->{
        char[] pwd=password.getText().toString().toCharArray();io.execute(()->{
          try{
            byte[] input=read(uri);String json=PortableArchive.decrypt(input,pwd);
            if(!valid(json))throw new IllegalArgumentException("Invalid JSON");
            dispatch("LifePlanPortableRestored",json);
          }catch(Exception ex){status("복원 실패: 비밀번호 또는 파일 형식을 확인하세요.");}finally{Arrays.fill(pwd,'\0');}
        });
      }).show();
    }
  }
  private byte[] read(Uri uri)throws Exception{
    try(InputStream in=activity.getContentResolver().openInputStream(uri);ByteArrayOutputStream output=new ByteArrayOutputStream()){
      if(in==null)throw new IllegalArgumentException("Unavailable file");byte[] buf=new byte[8192];int n;
      while((n=in.read(buf))!=-1){if(output.size()+n>PortableArchive.MAX_ARCHIVE_BYTES)throw new IllegalArgumentException("Too large");output.write(buf,0,n);}
      return output.toByteArray();
    }
  }
  public void saveNow(String json){
    if(!valid(json))return;String uri=prefs.getString(URI_KEY,null);char[] password=storedPassword();
    if(uri==null||password==null)return;
    io.execute(()->{try{
      byte[] encrypted=PortableArchive.encrypt(json,password);
      try(OutputStream out=activity.getContentResolver().openOutputStream(Uri.parse(uri),"wt")){if(out==null)throw new IllegalArgumentException("No write access");out.write(encrypted);out.flush();}
      status("외부 암호화 백업 저장 완료");
    }catch(Exception e){status("외부 백업 저장 실패: 저장소 연결과 파일 권한을 확인하세요.");}
    finally{Arrays.fill(password,'\0');}});
  }
  public void info(){status(prefs.contains(URI_KEY)?"외부 백업 위치 연결됨 · 수정 시 암호화 백업 저장":"외부 백업 위치 미설정 · 파일 선택 필요");}
  public void close(){io.shutdown();}
}
