package com.cgc.service.llm.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cgc.service.llm.config.StorageConfig;
import com.cgc.service.llm.dto.DocumentDto;
import com.cgc.service.llm.dto.TextChunkDto;
import com.cgc.service.llm.exception.ApplicationException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @author: anascreations
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {
	private final StorageConfig storageConfig;
	private Cache<String, List<TextChunkDto>> chunksCache;
	private Cache<String, DocumentDto> metadataCache;

	@PostConstruct
	public void initializeCaches() {
		chunksCache = Caffeine.newBuilder().maximumSize(storageConfig.getMaxSize())
				.expireAfterAccess(storageConfig.getExpiryMinutes(), TimeUnit.MINUTES).build();
		metadataCache = Caffeine.newBuilder().maximumSize(storageConfig.getMaxSize())
				.expireAfterAccess(storageConfig.getExpiryMinutes(), TimeUnit.MINUTES).build();
		log.info("Storage service initialized with cache size: {}, expiry: {} minutes", storageConfig.getMaxSize(),
				storageConfig.getExpiryMinutes());
	}

	public String storeFile(File file, String originalFilename) {
		String filePath = storageConfig.getBasePath() + "/files/" + originalFilename;
		try {
			Files.createDirectories(Paths.get(storageConfig.getBasePath() + "/files"));
			Files.copy(file.toPath(), Paths.get(filePath), StandardCopyOption.REPLACE_EXISTING);
			return filePath;
		} catch (Exception e) {
			log.error("Failed to store file: {}", originalFilename, e);
			throw new ApplicationException("Failed to store file", e);
		}
	}

	public String storeFile(MultipartFile file) {
		String filePath = storageConfig.getBasePath() + "/files/" + file.getOriginalFilename();
		try {
			Files.createDirectories(Paths.get(storageConfig.getBasePath() + "/files"));
			file.transferTo(new File(filePath));
			return filePath;
		} catch (Exception e) {
			log.error("Failed to store file: {}", file.getOriginalFilename(), e);
			throw new ApplicationException("Failed to store file", e);
		}
	}

	public void storeDocumentChunks(String documentId, List<TextChunkDto> chunks) {
		try {
			Files.createDirectories(Paths.get(storageConfig.getBasePath() + "/chunks"));
			int batchSize = storageConfig.getChunkBatchSize();
			int totalChunks = chunks.size();
			for (int i = 0; i < totalChunks; i += batchSize) {
				int end = Math.min(i + batchSize, totalChunks);
				List<TextChunkDto> batch = new ArrayList<>(chunks.subList(i, end));
				String batchFilePath = storageConfig.getBasePath() + "/chunks/" + documentId + "_" + i + ".chunks";
				try (FileOutputStream fileOut = new FileOutputStream(batchFilePath);
						ObjectOutputStream objectOut = new ObjectOutputStream(fileOut)) {
					objectOut.writeObject(batch);
				}
			}
			String indexFilePath = storageConfig.getBasePath() + "/chunks/" + documentId + "_index.meta";
			try (FileOutputStream fileOut = new FileOutputStream(indexFilePath);
					ObjectOutputStream objectOut = new ObjectOutputStream(fileOut)) {
				objectOut.writeObject(totalChunks);
			}
			if (storageConfig.isCacheEnabled()) {
				chunksCache.put(documentId, chunks);
			}
			log.info("Stored {} chunks for document: {}", totalChunks, documentId);
		} catch (Exception e) {
			log.error("Failed to store document chunks for ID: {}", documentId, e);
			throw new ApplicationException("Failed to store document chunks", e);
		}
	}

	public void storeDocumentMetadata(DocumentDto document) {
		try {
			Files.createDirectories(Paths.get(storageConfig.getBasePath() + "/metadata"));
			String metadataFilePath = storageConfig.getBasePath() + "/metadata/" + document.getId() + ".meta";
			try (FileOutputStream fileOut = new FileOutputStream(metadataFilePath);
					ObjectOutputStream objectOut = new ObjectOutputStream(fileOut)) {
				objectOut.writeObject(document);
			}
			if (storageConfig.isCacheEnabled()) {
				metadataCache.put(document.getId(), document);
			}
		} catch (Exception e) {
			log.error("Failed to store document metadata for ID: {}", document.getId(), e);
			throw new ApplicationException("Failed to store document metadata", e);
		}
	}

	@SuppressWarnings("unchecked")
	public List<TextChunkDto> loadDocumentChunks(String documentId) {
		if (storageConfig.isCacheEnabled()) {
			List<TextChunkDto> cachedChunks = chunksCache.getIfPresent(documentId);
			if (cachedChunks != null) {
				log.debug("Cache hit for document chunks: {}", documentId);
				return cachedChunks;
			}
		}
		try {
			String indexFilePath = storageConfig.getBasePath() + "/chunks/" + documentId + "_index.meta";
			int totalChunks;
			try (FileInputStream fileIn = new FileInputStream(indexFilePath);
					ObjectInputStream objectIn = new ObjectInputStream(fileIn)) {
				totalChunks = (Integer) objectIn.readObject();
			}
			List<TextChunkDto> allChunks = new ArrayList<>();
			for (int i = 0; i < totalChunks; i += storageConfig.getChunkBatchSize()) {
				String batchFilePath = storageConfig.getBasePath() + "/chunks/" + documentId + "_" + i + ".chunks";
				try (FileInputStream fileIn = new FileInputStream(batchFilePath);
						ObjectInputStream objectIn = new ObjectInputStream(fileIn)) {
					List<TextChunkDto> batch = (List<TextChunkDto>) objectIn.readObject();
					allChunks.addAll(batch);
				}
			}
			if (storageConfig.isCacheEnabled()) {
				chunksCache.put(documentId, allChunks);
			}
			return allChunks;
		} catch (Exception e) {
			log.error("Failed to load document chunks for ID: {}", documentId, e);
			throw new ApplicationException("Failed to load document chunks for ID: " + documentId, e);
		}
	}

	public List<String> getAllDocumentIds() {
		try {
			Path metadataPath = Paths.get(storageConfig.getBasePath() + "/metadata");
			if (!Files.exists(metadataPath)) {
				return Collections.emptyList();
			}
			return Files.list(metadataPath).filter(path -> path.toString().endsWith(".meta")).map(path -> {
				String filename = path.getFileName().toString();
				return filename.substring(0, filename.length() - 5);
			}).toList();
		} catch (Exception e) {
			log.error("Error retrieving document IDs", e);
			return Collections.emptyList();
		}
	}

	public DocumentDto getDocumentMetadata(String documentId) {
		if (storageConfig.isCacheEnabled()) {
			DocumentDto cachedMetadata = metadataCache.getIfPresent(documentId);
			if (cachedMetadata != null) {
				return cachedMetadata;
			}
		}
		try {
			String metadataFilePath = storageConfig.getBasePath() + "/metadata/" + documentId + ".meta";
			try (FileInputStream fileIn = new FileInputStream(metadataFilePath);
					ObjectInputStream objectIn = new ObjectInputStream(fileIn)) {
				DocumentDto document = (DocumentDto) objectIn.readObject();
				if (storageConfig.isCacheEnabled()) {
					metadataCache.put(documentId, document);
				}
				return document;
			}
		} catch (Exception e) {
			log.error("Failed to load metadata for document: {}", documentId, e);
			return null;
		}
	}

	public boolean deleteDocument(String documentId) {
		try {
			DocumentDto document = getDocumentMetadata(documentId);
			if (document == null) {
				log.warn("Cannot delete document - not found: {}", documentId);
				return false;
			}
			if (document.getStoragePath() != null) {
				deleteStoragePath(documentId, document);
			}
			Path metadataPath = Paths.get(storageConfig.getBasePath(), "metadata", documentId + ".meta");
			if (Files.exists(metadataPath)) {
				Files.delete(metadataPath);
			}
			deleteChunkFiles(documentId);
			if (storageConfig.isCacheEnabled()) {
				chunksCache.invalidate(documentId);
				metadataCache.invalidate(documentId);
			}
			log.info("Document deleted successfully: {}", documentId);
			return true;
		} catch (Exception e) {
			log.error("Failed to delete document: {}", documentId, e);
			return false;
		}
	}

	private void deleteStoragePath(String documentId, DocumentDto document) {
		try {
			Path filePath = Paths.get(document.getStoragePath());
			if (Files.exists(filePath)) {
				Files.delete(filePath);
				log.debug("Deleted file: {}", filePath);
			}
		} catch (Exception e) {
			log.warn("Failed to delete file for document: {}", documentId, e);
		}
	}

	private void deleteChunkFiles(String documentId) {
		try {
			Path chunksDir = Paths.get(storageConfig.getBasePath(), "chunks");
			if (Files.exists(chunksDir)) {
				try (Stream<Path> paths = Files.list(chunksDir)) {
					paths.filter(path -> path.getFileName().toString().startsWith(documentId)).forEach(file -> {
						try {
							Files.delete(file);
							log.debug("Deleted chunk file: {}", file);
						} catch (IOException e) {
							log.warn("Failed to delete chunk file: {}", file, e);
						}
					});
				}
			}
		} catch (Exception e) {
			log.warn("Error deleting chunk files for document: {}", documentId, e);
		}
	}

	public int deleteAllDocuments() {
		try {
			List<String> documentIds = getAllDocumentIds();
			int deletedCount = 0;
			for (String documentId : documentIds) {
				if (deleteDocument(documentId)) {
					deletedCount++;
				}
			}
			if (storageConfig.isCacheEnabled()) {
				chunksCache.invalidateAll();
				metadataCache.invalidateAll();
			}
			cleanupFilesDirectory();
			return deletedCount;
		} catch (Exception e) {
			log.error("Failed to delete all documents", e);
			throw new ApplicationException("Failed to delete all documents", e);
		}
	}

	private void cleanupFilesDirectory() {
		try {
			Path filesDir = Paths.get(storageConfig.getBasePath(), "files");
			if (Files.exists(filesDir)) {
				try (Stream<Path> paths = Files.list(filesDir)) {
					paths.forEach(file -> {
						try {
							Files.delete(file);
							log.debug("Deleted file: {}", file);
						} catch (IOException e) {
							log.warn("Failed to delete file: {}", file, e);
						}
					});
				}
				log.info("Cleaned up files directory: {}", filesDir);
			}
		} catch (Exception e) {
			log.error("Error cleaning up files directory", e);
		}
	}

}
