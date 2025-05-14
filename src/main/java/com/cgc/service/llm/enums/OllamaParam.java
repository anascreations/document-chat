package com.cgc.service.llm.enums;

import java.util.Map;

/**
 * @author: anascreations
 *
 */
public enum OllamaParam {
	MODEL("model"), NAME("name"), SOURCE("source"), DESTINATION("destination"), STREAM("stream"), PROMPT("prompt"),
	PROMPTS("prompts"), MESSAGES("messages"), TEMPERATURE("temperature"), TOP_P("top_p"), TOP_K("top_k"),
	NUM_PREDICT("num_predict"), STOP("stop"), REPEAT_PENALTY("repeat_penalty"), PRESENCE_PENALTY("presence_penalty"),
	MAX_TOKENS("max_tokens"), FREQUENCY_PENALTY("frequency_penalty"), SEED("seed"), SYSTEM("system"),
	TEMPLATE("template"), CONTEXT("context"), RAW("raw");

	private final String key;

	OllamaParam(String key) {
		this.key = key;
	}

	public String getKey() {
		return key;
	}

	@Override
	public String toString() {
		return key;
	}

	public <T> void addTo(Map<String, Object> request, T value) {
		request.put(key, value);
	}

	@SuppressWarnings("unchecked")
	public <T> T getFrom(Map<String, Object> request) {
		return (T) request.get(key);
	}
}
