import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import org.telegram.messenger.zephyr.plugins.Ed25519Verify;

/** Runs on a stock JDK 17+, with no Android, JUnit, network or persistent keys.
 * RFC fixtures: https://www.rfc-editor.org/rfc/rfc8032#section-7.1
 */
public final class Ed25519RegressionTest {
    private static int checks;
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(
            new BigInteger("27742317777372353535851937790883648493"));

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static BigInteger littleInteger(byte[] bytes) {
        byte[] reversed = bytes.clone();
        for (int i = 0; i < reversed.length / 2; i++) {
            byte temp = reversed[i];
            reversed[i] = reversed[reversed.length - 1 - i];
            reversed[reversed.length - 1 - i] = temp;
        }
        return new BigInteger(1, reversed);
    }

    private static byte[] littleBytes(BigInteger value) {
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) result[i] = value.shiftRight(8 * i).byteValue();
        return result;
    }

    private static void check(String name, boolean expected, byte[] msg, byte[] sig, byte[] pub) {
        checks++;
        if (Ed25519Verify.verify(msg, sig, pub) != expected) {
            throw new AssertionError(name + " expected=" + expected);
        }
    }

    private static void rfcVectors() {
        String[][] vectors = {
                {"", "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
                        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"},
                {"72", "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
                        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"},
                {"af82", "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
                        "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"}
        };
        for (int i = 0; i < vectors.length; i++) {
            check("RFC8032-" + (i + 1), true, hex(vectors[i][0]), hex(vectors[i][2]), hex(vectors[i][1]));
        }
    }

    private static void generatedSignatures() throws Exception {
        SecureRandom random = new SecureRandom();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        for (int i = 0; i < 64; i++) {
            KeyPair key = generator.generateKeyPair();
            byte[] publicEncoding = key.getPublic().getEncoded();
            byte[] publicKey = Arrays.copyOfRange(publicEncoding, publicEncoding.length - 32, publicEncoding.length);
            byte[] message = new byte[i * 37];
            random.nextBytes(message);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key.getPrivate());
            signer.update(message);
            byte[] signature = signer.sign();
            check("JDK-valid-" + i, true, message, signature, publicKey);
            check("changed-message-" + i, false, Arrays.copyOf(message, message.length + 1), signature, publicKey);
            byte[] badSignature = signature.clone();
            badSignature[i % 64] ^= 1 << (i % 8);
            check("changed-signature-" + i, false, message, badSignature, publicKey);
            byte[] wrongKey = publicKey.clone();
            wrongKey[i % 32] ^= 1 << (i % 8);
            check("wrong-public-key-" + i, false, message, signature, wrongKey);
            byte[] noncanonicalS = signature.clone();
            byte[] sPlusL = littleBytes(littleInteger(Arrays.copyOfRange(signature, 32, 64)).add(L));
            System.arraycopy(sPlusL, 0, noncanonicalS, 32, 32);
            check("noncanonical-S-" + i, false, message, noncanonicalS, publicKey);
        }
    }

    private static void weakPublicKeys() throws Exception {
        byte[] identity = new byte[32];
        identity[0] = 1;
        byte[] forged = new byte[64];
        forged[0] = 1; // R = identity, S = 0
        String[] publicKeys = {
                "0100000000000000000000000000000000000000000000000000000000000000",
                "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "0000000000000000000000000000000000000000000000000000000000000080",
                "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
                "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85",
                "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
                "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa",
                "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
                "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
                "0100000000000000000000000000000000000000000000000000000000000080"
        };
        int[] orders = {1, 2, 4, 4, 8, 8, 8, 8, 4, 1, 1};
        for (int k = 0; k < publicKeys.length; k++) {
            byte[] publicKey = hex(publicKeys[k]);
            boolean exercised = false;
            for (int attempt = 0; attempt < 10000; attempt++) {
                byte[] message = ("weak-key-" + k + "-" + attempt).getBytes(StandardCharsets.US_ASCII);
                MessageDigest hash = MessageDigest.getInstance("SHA-512");
                hash.update(identity);
                hash.update(publicKey);
                BigInteger h = littleInteger(hash.digest(message)).mod(L);
                if (h.mod(BigInteger.valueOf(orders[k])).signum() == 0) {
                    check("small-order-or-noncanonical-key-" + k, false, message, forged, publicKey);
                    exercised = true;
                    break;
                }
            }
            if (!exercised) throw new AssertionError("Failed to construct weak-key fixture " + k);
        }
    }

    private static void malformedArguments() {
        check("null-message", false, null, new byte[64], new byte[32]);
        check("null-signature", false, new byte[0], null, new byte[32]);
        check("null-key", false, new byte[0], new byte[64], null);
        for (int size : new int[]{0, 1, 31, 33, 63, 65}) {
            check("signature-length-" + size, false, new byte[0], new byte[size], new byte[32]);
        }
        for (int size : new int[]{0, 1, 31, 33, 64}) {
            check("key-length-" + size, false, new byte[0], new byte[64], new byte[size]);
        }
    }

    public static void main(String[] args) throws Exception {
        rfcVectors();
        generatedSignatures();
        weakPublicKeys();
        malformedArguments();
        System.out.println("Ed25519 regression checks passed: " + checks);
    }
}
