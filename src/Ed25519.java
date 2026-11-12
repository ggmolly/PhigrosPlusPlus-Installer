package sh.molly.phigrosplusplus.installer;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * ed25519 signature checking, after rfc 8032's reference code: android only has it from android 13. slow but simple,
 * it checks one release manifest
 */
final class Ed25519 {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(inverse(BigInteger.valueOf(121666))).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger[] BASE = point(BigInteger.valueOf(4).multiply(inverse(BigInteger.valueOf(5))).mod(P), false);
    private static final BigInteger[] ZERO = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};

    /** whether signature (64 bytes) signs message for the 32 byte public key */
    static boolean verify(byte[] key, byte[] message, byte[] signature) {
        if (key.length != 32 || signature.length != 64) return false;
        BigInteger[] a = decode(key), r = decode(Arrays.copyOf(signature, 32));
        BigInteger s = little(Arrays.copyOfRange(signature, 32, 64));
        if (a == null || r == null || s.compareTo(L) >= 0) return false;
        BigInteger h;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-512");
            sha.update(signature, 0, 32);
            sha.update(key);
            h = little(sha.digest(message)).mod(L);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return same(times(s, BASE), add(r, times(h, a)));
    }

    private static BigInteger inverse(BigInteger x) {
        return x.modPow(P.subtract(BigInteger.valueOf(2)), P);
    }

    private static BigInteger little(byte[] bytes) {
        byte[] big = new byte[bytes.length + 1];
        for (int i = 0; i < bytes.length; i++) big[bytes.length - i] = bytes[i];
        return new BigInteger(big);
    }

    // a point in extended coordinates (x, y, z, t), x / z and y / z being the affine ones and t = x * y / z
    private static BigInteger[] point(BigInteger y, boolean odd) {
        BigInteger yy = y.multiply(y);
        BigInteger xx = yy.subtract(BigInteger.ONE).multiply(inverse(D.multiply(yy).add(BigInteger.ONE))).mod(P);
        if (xx.signum() == 0) return odd ? null : new BigInteger[] {BigInteger.ZERO, y, BigInteger.ONE, BigInteger.ZERO};
        BigInteger x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) x = x.multiply(SQRT_M1).mod(P);
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) return null;
        if (x.testBit(0) != odd) x = P.subtract(x);
        return new BigInteger[] {x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    // 32 bytes: y little endian, its top bit being whether x is odd
    private static BigInteger[] decode(byte[] bytes) {
        boolean odd = (bytes[31] & 0x80) != 0;
        byte[] copy = bytes.clone();
        copy[31] &= 0x7F;
        BigInteger y = little(copy);
        return y.compareTo(P) >= 0 ? null : point(y, odd);
    }

    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
        BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
        BigInteger c = p[3].multiply(D).shiftLeft(1).multiply(q[3]).mod(P);
        BigInteger d = p[2].shiftLeft(1).multiply(q[2]).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
        return new BigInteger[] {e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    private static BigInteger[] times(BigInteger n, BigInteger[] p) {
        BigInteger[] q = ZERO;
        for (int i = n.bitLength() - 1; i >= 0; i--) {
            q = add(q, q);
            if (n.testBit(i)) q = add(q, p);
        }
        return q;
    }

    private static boolean same(BigInteger[] p, BigInteger[] q) {
        return p[0].multiply(q[2]).subtract(q[0].multiply(p[2])).mod(P).signum() == 0
            && p[1].multiply(q[2]).subtract(q[1].multiply(p[2])).mod(P).signum() == 0;
    }
}
