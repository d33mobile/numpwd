package net.d33.numpwd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeyNamesTest {
    @Test
    public void acceptsAnySingleLineName() {
        assertTrue(KeyNames.isValidName("test"));
        assertTrue(KeyNames.isValidName("rounds=1000$a b"));
        assertTrue(KeyNames.isValidName("zażółć"));
    }

    @Test
    public void rejectsNamesOpensslCannotTake() {
        assertFalse(KeyNames.isValidName(""));
        assertFalse(KeyNames.isValidName("a\nb"));
        assertFalse(KeyNames.isValidName("a\rb"));
        assertFalse(KeyNames.isValidName("a\0b"));
    }
}
