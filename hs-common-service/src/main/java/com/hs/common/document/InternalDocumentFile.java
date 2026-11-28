package com.hs.common.document;

/** One PDF handed to the notification service; never log {@code bytes}. */
public record InternalDocumentFile(String fileName, byte[] bytes) {}
