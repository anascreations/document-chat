package com.cgc.service.llm.dto;

import java.io.Serializable;

import lombok.Data;

/**
 * @author: anascreations
 *
 */
@Data
public class ProcessingStatusDto implements Serializable {
	private static final long serialVersionUID = -934699220276169674L;
	private final String filename;
	private int progress;
	private String message;
	private long lastUpdated;

	public ProcessingStatusDto(String filename, int progress, String message) {
		this.filename = filename;
		this.progress = progress;
		this.message = message;
		this.lastUpdated = System.currentTimeMillis();
	}

	public void update(int progress, String message) {
		this.progress = progress;
		this.message = message;
		this.lastUpdated = System.currentTimeMillis();
	}
}
