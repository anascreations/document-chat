package com.cgc.service.llm.dto;

import java.io.Serializable;

import lombok.Data;

/**
 * @author: anascreations
 *
 */
@Data
public class QueryRequestDto implements Serializable {
	private static final long serialVersionUID = 6074966112165255442L;
	private String question;
}
