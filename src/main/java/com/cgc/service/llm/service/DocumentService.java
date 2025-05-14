package com.cgc.service.llm.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cgc.service.llm.config.LlmConfig;
import com.cgc.service.llm.constants.Constants;
import com.cgc.service.llm.dto.ContentChunkDto;
import com.cgc.service.llm.dto.DocumentDto;
import com.cgc.service.llm.dto.ProcessingStatusDto;
import com.cgc.service.llm.dto.QueryResponseDto;
import com.cgc.service.llm.dto.RankedChunkDto;
import com.cgc.service.llm.dto.TextChunkDto;
import com.cgc.service.llm.enums.ContentType;
import com.cgc.service.llm.exception.ApplicationException;
import com.cgc.service.llm.utils.ContentUtils;
import com.cgc.service.llm.utils.PdfUtils;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * @author: anascreations
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {
	private final LlmService llmService;
	private final EmbeddingService embeddingService;
	private final LlmConfig llmConfig;
	private final LoadingCache<String, List<TextChunkDto>> documentChunksCache = Caffeine.newBuilder().maximumSize(100)
			.expireAfterAccess(1, TimeUnit.HOURS).build(key -> loadDocumentChunksFromStorage(key));
	private final StorageService storageService;
	private final Map<String, ProcessingStatusDto> processingStatus = new ConcurrentHashMap<>();
	private final AtomicInteger activeProcessingCount = new AtomicInteger(0);
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@SneakyThrows
	public DocumentDto processDocument(MultipartFile file) {
		String fileId = UUID.randomUUID().toString();
		processingStatus.put(fileId, new ProcessingStatusDto(file.getOriginalFilename(), 0, "Starting"));
		activeProcessingCount.incrementAndGet();
		File tempFile = null;
		try {
			long startTime = System.currentTimeMillis();
			tempFile = File.createTempFile("doc_upload_", "_" + sanitizeFilename(file.getOriginalFilename()));
			try (InputStream inputStream = file.getInputStream();
					FileOutputStream outputStream = new FileOutputStream(tempFile)) {
				byte[] buffer = new byte[8192];
				int bytesRead;
				while ((bytesRead = inputStream.read(buffer)) != -1) {
					outputStream.write(buffer, 0, bytesRead);
				}
				outputStream.flush();
			}
			log.debug("Created temporary file: {}", tempFile.getAbsolutePath());
			String storedFilePath = storageService.storeFile(tempFile, file.getOriginalFilename());
			try (PDDocument pdDocument = PDDocument.load(file.getInputStream())) {
				DocumentDto document = new DocumentDto();
				document.setId(fileId);
				document.setFilename(file.getOriginalFilename());
				document.setPageCount(pdDocument.getNumberOfPages());
				document.setStoragePath(storedFilePath);
				updateProcessingStatus(fileId, 10, "Extracting content");
				int batchSize = 50;
				List<String> allPageContents = new ArrayList<>();
				List<String> allTables = new ArrayList<>();
				for (int i = 0; i < pdDocument.getNumberOfPages(); i += batchSize) {
					int endPage = Math.min(i + batchSize, pdDocument.getNumberOfPages());
					log.debug("Processing pages {} to {}", i, endPage);
					try (PDDocument batchDocument = new PDDocument()) {
						for (int j = i; j < endPage; j++) {
							batchDocument.addPage(pdDocument.getPage(j));
						}
						List<String> batchTables = ContentUtils.extractTables(batchDocument);
						List<String> batchPageContents = ContentUtils.extractPageTexts(batchDocument);
						allTables.addAll(batchTables);
						allPageContents.addAll(batchPageContents);
					}
					int progress = (int) (((double) endPage / pdDocument.getNumberOfPages()) * 50);
					updateProcessingStatus(fileId, 10 + progress,
							"Extracted " + endPage + "/" + pdDocument.getNumberOfPages() + " pages");
				}
				updateProcessingStatus(fileId, 60, "Processing tables");
				List<ContentChunkDto> tableChunks = allTables.parallelStream().filter(table -> !table.trim().isEmpty())
						.map(tableText -> {
							float[] embedding = embeddingService.generateEmbedding(tableText);
							return new ContentChunkDto(tableText, embedding, 1, document.getPageCount(),
									ContentType.TABLE);
						}).toList();
				updateProcessingStatus(fileId, 70, "Generating semantic chunks");
				List<String> textChunks = generateSemanticChunks(allPageContents);
				updateProcessingStatus(fileId, 75, "Creating embeddings");
				List<ContentChunkDto> contentChunks = new ArrayList<>();
				int batchCounter = 0;
				List<String> embeddingBatch = new ArrayList<>();
				Map<String, ContentType> contentTypeMap = new HashMap<>();
				for (String chunkText : textChunks) {
					if (chunkText.trim().isEmpty()) {
						continue;
					}
					ContentType contentType = ContentUtils.detectContentType(chunkText);
					String formattedContent = ContentUtils.formatContentByType(chunkText, contentType);
					embeddingBatch.add(formattedContent);
					contentTypeMap.put(formattedContent, contentType);
					batchCounter++;
					if (batchCounter >= 20 || contentChunks.size() + batchCounter == textChunks.size()) {
						List<float[]> embeddings = embeddingService.generateEmbeddings(embeddingBatch);
						for (int i = 0; i < embeddingBatch.size(); i++) {
							String content = embeddingBatch.get(i);
							ContentType type = contentTypeMap.get(content);
							float[] embedding = embeddings.get(i);
							contentChunks
									.add(new ContentChunkDto(content, embedding, 1, document.getPageCount(), type));
						}
						embeddingBatch.clear();
						contentTypeMap.clear();
						batchCounter = 0;
						int progress = 75 + (int) ((double) contentChunks.size() / textChunks.size() * 20);
						updateProcessingStatus(fileId, Math.min(95, progress),
								"Created embeddings for " + contentChunks.size() + "/" + textChunks.size() + " chunks");
					}
				}
				updateProcessingStatus(fileId, 95, "Finalizing document");
				List<TextChunkDto> allChunks = new ArrayList<>();
				for (ContentChunkDto tableChunk : tableChunks) {
					TextChunkDto textChunk = convertToTextChunkDto(tableChunk);
					allChunks.add(textChunk);
				}
				for (ContentChunkDto contentChunk : contentChunks) {
					TextChunkDto textChunk = convertToTextChunkDto(contentChunk);
					allChunks.add(textChunk);
				}
				document.setChunksCount(allChunks.size());
				document.setProcessedTime(System.currentTimeMillis() - startTime);
				storageService.storeDocumentChunks(document.getId(), allChunks);
				storageService.storeDocumentMetadata(document);
				updateProcessingStatus(fileId, 100, "Completed");
				log.info("Document processed: {} with {} chunks in {}ms", document.getFilename(), allChunks.size(),
						document.getProcessedTime());
				return document;
			}
		} catch (Exception e) {
			updateProcessingStatus(fileId, -1, "Failed: " + e.getMessage());
			log.error("Error processing file: " + file.getOriginalFilename(), e);
			throw e;
		} finally {
			activeProcessingCount.decrementAndGet();
		}
	}

	private TextChunkDto convertToTextChunkDto(ContentChunkDto contentChunk) {
		TextChunkDto textChunk = new TextChunkDto();
		textChunk.setText(contentChunk.getText());
		textChunk.setEmbedding(contentChunk.getEmbedding());
		textChunk.setContentType(contentChunk.getContentType());
		return textChunk;
	}

	public CompletableFuture<List<DocumentDto>> processDocumentsAsync(MultipartFile[] files) {
		List<CompletableFuture<DocumentDto>> futures = Arrays.stream(files)
				.map(file -> CompletableFuture.supplyAsync(() -> {
					try {
						return processDocument(file);
					} catch (Exception e) {
						log.error("Error processing file: " + file.getOriginalFilename(), e);
						return null;
					}
				}, executor)).toList();
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
				.thenApply(v -> futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList());
	}

	public List<DocumentDto> processDocuments(MultipartFile[] files) {
		List<DocumentDto> documents = new ArrayList<>();
		for (MultipartFile file : files) {
			try {
				DocumentDto document = processDocument(file);
				documents.add(document);
			} catch (Exception e) {
				log.error("Error processing file: " + file.getOriginalFilename(), e);
			}
		}
		return documents;
	}

	private List<String> generateSemanticChunks(List<String> pageContents) {
		List<String> result = new ArrayList<>();
		StringBuilder currentChunk = new StringBuilder();
		int chunkSize = llmConfig.getChunkSize();
		int currentSize = 0;
		ContentType lastDetectedType = null;
		boolean inList = false;
		boolean inTable = false;
		int paragraphCounter = 0;
		String currentSection = "";
		for (String pageContent : pageContents) {
			List<String> headings = extractHeadings(pageContent);
			if (!headings.isEmpty() && !currentSection.equals(headings.get(0))) {
				if (currentSize > 0) {
					result.add(currentChunk.toString());
					currentChunk = new StringBuilder();
					currentSize = 0;
				}
				currentSection = headings.get(0);
			}
//			List<String> paragraphs = ContentUtils.splitIntoParagraphs(pageContent);
			List<String> paragraphs = PdfUtils.splitIntoParagraphs(pageContent);
			for (String paragraph : paragraphs) {
				if (paragraph.trim().isEmpty()) {
					continue;
				}
				ContentType currentType = ContentUtils.detectContentType(paragraph);
				boolean isSpecialContent = isSpecialContentType(currentType);
				boolean isTypeChange = lastDetectedType != currentType;
				boolean shouldStartNewChunk = false;
				if (currentSize > 0 && isTypeChange && (isSpecialContent || isSpecialContentType(lastDetectedType))) {
					shouldStartNewChunk = true;
				}
				if (currentSize > 0 && currentSize + paragraph.length() > chunkSize) {
					shouldStartNewChunk = true;
				}
				if (currentType == ContentType.TABLE || paragraph.startsWith("- ") || paragraph.startsWith("* ")
						|| paragraph.matches("\\d+\\.\\s.*")) {
					if (currentSize > 0 && !inList) {
						shouldStartNewChunk = true;
					}
					inList = true;
				} else {
					inList = false;
				}
				if (paragraph.trim().matches("^[0-9.]+\\s+.*") || paragraph.trim().matches("^[A-Z][A-Za-z\\s]+$")
						|| paragraph.trim().matches("^[A-Z][A-Za-z\\s]+:$")) {
					if (currentSize > 0) {
						shouldStartNewChunk = true;
					}
				}
				if (shouldStartNewChunk) {
					result.add(currentChunk.toString());
					currentChunk = new StringBuilder();
					currentSize = 0;
					if (!currentSection.isEmpty()) {
						currentChunk.append("[Section: ").append(currentSection).append("]\n\n");
						currentSize += currentSection.length() + 13;
					}
				}
				if (currentSize > 0) {
					currentChunk.append("\n\n");
					currentSize += 2;
				}
				currentChunk.append(paragraph);
				currentSize += paragraph.length();
				lastDetectedType = currentType;
				paragraphCounter++;
				if (isSpecialContent && currentSize > 0) {
					result.add(currentChunk.toString());
					currentChunk = new StringBuilder();
					currentSize = 0;
					lastDetectedType = null;
					paragraphCounter = 0;
				}
			}
		}
		if (currentSize > 0) {
			result.add(currentChunk.toString());
		}
		return result;
	}

	private boolean isSpecialContentType(ContentType type) {
		return type == ContentType.CODE_JAVA || type == ContentType.CODE_PYTHON || type == ContentType.CODE_JAVASCRIPT
				|| type == ContentType.CODE_CSHARP || type == ContentType.CODE_SQL || type == ContentType.CODE_OTHER
				|| type == ContentType.TABLE;
	}

	private List<String> extractHeadings(String text) {
		List<String> headings = new ArrayList<>();
		Pattern headingPattern = Pattern
				.compile("(?m)^\\s*((?:[0-9]+\\.)+\\s+[A-Za-z][A-Za-z\\s]+|[A-Z][A-Za-z\\s]+:?)$");
		Matcher matcher = headingPattern.matcher(text);
		while (matcher.find()) {
			headings.add(matcher.group(1).trim());
		}
		return headings;
	}

	public QueryResponseDto queryDocuments(List<String> documentIds, String question, int maxResults,
			float minRelevanceScore) {
		long startTime = System.currentTimeMillis();
		String text = enhanceQuestion(question);
		log.info("Question: " + text);
		List<TextChunkDto> allChunks = fetchAllDocumentChunks(documentIds);
		if (allChunks.isEmpty()) {
			throw new ApplicationException("No valid documents found for the provided IDs");
		}
//		log.debug("Retrieved {} chunks from {} documents", allChunks.size(), documentIds.size());
		float[] queryEmbedding = embeddingService.generateEmbedding(text);
		float initialThreshold = Math.min(minRelevanceScore, 0.3f);
		int totalMaxResults = maxResults * 3;
		List<RankedChunkDto> rankedChunks = rankChunksByRelevance(allChunks, queryEmbedding, totalMaxResults,
				initialThreshold);
//		log.debug("Found {} chunks above relevance threshold {}", rankedChunks.size(), initialThreshold);
		if (rankedChunks.isEmpty()) {
			log.debug("No chunks found with embedding similarity, trying keyword matching");
			rankedChunks = rankChunksByKeywords(allChunks, question, totalMaxResults);
		}
		if (rankedChunks.isEmpty()) {
			return QueryResponseDto.builder()
					.answer("I don't have enough information to answer this question based on the documents provided.")
					.confidenceScore(0.0f).processingTimeMs(System.currentTimeMillis() - startTime).build();
		}
		List<TextChunkDto> selectedChunks = selectDiverseChunks(rankedChunks, maxResults);
		String context = prepareEnhancedContext(selectedChunks, question);
//		log.debug("Prepared context with {} characters from {} chunks", context.length(), selectedChunks.size());
		String prompt = buildImprovedPrompt(question, context, rankedChunks);
		String answer = llmService.generateResponse(prompt);
		float avgConfidence = (float) rankedChunks.stream().mapToDouble(RankedChunkDto::getScore).average().orElse(0.0);
		return QueryResponseDto.builder().answer(answer).confidenceScore(avgConfidence).relevantChunks(selectedChunks)
				.processingTimeMs(System.currentTimeMillis() - startTime).build();
	}

	public Flux<QueryResponseDto> queryDocumentsStream(List<String> documentIds, String question, int maxResults,
			float minRelevanceScore) {
		return Flux.create(sink -> {
			try {
				long startTime = System.currentTimeMillis();
				sink.next(
						QueryResponseDto.builder().status("PROCESSING").description("Starting document query").build());
				String enhancedQuestion = enhanceQuestion(question);
				List<TextChunkDto> allChunks = fetchAllDocumentChunks(documentIds);
				if (allChunks.isEmpty()) {
					sink.next(QueryResponseDto.builder().status("ERROR")
							.answer("No valid documents found for the provided IDs").confidenceScore(0.0f).build());
					sink.complete();
					return;
				}
				sink.next(QueryResponseDto
						.builder().status("PROCESSING").description(String
								.format("Retrieved %d chunks from %d documents", allChunks.size(), documentIds.size()))
						.build());
				float[] queryEmbedding = embeddingService.generateEmbedding(enhancedQuestion);
				float initialThreshold = Math.min(minRelevanceScore, 0.3f);
				int totalMaxResults = maxResults * 3;
				List<RankedChunkDto> rankedChunks = rankChunksByRelevance(allChunks, queryEmbedding, totalMaxResults,
						initialThreshold);
				sink.next(QueryResponseDto.builder().status("PROCESSING").description(String
						.format("Found %d chunks above relevance threshold %f", rankedChunks.size(), initialThreshold))
						.build());
				if (rankedChunks.isEmpty()) {
					log.debug("No chunks found with embedding similarity, trying keyword matching");
					rankedChunks = rankChunksByKeywords(allChunks, question, totalMaxResults);
				}
				if (rankedChunks.isEmpty()) {
					sink.next(QueryResponseDto.builder().status("NO_RESULTS").answer(
							"I don't have enough information to answer this question based on the documents provided.")
							.confidenceScore(0.0f).processingTimeMs(System.currentTimeMillis() - startTime).build());
					sink.complete();
					return;
				}
				List<TextChunkDto> selectedChunks = selectDiverseChunks(rankedChunks, maxResults);
				sink.next(QueryResponseDto.builder().status("PROCESSING")
						.description(String.format("Selected %d diverse chunks", selectedChunks.size())).build());
				String context = prepareEnhancedContext(selectedChunks, question);
				String prompt = buildImprovedPrompt(question, context, rankedChunks);
				String answer = llmService.generateResponse(prompt);
				float avgConfidence = (float) rankedChunks.stream().mapToDouble(RankedChunkDto::getScore).average()
						.orElse(0.0);
				QueryResponseDto finalResponse = QueryResponseDto.builder().status("COMPLETED").answer(answer)
						.confidenceScore(avgConfidence).relevantChunks(selectedChunks)
						.processingTimeMs(System.currentTimeMillis() - startTime).build();
				sink.next(finalResponse);
				sink.complete();
			} catch (Exception e) {
				sink.next(QueryResponseDto.builder().status("ERROR")
						.answer("An error occurred during document querying: " + e.getMessage()).confidenceScore(0.0f)
						.build());
				sink.error(e);
			}
		});
	}

	public Flux<String> queryDocumentsStreamAsString(List<String> documentIds, String question, int maxResults,
			float minRelevanceScore) {
		return Flux.create(sink -> {
			try {
				long startTime = System.currentTimeMillis();
				String enhancedQuestion = enhanceQuestion(question);
				List<TextChunkDto> allChunks = fetchAllDocumentChunks(documentIds);
				if (allChunks.isEmpty()) {
					sink.next("No valid documents found for the provided IDs");
					sink.complete();
					return;
				}
				float[] queryEmbedding = embeddingService.generateEmbedding(enhancedQuestion);
				float initialThreshold = Math.min(minRelevanceScore, 0.3f);
				int totalMaxResults = maxResults * 3;
				List<RankedChunkDto> rankedChunks = rankChunksByRelevance(allChunks, queryEmbedding, totalMaxResults,
						initialThreshold);
				if (rankedChunks.isEmpty()) {
					log.debug("No chunks found with embedding similarity, trying keyword matching");
					rankedChunks = rankChunksByKeywords(allChunks, question, totalMaxResults);
				}
				if (rankedChunks.isEmpty()) {
					sink.next(
							"I don't have enough information to answer this question based on the documents provided.");
					sink.complete();
					return;
				}
				List<TextChunkDto> selectedChunks = selectDiverseChunks(rankedChunks, maxResults);
				String context = prepareEnhancedContext(selectedChunks, question);
				String prompt = buildImprovedPrompt(question, context, rankedChunks);
				AtomicBoolean isFirstChunk = new AtomicBoolean(true);
				llmService.generateAnswerStreaming(prompt, chunk -> {
					if (isFirstChunk.getAndSet(false)) {
						chunk = chunk.replaceAll("(?i)^(based on|according to) the (provided |)context,?\\s*", "");
						chunk = chunk.replaceAll("(?i)^(the answer is|to answer your question)[,:]?\\s*", "");
					}
					sink.next(chunk);
				}, () -> {
					log.info("Answer generation completed in {} ms", System.currentTimeMillis() - startTime);
					sink.complete();
				});
			} catch (Exception e) {
				log.error("Error during document querying", e);
				sink.next("Error generating answer: " + e.getMessage());
				sink.error(e);
			}
		}, FluxSink.OverflowStrategy.BUFFER);
	}

	private String enhanceQuestion(String question) {
		String lowerQuestion = question.toLowerCase();
		lowerQuestion = lowerQuestion.replace("who is", "who is information about person details");
		lowerQuestion = lowerQuestion.replace("what is", "what is information about details");
		lowerQuestion = lowerQuestion.replace("when", "when date time");
		lowerQuestion = lowerQuestion.replace("where", "where location place");
		lowerQuestion = lowerQuestion.replace("how much", "how much amount cost price value");
		lowerQuestion = lowerQuestion.replace("salary", "salary income compensation");
		return lowerQuestion + " " + question;
	}

	private List<RankedChunkDto> rankChunksByKeywords(List<TextChunkDto> chunks, String question, int maxResults) {
		Set<String> keywords = extractKeywords(question.toLowerCase());
		return chunks.stream().map(chunk -> {
			String lowerText = chunk.getText().toLowerCase();
			long matchCount = keywords.stream().filter(lowerText::contains).count();
			float score = matchCount > 0 ? (float) matchCount / keywords.size() : 0f;
			return new RankedChunkDto(chunk, score);
		}).filter(rankedChunk -> rankedChunk.getScore() > 0)
				.sorted(Comparator.comparing(RankedChunkDto::getScore).reversed()).limit(maxResults).toList();
	}

	private Set<String> extractKeywords(String question) {
		Set<String> stopWords = Set.of("a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "in", "on",
				"at", "to", "for", "with", "by", "about", "like", "through", "over", "before", "after", "between",
				"under", "above", "of", "and", "or", "not", "no", "but", "pay", "payment", "paying");
		return Arrays.stream(question.split("\\W+")).filter(word -> word.length() > 1)
				.filter(word -> !stopWords.contains(word)).collect(Collectors.toSet());
	}

	private List<TextChunkDto> fetchAllDocumentChunks(List<String> documentIds) {
		try {
			List<CompletableFuture<List<TextChunkDto>>> futures = documentIds.stream()
					.map(documentId -> CompletableFuture.supplyAsync(() -> {
						try {
							return documentChunksCache.get(documentId);
						} catch (Exception e) {
							log.error("Failed to retrieve chunks for document: " + documentId, e);
							return Collections.<TextChunkDto>emptyList();
						}
					}, executor)).toList();
			return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
					.thenApply(v -> futures.stream().map(CompletableFuture::join).flatMap(List::stream).toList())
					.get(30, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new ApplicationException("Failed to retrieve document chunks", e);
		}
	}

	private List<TextChunkDto> loadDocumentChunksFromStorage(String documentId) {
		return storageService.loadDocumentChunks(documentId);
	}

	private List<RankedChunkDto> rankChunksByRelevance(List<TextChunkDto> chunks, float[] queryEmbedding,
			int maxResults, float minRelevanceScore) {
		return chunks.parallelStream().map(chunk -> {
			float similarity = calculateCosineSimilarityOptimize(chunk.getEmbedding(), queryEmbedding);
			return new RankedChunkDto(chunk, similarity);
		}).filter(rankedChunk -> rankedChunk.getScore() >= minRelevanceScore)
				.sorted(Comparator.comparing(RankedChunkDto::getScore).reversed()).limit(maxResults).toList();
	}

	private List<TextChunkDto> selectDiverseChunks(List<RankedChunkDto> rankedChunks, int maxResults) {
		if (rankedChunks.size() <= maxResults) {
			return rankedChunks.stream().map(RankedChunkDto::getChunk).toList();
		}
		List<RankedChunkDto> selected = new ArrayList<>();
		Set<String> seenContent = new HashSet<>();
		selected.add(rankedChunks.get(0));
		seenContent.add(normalizeContent(rankedChunks.get(0).getChunk().getText()));
		for (int i = 1; i < rankedChunks.size() && selected.size() < maxResults; i++) {
			RankedChunkDto current = rankedChunks.get(i);
			String normalizedContent = normalizeContent(current.getChunk().getText());
			boolean isDiverse = true;
			for (String existingContent : seenContent) {
				if (calculateJaccardSimilarity(normalizedContent, existingContent) > 0.7) {
					isDiverse = false;
					break;
				}
			}
			if (isDiverse) {
				selected.add(current);
				seenContent.add(normalizedContent);
			}
		}
		return selected.stream().map(RankedChunkDto::getChunk).toList();
	}

	private String normalizeContent(String content) {
		return content.toLowerCase().replaceAll("\\s+", " ").replaceAll("[^a-z0-9 ]", "").trim();
	}

	private double calculateJaccardSimilarity(String text1, String text2) {
		Set<String> set1 = new HashSet<>(Arrays.asList(text1.split("\\s+")));
		Set<String> set2 = new HashSet<>(Arrays.asList(text2.split("\\s+")));
		Set<String> intersection = new HashSet<>(set1);
		intersection.retainAll(set2);
		Set<String> union = new HashSet<>(set1);
		union.addAll(set2);
		if (union.isEmpty()) {
			return 0.0;
		}
		return (double) intersection.size() / union.size();
	}

	private float calculateCosineSimilarityOptimize(float[] embedding1, float[] embedding2) {
		if (embedding1 == null || embedding2 == null || embedding1.length != embedding2.length
				|| embedding1.length == 0) {
			return 0.0f;
		}
		float dotProduct = 0.0f;
		float norm1 = 0.0f;
		float norm2 = 0.0f;
		int i = 0;
		final int VECTOR_SIZE = 4;
		final int limit = embedding1.length - (embedding1.length % VECTOR_SIZE);
		for (; i < limit; i += VECTOR_SIZE) {
			dotProduct += embedding1[i] * embedding2[i];
			dotProduct += embedding1[i + 1] * embedding2[i + 1];
			dotProduct += embedding1[i + 2] * embedding2[i + 2];
			dotProduct += embedding1[i + 3] * embedding2[i + 3];

			norm1 += embedding1[i] * embedding1[i];
			norm1 += embedding1[i + 1] * embedding1[i + 1];
			norm1 += embedding1[i + 2] * embedding1[i + 2];
			norm1 += embedding1[i + 3] * embedding1[i + 3];

			norm2 += embedding2[i] * embedding2[i];
			norm2 += embedding2[i + 1] * embedding2[i + 1];
			norm2 += embedding2[i + 2] * embedding2[i + 2];
			norm2 += embedding2[i + 3] * embedding2[i + 3];
		}
		for (; i < embedding1.length; i++) {
			dotProduct += embedding1[i] * embedding2[i];
			norm1 += embedding1[i] * embedding1[i];
			norm2 += embedding2[i] * embedding2[i];
		}
		if (norm1 <= 0.0f || norm2 <= 0.0f) {
			return 0.0f;
		}
		return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
	}

	private String prepareEnhancedContext(List<TextChunkDto> chunks, String question) {
		Map<ContentType, List<TextChunkDto>> chunksByType = chunks.stream().collect(Collectors
				.groupingBy(chunk -> chunk.getContentType() != null ? chunk.getContentType() : ContentType.TEXT));
		StringBuilder context = new StringBuilder();
		if (chunksByType.containsKey(ContentType.TABLE)) {
			context.append("### Tables\n\n");
			for (TextChunkDto chunk : chunksByType.get(ContentType.TABLE)) {
				context.append(chunk.getText()).append("\n\n");
			}
		}
		List<ContentType> codeTypes = Arrays.asList(ContentType.CODE_JAVA, ContentType.CODE_PYTHON,
				ContentType.CODE_JAVASCRIPT, ContentType.CODE_CSHARP, ContentType.CODE_SQL, ContentType.CODE_OTHER);
		for (ContentType codeType : codeTypes) {
			if (chunksByType.containsKey(codeType)) {
				context.append("### ").append(codeType.name()).append("\n\n");
				for (TextChunkDto chunk : chunksByType.get(codeType)) {
					context.append(chunk.getText()).append("\n\n");
				}
			}
		}
		if (chunksByType.containsKey(ContentType.TEXT)) {
			context.append("### Document Text\n\n");
			for (TextChunkDto chunk : chunksByType.get(ContentType.TEXT)) {
				context.append(chunk.getText()).append("\n\n");
			}
		}
		return context.toString();
	}

	private String buildImprovedPrompt(String question, String context, List<RankedChunkDto> rankedChunks) {
		StringBuilder instructions = new StringBuilder();
		boolean containsTables = context.contains("### Tables");
		boolean containsCode = context.contains("CODE_JAVA") || context.contains("CODE_PYTHON");
		boolean containsMath = context.contains("Math Formula:");
		if (containsTables) {
			instructions.append("- The context contains tables. When answering questions about tabular data, "
					+ "refer to specific values, column headers, and relationships between data points. "
					+ "Present numerical analysis of the table data when appropriate.\n");
		}
		if (containsCode) {
			instructions.append("- The context contains code snippets. When answering questions about code, "
					+ "explain the functionality clearly and refer to specific parts of the code in your explanation.\n");
		}
		if (containsMath) {
			instructions.append("- The context contains mathematical formulas. Explain the meaning of "
					+ "variables and operations in the formulas when relevant to the question.\n");
		}
		Set<String> keywords = extractKeywords(question);
		if (!keywords.isEmpty()) {
			instructions.append("- The question focuses on these key concepts: ").append(String.join(", ", keywords))
					.append(". Pay special attention to these terms in the context.\n");
		}
		float avgConfidence = (float) rankedChunks.stream().mapToDouble(RankedChunkDto::getScore).average().orElse(0.0);
		instructions.append("- The average relevance score of the retrieved content is ")
				.append(String.format("%.2f", avgConfidence)).append(". ");
		if (avgConfidence < 0.7) {
			instructions.append("Be cautious as the retrieved content may not fully address the question. "
					+ "If you cannot find a clear answer, be transparent about this limitation.\n");
		} else if (avgConfidence > 0.85) {
			instructions.append("The retrieved content appears highly relevant to the question. "
					+ "Answer with confidence based on the provided information.\n");
		}
		return String.format("""
				You are an intelligent document assistant that provides precise answers based on the provided context.

				INSTRUCTIONS:
				%s
				Answer the question based ONLY on the information provided in the context below.
				If the context doesn't contain the information needed to answer the question, explicitly state:
				"Based on the provided document content, I cannot find information about [specific topic]."

				Be specific and direct in your answer. Include relevant details from the context, such as:
				- Numerical values, percentages, or ranges when present
				- Specific terms and definitions exactly as they appear in the document
				- Quoted phrases from the document when they directly answer the question

				CONTEXT:
				%s

				QUESTION: %s

				ANSWER:
				""", instructions.toString(), context, question);
	}

	public Map<String, Object> getProcessingStatus() {
		Map<String, Object> statusInfo = new HashMap<>();
		statusInfo.put("activeProcessingCount", activeProcessingCount.get());
		statusInfo.put("totalDocumentsInProgress", processingStatus.size());
		Map<String, ProcessingStatusDto> recentStatus = processingStatus.entrySet().stream().filter(entry -> {
			ProcessingStatusDto status = entry.getValue();
			return status.getProgress() < 100 && status.getProgress() >= 0
					|| status.getLastUpdated() > System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
		}).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		Map<String, List<ProcessingStatusDto>> documentsByState = recentStatus.values().stream()
				.collect(Collectors.groupingBy(status -> {
					if (status.getProgress() < 0)
						return Constants.FAILED;
					if (status.getProgress() >= 100)
						return Constants.COMPLETED;
					return Constants.PROCESSING;
				}));
		statusInfo.put(Constants.PROCESSING,
				documentsByState.getOrDefault(Constants.PROCESSING, Collections.emptyList()).size());
		statusInfo.put(Constants.COMPLETED,
				documentsByState.getOrDefault(Constants.COMPLETED, Collections.emptyList()).size());
		statusInfo.put(Constants.FAILED,
				documentsByState.getOrDefault(Constants.FAILED, Collections.emptyList()).size());
		statusInfo.put("documents", recentStatus);
		return statusInfo;
	}

	private void updateProcessingStatus(String documentId, int progress, String message) {
		ProcessingStatusDto status = processingStatus.get(documentId);
		if (status != null) {
			status.update(progress, message);
			log.debug("Document {} processing status: {}% - {}", documentId, progress, message);
		}
	}

	public List<String> getAllDocumentIds() {
		return storageService.getAllDocumentIds();
	}

	public DocumentDto getDocumentMetadata(String documentId) {
		return storageService.getDocumentMetadata(documentId);
	}

	public DocumentDto getDocument(String documentId) {
		DocumentDto document = storageService.getDocumentMetadata(documentId);
		if (document == null) {
			log.warn("Document not found: {}", documentId);
			return null;
		}
		ProcessingStatusDto status = processingStatus.get(documentId);
		if (status != null) {
			document.setProcessingStatus(status.getProgress());
			document.setProcessingMessage(status.getMessage());
		}
		return document;
	}

	public boolean removeDocument(String documentId) {
		DocumentDto document = storageService.getDocumentMetadata(documentId);
		if (document == null) {
			log.warn("Cannot remove document - not found: {}", documentId);
			return false;
		}
		processingStatus.remove(documentId);
		if (documentChunksCache.getIfPresent(documentId) != null) {
			documentChunksCache.invalidate(documentId);
		}
		boolean deleted = storageService.deleteDocument(documentId);
		if (deleted) {
			log.info("Document deleted successfully: {}", documentId);
		} else {
			log.error("Failed to delete document: {}", documentId);
		}
		return deleted;
	}

//	public int removeAllDocuments() {
//		List<String> documentIds = storageService.getAllDocumentIds();
//		if (documentIds.isEmpty()) {
//			log.info("No documents to delete");
//			return 0;
//		}
//		log.info("Deleting all {} documents", documentIds.size());
//		processingStatus.clear();
//		documentChunksCache.invalidateAll();
//		int deletedCount = 0;
//		for (String documentId : documentIds) {
//			try {
//				boolean deleted = storageService.deleteDocument(documentId);
//				if (deleted) {
//					deletedCount++;
//				} else {
//					log.warn("Failed to delete document: {}", documentId);
//				}
//			} catch (Exception e) {
//				log.error("Error deleting document: {}", documentId, e);
//			}
//		}
//		log.info("Successfully deleted {} out of {} documents", deletedCount, documentIds.size());
//		return deletedCount;
//	}

	public int removeAllDocuments() {
		processingStatus.clear();
		documentChunksCache.invalidateAll();
		return storageService.deleteAllDocuments();
	}

	private String sanitizeFilename(String filename) {
		if (filename == null) {
			return "unknown";
		}
		return new File(filename).getName().replaceAll("[^a-zA-Z0-9.-]", "_");
	}

	public Map<String, String> analyzePdf(MultipartFile file) {
		String pdfText = PdfUtils.extractText(file);
		if (pdfText.length() > 8000) {
			pdfText = pdfText.substring(0, 8000);
		}
		String summary = llmService.generateSummary(pdfText);
		String recommendations = llmService.generateRecommendations(pdfText);
		Map<String, String> result = new HashMap<>();
		result.put("summary", summary);
		result.put("recommendations", recommendations);
		return result;
	}

	public Flux<ServerSentEvent<String>> summarizeDocument(MultipartFile file) {
		try {
			String pdfText = PdfUtils.extractText(file);
			return Flux.create(sink -> {
				AtomicBoolean completed = new AtomicBoolean(false);
				llmService.generateSummaryStreaming(pdfText, chunk -> {
					sink.next(ServerSentEvent.<String>builder().id(UUID.randomUUID().toString()).event("chunk")
							.data(chunk).build());
				}, () -> {
					log.info("Summary generation completed");
					completed.set(true);
					sink.complete();
				});
				sink.onDispose(() -> {
					log.info("Client disconnected from summary stream");
					if (!completed.get()) {
						log.warn("Stream disposed before completion");
					}
				});
			}, FluxSink.OverflowStrategy.BUFFER);
		} catch (Exception e) {
			log.error("Error extracting text from PDF", e);
			return Flux.just(ServerSentEvent.<String>builder().event("error")
					.data("Error processing document: " + e.getMessage()).build());
		}
	}

}
