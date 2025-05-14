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
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponseDto implements Serializable {
	private static final long serialVersionUID = 3959547389525427126L;
	private String id;
	private String filename;
	private int pageCount;
	private long processedTime;
}
