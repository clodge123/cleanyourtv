package com.gathin.tvhome.jarvis;

import android.content.Context;
import android.util.Base64;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.KeyGenerationParameters;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.File;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;

/**
 * Stable device identity for the gateway: Ed25519 keypair (BouncyCastle), persisted
 * in app-private storage. deviceId = hex(sha256(raw public key)). Mirrors the
 * ShieldClaw node protocol so the launcher pairs as its own gateway node.
 */
public final class Identity {
    public static final class Keys {
        public final Ed25519PrivateKeyParameters priv;
        public final Ed25519PublicKeyParameters pub;
        public final String deviceId;
        public final byte[] rawPublicKey;
        Keys(Ed25519PrivateKeyParameters p, Ed25519PublicKeyParameters q) {
            priv = p; pub = q; rawPublicKey = q.getEncoded();
            deviceId = hex(sha256(rawPublicKey));
        }
    }

    private static volatile Keys cached;

    public static Keys get(Context ctx) {
        Keys k = cached;
        if (k != null) return k;
        synchronized (Identity.class) {
            if (cached != null) return cached;
            cached = loadOrCreate(ctx);
            return cached;
        }
    }

    private static File keyFile(Context ctx) {
        return new File(ctx.getDir("jarvis_identity", Context.MODE_PRIVATE), "ed25519.key");
    }

    private static Keys loadOrCreate(Context ctx) {
        File f = keyFile(ctx);
        if (f.exists() && f.length() == 32L) {
            try {
                Ed25519PrivateKeyParameters priv =
                        new Ed25519PrivateKeyParameters(readAll(f), 0);
                return new Keys(priv, priv.generatePublicKey());
            } catch (Exception ignored) { }
        }
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        gen.init(new KeyGenerationParameters(new SecureRandom(), 256));
        AsymmetricCipherKeyPair pair = gen.generateKeyPair();
        Ed25519PrivateKeyParameters priv = (Ed25519PrivateKeyParameters) pair.getPrivate();
        f.getParentFile().mkdirs();
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(priv.getEncoded());
            out.close();
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
        } catch (Exception ignored) { }
        return new Keys(priv, (Ed25519PublicKeyParameters) pair.getPublic());
    }

    /** v3 signature payload, byte-compatible with the OpenClaw gateway. */
    public static String buildV3Payload(String deviceId, String clientId, String clientMode,
                                       String role, List<String> scopes, long signedAtMs,
                                       String token, String nonce, String platform,
                                       String deviceFamily) {
        StringBuilder sb = new StringBuilder("v3|").append(deviceId).append('|').append(clientId)
                .append('|').append(clientMode).append('|').append(role).append('|')
                .append(String.join(",", scopes)).append('|').append(signedAtMs).append('|')
                .append(token == null ? "" : token).append('|').append(nonce)
                .append('|').append(platform).append('|').append(deviceFamily);
        return sb.toString();
    }

    public static String sign(Keys k, String payload) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, k.priv);
        byte[] msg = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        signer.update(msg, 0, msg.length);
        return Base64.encodeToString(signer.generateSignature(), Base64.NO_WRAP);
    }

    private static byte[] readAll(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) break;
            off += n;
        }
        in.close();
        return b;
    }

    private static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
