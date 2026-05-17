package com.nexus.identity.application.command;

/** Thrown when S3 document upload fails. */
public class DocumentUploadException extends RuntimeException {
    public DocumentUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
