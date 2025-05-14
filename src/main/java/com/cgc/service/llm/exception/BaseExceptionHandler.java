package com.cgc.service.llm.exception;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * @author: anascreations
 *
 */
@Slf4j
@ControllerAdvice
public class BaseExceptionHandler {
	private static final String ERROR = "error";

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<String> handleNoResourceFoundException(NoResourceFoundException ex) {
		if (!ex.getMessage().contains("favicon.ico")) {
			log.error("Resource not found: {}", ex.getMessage());
		}
		return ResponseEntity.notFound().build();
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<Map<String, String>> handleMaxSizeException(MaxUploadSizeExceededException e) {
		log.error("File size exceeds the maximum allowed size", e);
		Map<String, String> response = new HashMap<>();
		response.put(ERROR, "File size exceeds the maximum allowed size");
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
	}

	@ExceptionHandler(IOException.class)
	public ResponseEntity<Map<String, String>> handleIOException(IOException e) {
		log.error("Error processing file", e);
		Map<String, String> response = new HashMap<>();
		response.put(ERROR, "Error processing file: " + e.getMessage());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> handleGenericException(Exception e) {
		log.error("Unexpected error", e);
		Map<String, String> response = new HashMap<>();
		response.put(ERROR, "An unexpected error occurred: " + e.getMessage());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
	}
}
