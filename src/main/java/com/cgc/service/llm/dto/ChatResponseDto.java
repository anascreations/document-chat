package com.cgc.service.llm.dto;

import java.util.List;

import lombok.Data;

/**
 * @author: anascreations
 *
 */
@Data
public class ChatResponseDto {
	private String model;
	private String createdAt;
	private Message message;
	private boolean done;
	private List<Integer> context;
	private long totalDuration;
	private long loadDuration;
	private long promptEvalDuration;
	private int evalCount;
	private long evalDuration;

	@Data
	public static class Message {
		private String role;
		private String content;
	}
}
