package com.cgc.service.llm.config;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * @author: anascreations
 *
 */
@EnableCaching
@Configuration
public class CacheConfig {
	@Value("${storage.cache.expiry-minutes:1440}")
	private int cacheExpiryMinutes;

	@Value("${storage.cache.max-size:10000}")
	private int cacheMaxSize;

	@Bean
	Cache<String, String> metadataCache() {
		return Caffeine.newBuilder().expireAfterWrite(cacheExpiryMinutes, TimeUnit.MINUTES).maximumSize(cacheMaxSize)
				.recordStats().build();
	}

	@Bean
	Cache<String, String> chunkIndexCache() {
		return Caffeine.newBuilder().expireAfterWrite(cacheExpiryMinutes, TimeUnit.MINUTES).maximumSize(cacheMaxSize)
				.recordStats().build();
	}
}
