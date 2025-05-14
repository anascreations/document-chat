package com.cgc.service.llm.dto;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author: anascreations
 *
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RankedChunkDto implements Serializable {
	private static final long serialVersionUID = -5000069285069098641L;
	private TextChunkDto chunk;
	private float score;

}
