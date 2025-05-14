package com.cgc.service.llm.utils;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.cgc.service.llm.enums.ContentType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;

import lombok.experimental.UtilityClass;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

/**
 * @author: anascreations
 *
 */
@UtilityClass
public class ContentUtils {
	private final Pattern JAVA_PATTERN = Pattern.compile(
			"(?:public|private|protected|class|interface|enum|import|package|void|static|final)\\s+\\w+.*\\{",
			Pattern.MULTILINE);

	private final Pattern PYTHON_PATTERN = Pattern.compile(
			"(?:def\\s+\\w+\\s*\\(|class\\s+\\w+\\s*(?:\\(|:)|import\\s+\\w+|from\\s+\\w+\\s+import)",
			Pattern.MULTILINE);

	private final Pattern JAVASCRIPT_PATTERN = Pattern.compile(
			"(?:function\\s+\\w+\\s*\\(|const\\s+\\w+\\s*=|let\\s+\\w+\\s*=|var\\s+\\w+\\s*=|=>\\s*\\{|\\}\\)\\()",
			Pattern.MULTILINE);

	private final Pattern CSHARP_PATTERN = Pattern.compile(
			"(?:namespace\\s+\\w+|using\\s+\\w+(?:\\.\\w+)*;|class\\s+\\w+\\s*(?::|\\{)|public\\s+\\w+\\s+\\w+)",
			Pattern.MULTILINE);

