package com.cgc.service.llm.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cgc.service.llm.enums.OllamaParam;

import lombok.experimental.UtilityClass;

/**
 * @author: anascreations
 *
 */
@UtilityClass
public class OllamaUtils {

	public Map<String, Object> createModelRequest(String modelName) {
		Map<String, Object> request = new HashMap<>();
		OllamaParam.MODEL.addTo(request, modelName);
		return request;
	}

	public Map<String, Object> createGenerateRequest(String modelName, String prompt) {
		Map<String, Object> request = createModelRequest(modelName);
		OllamaParam.PROMPT.addTo(request, prompt);
		return request;
	}

	public Map<String, Object> createChatRequest(String modelName, List<Map<String, String>> messages) {
		Map<String, Object> request = createModelRequest(modelName);
		OllamaParam.MESSAGES.addTo(request, messages);
		return request;
	}
}
