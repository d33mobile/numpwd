package net.d33.numpwd;

import android.graphics.Rect;
import android.text.Editable;
import android.text.method.PasswordTransformationMethod;
import android.view.View;

import java.util.Arrays;

/**
 * Shows every character of a password as a dot from the first moment. The platform
 * PasswordTransformationMethod shows the last typed character for a while when the system
 * "Show passwords" setting is on. This subclass keeps the password semantics that TextView
 * checks with instanceof (no copy or cut, not read out by accessibility services) and never
 * reveals a character.
 */
final class HiddenPasswordTransformation extends PasswordTransformationMethod {
    private static final char DOT = '•';

    @Override
    public CharSequence getTransformation(CharSequence source, View view) {
        return new Dots(source);
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        // The platform version marks the typed character visible here; never do that.
    }

    @Override
    public void afterTextChanged(Editable s) {
        // Nothing to undo, since onTextChanged never reveals anything.
    }

    @Override
    public void onFocusChanged(View view, CharSequence sourceText, boolean focused, int direction,
                               Rect previouslyFocusedRect) {
        // Nothing to hide on focus loss either.
    }

    /** A live view of the source text with the same length, made only of dots. */
    private static final class Dots implements CharSequence {
        private final CharSequence source;

        Dots(CharSequence source) {
            this.source = source;
        }

        @Override
        public int length() {
            return source.length();
        }

        @Override
        public char charAt(int index) {
            return DOT;
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return dots(end - start);
        }

        @Override
        public String toString() {
            return dots(length());
        }

        private static String dots(int count) {
            char[] out = new char[count];
            Arrays.fill(out, DOT);
            return new String(out);
        }
    }
}