	private final Pattern SQL_PATTERN = Pattern.compile(
			"(?:SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|FROM|WHERE|JOIN)\\s+\\w+",
			Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

	private final Pattern XML_PATTERN = Pattern.compile(
			"(?:<\\?xml.*\\?>|<!DOCTYPE.*>|<[a-zA-Z][a-zA-Z0-9]*(\\s+[a-zA-Z][a-zA-Z0-9]*=\".*\")*\\s*>)",
			Pattern.DOTALL);

	private final Pattern JSON_PATTERN = Pattern.compile("\\s*[{\\[]\\s*[\"\']?\\w+[\"\']?\\s*:.*", Pattern.DOTALL);

	private final Pattern MATH_FORMULA_PATTERN = Pattern.compile(
			"(?:\\$\\$.+?\\$\\$|\\$.+?\\$|\\\\begin\\{(?:equation|align|gather|math)\\}.*?\\\\end\\{(?:equation|align|gather|math)\\})",
			Pattern.DOTALL);

	private final Pattern LIST_PATTERN = Pattern.compile(
			"(?:^\\s*[\\*\\-\\+]\\s+.+$|^\\s*\\d+\\.\\s+.+$|^\\s*[a-z]\\)\\s+.+$|^\\s*[ixv]+\\.\\s+.+$)",
			Pattern.MULTILINE);

	public ContentType detectContentType(String text) {
		if (isLikelyTable(text)) {
			return ContentType.TABLE;
		}
		if (isXmlContent(text)) {
			return ContentType.CODE_XML;
		} else if (isJsonContent(text)) {
			return ContentType.CODE_JSON;
		}
		if (isJavaCode(text)) {
			return ContentType.CODE_JAVA;
		} else if (isPythonCode(text)) {
			return ContentType.CODE_PYTHON;
		} else if (isJavaScriptCode(text)) {
			return ContentType.CODE_JAVASCRIPT;
		} else if (isCSharpCode(text)) {
			return ContentType.CODE_CSHARP;
		} else if (isSqlCode(text)) {
			return ContentType.CODE_SQL;
		} else if (isOtherCode(text)) {
			return ContentType.CODE_OTHER;
		}
		if (isMathFormula(text)) {
			return ContentType.MATH_FORMULA;
		}
		if (isList(text)) {
			return ContentType.LIST;
		}
		if (text.matches("^[A-Z\\s]+$") && text.length() < 100 || text.matches("^\\d+\\.\\s.*") && text.length() < 150
				|| text.length() < 50 && !text.contains(" ")) {
			return ContentType.HEADING;
		}
		return ContentType.TEXT;
	}

	public boolean isJavaCode(String text) {
		if (!JAVA_PATTERN.matcher(text).find()) {
			return false;
		}
		try {
			JavaParser parser = new JavaParser();
			ParseResult<CompilationUnit> result = parser.parse(text);
			return !result.isSuccessful() || result.getProblems().size() < 5;
		} catch (ParseProblemException e) {
			return false;
		}
	}

	public boolean isPythonCode(String text) {
		Matcher matcher = PYTHON_PATTERN.matcher(text);
		int matchCount = 0;
		while (matcher.find()) {
			matchCount++;
		}
		boolean hasIndentationPattern = Pattern.compile("^\\s{4}\\S", Pattern.MULTILINE).matcher(text).find();
		return matchCount >= 2 || (matchCount >= 1 && hasIndentationPattern);
	}

	public boolean isJavaScriptCode(String text) {
		Matcher matcher = JAVASCRIPT_PATTERN.matcher(text);
		int matchCount = 0;
		while (matcher.find()) {
			matchCount++;
		}
		boolean hasJSSpecificFeatures = text.contains("document.") || text.contains("window.") || text.contains("$.")
				|| text.contains("React.");
		return matchCount >= 2 || (matchCount >= 1 && hasJSSpecificFeatures);
	}

	public boolean isCSharpCode(String text) {
		return CSHARP_PATTERN.matcher(text).find();
	}

	public boolean isSqlCode(String text) {
		return SQL_PATTERN.matcher(text).find();
	}

	public boolean isXmlContent(String text) {
		if (!XML_PATTERN.matcher(text).find()) {
			return false;
		}
		String trimmed = text.trim();
		if (trimmed.startsWith("<?xml") || trimmed.startsWith("<!DOCTYPE")
				|| (trimmed.startsWith("<") && trimmed.endsWith(">"))) {
			try {
				DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
				DocumentBuilder builder = factory.newDocumentBuilder();
				builder.parse(new InputSource(new StringReader(text)));
				return true;
			} catch (ParserConfigurationException | SAXException | IOException e) {
				Pattern tagPattern = Pattern.compile("</?[a-zA-Z][a-zA-Z0-9]*[^>]*>");
				Matcher matcher = tagPattern.matcher(text);
				int tagCount = 0;
				while (matcher.find()) {
					tagCount++;
				}
				return tagCount >= 3;
			}
		}
		return false;
	}

	public boolean isJsonContent(String text) {
		if (!JSON_PATTERN.matcher(text).find()) {
			return false;
		}
		String trimmed = text.trim();
		if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
			try {
				if (trimmed.startsWith("{")) {
					new JSONObject(trimmed);
				} else {
					new JSONArray(trimmed);
				}
				return true;
			} catch (JSONException e) {
				Pattern kvPattern = Pattern.compile("\"[^\"]+\"\\s*:\\s*(?:\"[^\"]*\"|\\d+|true|false|null|\\{|\\[)");
				Matcher matcher = kvPattern.matcher(text);
				int kvCount = 0;
				while (matcher.find()) {
					kvCount++;
				}
				return kvCount >= 2;
			}
		}

		return false;
	}

	public boolean isOtherCode(String text) {
		int codeLineCount = 0;
		int totalLines = 0;
		String[] lines = text.split("\n");
		for (String line : lines) {
			totalLines++;
			String trimmed = line.trim();
			if (trimmed.isEmpty())
				continue;
			if (trimmed.startsWith("if ") || trimmed.startsWith("for ") || trimmed.startsWith("while ")
					|| trimmed.contains(" = ") || trimmed.contains("==") || trimmed.contains("!=")
					|| trimmed.contains("<=") || trimmed.contains(">=") || trimmed.matches(".*[{};]\\s*$")
					|| trimmed.matches("^\\s*[)}].*$")) {
				codeLineCount++;
			}
		}
		return totalLines > 5 && (double) codeLineCount / totalLines > 0.3;
	}

	public boolean isMathFormula(String text) {
		return MATH_FORMULA_PATTERN.matcher(text).find() || (text.contains("=")
				&& Pattern.compile("[a-z]\\s*=\\s*[a-z0-9]", Pattern.CASE_INSENSITIVE).matcher(text).find());
	}

	public boolean isList(String text) {
		Matcher matcher = LIST_PATTERN.matcher(text);
		int listItemCount = 0;
		while (matcher.find()) {
			listItemCount++;
		}
		return listItemCount >= 3;
	}

