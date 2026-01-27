package io.clype.reactorsns.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnsEventTest {

    @Test
    void testValidSnsEvent() {
        assertDoesNotThrow(() -> new SnsEvent("group-123", "dedup-456", "payload"));
    }

    @Test
    void testValidSnsEventWithUnderscore() {
        assertDoesNotThrow(() -> new SnsEvent("group_123", "dedup_456", "payload"));
    }

    @Test
    void testValidSnsEventWithHyphen() {
        assertDoesNotThrow(() -> new SnsEvent("group-123-abc", "dedup-456-xyz", "payload"));
    }

    @Test
    void testValidSnsEventAlphanumericOnly() {
        assertDoesNotThrow(() -> new SnsEvent("GroupABC123", "DedupXYZ789", "payload"));
    }

    @Test
    void testNullMessageGroupIdThrows() {
        assertThrows(NullPointerException.class, () -> new SnsEvent(null, "dedup", "payload"));
    }

    @Test
    void testNullMessageDeduplicationIdThrows() {
        assertThrows(NullPointerException.class, () -> new SnsEvent("group", null, "payload"));
    }

    @Test
    void testEmptyMessageGroupIdThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new SnsEvent("", "dedup", "payload"));
        assertEquals("messageGroupId cannot be empty", ex.getMessage());
    }

    @Test
    void testEmptyMessageDeduplicationIdThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new SnsEvent("group", "", "payload"));
        assertEquals("messageDeduplicationId cannot be empty", ex.getMessage());
    }

    @Test
    void testMessageGroupIdExceeds128CharsThrows() {
        String longId = "a".repeat(129);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new SnsEvent(longId, "dedup", "payload"));
        assertEquals("messageGroupId exceeds 128 characters", ex.getMessage());
    }

    @Test
    void testMessageDeduplicationIdExceeds128CharsThrows() {
        String longId = "a".repeat(129);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new SnsEvent("group", longId, "payload"));
        assertEquals("messageDeduplicationId exceeds 128 characters", ex.getMessage());
    }

    @Test
    void testMessageGroupIdExactly128CharsIsValid() {
        String maxLengthId = "a".repeat(128);
        assertDoesNotThrow(() -> new SnsEvent(maxLengthId, "dedup", "payload"));
    }

    @Test
    void testInvalidCharactersInMessageGroupIdThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new SnsEvent("group.with.dots", "dedup", "payload"));
        assertEquals("messageGroupId contains invalid characters. Allowed: alphanumeric, hyphen, underscore",
                ex.getMessage());
    }

    @Test
    void testInvalidCharactersInMessageDeduplicationIdThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new SnsEvent("group", "dedup@with#special", "payload"));
        assertEquals("messageDeduplicationId contains invalid characters. Allowed: alphanumeric, hyphen, underscore",
                ex.getMessage());
    }

    @Test
    void testSpacesInMessageGroupIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new SnsEvent("group with spaces", "dedup", "payload"));
    }

    @Test
    void testNullPayloadIsAllowed() {
        assertDoesNotThrow(() -> new SnsEvent("group", "dedup", null));
    }
}
