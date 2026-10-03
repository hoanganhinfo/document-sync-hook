package vn.penta;

import com.liferay.portal.ModelListenerException;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.transaction.TransactionCommitCallbackRegistryUtil;
import com.liferay.portal.kernel.util.FileUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.model.BaseModelListener;
import com.liferay.portlet.documentlibrary.model.DLFileEntry;
import com.liferay.portlet.documentlibrary.model.DLFolder;
import com.liferay.portlet.documentlibrary.service.DLFolderLocalServiceUtil;
import com.liferay.portlet.documentlibrary.service.DLFileEntryLocalServiceUtil;
import com.liferay.portlet.trash.model.TrashEntry;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;

/** Syncs creates and deletes under configured source folders to a backup Liferay. */
public class DocumentLibrarySyncListener extends BaseModelListener<DLFileEntry> {
	private static final Log _log = LogFactoryUtil.getLog(DocumentLibrarySyncListener.class);

	@Override
	public void onAfterCreate(DLFileEntry fileEntry) throws ModelListenerException {
		final long fileEntryId = fileEntry.getFileEntryId();
		TransactionCommitCallbackRegistryUtil.registerCallback(new Callable<Void>() {
			@Override
			public Void call() {
				try {
					sync(DLFileEntryLocalServiceUtil.getDLFileEntry(fileEntryId));
				}
				catch (Exception e) {
					_log.error("Unable to sync document " + fileEntryId + " after upload committed.", e);
				}
				return null;
			}
		});
	}

	@Override
	public void onAfterRemove(final DLFileEntry fileEntry) throws ModelListenerException {
		scheduleBackupDelete(fileEntry);
	}

	@Override
	public void onAfterUpdate(final DLFileEntry fileEntry) throws ModelListenerException {
		if (fileEntry.isInTrash()) {
			scheduleBackupDelete(fileEntry);
		}
	}

	private void scheduleBackupDelete(final DLFileEntry fileEntry) {
		final long fileEntryId = fileEntry.getFileEntryId();
		final List<String> sourcePath;
		final boolean selected;
		final Properties syncProperties;
		try {
			sourcePath = getFolderPath(fileEntry.getFolderId());
			syncProperties = getSyncProperties();
			selected = !sourcePath.isEmpty() && isSelected(fileEntry.getFolderId(), sourcePath,
				getFolderIdMappings(syncProperties), getFolderNames(syncProperties));
		}
		catch (Exception e) {
			_log.error("Unable to determine backup folder for deleted document " + fileEntryId + ".", e);
			return;
		}
		if (!selected) {
			return;
		}

		final String fileName = isBlank(fileEntry.getName()) ? fileEntry.getTitle() : fileEntry.getName();
		String resolvedTitle = fileEntry.getTitle();
		if (fileEntry.isInTrash()) {
			try {
				TrashEntry trashEntry = fileEntry.getTrashEntry();
				if (trashEntry != null) {
					String originalTitle = trashEntry.getTypeSettingsProperty("title");
					if (!isBlank(originalTitle)) {
						resolvedTitle = originalTitle;
					}
				}
			}
			catch (Exception e) {
				_log.warn("Unable to read original title from TrashEntry for document " + fileEntryId +
					"; trying its current title.", e);
			}
		}
		final String title = resolvedTitle;
		TransactionCommitCallbackRegistryUtil.registerCallback(new Callable<Void>() {
			@Override
			public Void call() {
				try {
					String backupUrl = syncProperties.getProperty("document.library.sync.jsonws.url");
					String userIdValue = syncProperties.getProperty("document.library.sync.userId");
					String user = syncProperties.getProperty("document.library.sync.username");
					String password = syncProperties.getProperty("document.library.sync.password");
					String repositoryId = syncProperties.getProperty("document.library.sync.repository.id");
					if (isBlank(backupUrl) || isBlank(userIdValue) || isBlank(user) || isBlank(password)) {
						_log.error("Cannot delete backup document " + fileName + ": sync configuration is incomplete.");
						return null;
					}
					long userId = GetterUtil.getLong(userIdValue);
					if (userId <= 0) {
						_log.error("document.library.sync.userId must be the numeric user ID on the backup Liferay.");
						return null;
					}
					boolean deleted = LiferayDocumentUploader.deleteDocument(
						userId, user, password, backupUrl, repositoryId, sourcePath, fileName, title);
					if (deleted) {
						_log.info("Deleted backup document '" + fileName + "' from folder path " + sourcePath + ".");
					}
					else {
						_log.info("No matching backup document '" + fileName + "' (title '" + title +
							"') exists in folder path " + sourcePath + ".");
					}
				}
				catch (Exception e) {
					_log.error("Unable to delete backup document '" + fileName + "' from folder path " +
						sourcePath + ".", e);
				}
				return null;
			}
		});
	}