	public String formatContentByType(String content, ContentType type) {
		switch (type) {
		case CODE_JAVA:
		case CODE_PYTHON:
		case CODE_JAVASCRIPT:
		case CODE_CSHARP:
		case CODE_SQL:
		case CODE_XML:
		case CODE_JSON:
		case CODE_OTHER:
			return formatCodeBlock(content, type);
		case TABLE:
			return content;
		case MATH_FORMULA:
			return formatMathFormula(content);
		case LIST:
			return formatList(content);
		default:
			return content;
		}
	}

	private String formatCodeBlock(String code, ContentType type) {
		String language;
		switch (type) {
		case CODE_JAVA:
			language = "java";
			break;
		case CODE_PYTHON:
			language = "python";
			break;
		case CODE_JAVASCRIPT:
			language = "javascript";
			break;
		case CODE_CSHARP:
			language = "csharp";
			break;
		case CODE_SQL:
			language = "sql";
			break;
		case CODE_XML:
			language = "xml";
			break;
		case CODE_JSON:
			language = "json";
			break;
		default:
			language = "";
			break;
		}
		code = code.replaceAll("\n{3,}", "\n\n");
		return "```" + language + "\n" + code + "\n```";
	}

	private String formatMathFormula(String formula) {
		if (formula.contains("$$") || formula.contains("\\begin{")) {
			return formula;
		}
		formula = formula.trim();
		return "Math Formula:\n" + formula;
	}

	private String formatList(String list) {
		return list.replaceAll("\\n{3,}", "\n\n").trim();
	}

	public String prettifyXml(String xml) {
		try {
			javax.xml.transform.TransformerFactory transformerFactory = javax.xml.transform.TransformerFactory
					.newInstance();
			javax.xml.transform.Transformer transformer = transformerFactory.newTransformer();
			transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
			transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
			javax.xml.transform.stream.StreamSource source = new javax.xml.transform.stream.StreamSource(
					new StringReader(xml));
			java.io.StringWriter writer = new java.io.StringWriter();
			javax.xml.transform.stream.StreamResult result = new javax.xml.transform.stream.StreamResult(writer);
			transformer.transform(source, result);
			return writer.toString();
		} catch (Exception e) {
			return xml;
		}
	}

	public String prettifyJson(String json) {
		try {
			if (json.trim().startsWith("{")) {
				JSONObject jsonObj = new JSONObject(json);
				return jsonObj.toString(2);
			} else if (json.trim().startsWith("[")) {
				JSONArray jsonArray = new JSONArray(json);
				return jsonArray.toString(2);
			}
			return json;
		} catch (JSONException e) {
			return json;
		}
	}

	@SuppressWarnings("resource")
	public List<String> extractTables(PDDocument document) {
		ObjectExtractor extractor = new ObjectExtractor(document);
		SpreadsheetExtractionAlgorithm spreadsheetExtractor = new SpreadsheetExtractionAlgorithm();
		BasicExtractionAlgorithm basicExtractor = new BasicExtractionAlgorithm();
		List<String> extractedTables = new ArrayList<>();
		for (int i = 1; i <= document.getNumberOfPages(); i++) {
			Page page = extractor.extract(i);
			List<Table> spreadsheetTables = spreadsheetExtractor.extract(page);
			List<Table> tables = !spreadsheetTables.isEmpty() ? spreadsheetTables : basicExtractor.extract(page);
			for (Table table : tables) {
				String formattedTable = formatTableAsString(table, i);
				extractedTables.add(formattedTable);
			}
		}
		return extractedTables;
	}

