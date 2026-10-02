package vn.penta;

import com.liferay.portal.ModelListenerException;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.transaction.TransactionCommitCallbackRegistryUtil;
import com.liferay.portal.kernel.util.FileUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.PropsUtil;
import com.liferay.portal.model.BaseModelListener;
import com.liferay.portlet.documentlibrary.model.DLFileEntry;
import com.liferay.portlet.documentlibrary.model.DLFolder;
import com.liferay.portlet.documentlibrary.service.DLFolderLocalServiceUtil;
import com.liferay.portlet.documentlibrary.service.DLFileEntryLocalServiceUtil;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** Syncs new documents under configured source folders to a backup Liferay. */
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

	private void sync(DLFileEntry fileEntry) throws Exception {
		Map<Long, String> selectedFolders = getFolderMappings();
		List<String> sourcePath = getFolderPath(fileEntry.getFolderId());
		if (sourcePath.isEmpty() || !isSelected(fileEntry.getFolderId(), sourcePath.size(), selectedFolders)) {
			return;
		}

		String backupUrl = PropsUtil.get("document.library.sync.jsonws.url");
		String userIdValue = PropsUtil.get("document.library.sync.userId");
		String user = PropsUtil.get("document.library.sync.username");
		String password = PropsUtil.get("document.library.sync.password");
		String repositoryId = PropsUtil.get("document.library.sync.repository.id");
		if (isBlank(backupUrl) || isBlank(userIdValue) || isBlank(user) || password == null) {
			_log.error("JSONWS sync is missing backup URL, backup user ID, or credentials.");
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
				fileEntry.getTitle(), fileEntry.getDescription());
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

	private boolean isSelected(long folderId, int pathDepth, Map<Long, String> selectedFolders) {
		long currentFolderId = folderId;
		for (int depth = pathDepth; depth > 0; depth--) {
			if (selectedFolders.containsKey(currentFolderId)) {
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

	private Map<Long, String> getFolderMappings() {
		Map<Long, String> mappings = new HashMap<Long, String>();
		String value = PropsUtil.get("document.library.sync.folder.mappings");
		if (value == null) {
			return mappings;
		}
		for (String entry : value.split(",")) {
			int separator = entry.indexOf('=');
			if (separator <= 0) {
				continue;
			}
			long sourceFolderId = GetterUtil.getLong(entry.substring(0, separator).trim());
			if (sourceFolderId > 0) {
				mappings.put(sourceFolderId, entry.substring(separator + 1).trim());
			}
		}
		return mappings;
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().length() == 0;
	}
}