	private void sync(DLFileEntry fileEntry) throws Exception {
		Properties syncProperties = getSyncProperties();
		Map<Long, String> selectedFolderIds = getFolderIdMappings(syncProperties);
		Set<String> selectedFolderNames = getFolderNames(syncProperties);
		List<String> sourcePath = getFolderPath(fileEntry.getFolderId());
		if (sourcePath.isEmpty() || !isSelected(fileEntry.getFolderId(), sourcePath,
			selectedFolderIds, selectedFolderNames)) {
			return;
		}

		String backupUrl = syncProperties.getProperty("document.library.sync.jsonws.url");
		String userIdValue = syncProperties.getProperty("document.library.sync.userId");
		String user = syncProperties.getProperty("document.library.sync.username");
		String password = syncProperties.getProperty("document.library.sync.password");
		String repositoryId = syncProperties.getProperty("document.library.sync.repository.id");
		if (isBlank(backupUrl) || isBlank(userIdValue) || isBlank(user) || isBlank(password)) {
			_log.error("JSONWS sync configuration is incomplete. Set document.library.sync.jsonws.url, " +
				"document.library.sync.userId, document.library.sync.username, and " +
				"document.library.sync.password in the hook's document-library-sync.properties.");
			return;
		}
		long userId = GetterUtil.getLong(userIdValue);
		if (userId <= 0) {
			_log.error("document.library.sync.userId must be the numeric user ID on the backup Liferay.");
			return;
		}

		InputStream input = null;
		try {
			input = fileEntry.getContentStream();
			byte[] bytes = FileUtil.getBytes(input);
			String receipt = LiferayDocumentUploader.uploadDocument(
				userId, user, password, backupUrl, repositoryId, sourcePath, bytes,
				fileEntry.getName(), fileEntry.getTitle(), fileEntry.getDescription());
			if (receipt != null) {
				_log.info("Synced document '" + fileEntry.getTitle() + "' to backup folder path " +
					sourcePath + " (" + receipt + ")");
			}
			else {
				_log.error("Backup folder path does not exist for document '" +
					fileEntry.getTitle() + "': " + sourcePath + ". Upload skipped.");
			}
		}
		finally {
			if (input != null) {
				input.close();
			}
		}
	}

	private List<String> getFolderPath(long folderId) throws PortalException, SystemException {
		List<String> path = new ArrayList<String>();
		long currentFolderId = folderId;
		while (currentFolderId > 0) {
			DLFolder folder = DLFolderLocalServiceUtil.getDLFolder(currentFolderId);
			path.add(folder.getName());
			currentFolderId = folder.getParentFolderId();
		}
		Collections.reverse(path);
		return path;
	}

	private boolean isSelected(long folderId, List<String> folderPath,
		Map<Long, String> selectedFolderIds, Set<String> selectedFolderNames) {
		long currentFolderId = folderId;
		for (int pathIndex = folderPath.size() - 1; pathIndex >= 0; pathIndex--) {
			if (selectedFolderIds.containsKey(currentFolderId) ||
				selectedFolderNames.contains(folderPath.get(pathIndex))) {
				return true;
			}
			try {
				currentFolderId = DLFolderLocalServiceUtil.getDLFolder(currentFolderId).getParentFolderId();
			}
			catch (Exception e) {
				return false;
			}
		}
		return false;
	}

	private Map<Long, String> getFolderIdMappings(Properties syncProperties) {
		Map<Long, String> mappings = new HashMap<Long, String>();
		for (String entry : getFolderMappingEntries(syncProperties)) {
			int separator = entry.indexOf('=');
			if (separator <= 0 || !isPositiveLong(entry.substring(0, separator).trim())) {
				continue;
			}
			long sourceFolderId = GetterUtil.getLong(entry.substring(0, separator).trim());
			if (sourceFolderId > 0) {
				mappings.put(sourceFolderId, entry.substring(separator + 1).trim());
			}
		}
		return mappings;
	}

	private Set<String> getFolderNames(Properties syncProperties) {
		Set<String> folderNames = new HashSet<String>();
		for (String entry : getFolderMappingEntries(syncProperties)) {
			int separator = entry.indexOf('=');
			if (separator > 0 && isPositiveLong(entry.substring(0, separator).trim())) {
				continue;
			}
			String folderName = entry.trim();
			if (folderName.length() > 0) {
				folderNames.add(folderName);
			}
		}
		return folderNames;
	}

	private List<String> getFolderMappingEntries(Properties syncProperties) {
		List<String> entries = new ArrayList<String>();
		String value = syncProperties.getProperty("document.library.sync.folder.mappings");
		if (value != null) {
			for (String entry : value.split(",")) {
				if (entry.trim().length() > 0) {
					entries.add(entry.trim());
				}
			}
		}
		return entries;
	}

	private Properties getSyncProperties() throws IOException {
		Properties properties = new Properties();
		InputStream input = DocumentLibrarySyncListener.class.getClassLoader()
			.getResourceAsStream("document-library-sync.properties");
		if (input == null) {
			throw new IOException("Hook resource document-library-sync.properties is missing.");
		}
		try {
			properties.load(input);
		}
		finally {
			input.close();
		}
		return properties;
	}

	private boolean isPositiveLong(String value) {
		try {
			return Long.parseLong(value) > 0;
		}
		catch (NumberFormatException e) {
			return false;
		}
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().length() == 0;
	}
}