	@SuppressWarnings({ "resource", "rawtypes" })
	private String formatTableAsString(Table table, int pageNumber) {
		StringBuilder tableText = new StringBuilder();
		tableText.append("Table from page ").append(pageNumber).append(":\n\n");
		try {
			StringWriter writer = new StringWriter();
			CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT);
			if (table.getRowCount() > 0) {
				List<String> headers = table.getRows().get(0).stream().map(RectangularTextContainer::getText)
						.map(String::trim).toList();
				printer.printRecord(headers);
			}
			for (int i = 1; i < table.getRowCount(); i++) {
				List<String> rowData = table.getRows().get(i).stream().map(RectangularTextContainer::getText)
						.map(String::trim).toList();
				printer.printRecord(rowData);
			}
			printer.flush();
			tableText.append(writer.toString());
			tableText.insert(tableText.indexOf("\n") + 1, "```\n").append("\n```");

		} catch (IOException e) {
			for (List<RectangularTextContainer> row : table.getRows()) {
				for (RectangularTextContainer cell : row) {
					tableText.append(cell.getText().trim()).append("\t");
				}
				tableText.append("\n");
			}
		}
		return tableText.toString();
	}

	@SuppressWarnings("rawtypes")
	public String convertToMarkdownTable(Table table) {
		if (table.getRowCount() == 0)
			return "";
		StringBuilder md = new StringBuilder();
		md.append("|");
		for (RectangularTextContainer cell : table.getRows().get(0)) {
			md.append(" ").append(cell.getText().trim()).append(" |");
		}
		md.append("\n");
		md.append("|");
		for (int i = 0; i < table.getRows().get(0).size(); i++) {
			md.append(" --- |");
		}
		md.append("\n");
		for (int i = 1; i < table.getRowCount(); i++) {
			md.append("|");
			for (RectangularTextContainer cell : table.getRows().get(i)) {
				md.append(" ").append(cell.getText().trim()).append(" |");
			}
			md.append("\n");
		}
		return md.toString();
	}

	public boolean isLikelyTable(String text) {
		int lineCount = 0;
		int tableLineCount = 0;
		String[] lines = text.split("\n");
		for (String line : lines) {
			lineCount++;
			String trimmed = line.trim();
			if (trimmed.isEmpty())
				continue;
			if ((trimmed.split("\\s{2,}").length > 2) || (trimmed.split("\\t").length > 2)
					|| (trimmed.split("\\|").length > 2) || (trimmed.split(",").length > 2)) {
				tableLineCount++;
			}
		}
		return lineCount > 0 && tableLineCount > 0 && (double) tableLineCount / lineCount > 0.3;
	}

//	public  List<String> splitIntoParagraphs(String text) {
//		String[] paragraphs = text.split("\\n\\s*\\n");
//		return Arrays.stream(paragraphs).map(String::trim).filter(p -> !p.isEmpty()).toList();
//	}

	public List<String> createSemanticallyCoherentChunks(List<String> paragraphs, int targetSize, int overlap) {
		List<String> chunks = new ArrayList<>();
		StringBuilder currentChunk = new StringBuilder();
		int currentSize = 0;
		for (String paragraph : paragraphs) {
			if (currentSize > 0 && currentSize + paragraph.length() > targetSize) {
				chunks.add(currentChunk.toString());
				if (overlap > 0 && currentChunk.length() > overlap) {
					String overlapText = currentChunk.substring(Math.max(0, currentChunk.length() - overlap));
					currentChunk = new StringBuilder(overlapText);
					currentSize = overlapText.length();
				} else {
					currentChunk = new StringBuilder();
					currentSize = 0;
				}
			}
			if (currentSize > 0) {
				currentChunk.append("\n\n");
				currentSize += 2;
			}
			currentChunk.append(paragraph);
			currentSize += paragraph.length();
			if (paragraph.length() >= targetSize) {
				chunks.add(currentChunk.toString());
				currentChunk = new StringBuilder();
				currentSize = 0;
			}
		}
		if (currentSize > 0) {
			chunks.add(currentChunk.toString());
		}
		return chunks;
	}

	public List<String> extractPageTexts(PDDocument document) throws IOException {
		List<String> pageTexts = new ArrayList<>();
		PDFTextStripper stripper = new PDFTextStripper();
		for (int i = 1; i <= document.getNumberOfPages(); i++) {
			stripper.setStartPage(i);
			stripper.setEndPage(i);
			String pageText = stripper.getText(document);
			pageTexts.add(pageText);
		}
		return pageTexts;
	}

	public String cleanText(String text) {
		text = text.replaceAll("\\s+", " ");
		text = text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
		text = text.replaceAll("�", "");
		return text.trim();
	}
}
