package sh.molly.phigrosplusplus.installer;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/** signs apks with apk signature scheme v2 (source.android.com/docs/security/features/apksigning/v2) */
final class Signer {
    private static final int CHUNK = 1 << 20, RSA_PKCS1_SHA256 = 0x0103, V2 = 0x7109871A;
    private static final byte[] SHA256_RSA = {6, 9, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 1, 1, 0x0B};
    private static final byte[] COMMON_NAME = {6, 3, 0x55, 4, 3};
    private final PrivateKey key;
    private final byte[] publicKey, certificate;

    /** with the rsa key kept in file, made on first use */
    Signer(File file) throws IOException, GeneralSecurityException {
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        if (!file.exists()) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            File temporary = new File(file.getPath() + ".new");
            try (OutputStream out = new FileOutputStream(temporary)) {
                out.write(generator.generateKeyPair().getPrivate().getEncoded());
            }
            if (!temporary.renameTo(file)) throw new IOException("can't write " + file);
        }
        byte[] encoded = new byte[(int) file.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            in.readFully(encoded);
        }
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(encoded));
        key = crt;
        publicKey = rsa.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent())).getEncoded();
        certificate = certificate();
    }

    // a self-signed x.509 certificate made only of the key and fixed fields, so the same key always gives the same one
    private byte[] certificate() throws GeneralSecurityException {
        byte[] algorithm = der(0x30, SHA256_RSA, new byte[] {5, 0});
        byte[] name = der(0x30, der(0x31, der(0x30, COMMON_NAME, der(0x0C, "Phigros++".getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = der(0x30, der(0x17, ascii("200101000000Z")), der(0x18, ascii("99991231235959Z")));
        byte[] tbs = der(0x30, der(0x02, new byte[] {1}), algorithm, name, validity, name, publicKey);
        return der(0x30, tbs, algorithm, der(0x03, new byte[] {0}, sign(tbs)));
    }

    private static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] part : parts) content.write(part, 0, part.length);
        int n = content.size(), bytes = n < 0x80 ? 0 : n < 0x100 ? 1 : n < 0x10000 ? 2 : 3;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        out.write(bytes == 0 ? n : 0x80 | bytes);
        for (int i = bytes - 1; i >= 0; i--) out.write(n >> 8 * i);
        out.write(content.toByteArray(), 0, n);
        return out.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] sign(byte[] data) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(data);
        return signature.sign();
    }

    Output output(OutputStream out) throws GeneralSecurityException {
        return new Output(out);
    }

    /**
     * the stream the zip entries of an apk are written to, digested on the way so the apk is written in one pass,
     * then finish adds the signature and the central directory
     */
    final class Output extends OutputStream {
        private final OutputStream out;
        private final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        private final ByteArrayOutputStream digests = new ByteArrayOutputStream();
        private final byte[] chunk = new byte[CHUNK];
        private int used, chunks;

        private Output(OutputStream out) throws GeneralSecurityException {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, CHUNK - used);
                System.arraycopy(b, off, chunk, used, n);
                used += n;
                off += n;
                len -= n;
                if (used == CHUNK) flushChunk();
            }
        }

        private void flushChunk() throws IOException {
            digest(chunk, used);
            out.write(chunk, 0, used);
            used = 0;
        }

        // the apk's digest is over 1 MiB chunks of the entries, the central directory and its end record, each
        // section split on its own
        private void digest(byte[] data, int length) {
            for (int at = 0; at < length; at += CHUNK, chunks++) {
                int n = Math.min(CHUNK, length - at);
                sha256.update((byte) 0xA5);
                sha256.update(u32(n));
                sha256.update(data, at, n);
                byte[] d = sha256.digest();
                digests.write(d, 0, d.length);
            }
        }

        void finish(byte[] central, byte[] end) throws IOException, GeneralSecurityException {
            flushChunk();
            digest(central, central.length);
            digest(end, end.length);
            sha256.update((byte) 0x5A);
            sha256.update(u32(chunks));
            sha256.update(digests.toByteArray());
            byte[] digest = sha256.digest();

            byte[] signed = cat(prefixed(prefixed(cat(u32(RSA_PKCS1_SHA256), prefixed(digest)))),
                prefixed(prefixed(certificate)), prefixed(new byte[0]));
            byte[] signer = cat(prefixed(signed), prefixed(prefixed(cat(u32(RSA_PKCS1_SHA256), prefixed(sign(signed))))),
                prefixed(publicKey));
            byte[] value = prefixed(prefixed(signer));
            long size = 8 + 4 + value.length + 8 + 16;
            ByteBuffer block = Zip.le(8 + (int) size).putLong(size).putLong(4 + value.length).putInt(V2).put(value)
                .putLong(size).put(ascii("APK Sig Block 42"));
            out.write(block.array());
            out.write(central);
            // the central directory now starts after the signing block
            ByteBuffer moved = Zip.le(end.length).put(end);
            out.write(moved.putInt(16, moved.getInt(16) + block.capacity()).array());
            out.flush();
        }
    }

    private static byte[] u32(int n) {
        return Zip.le(4).putInt(n).array();
    }

    private static byte[] prefixed(byte[] data) {
        return cat(u32(data.length), data);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }
}
