package com.vedicmeet.appserver.consultant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConsultantPreferenceServiceTest {

    @Test
    void nextAvailableTimeRoundsUpToFiveMinuteBoundaryInKolkata() {
        assertEquals("2026-09-08 12:40",
                ConsultantPreferenceService.roundToNextFiveMinutes("2026-09-08T07:08:12Z"));
    }

    @Test
    void exactBoundaryIsNotMoved() {
        assertEquals("2026-09-08 12:35",
                ConsultantPreferenceService.roundToNextFiveMinutes("2026-09-08T12:35:00"));
    }

    @Test
    void blankIsCompatibleNullAndInvalidInputIsRejected() {
        assertNull(ConsultantPreferenceService.roundToNextFiveMinutes(""));
        assertThrows(IllegalArgumentException.class,
                () -> ConsultantPreferenceService.roundToNextFiveMinutes("tomorrow-ish"));
    }
}
