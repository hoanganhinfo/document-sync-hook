# Document Library backup sync (Liferay 6.2)

The hook copies newly created documents under selected source folders to the same folder-name path in a backup Liferay. It builds the path by walking the source document's folder and its parent folders, then looks up each exact folder name through JSONWS under the configured backup repository. It uploads the file through JSONWS only when every folder in that path exists. Existing files are not copied, and updates/deletes are not synchronized.

## Configure

Edit `docroot/WEB-INF/src/document-library-sync.properties` inside the hook project. The file is packaged in the hook WAR. After changing it, rebuild and redeploy the hook; Liferay itself does not need a restart.

```properties
# Backup Liferay base URL (the JSONWS API path is appended by the hook).
document.library.sync.jsonws.url=http://192.168.10.18:8080

# Numeric user ID on the backup Liferay that owns the upload.
document.library.sync.userId=BACKUP_USER_ID

# Backup account credentials.
document.library.sync.username=BACKUP_USERNAME
document.library.sync.password=BACKUP_PASSWORD

# Repository/site group ID on the backup Liferay; this defaults to 20182 if omitted.
document.library.sync.repository.id=20182

# Folder names to sync. Files directly in each selected folder and in any of
# its descendant folders are included. Destination folders are matched by name.
document.library.sync.folder.mappings=IT,Engineering
```

The hook's `portal.properties` is reserved for Liferay hook overrides such as the `value.object.listener.*` registration. Liferay 6.2 only allows hooks to override a documented allowlist of built-in portal properties; custom `document.library.sync.*` values are not supported there. Changes to `document-library-sync.properties` take effect when you rebuild and redeploy the hook. This does not require restarting Liferay. Keep credentials out of the WAR because anyone with the deployed artifact can extract them.

Because the username and password are packaged in the WAR, anyone who can access that artifact can extract them. The hook's `portal.properties` is reserved for Liferay hook overrides such as the `value.object.listener.*` registration. Liferay 6.2 only allows hooks to override a documented allowlist of built-in portal properties; custom `document.library.sync.*` values are not supported there.

Each configured name selects documents directly inside a folder with that name and inside any of its descendant folders. The name is matched against every folder in the source path, so a matching folder can be a top-level parent or a nested folder. The backup hierarchy must already exist with the same folder names beneath the target repository. If any folder in the source path is missing from the backup, the hook logs the mismatch and skips that document. Set the repository ID to the backup site's positive group ID. Verify the backup user ID and repository ID in the backup Liferay; these IDs can differ from the live environment.

The source upload succeeds even if the backup is unavailable. Failures are logged by the source portal and are not automatically retried. Use HTTPS when available and keep credentials only in the server's protected `portal-ext.properties`, never in the plugin source.

Deploy the hook using the Plugins SDK's normal hook deployment process. Ensure this hook's portal property is merged with other `value.object.listener.*` registrations if another plugin also customizes the same model.
