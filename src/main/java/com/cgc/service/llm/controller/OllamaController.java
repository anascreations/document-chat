package com.cgc.service.llm.controller;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cgc.service.llm.client.LlmClient;
import com.cgc.service.llm.dto.ChatResponseDto;
import com.cgc.service.llm.dto.EmbeddingDataDto;
import com.cgc.service.llm.dto.LlmDataDto;
import com.cgc.service.llm.dto.ModelsListDto;
import com.cgc.service.llm.dto.VersionInfoDto;
import com.cgc.service.llm.enums.OllamaParam;
import com.cgc.service.llm.utils.OllamaUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * @author: anascreations
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("ollama/api")
public class OllamaController {
	private final LlmClient llmClient;

	@PostMapping("generate")
	public LlmDataDto generate(@RequestBody Map<String, Object> requestBody) {
		log.info("Generating text with model: {}", requestBody.get(OllamaParam.MODEL.getKey()));
		return llmClient.generate(requestBody);
	}

	@PostMapping("generate/simple")
	public LlmDataDto generateSimple(@RequestParam String model, @RequestParam String prompt) {
		Map<String, Object> request = OllamaUtils.createGenerateRequest(model, prompt);
		return llmClient.generate(request);
	}

	@PostMapping(value = "generate/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
	public Flux<Map<String, Object>> generateStream(@RequestBody Map<String, Object> requestBody) {
		log.info("Streaming generation with model: {}", requestBody.get(OllamaParam.MODEL.getKey()));
		requestBody.put(OllamaParam.STREAM.getKey(), true);
		return llmClient.generateStream(requestBody);
	}

	@PostMapping("embeddings")
	public EmbeddingDataDto createEmbedding(@RequestBody Map<String, Object> requestBody) {
		log.info("Creating embeddings with model: {}", requestBody.get(OllamaParam.MODEL.getKey()));
		return llmClient.embedding(requestBody);
	}

	@PostMapping("chat")
	public ChatResponseDto chat(@RequestBody Map<String, Object> requestBody) {
		log.info("Chat completion with model: {}", requestBody.get(OllamaParam.MODEL.getKey()));
		return llmClient.chat(requestBody);
	}

	@PostMapping(value = "chat/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
	public Flux<Map<String, Object>> chatStream(@RequestBody Map<String, Object> requestBody) {
		log.info("Streaming chat with model: {}", requestBody.get(OllamaParam.MODEL.getKey()));
		requestBody.put(OllamaParam.STREAM.getKey(), true);
		return llmClient.chatStream(requestBody);
	}

	@GetMapping("models")
	public ModelsListDto listModels() {
		log.info("Listing available models");
		return llmClient.listModels();
	}

	@PostMapping("models/pull")
	public Flux<Map<String, Object>> pullModel(@RequestBody Map<String, Object> requestBody) {
		log.info("Pulling model: {}", requestBody.get(OllamaParam.NAME.getKey()));
		return llmClient.pullModel(requestBody);
	}

	@PostMapping("models/push")
	public Flux<Map<String, Object>> pushModel(@RequestBody Map<String, Object> requestBody) {
		log.info("Pushing model: {}", requestBody.get(OllamaParam.NAME.getKey()));
		return llmClient.pushModel(requestBody);
	}

	@PostMapping("models/create")
	public Flux<Map<String, Object>> createModel(@RequestBody Map<String, Object> requestBody) {
		log.info("Creating model: {}", requestBody.get(OllamaParam.NAME.getKey()));
		return llmClient.createModel(requestBody);
	}

	@PostMapping("models/copy")
	public Map<String, Object> copyModel(@RequestBody Map<String, Object> requestBody) {
		log.info("Copying model from {} to {}", requestBody.get(OllamaParam.SOURCE.getKey()),
				requestBody.get(OllamaParam.DESTINATION.getKey()));
		return llmClient.copyModel(requestBody);
	}

	@DeleteMapping("models")
	public Map<String, Object> deleteModel(@RequestBody Map<String, Object> requestBody) {
		log.info("Deleting model: {}", requestBody.get(OllamaParam.NAME.getKey()));
		return llmClient.deleteModel(requestBody);
	}

	@SuppressWarnings("unchecked")
	@GetMapping("models/{modelName}/info")
	public ResponseEntity<Map<String, Object>> getModelInfo(@PathVariable String modelName) {
		log.info("Getting simplified info for model: {}", modelName);
		try {
			Map<String, Object> requestBody = new HashMap<>();
			OllamaParam.NAME.addTo(requestBody, modelName);
			Object rawResponse = llmClient.showModel(requestBody);
			Map<String, Object> simplifiedInfo = new HashMap<>();
			simplifiedInfo.put("name", modelName);
			Map<String, Object> responseMap = (Map<String, Object>) rawResponse;
			List<String> keysOfInterest = Arrays.asList("license", "modelfile", "parameters", "template", "details",
					"model_info", "tensors", "capabilities", "modified_at");
			for (String key : keysOfInterest) {
				if (responseMap.containsKey(key)) {
					simplifiedInfo.put(key, responseMap.get(key));
				}
			}
			return ResponseEntity.ok(simplifiedInfo);
		} catch (Exception e) {
			log.error("Error getting model info for {}: {}", modelName, e.getMessage());
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("name", modelName);
			errorResponse.put("error", e.getMessage());
			errorResponse.put("errorType", e.getClass().getName());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
		}
	}

	@GetMapping("version")
	public VersionInfoDto getVersion() {
		log.info("Getting Ollama version");
		return llmClient.getVersion();
	}

	@GetMapping("ping")
	public ResponseEntity<Map<String, Object>> ping() {
		Map<String, Object> response = new HashMap<>();
		try {
			VersionInfoDto version = llmClient.getVersion();
			response.put("status", "ok");
			response.put("version", version.getVersion());
			response.put("message", "Ollama server is running");
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			response.put("status", "error");
			response.put("message", "Ollama server is not responding");
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
		}
	}
}
