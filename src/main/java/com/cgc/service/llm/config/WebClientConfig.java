package com.cgc.service.llm.config;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.retry.annotation.Retryable;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.cgc.service.llm.client.LlmClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * @author: anascreations
 *
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {
	private final LlmConfig llmConfig;

	@Bean
	HttpClient httpClient() {
		int maxConnection = 300;
		int maxCount = 500;
		Duration maxTimeout = Duration.ofSeconds(60);
		Duration maxIdleTime = Duration.ofSeconds(30);
		Duration maxLifeTime = Duration.ofSeconds(300);
		Duration evictBackground = Duration.ofSeconds(120);
		ConnectionProvider provider = ConnectionProvider.builder("llm-pool").maxConnections(maxConnection)
				.pendingAcquireMaxCount(maxCount).pendingAcquireTimeout(maxTimeout).maxIdleTime(maxIdleTime)
				.maxLifeTime(maxLifeTime).evictInBackground(evictBackground).build();
		HttpClient httpClient = HttpClient.create(provider);
		httpClient.warmup().doOnSuccess(unused -> log.info("Reactor HttpClient Warmup Complete"))
				.doOnError(err -> log.error("Reactor HttpClient Warmup Error", err)).subscribe();
		return httpClient;
	}

	@Bean
	@Retryable(recover = "fallbackLlmService")
	LlmClient llmClient() {
		String url = llmConfig.getBaseUrl();
		String model = llmConfig.getModel();
		log.info("Pre-trained model: " + model);
		WebClient webClient = webClient(url, httpClient());
		return createClient(webClient, LlmClient.class);
	}

	private <T> T createClient(WebClient webClient, Class<T> clazz) {
		WebClientAdapter adapter = WebClientAdapter.create(webClient);
		HttpServiceProxyFactory proxyFactory = HttpServiceProxyFactory.builderFor(adapter).build();
		return proxyFactory.createClient(clazz);
	}

	private WebClient webClient(String url, HttpClient httpClient) {
		return WebClient.builder().baseUrl(url).clientConnector(new ReactorClientHttpConnector(httpClient)).build();
	}

	public ResponseEntity<Map<String, Object>> fallbackLlmService(Exception e) {
		log.error("Ollama service failed after retry attempts", e);
		Map<String, Object> errorResponse = new HashMap<>();
		errorResponse.put("status", "ERROR");
		errorResponse.put("message", "LLM service is currently unavailable. Please try again later.");
		errorResponse.put("error", e.getMessage());
		errorResponse.put("timestamp", new Date());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
	}

}
