package net.d33.numpwd;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.os.SystemClock;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

/** UI smoke test: add a key, enter a password, read the digits; rename and delete a key. */
@RunWith(AndroidJUnit4.class)
public class KeysActivityTest {
    // openssl passwd -6 -salt test abc | tr -dc '[0-9]' -> 65955012972, shown in groups of four.
    private static final String TEST_DIGITS_GROUPED = "6595 5012 972";
    private static final long RESULT_TIMEOUT_MS = 10_000;

    private ActivityScenario<KeysActivity> scenario;

    @Before
    public void clearKeys() {
        Context context = ApplicationProvider.getApplicationContext();
        new KeyNames(context).save(Collections.emptyList());
    }

    @After
    public void close() {
        if (scenario != null)
            scenario.close();
    }

    private void enterInDialog(String text) {
        onView(withId(R.id.input)).inRoot(isDialog()).perform(replaceText(text));
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
    }

    @Test
    public void addKeyThenDerivesOpensslDigits() {
        scenario = ActivityScenario.launch(KeysActivity.class);
        onView(withId(R.id.empty)).check(matches(isDisplayed()));

        onView(withId(R.id.fab_add)).perform(click());
        enterInDialog("test");
        onData(is("test")).inAdapterView(withId(R.id.keys)).perform(click());
        enterInDialog("abc");

        assertEquals(TEST_DIGITS_GROUPED, waitForResult());
        assertEquals(Collections.singletonList("test"),
                new KeyNames(ApplicationProvider.getApplicationContext()).load());
    }

    @Test
    public void renameAndDeleteKey() {
        new KeyNames(ApplicationProvider.getApplicationContext()).save(Arrays.asList("a", "b"));
        scenario = ActivityScenario.launch(KeysActivity.class);

        onData(is("a")).inAdapterView(withId(R.id.keys)).perform(longClick());
        onView(withText(R.string.rename)).inRoot(isDialog()).perform(click());
        enterInDialog("b");
        onView(withText(R.string.key_name_exists)).inRoot(isDialog()).check(matches(isDisplayed()));
        enterInDialog("c");

        onData(is("b")).inAdapterView(withId(R.id.keys)).perform(longClick());
        onView(withText(R.string.delete)).inRoot(isDialog()).perform(click());
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());

        onView(withText("b")).check(doesNotExist());
        assertEquals(Collections.singletonList("c"),
                new KeyNames(ApplicationProvider.getApplicationContext()).load());
    }

    /** The digits are computed off the main thread, which Espresso does not wait for. */
    private String waitForResult() {
        String computing = ApplicationProvider.getApplicationContext().getString(R.string.computing);
        long deadline = SystemClock.uptimeMillis() + RESULT_TIMEOUT_MS;
        String[] text = {computing};
        while (SystemClock.uptimeMillis() < deadline) {
            onView(withId(R.id.result)).inRoot(isDialog())
                    .check((view, e) -> text[0] = String.valueOf(((TextView) view).getText()));
            if (!computing.equals(text[0]))
                return text[0];
            SystemClock.sleep(100);
        }
        return text[0];
    }
}
