package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ToolsTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "80", "8080", "65535", "-1", "+42"})
    void isNumericAcceptsNumbers(String s) {
        assertTrue(Tools.isNumeric(s));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "80a", " 80"})
    void isNumericRejectsNonNumbers(String s) {
        assertFalse(Tools.isNumeric(s));
    }

    @Test
    void isNumericRejectsNull() {
        assertFalse(Tools.isNumeric(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "80", "8080", "65535"})
    void validPorts(String s) {
        assertTrue(Sjws_nettask.checkValidPort(s));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "-1", "abc", ""})
    void invalidPortsRejected(String s) {
        assertFalse(Sjws_nettask.checkValidPort(s));
    }

    @Tag("bug")
    @ParameterizedTest(name = "BUG: checkValidPort(\"{0}\") throws instead of returning false")
    @ValueSource(strings = {"80.5", "99999999999"})
    void invalidPortsMustNotThrow(String s) {
        // isNumeric() accepts decimals and arbitrarily long digit strings,
        // then Integer.parseInt() throws NumberFormatException -> main() crashes.
        assertDoesNotThrow(() -> assertFalse(Sjws_nettask.checkValidPort(s)));
    }

    @Test
    void randomAlphaNumericLengthAndCharset() {
        String id = Tools.randomAlphaNumeric(CONSTANTS.ALPHANUMERIC_SET, CONSTANTS.ID_SIZE);
        assertEquals(CONSTANTS.ID_SIZE, id.length());
        String allowed = new String(CONSTANTS.ALPHANUMERIC_SET);
        for (char c : id.toCharArray()) {
            assertTrue(allowed.indexOf(c) >= 0, "unexpected char " + c);
        }
    }

    @Test
    void currentDateHasDatePrefix() {
        assertTrue(Tools.formatedCurrentDate().startsWith("Date: "));
    }

    @Tag("bug")
    @Test
    void BUG_currentDateIsRfc1123() {
        // Expected e.g. "Date: Mon, 12 Oct 2026 08:05:03 GMT".
        // Actual: no space between year and hour, hour AM/PM logic inverted,
        // no zero padding, local time labelled GMT.
        String value = Tools.formatedCurrentDate().substring("Date: ".length());
        assertDoesNotThrow(() -> ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME),
                "not RFC 1123: '" + value + "'");
    }
}
