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
public class TextChunkDto implements Serializable {
	private static final long serialVersionUID = -6755676913493255700L;
	private String text;
	private float[] embedding;
	private int startPage;
	private int endPage;
	private ContentType contentType;
}