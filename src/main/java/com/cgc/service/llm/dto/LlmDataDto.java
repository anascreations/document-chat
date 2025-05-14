package com.cgc.service.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * @author: anascreations
 *
 */
@Data
public class LlmDataDto {
	@JsonProperty("response")
	private String response;
}
