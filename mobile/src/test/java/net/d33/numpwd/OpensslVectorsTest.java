package net.d33.numpwd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Checks against vectors produced by tools/gen_vectors.py from the real openssl binary. */
public class OpensslVectorsTest {
    private static final int MIN_VECTORS = 200;
    private static final String ERROR_MARKER = "!ERROR";

    private static final class Vector {
        final byte[] name;
        final byte[] password;
        final String cryptHex;
        final String digits;

        Vector(String[] f) {
            name = hex(f[0]);
            password = hex(f[1]);
            cryptHex = f[2];
            digits = f[3];
        }
    }

    private static List<Vector> load() throws Exception {
        List<Vector> out = new ArrayList<>();
        try (InputStream in = OpensslVectorsTest.class.getResourceAsStream("/openssl_vectors.tsv");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty())
                    continue;
                out.add(new Vector(line.split("\t", -1)));
            }
        }
        assertTrue("too few vectors: " + out.size(), out.size() >= MIN_VECTORS);
        return out;
    }

    @Test
    public void cryptMatchesOpensslArgvMode() throws Exception {
        for (Vector v : load()) {
            // argv mode truncates every over-long password, so compare on the truncated bytes.
            byte[] pw = v.password.length > NumericPassword.PASSWORD_MAX_BYTES
                    ? Arrays.copyOf(v.password, NumericPassword.PASSWORD_MAX_BYTES) : v.password;
            String label = new String(v.name, StandardCharsets.UTF_8);
            if (v.cryptHex.equals("-")) {
                try {
                    OpensslShaCrypt.crypt(pw, v.name);
                    fail("expected rejection for salt " + label);
                } catch (OpensslShaCrypt.InvalidInputException expected) {
                    // openssl printed <NULL> for this input
                }
            } else {
                assertArrayEquals("salt " + label, hex(v.cryptHex), OpensslShaCrypt.crypt(pw, v.name));
            }
        }
    }

    @Test
    public void digitsMatchInteractivePipeline() throws Exception {
        for (Vector v : load()) {
            String name = new String(v.name, StandardCharsets.UTF_8);
            String password = new String(v.password, StandardCharsets.UTF_8);
            if (v.digits.equals(ERROR_MARKER)) {
                try {
                    String got = NumericPassword.derive(name, password);
                    fail("expected error for salt " + name + ", got " + got);
                } catch (OpensslShaCrypt.InvalidInputException expected) {
                    // the openssl pipeline printed no digits for this input
                }
            } else {
                assertEquals("salt " + name, v.digits, NumericPassword.derive(name, password));
            }
        }
    }

    @Test
    public void readmeExample() throws Exception {
        // openssl passwd -6 -salt test abc | tr -dc '[0-9]'
        assertEquals("65955012972", NumericPassword.derive("test", "abc"));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
