package vn.penta;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.codec.binary.Base64;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/** Uses JSONWS to locate a matching folder and upload the file. */
public class LiferayDocumentUploader {
	private static final String DEFAULT_REPOSITORY_ID = "20182";

	public static String uploadDocument(long userId, String userName, String password,
		String backupUrl, String repositoryId, List<String> sourceFolderPath,
		byte[] bytes, String fileName, String title, String description) throws Exception {

		String baseUrl = normalizeUrl(backupUrl);
		long backupRepositoryId = parseRepositoryId(repositoryId);
		repositoryId = String.valueOf(backupRepositoryId);

		RestTemplate restTemplate = new RestTemplate();
		restTemplate.getMessageConverters().add(0,
			new StringHttpMessageConverter(StandardCharsets.UTF_8));
		HttpHeaders headers = new HttpHeaders();
		headers.set("Authorization", "Basic " + Base64.encodeBase64String(
			(userName + ":" + password).getBytes(StandardCharsets.UTF_8)));
		headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
		HttpEntity<String> getRequest = new HttpEntity<String>(headers);
		long parentFolderId = findDestinationFolder(
			restTemplate, getRequest, baseUrl, repositoryId, sourceFolderPath);
		if (parentFolderId < 0) {
			return null;
		}

		String addUrl = baseUrl + "/api/jsonws/dlapp/add-file-entry";
		HttpHeaders uploadHeaders = new HttpHeaders();
		uploadHeaders.set("Authorization", headers.getFirst("Authorization"));
		uploadHeaders.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
		uploadHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<String, String>();
		parameters.add("formDate", "");
		parameters.add("repositoryId", String.valueOf(backupRepositoryId));
		parameters.add("folderId", String.valueOf(parentFolderId));
		parameters.add("sourceFileName", fileName);
		parameters.add("mimeType", "");
		parameters.add("title", title);
		parameters.add("description", description == null ? "" : description);
		parameters.add("changeLog", "1.0");
		parameters.add("bytes", byteArrayParameter(bytes));
		HttpEntity<MultiValueMap<String, String>> uploadRequest = new HttpEntity<MultiValueMap<String, String>>(
			parameters, uploadHeaders);
		ResponseEntity<String> uploadResponse = restTemplate.exchange(
			addUrl, HttpMethod.POST, uploadRequest, String.class);
		JsonObject created = new JsonParser().parse(uploadResponse.getBody()).getAsJsonObject();
		if (created.has("exception")) {
			throw new IllegalStateException("JSONWS add-file-entry failed: " + uploadResponse.getBody());
		}
		long fileEntryId = created.get("fileEntryId").getAsLong();
		String verifyUrl = baseUrl + "/api/jsonws/dlapp/get-file-entry/file-entry-id/" + fileEntryId;
		ResponseEntity<String> verifyResponse = restTemplate.exchange(
			verifyUrl, HttpMethod.GET, getRequest, String.class);
		JsonObject verified = new JsonParser().parse(verifyResponse.getBody()).getAsJsonObject();
		if (!verified.has("folderId") || verified.get("folderId").getAsLong() != parentFolderId ||
			!verified.has("title") || !title.equals(verified.get("title").getAsString()) ||
			!verified.has("name") || !fileName.equals(verified.get("name").getAsString())) {
			throw new IllegalStateException("Backup Liferay returned FileEntry " + fileEntryId +
				" but its folder/name/title did not match the upload request. Response: " +
				verifyResponse.getBody());
		}
		return "backup repository " + repositoryId + ", folderId " + parentFolderId +
			", fileEntryId " + fileEntryId;
	}

	public static boolean deleteDocument(long userId, String userName, String password,
		String backupUrl, String repositoryId, List<String> sourceFolderPath,
		String fileName, String title) throws Exception {

		String baseUrl = normalizeUrl(backupUrl);
		String normalizedRepositoryId = String.valueOf(parseRepositoryId(repositoryId));
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.getMessageConverters().add(0,
			new StringHttpMessageConverter(StandardCharsets.UTF_8));
		HttpHeaders headers = new HttpHeaders();
		headers.set("Authorization", "Basic " + Base64.encodeBase64String(
			(userName + ":" + password).getBytes(StandardCharsets.UTF_8)));
		headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
		HttpEntity<String> request = new HttpEntity<String>(headers);

		long folderId = findDestinationFolder(
			restTemplate, request, baseUrl, normalizedRepositoryId, sourceFolderPath);
		if (folderId < 0) {
			return false;
		}

		long fileEntryId = findFileEntryIdByTitle(
			restTemplate, request, baseUrl, normalizedRepositoryId, folderId, title);
		if (fileEntryId <= 0 && (title == null || !title.equals(fileName))) {
			// Older hook versions used the source title as both the file name and title.
			fileEntryId = findFileEntryIdByTitle(
				restTemplate, request, baseUrl, normalizedRepositoryId, folderId, fileName);
		}
		if (fileEntryId <= 0) {
			return false;
		}

		String deleteUrl = baseUrl + "/api/jsonws/dlapp/delete-file-entry/file-entry-id/" + fileEntryId;
		ResponseEntity<String> deleteResponse = restTemplate.exchange(
			deleteUrl, HttpMethod.POST, request, String.class);
		String responseBody = deleteResponse.getBody();
		if (responseBody != null && responseBody.trim().startsWith("{")) {
			JsonObject result = new JsonParser().parse(responseBody).getAsJsonObject();
			if (result.has("exception")) {
				throw new IllegalStateException("JSONWS delete-file-entry failed: " + responseBody);
			}
		}
		return true;
	}

