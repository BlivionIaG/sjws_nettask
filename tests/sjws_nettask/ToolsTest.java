package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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

    @ParameterizedTest(name = "BUG: checkValidPort(\"{0}\") throws instead of returning false")
    @ValueSource(strings = {"80.5", "99999999999"})
    void invalidPortsMustNotThrow(String s) {
        // isNumeric() accepts decimals and arbitrarily long digit strings,
        // then Integer.parseInt() throws NumberFormatException -> main() crashes.
        assertDoesNotThrow(() -> assertFalse(Sjws_nettask.checkValidPort(s)));
    }

    @Test
    void currentDateHasDatePrefix() {
        assertTrue(Tools.formatedCurrentDate().startsWith("Date: "));
    }

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
