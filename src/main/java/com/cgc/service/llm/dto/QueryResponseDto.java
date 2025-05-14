package com.cgc.service.llm.dto;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author: anascreations
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(Include.NON_NULL)
public class QueryResponseDto implements Serializable {
	private static final long serialVersionUID = 89308963689802219L;
	private String status;
	private String description;
	private String answer;
	private float confidenceScore;
	@JsonIgnore
	private List<TextChunkDto> relevantChunks;
	private long processingTimeMs;

	public QueryResponseDto(String description) {
		this.description = description;
	}
}
