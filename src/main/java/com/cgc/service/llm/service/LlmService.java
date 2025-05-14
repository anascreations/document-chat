package com.cgc.service.llm.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.cgc.service.llm.client.LlmClient;
import com.cgc.service.llm.config.LlmConfig;
import com.cgc.service.llm.constants.Constants;
import com.cgc.service.llm.dto.LlmDataDto;
import com.cgc.service.llm.enums.OllamaParam;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @author: anascreations
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

	private final LlmConfig llmConfig;
	private final LlmClient llmClient;

	public String generateResponse(String prompt) {
		try {
			int estimatedTokens = prompt.length() / 4;
			log.info("Prompt length: {}, estimated tokens: {}", prompt.length(), estimatedTokens);
			var param = llmParam(prompt, false);
			LlmDataDto data = llmClient.generate(param);
			if (data != null)
				return data.getResponse();
		} catch (Exception ex) {
			log.error("LLM server request failed: {}", ex.getMessage(), ex);
		}
		return "";
	}

	public String generateSummary(String pdfText) {
		String enhancedPrompt = """
				You are a professional document summarizer. Create a concise, well-structured summary of the following document.

				Follow these guidelines:
				1. Extract key facts and information
				2. Organize the summary by categories (people, dates, numbers, etc.)
				3. Use bullet points for clarity
				4. Include all important numerical data
				5. Keep the summary under 200 words
				6. Do not start with phrases like "This document" or "Here is a summary"

				Document text:
				%s
				"""
				.formatted(pdfText);
		var param = llmParam(enhancedPrompt, false);
		try {
			LlmDataDto response = llmClient.generate(param);
			if (response != null) {
				String summary = response.getResponse();
				summary = summary.replaceAll("(?i)^(here is|here's|this is|the following is) (a|the) summary:?\\s*",
						"");
				summary = summary.replaceAll("(?i)^summary:?\\s*", "");
				return summary.trim();
			} else {
				log.error("Invalid response format from Ollama: {}", response);
				return "Failed to generate summary. Please try again.";
			}
		} catch (Exception e) {
			log.error("Error generating summary", e);
			return "Error generating summary: " + e.getMessage();
		}
	}

	public void generateSummaryStreaming(String pdfText, Consumer<String> resultConsumer, Runnable completionCallback) {
		String enhancedPrompt = """
				You are a professional document summarizer. Create a concise, well-structured summary of the following document.

				Follow these guidelines:
				1. Extract key facts and information
				2. Organize the summary by categories (people, dates, numbers, etc.)
				3. Use bullet points for clarity
				4. Include all important numerical data
				5. Keep the summary under 200 words
				6. Do not start with phrases like "This document" or "Here is a summary"

				Document text:
				%s
				"""
				.formatted(pdfText);
		try {
			StringBuilder fullResponse = new StringBuilder();
			AtomicBoolean firstChunk = new AtomicBoolean(true);
			AtomicBoolean isDone = new AtomicBoolean(false);
			var param = llmParam(enhancedPrompt, false);
			generateStream(param, chunk -> {
				if (chunk.containsKey(Constants.RESPONSE)) {
					String responseChunk = (String) chunk.get(Constants.RESPONSE);
					if (firstChunk.get()) {
						responseChunk = responseChunk
								.replaceAll("(?i)^(here is|here's|this is|the following is) (a|the) summary:?\\s*", "");
						responseChunk = responseChunk.replaceAll("(?i)^summary:?\\s*", "");
						firstChunk.set(false);
					}
					fullResponse.append(responseChunk);
					resultConsumer.accept(responseChunk);
				}
				if (chunk.containsKey("done") && Boolean.TRUE.equals(chunk.get("done"))) {
					isDone.set(true);
					log.info("Streaming response completed");
					if (completionCallback != null) {
						completionCallback.run();
					}
				}
			});
			log.debug("Generated summary: {}", fullResponse.toString());
		} catch (Exception e) {
			log.error("Error generating summary stream", e);
			resultConsumer.accept("Error generating summary: " + e.getMessage());
			if (completionCallback != null) {
				completionCallback.run();
			}
		}
	}

	public void generateStream(Map<String, Object> param, Consumer<Map<String, Object>> chunkConsumer) {
		try {
			var responseFlux = llmClient.generateStream(param);
			CountDownLatch latch = new CountDownLatch(1);
			responseFlux.subscribe(chunk -> {
				try {
					chunkConsumer.accept(chunk);
					if (chunk.containsKey("done") && Boolean.TRUE.equals(chunk.get("done"))) {
						latch.countDown();
					}
				} catch (Exception e) {
					log.error("Error processing response chunk", e);
				}
			}, error -> {
				log.error("Error in streaming response", error);
				Map<String, Object> errorChunk = new HashMap<>();
				errorChunk.put(Constants.RESPONSE, "\nError: " + error.getMessage());
				errorChunk.put("done", true);
				chunkConsumer.accept(errorChunk);
				latch.countDown();
			}, () -> {
				log.debug("Streaming response completed");
				Map<String, Object> doneChunk = new HashMap<>();
				doneChunk.put("done", true);
				chunkConsumer.accept(doneChunk);
				latch.countDown();
			});
			try {
				boolean completed = latch.await(120, TimeUnit.SECONDS);
				if (!completed) {
					log.warn("Streaming response timed out");
					Map<String, Object> timeoutChunk = new HashMap<>();
					timeoutChunk.put(Constants.RESPONSE, "\nResponse generation timed out.");
					timeoutChunk.put("done", true);
					chunkConsumer.accept(timeoutChunk);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.error("Interrupted while waiting for streaming response", e);
			}

		} catch (Exception e) {
			log.error("Error initiating streaming request", e);
			Map<String, Object> errorChunk = new HashMap<>();
			errorChunk.put(Constants.RESPONSE, "\nError: " + e.getMessage());
			errorChunk.put("done", true);
			chunkConsumer.accept(errorChunk);
			throw new RuntimeException("Failed to process streaming request", e);
		}
	}

	public String generateRecommendations(String pdfText) {
		String model = llmConfig.getModel();
		Map<String, Object> param = Map.of("model", model, "prompt",
				"Based on this document, provide 3-5 key recommendations or actionable insights: \n\n" + pdfText,
				"stream", true);
		return null;
//		return ollamaClient.post().uri("/api/generate").contentType(MediaType.APPLICATION_JSON).bodyValue(param)
//				.retrieve().bodyToMono(Map.class).map(response -> (String) response.get("response")).block();
	}

	public void generateAnswerStreaming(String prompt, Consumer<String> resultConsumer, Runnable completionCallback) {
		Map<String, Object> param = llmParam(prompt, true);
		try {
			StringBuilder fullResponse = new StringBuilder();
			AtomicBoolean firstChunk = new AtomicBoolean(true);
			AtomicBoolean isDone = new AtomicBoolean(false);
			generateStream(param, chunk -> {
				if (chunk.containsKey(Constants.RESPONSE)) {
					String responseChunk = (String) chunk.get(Constants.RESPONSE);
					if (firstChunk.get()) {
						responseChunk = responseChunk
								.replaceAll("(?i)^(based on|according to) the (provided |)context,?\\s*", "");
						responseChunk = responseChunk
								.replaceAll("(?i)^(the answer is|to answer your question)[,:]?\\s*", "");
						firstChunk.set(false);
					}
					fullResponse.append(responseChunk);
					resultConsumer.accept(responseChunk);
				}
				if (chunk.containsKey("done") && Boolean.TRUE.equals(chunk.get("done"))) {
					isDone.set(true);
					log.info("Answer streaming completed");
					if (completionCallback != null) {
						completionCallback.run();
					}
				}
			});
			log.debug("Generated answer: {}", fullResponse.toString());
		} catch (Exception e) {
			log.error("Error generating answer stream", e);
			resultConsumer.accept("Error generating answer: " + e.getMessage());
			if (completionCallback != null) {
				completionCallback.run();
			}
		}
	}

	private Map<String, Object> llmParam(String prompt, boolean isStream) {
		Map<String, Object> param = new HashMap<>();
		param.put(OllamaParam.MODEL.getKey(), llmConfig.getModel());
		param.put(OllamaParam.PROMPT.getKey(), prompt);
		param.put(OllamaParam.STREAM.getKey(), isStream);
		param.put(OllamaParam.TEMPERATURE.getKey(), llmConfig.getTemperature());
		param.put(OllamaParam.TOP_P.getKey(), llmConfig.getTopP());
		param.put(OllamaParam.MAX_TOKENS.getKey(), llmConfig.getMaxTokens());
		return param;
	}
}
