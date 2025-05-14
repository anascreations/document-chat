package com.cgc.service.llm.dto;

import java.util.List;

import lombok.Data;

/**
 * @author: anascreations
 *
 */
@Data
public class ModelsListDto {
	private List<ModelInfo> models;

	@Data
	public static class ModelInfo {
		private String name;
		private String modifiedAt;
		private long size;
		private String digest;
	}
}
