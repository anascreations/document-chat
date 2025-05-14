package com.cgc.service.llm.response;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

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
@JsonPropertyOrder({ "status", "statusCode", "messageCode", "description" })
public class ApiResponse {
	private String status;
	private Integer statusCode;
	private String messageCode;
	private String description;
	@JsonIgnore
	private Map<String, Object> properties = new HashMap<>();
	@JsonIgnore
	private boolean success;
	@JsonIgnore
	private String message;
	@JsonIgnore
	private Map<String, Object> data;
	@JsonIgnore
	private String errorCode;

	@JsonAnySetter
	public void set(String property, Object obj) {
		this.properties.put(property, obj);
	}

	public Object get(String property) {
		return this.properties.get(property);
	}

	public ApiResponse(Map<String, Object> properties) {
		this.properties = properties;
	}

	@JsonAnyGetter
	public Map<String, Object> getProperties() {
		return properties;
	}

	public void setProperties(Map<String, Object> properties) {
		this.properties = properties;
	}

	public ApiResponse put(String key, Object value) {
		properties.put(key, value);
		return this;
	}

	public static ResponseEntity<ApiResponse> success(String message) {
		ApiResponse response = new ApiResponse();
		response.setSuccess(true);
		response.setMessage(message);
		response.setData(new HashMap<>());
		return ResponseEntity.ok(response);
	}

	public static ResponseEntity<ApiResponse> success(String key, Object value) {
		ApiResponse response = new ApiResponse();
		response.setSuccess(true);
		response.setMessage("Success");
		Map<String, Object> data = assignData(key, value);
		response.setData(data);
		return ResponseEntity.ok(response);
	}

	public static ResponseEntity<ApiResponse> success(String message, String key, Object value) {
		ApiResponse response = new ApiResponse();
		response.setSuccess(true);
		response.setMessage(message);
		Map<String, Object> data = assignData(key, value);
		response.setData(data);
		return ResponseEntity.ok(response);
	}

	public static ResponseEntity<ApiResponse> error(String message) {
		ApiResponse response = new ApiResponse();
		response.setSuccess(false);
		response.setMessage(message);
		response.setData(new HashMap<>());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
	}

	public static ResponseEntity<ApiResponse> error(String message, String errorCode) {
		ApiResponse response = new ApiResponse();
		response.setSuccess(false);
		response.setMessage(message);
		response.setErrorCode(errorCode);
		response.setData(new HashMap<>());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
	}

	public static ResponseEntity<ApiResponse> exception(Exception e) {
		ApiResponse response = new ApiResponse();
		response.setSuccess(false);
		response.setMessage(e.getMessage());
		response.setErrorCode("INTERNAL_ERROR");
		response.setData(new HashMap<>());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
	}

	private static Map<String, Object> assignData(String key, Object value) {
		Map<String, Object> data = new HashMap<>();
		data.put(key, value);
		return data;
	}
}
