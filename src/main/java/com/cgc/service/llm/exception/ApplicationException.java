package com.cgc.service.llm.exception;

/**
 * @author: anascreations
 *
 */
public class ApplicationException extends RuntimeException {
	private static final long serialVersionUID = -2863338217762471352L;

	public ApplicationException(String message) {
		super(message);
	}

	public ApplicationException(String message, Throwable cause) {
		super(message, cause);
	}
}
