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
@ConfigurationProperties(prefix = "storage")
public class StorageConfig {
	private boolean cacheEnabled;
	private int expiryMinutes;
	private int maxSize;
	private String basePath;
	private int chunkBatchSize;
}
