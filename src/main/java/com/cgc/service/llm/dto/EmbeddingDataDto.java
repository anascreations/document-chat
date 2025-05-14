package com.cgc.service.llm.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class EmbeddingDataDto {
	@JsonProperty("embedding")
	private float[] embedding;
	@JsonProperty("embeddings")
	private List<float[]> embeddings = new ArrayList<>();
	@JsonProperty("model")
	private String model;
}
