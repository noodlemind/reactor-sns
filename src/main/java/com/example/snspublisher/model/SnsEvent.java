package com.example.snspublisher.model;

public record SnsEvent(
    String messageGroupId,
    String messageDeduplicationId,
    String payload,
    String sequenceNumber // Optional, useful for tracking/debugging or if explicit sequencing is needed
) {}
