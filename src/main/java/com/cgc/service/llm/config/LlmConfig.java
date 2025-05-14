package com.cgc.service.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * @author: anascreations
 *
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ollama")
public class LlmConfig {
	private String baseUrl;
	private String model;
	private String embeddingModel;
	private int chunkSize;
	private int chunkOverlap;
	private int maxTokens;
	private double temperature;
	private double topP;
	private boolean stream;
	private boolean isSupportsBatchEmbeddings;
}