package kr.lifeplan.app;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** No PINs are stored in cleartext. */
public final class PinHash {
    private static final int ITERATIONS=160000;
    private static final int KEY_BITS=256;
    private PinHash() {}
    public static boolean valid(String pin) { return pin!=null && pin.matches("[0-9]{4,8}"); }
    public static String create(String pin) throws Exception {
        if(!valid(pin))throw new IllegalArgumentException("PIN must be 4 to 8 digits");
        byte[] salt=new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(derive(pin,salt));
    }
    public static boolean verify(String stored,String pin) {
        if(!valid(pin)||stored==null)return false;
        String[] parts=stored.split(":",-1);
        if(parts.length!=2)return false;
        try {
            byte[] salt=Base64.getDecoder().decode(parts[0]);
            byte[] actual=Base64.getDecoder().decode(parts[1]);
            if(salt.length!=16 || actual.length!=KEY_BITS/8)return false;
            return MessageDigest.isEqual(actual,derive(pin,salt));
        } catch(Exception e){return false;}
    }
    private static byte[] derive(String pin,byte[] salt) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),salt,ITERATIONS,KEY_BITS);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
}
