package com.cgc.service.llm.client;

import java.util.Map;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import com.cgc.service.llm.dto.ChatResponseDto;
import com.cgc.service.llm.dto.EmbeddingDataDto;
import com.cgc.service.llm.dto.LlmDataDto;
import com.cgc.service.llm.dto.ModelsListDto;
import com.cgc.service.llm.dto.VersionInfoDto;

import reactor.core.publisher.Flux;

@HttpExchange(value = "api")
public interface LlmClient {

	@PostExchange("generate")
	LlmDataDto generate(@RequestBody Map<String, Object> param);

	@PostExchange("generate")
	Flux<Map<String, Object>> generateStream(@RequestBody Map<String, Object> param);

	@PostExchange("embeddings")
	EmbeddingDataDto embedding(@RequestBody Map<String, Object> param);

	@PostExchange("chat")
	ChatResponseDto chat(@RequestBody Map<String, Object> param);

	@PostExchange("chat")
	Flux<Map<String, Object>> chatStream(@RequestBody Map<String, Object> param);

	@GetExchange("tags")
	ModelsListDto listModels();

	@PostExchange("pull")
	Flux<Map<String, Object>> pullModel(@RequestBody Map<String, Object> param);

	@PostExchange("push")
	Flux<Map<String, Object>> pushModel(@RequestBody Map<String, Object> param);

	@PostExchange("create")
	Flux<Map<String, Object>> createModel(@RequestBody Map<String, Object> param);

	@PostExchange("copy")
	Map<String, Object> copyModel(@RequestBody Map<String, Object> param);

	@PostExchange("delete")
	Map<String, Object> deleteModel(@RequestBody Map<String, Object> param);

	@PostExchange("show")
	Object showModel(@RequestBody Map<String, Object> param);

	@GetExchange("version")
	VersionInfoDto getVersion();

}
