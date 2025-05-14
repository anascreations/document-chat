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
public class DocumentDto implements Serializable {
	private static final long serialVersionUID = 3241651909794683909L;
	private String id;
	private String filename;
	private int pageCount;
	private int chunksCount;
	private long processedTime;
	private String contentType;
	private int processingStatus;
	private String processingMessage;
	private String storagePath;
	private long fileSize;
}