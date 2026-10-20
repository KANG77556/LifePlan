package kr.lifeplan.app;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypted, app-private rolling backups. Encryption key stays in Android Keystore.
 * These snapshots are intentionally not cloud backups and vanish when the app is uninstalled.
 */
public final class BackupVault {
    private static final String TAG="LifePlanBackup";
    private static final String ALIAS="lifeplan_vault_aes_gcm_v1";
    private static final String HEADER="LPB1";
    private static final int IV_SIZE=12;
    private static final int MAX_JSON_CHARS=800000;
    private static final int MAX_FILE_BYTES=2400000;
    private final Activity activity;
    private final Context app;
    private final WebView web;
    private final File folder;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean closed;
    private byte[] lastDigest;
    private volatile boolean forceNewAfterRestore;

    public BackupVault(Activity activity,WebView web) {
        this.activity=activity;this.app=activity.getApplicationContext();this.web=web;
        folder=new File(app.getNoBackupFilesDir(),"encrypted_backups");
        if(!folder.exists()&&!folder.mkdirs())Log.w(TAG,"Backup directory unavailable");
    }
    public void saveAutomatic(String json) {
        if(closed||!valid(json))return;
        worker.execute(()->{
            try {
                byte[] digest=MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
                if(lastDigest!=null&&MessageDigest.isEqual(lastDigest,digest)&&!forceNewAfterRestore)return;
                boolean force=forceNewAfterRestore;
                persist(json,force);
                forceNewAfterRestore=false;
                lastDigest=digest;
                notifyStatus("자동 암호화 백업 완료");
            } catch(Exception e){
                Log.e(TAG,"Encrypted backup failed",e);
                notifyStatus("암호화 백업에 실패했습니다. 저장공간 또는 보안키를 확인하세요.");
            }
        });
    }
    public void listBackups() {
        if(closed)return;
        worker.execute(()->{
            JSONArray list=new JSONArray();
            for(String name:names()) {
                JSONObject o=new JSONObject();
                try{
                    o.put("name",name).put("timestamp",VaultRules.timestamp(name));
                    list.put(o);
                }catch(Exception ignored){}
            }
            String script="window.LifePlanVaultList && window.LifePlanVaultList("+list.toString()+");";
            dispatch(script);
        });
    }
    public void restore(String name,String currentJson) {
        if(closed||!VaultRules.trustedName(name))return;
        worker.execute(()->{
            try {
                File selected=new File(folder,name);
                if(!selected.isFile()||selected.length()>MAX_FILE_BYTES)throw new IOException("No valid backup");
                String decrypted=decrypt(selected);
                if(!valid(decrypted))throw new IOException("Backup content invalid");
                // Preserve the data being replaced as a separate restorable checkpoint.
                if(valid(currentJson))persist(currentJson,true);
                // The subsequent auto-backup of restored data must NOT overwrite the
                // pre-restore checkpoint, even if both were saved within 15 minutes.
                forceNewAfterRestore=true;
                lastDigest=null;
                dispatch("window.LifePlanVaultRestored && window.LifePlanVaultRestored("+JSONObject.quote(decrypted)+");");
            } catch(Exception e){
                Log.e(TAG,"Encrypted restore failed",e);
                notifyStatus("자동 백업 복원 실패: 파일이나 기기 보안키를 확인하세요.");
            }
        });
    }
    public void close() {
        closed=true;
        worker.shutdown(); // queued saves continue, even when Activity is destroyed
    }
    private static boolean valid(String json) {
        if(json==null||json.length()<10||json.length()>MAX_JSON_CHARS)return false;
        try {
            JSONObject x=new JSONObject(json);
            return x.optJSONArray("tasks")!=null && x.optJSONArray("goals")!=null
                && x.optJSONArray("habits")!=null;
        } catch(Exception e){return false;}
    }
    private List<String> names() {
        ArrayList<String> list=new ArrayList<>();
        File[] files=folder.listFiles();
        if(files!=null)for(File f:files)if(f.isFile()&&VaultRules.trustedName(f.getName()))list.add(f.getName());
        return VaultRules.newest(list);
    }
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry stored=ks.getEntry(ALIAS,null);
        if(stored instanceof KeyStore.SecretKeyEntry)return ((KeyStore.SecretKeyEntry)stored).getSecretKey();
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    private void persist(String json,boolean forceNew) throws Exception {
        byte[] raw=json.getBytes(StandardCharsets.UTF_8);
        if(raw.length>MAX_FILE_BYTES)throw new IOException("Data exceeds backup size");
        List<String> existing=names();
        long now=System.currentTimeMillis();
        String target=VaultRules.preferredName(existing,now,forceNew);
        if(forceNew) {
            while(new File(folder,target).exists())target=VaultRules.fileName(++now);
        }
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] iv=cipher.getIV();
        byte[] secret=cipher.doFinal(raw);
        if(iv.length!=IV_SIZE)throw new IOException("Invalid IV");
        File temp=new File(folder,".new-"+System.nanoTime());
        File destination=new File(folder,target);
        try {
            try(FileOutputStream out=new FileOutputStream(temp)) {
                out.write(HEADER.getBytes(StandardCharsets.US_ASCII));
                out.write(iv);
                out.write(secret);
                out.getFD().sync();
            }
            if(!temp.renameTo(destination))throw new IOException("Atomic replacement failed");
        } finally {if(temp.exists())temp.delete();}
        List<String> all=names();
        for(int i=VaultRules.KEEP;i<all.size();i++){
            File old=new File(folder,all.get(i));
            if(!old.delete())Log.w(TAG,"Could not prune old backup");
        }
    }
    private String decrypt(File f) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(FileInputStream in=new FileInputStream(f)){
            byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))!=-1){
                if(out.size()+n>MAX_FILE_BYTES)throw new IOException("Too large");
                out.write(buf,0,n);
            }
        }
        byte[] bytes=out.toByteArray();
        if(bytes.length<HEADER.length()+IV_SIZE+16
                ||!Arrays.equals(Arrays.copyOfRange(bytes,0,4),HEADER.getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("Unknown backup format");
        byte[] iv=Arrays.copyOfRange(bytes,4,4+IV_SIZE);
        byte[] ct=Arrays.copyOfRange(bytes,4+IV_SIZE,bytes.length);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
        return new String(cipher.doFinal(ct),StandardCharsets.UTF_8);
    }
    private void notifyStatus(String status) {
        dispatch("window.LifePlanVaultStatus && window.LifePlanVaultStatus("+JSONObject.quote(status)+");");
    }
    private void dispatch(String script) {
        activity.runOnUiThread(()->{
            if(!activity.isFinishing()&&!activity.isDestroyed()&&web!=null)web.evaluateJavascript(script,null);
        });
    }
}