	private static long findFileEntryIdByTitle(RestTemplate restTemplate,
		HttpEntity<String> request, String baseUrl, String repositoryId,
		long folderId, String title) throws Exception {
		if (title == null || title.trim().length() == 0) {
			return -1;
		}

		String lookupUrl = baseUrl + "/api/jsonws/dlapp/get-file-entry?groupId=" +
			url(repositoryId) + "&folderId=" + folderId + "&title=" + url(title);
		ResponseEntity<String> response = restTemplate.exchange(
			lookupUrl, HttpMethod.GET, request, String.class);
		JsonObject entry = new JsonParser().parse(response.getBody()).getAsJsonObject();
		if (entry.has("exception")) {
			String exception = entry.get("exception").getAsString();
			if (exception.indexOf("NoSuchFileEntryException") >= 0 ||
				exception.indexOf("NoSuchRepositoryEntryException") >= 0) {
				return -1;
			}
			throw new IllegalStateException("JSONWS get-file-entry by title failed: " + response.getBody());
		}
		if (!entry.has("fileEntryId") || !entry.has("folderId") ||
			entry.get("folderId").getAsLong() != folderId || !entry.has("title") ||
			!title.equals(entry.get("title").getAsString())) {
			throw new IllegalStateException("Backup Liferay returned a file that did not match title '" +
				title + "' in folder " + folderId + ". Response: " + response.getBody());
		}
		return entry.get("fileEntryId").getAsLong();
	}

	private static long findDestinationFolder(RestTemplate restTemplate,
		HttpEntity<String> request, String baseUrl, String repositoryId,
		List<String> sourceFolderPath) throws Exception {

		long parentFolderId = 0;
		for (String folderName : sourceFolderPath) {
			String foldersUrl = baseUrl + "/api/jsonws/dlapp/get-folders/repository-id/" +
				url(repositoryId) + "/parent-folder-id/" + parentFolderId +
				"/include-mount-folders/true";
			ResponseEntity<String> response = restTemplate.exchange(
				foldersUrl, HttpMethod.GET, request, String.class);
			JsonArray folders = new JsonParser().parse(response.getBody()).getAsJsonArray();
			Long match = null;
			for (int i = 0; i < folders.size(); i++) {
				JsonObject folder = folders.get(i).getAsJsonObject();
				if (folderName.equals(folder.get("name").getAsString())) {
					match = folder.get("folderId").getAsLong();
					break;
				}
			}
			if (match == null) {
				return -1;
			}
			parentFolderId = match.longValue();
		}
		return parentFolderId;
	}

	private static String normalizeUrl(String backupUrl) {
		String baseUrl = backupUrl.trim();
		while (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		return baseUrl;
	}

	private static long parseRepositoryId(String repositoryId) {
		if (repositoryId == null || repositoryId.trim().length() == 0) {
			repositoryId = DEFAULT_REPOSITORY_ID;
		}
		long backupRepositoryId = Long.parseLong(repositoryId.trim());
		if (backupRepositoryId <= 0) {
			throw new IllegalArgumentException(
				"Backup repository ID must be a positive group ID, not " + backupRepositoryId + ".");
		}
		return backupRepositoryId;
	}

	private static String url(String value) throws UnsupportedEncodingException {
		return URLEncoder.encode(value, "UTF-8");
	}

	private static String byteArrayParameter(byte[] bytes) {
		StringBuilder value = new StringBuilder(bytes.length * 4);
		for (int i = 0; i < bytes.length; i++) {
			if (i > 0) {
				value.append(',');
			}
			value.append(bytes[i]);
		}
		return value.toString();
	}

}
