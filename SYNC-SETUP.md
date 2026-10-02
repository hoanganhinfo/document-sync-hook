# Document Library backup sync (Liferay 6.2)

The hook copies newly created documents under selected source folders to the same folder-name path in a backup Liferay. It builds the path by walking the source document's folder and its parent folders, then looks up each exact folder name through JSONWS under the configured backup repository. It uploads the file through JSONWS only when every folder in that path exists. Existing files are not copied, and updates/deletes are not synchronized.

## Configure

Add these properties to the source Liferay's protected `portal-ext.properties`, then restart the portal:

```properties
# Backup Liferay base URL (the JSONWS API path is appended by the hook).
document.library.sync.jsonws.url=http://192.168.10.18:8080

# Numeric user ID on the backup Liferay that owns the upload.
document.library.sync.userId=BACKUP_USER_ID
# Backup Liferay credentials with permission to add documents.
document.library.sync.username=BACKUP_USERNAME
document.library.sync.password=BACKUP_PASSWORD

# Repository/site group ID on the backup Liferay; this defaults to 20182 if omitted.
document.library.sync.repository.id=20182

# Source folder IDs to sync. The text after '=' is retained for readability;
# destination folders are found from the source folder-name hierarchy.
document.library.sync.folder.mappings=21642=IT,5710659=Apple Mandrel DWG
```

A configured source folder includes documents in its descendant folders. The backup hierarchy must already exist with the same folder names beneath the target repository. If any folder in the source path is missing from the backup, the hook logs the mismatch and skips that document. Set the repository ID to the backup site's positive group ID. Verify the backup user ID and repository ID in the backup Liferay; these IDs can differ from the live environment.

The source upload succeeds even if the backup is unavailable. Failures are logged by the source portal and are not automatically retried. Use HTTPS when available and keep credentials only in the server's protected `portal-ext.properties`, never in the plugin source.

Deploy the hook using the Plugins SDK's normal hook deployment process. Ensure this hook's portal property is merged with other `value.object.listener.*` registrations if another plugin also customizes the same model.
