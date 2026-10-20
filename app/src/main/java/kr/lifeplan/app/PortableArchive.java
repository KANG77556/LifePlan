package kr.lifeplan.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Portable, password-protected .lpbackup format. Unlike AndroidKeyStore snapshots,
 * it can be decrypted after reinstall or on another Android device.
 *
 * Format: "LPE2" | salt(16) | iv(12) | AES-256-GCM ciphertext+tag.
 * All writes use fresh randomness; no PIN or platform key is embedded in the file.
 */
public final class PortableArchive {
    private static final byte[] HEADER="LPE2".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_LENGTH=16,IV_LENGTH=12,TAG_BITS=128;
    private static final int PBKDF2_ROUNDS=220000;
    public static final int MAX_JSON_BYTES=2_400_000,MAX_ARCHIVE_BYTES=MAX_JSON_BYTES+1024;
    private static final SecureRandom RANDOM=new SecureRandom();

    private PortableArchive() {}
    public static boolean validPassword(char[] password) {
        return password!=null&&password.length>=10&&password.length<=128;
    }
    private static SecretKeySpec derive(char[] password,byte[] salt) throws Exception {
        if(!validPassword(password))throw new IllegalArgumentException("Passphrase needs at least 10 characters");
        PBEKeySpec spec=new PBEKeySpec(password,salt,PBKDF2_ROUNDS,256);
        try {return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),"AES");}
        finally {spec.clearPassword();}
    }
    public static byte[] encrypt(String json,char[] password) throws Exception {
        if(json==null||!validPassword(password))throw new IllegalArgumentException("Invalid backup input");
        byte[] raw=json.getBytes(StandardCharsets.UTF_8);
        if(raw.length==0||raw.length>MAX_JSON_BYTES)throw new IOException("Backup too large");
        byte[] salt=new byte[SALT_LENGTH],iv=new byte[IV_LENGTH];
        RANDOM.nextBytes(salt);RANDOM.nextBytes(iv);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,derive(password,salt),new GCMParameterSpec(TAG_BITS,iv));
        cipher.updateAAD(HEADER);
        byte[] ciphertext=cipher.doFinal(raw);
        byte[] result=new byte[HEADER.length+SALT_LENGTH+IV_LENGTH+ciphertext.length];
        int pos=0;
        System.arraycopy(HEADER,0,result,pos,HEADER.length);pos+=HEADER.length;
        System.arraycopy(salt,0,result,pos,SALT_LENGTH);pos+=SALT_LENGTH;
        System.arraycopy(iv,0,result,pos,IV_LENGTH);pos+=IV_LENGTH;
        System.arraycopy(ciphertext,0,result,pos,ciphertext.length);
        Arrays.fill(raw,(byte)0);
        return result;
    }
    public static String decrypt(byte[] input,char[] password) throws Exception {
        if(input==null||input.length<HEADER.length+SALT_LENGTH+IV_LENGTH+16||
            input.length>MAX_ARCHIVE_BYTES||!validPassword(password))throw new IOException("Invalid encrypted archive");
        for(int i=0;i<HEADER.length;i++)if(input[i]!=HEADER[i])throw new IOException("Unsupported archive version");
        byte[] salt=Arrays.copyOfRange(input,HEADER.length,HEADER.length+SALT_LENGTH);
        byte[] iv=Arrays.copyOfRange(input,HEADER.length+SALT_LENGTH,HEADER.length+SALT_LENGTH+IV_LENGTH);
        byte[] cipherBytes=Arrays.copyOfRange(input,HEADER.length+SALT_LENGTH+IV_LENGTH,input.length);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,derive(password,salt),new GCMParameterSpec(TAG_BITS,iv));
        cipher.updateAAD(HEADER);
        byte[] raw=cipher.doFinal(cipherBytes); // AEADBadTagException on wrong password/corruption
        try{return new String(raw,StandardCharsets.UTF_8);}
        finally {Arrays.fill(raw,(byte)0);}
    }
}
