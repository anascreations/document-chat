package com.cgc.service.llm.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.cgc.service.llm.constants.Constants;
import com.cgc.service.llm.dto.DocumentDto;
import com.cgc.service.llm.dto.DocumentResponseDto;
import com.cgc.service.llm.dto.QueryRequestDto;
import com.cgc.service.llm.dto.QueryResponseDto;
import com.cgc.service.llm.response.ApiResponse;
import com.cgc.service.llm.service.DocumentService;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * @author: anascreations
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("document")
public class DocumentController {
	private final DocumentService documentService;

	@PostMapping("upload")
	public ResponseEntity<ApiResponse> uploadMultipleFiles(@RequestParam MultipartFile[] files,
			@RequestParam(required = false, defaultValue = "false") boolean async) {
		try {
			long startTime = System.currentTimeMillis();
			if (async && files.length > 1) {
				CompletableFuture.runAsync(() -> {
					try {
						documentService.processDocumentsAsync(files);
					} catch (Exception e) {
						log.error("Background processing error", e);
					}
				});
				return ApiResponse.success(
						"Processing started for " + files.length + " files. Check status endpoint for progress.");
			} else {
				List<DocumentDto> documents = documentService.processDocuments(files);
				List<DocumentResponseDto> responses = documents.stream().map(doc -> new DocumentResponseDto(doc.getId(),
						doc.getFilename(), doc.getPageCount(), doc.getProcessedTime())).toList();
				long endTime = System.currentTimeMillis() - startTime;
				log.info("Processing time(ms): " + endTime);
				ApiResponse response = new ApiResponse();
				response.setStatus(Constants.SUCCESS);
				response.setStatusCode(HttpServletResponse.SC_OK);
				response.setMessageCode(Constants.SUCCESS_CODE);
				response.setDescription("");
				response.set("documents", responses);
				return ResponseEntity.ok(response);
			}
		} catch (Exception e) {
			log.error("Error", e);
			return ApiResponse.exception(e);
		}
	}

	@GetMapping("status")
	public ResponseEntity<ApiResponse> getProcessingStatus() {
		Map<String, Object> status = documentService.getProcessingStatus();
		return ApiResponse.success("status", status);
	}

	@PostMapping("query")
	public ResponseEntity<ApiResponse> queryDocuments(@RequestBody QueryRequestDto request,
			@RequestParam(required = false, defaultValue = "5") Integer maxResults,
			@RequestParam(required = false, defaultValue = "0.6") Float minRelevanceScore) {
		ApiResponse response = new ApiResponse();
		try {
			response.setStatus(Constants.SUCCESS);
			response.setStatusCode(HttpServletResponse.SC_OK);
			response.setMessageCode(Constants.SUCCESS_CODE);
			response.setDescription("");
			List<String> documentIds = documentService.getAllDocumentIds();
			if (documentIds.isEmpty()) {
				response.setDescription("No documents have been uploaded");
				return ResponseEntity.ok(response);
			}
			String question = request.getQuestion();
			QueryResponseDto queryResponse = documentService.queryDocuments(documentIds, question, maxResults,
					minRelevanceScore);
			response.set("documents", queryResponse);
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("Error", e);
			return ApiResponse.exception(e);
		}
	}

	@PostMapping(value = "query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> queryDocumentsStream(@RequestBody QueryRequestDto request,
			@RequestParam(required = false, defaultValue = "5") Integer maxResults,
			@RequestParam(required = false, defaultValue = "0.6") Float minRelevanceScore) {
		return Flux.create(sink -> {
			try {
				List<String> documentIds = documentService.getAllDocumentIds();
				if (documentIds.isEmpty()) {
					sink.next(ServerSentEvent.<String>builder().event("error").data("No documents have been uploaded")
							.build());
					sink.complete();
					return;
				}
				String question = request.getQuestion();
				documentService.queryDocumentsStreamAsString(documentIds, question, maxResults, minRelevanceScore)
						.subscribe(chunk -> {
							sink.next(ServerSentEvent.<String>builder().id(UUID.randomUUID().toString()).event("chunk")
									.data(chunk).build());
						}, error -> {
							log.error("Error during document query", error);
							sink.next(ServerSentEvent.<String>builder().event("error")
									.data("Error: " + error.getMessage()).build());
							sink.complete();
						}, () -> {
							sink.next(ServerSentEvent.<String>builder().event("complete").data("Query completed")
									.build());
							sink.complete();
						});
			} catch (Exception e) {
				log.error("Exception in query controller", e);
				sink.next(ServerSentEvent.<String>builder().event("error").data("Error: " + e.getMessage()).build());
				sink.complete();
			}
		}, FluxSink.OverflowStrategy.BUFFER);
	}

	@PostMapping(value = "summary/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> generateSummaryStream(@RequestParam MultipartFile file) {
		return documentService.summarizeDocument(file);
	}

	@GetMapping("{documentId}")
	public ResponseEntity<ApiResponse> getDocumentDetails(@PathVariable String documentId) {
		try {
			DocumentDto document = documentService.getDocument(documentId);
			if (document == null) {
				return ApiResponse.error("Document not found: " + documentId);
			}
			return ApiResponse.success("document", document);
		} catch (Exception e) {
			log.error("Error", e);
			return ApiResponse.exception(e);
		}
	}

	@DeleteMapping("{documentId}")
	public ResponseEntity<ApiResponse> deleteDocument(@PathVariable String documentId) {
		try {
			boolean removed = documentService.removeDocument(documentId);
			if (!removed) {
				return ApiResponse.error("Document not found: " + documentId);
			}
			return ApiResponse.success("Document deleted successfully");
		} catch (Exception e) {
			log.error("Error", e);
			return ApiResponse.exception(e);
		}
	}

	@DeleteMapping
	public ResponseEntity<ApiResponse> deleteAllDocuments() {
		try {
			int count = documentService.removeAllDocuments();
			return ApiResponse.success("Successfully deleted " + count + " documents");
		} catch (Exception e) {
			log.error("Error deleting all documents", e);
			return ApiResponse.exception(e);
		}
	}

}
