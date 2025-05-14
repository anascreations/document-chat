package com.cgc.service.llm.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.cgc.service.llm.client.LlmClient;
import com.cgc.service.llm.config.LlmConfig;
import com.cgc.service.llm.enums.OllamaParam;
import com.cgc.service.llm.exception.ApplicationException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @author: anascreations
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {
	private final LlmConfig llmConfig;
	private final LlmClient llmClient;

//	public float[] generateEmbedding(String text) {
//		String embeddingModel = llmConfig.getEmbeddingModel();
//		Map<String, Object> param = Map.of(OllamaParam.MODEL.getKey(), embeddingModel,
//				OllamaParam.PROMPT.getKey(), text);
//		EmbeddingDataDto embed = llmClient.embedding(param);
//		return embed.getEmbedding();
//	}
//
//	public List<float[]> generateEmbeddings(List<String> texts) {
//		if (!llmConfig.isSupportsBatchEmbeddings()) {
//			return texts.stream().map(this::generateEmbedding).toList();
//		}
//		String embeddingModel = llmConfig.getEmbeddingModel();
//		Map<String, Object> param = Map.of(OllamaParam.MODEL.getKey(), embeddingModel,
//				OllamaParam.PROMPTS.getKey(), texts);
//		EmbeddingDataDto embed = llmClient.embedding(param);
//		return embed.getEmbeddings();
//	}

	private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
	private static final int BATCH_SIZE = 10;

	@Cacheable(value = "embeddings", key = "#text.hashCode()")
	public float[] generateEmbedding(String text) {
		if (text == null || text.isBlank()) {
			throw new ApplicationException("Text cannot be null or blank");
		}
		var param = createEmbeddingParam(text);
		try {
			var embed = llmClient.embedding(param);
			return embed.getEmbedding();
		} catch (Exception e) {
			log.error("Error", e);
			throw new ApplicationException("Failed to generate embedding", e);
		}
	}

	public List<float[]> generateEmbeddings(List<String> texts) {
		if (texts == null || texts.isEmpty()) {
			return List.of();
		}
		var validTexts = texts.stream().filter(text -> text != null && !text.isBlank()).toList();
		if (validTexts.isEmpty()) {
			return List.of();
		}
		if (llmConfig.isSupportsBatchEmbeddings()) {
			return processBatchEmbeddings(validTexts);
		} else {
			return processNonBatchEmbeddings(validTexts);
		}
	}

	private List<float[]> processBatchEmbeddings(List<String> texts) {
		if (texts.size() > BATCH_SIZE) {
			List<float[]> results = new ArrayList<>();
			for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
				int end = Math.min(i + BATCH_SIZE, texts.size());
				List<String> batch = texts.subList(i, end);
				results.addAll(processSingleBatch(batch));
			}
			return results;
		} else {
			return processSingleBatch(texts);
		}
	}

	private List<float[]> processSingleBatch(List<String> texts) {
		var param = Map.of(OllamaParam.MODEL.getKey(), llmConfig.getEmbeddingModel(), OllamaParam.PROMPTS.getKey(),
				texts);
		try {
			var embed = llmClient.embedding(param);
			return embed.getEmbeddings();
		} catch (Exception e) {
			log.error("Error", e);
			throw new ApplicationException("Failed to generate batch embeddings", e);
		}
	}

	private List<float[]> processNonBatchEmbeddings(List<String> texts) {
		var futures = texts.stream()
				.map(text -> CompletableFuture.supplyAsync(() -> generateEmbedding(text), executorService)).toList();
		return futures.stream().map(CompletableFuture::join).toList();
	}

	private Map<String, Object> createEmbeddingParam(String text) {
		return Map.of(OllamaParam.MODEL.getKey(), llmConfig.getEmbeddingModel(), OllamaParam.PROMPT.getKey(), text);
	}

}
