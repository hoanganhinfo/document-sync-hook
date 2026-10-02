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
		byte[] bytes, String fileName, String description) throws Exception {

		String baseUrl = backupUrl.trim();
		while (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		if (repositoryId == null || repositoryId.trim().length() == 0) {
			repositoryId = DEFAULT_REPOSITORY_ID;
		}
		long backupRepositoryId = Long.parseLong(repositoryId.trim());
		if (backupRepositoryId <= 0) {
			throw new IllegalArgumentException(
				"Backup repository ID must be a positive group ID, not " + backupRepositoryId + ".");
		}
		repositoryId = String.valueOf(backupRepositoryId);

		RestTemplate restTemplate = new RestTemplate();
		restTemplate.getMessageConverters().add(0,
			new StringHttpMessageConverter(StandardCharsets.UTF_8));
		HttpHeaders headers = new HttpHeaders();
		headers.set("Authorization", "Basic " + Base64.encodeBase64String(
			(userName + ":" + password).getBytes(StandardCharsets.UTF_8)));
		headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
		HttpEntity<String> getRequest = new HttpEntity<String>(headers);
		long parentFolderId = 0;

		for (String folderName : sourceFolderPath) {
			String foldersUrl = baseUrl + "/api/jsonws/dlapp/get-folders/repository-id/" +
				url(repositoryId) + "/parent-folder-id/" + parentFolderId +
				"/include-mount-folders/true";
			ResponseEntity<String> response = restTemplate.exchange(
				foldersUrl, HttpMethod.GET, getRequest, String.class);
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
				return null;
			}
			parentFolderId = match.longValue();
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
		parameters.add("title", fileName);
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
			!verified.has("title") || !fileName.equals(verified.get("title").getAsString())) {
			throw new IllegalStateException("Backup Liferay returned FileEntry " + fileEntryId +
				" but its folder/title did not match the upload request. Response: " +
				verifyResponse.getBody());
		}
		return "backup repository " + repositoryId + ", folderId " + parentFolderId +
			", fileEntryId " + fileEntryId;
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
