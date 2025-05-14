package com.cgc.service.llm.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.multipart.MultipartFile;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

@UtilityClass
public class PdfUtils {

	@SneakyThrows
	public String extractText(MultipartFile file) {
		try (PDDocument document = PDDocument.load(file.getInputStream())) {
			PDFTextStripper stripper = new PDFTextStripper();
			return stripper.getText(document);
		}
	}

	public List<String> splitIntoParagraphs(String text) {
		if (text == null || text.isEmpty()) {
			return new ArrayList<>();
		}
		String[] potentialParagraphs = text.split("(?:\r\n|\r|\n){2,}");
		return Arrays.stream(potentialParagraphs).map(String::trim).filter(p -> !p.isEmpty())
				.filter(p -> p.length() > 10).filter(p -> !p.matches("^[\\d\\s]+$")).map(PdfUtils::cleanParagraph)
				.filter(p -> !p.contains("(The filename, directory name, or volume label syntax is incorrect)"))
				.toList();
	}

	private String cleanParagraph(String paragraph) {
		paragraph = paragraph.replaceAll("\\s+", " ");
		paragraph = paragraph.replaceAll("-\\s*\n\\s*", "").replaceAll("\n", " ");
		paragraph = paragraph.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
		paragraph = paragraph.replaceAll("^\\d+\\.\\s*", "");
		return paragraph.trim();
	}

	public List<String> extractStructuredContent(String text) {
		if (text == null || text.isEmpty()) {
			return new ArrayList<>();
		}
		String[] sections = text.split("SECTION \\d+:");
		List<String> processedSections = new ArrayList<>();
		for (String section : sections) {
			if (section.trim().isEmpty())
				continue;
			List<String> sectionParagraphs = Arrays.stream(section.split("\n\n")).map(String::trim)
					.filter(p -> !p.isEmpty()).filter(p -> p.length() > 10).map(PdfUtils::cleanParagraph).toList();
			processedSections.addAll(sectionParagraphs);
		}
		return processedSections;
	}

	public Map<String, List<String>> extractDocumentSections(String text) {
		Map<String, List<String>> documentSections = new LinkedHashMap<>();
		String[] sections = text.split("SECTION \\d+:");
		for (int i = 0; i < sections.length; i++) {
			if (sections[i].trim().isEmpty())
				continue;
			String sectionKey = i == 0 ? "HEADER" : "SECTION " + i;
			List<String> sectionParagraphs = Arrays.stream(sections[i].split("\n\n")).map(String::trim)
					.filter(p -> !p.isEmpty()).filter(p -> p.length() > 10).map(PdfUtils::cleanParagraph).toList();
			documentSections.put(sectionKey, sectionParagraphs);
		}
		return documentSections;
	}

}
