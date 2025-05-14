package com.cgc.service.llm.dto;

import java.io.Serializable;

import com.cgc.service.llm.enums.ContentType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author: anascreations
 *
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContentChunkDto implements Serializable {
	private static final long serialVersionUID = 1404171385146633723L;
	private String text;
	private float[] embedding;
	private int startPage;
	private int endPage;
	private ContentType contentType;
}
