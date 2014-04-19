/*
 * Syncany, www.syncany.org
 * Copyright (C) 2011-2014 Philipp C. Heckel <philipp.heckel@gmail.com> 
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.syncany.connection.plugins.webdav;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.conn.ssl.SSLSocketFactory;
import org.syncany.connection.plugins.AbstractTransferManager;
import org.syncany.connection.plugins.DatabaseRemoteFile;
import org.syncany.connection.plugins.MultiChunkRemoteFile;
import org.syncany.connection.plugins.RemoteFile;
import org.syncany.connection.plugins.RepoRemoteFile;
import org.syncany.connection.plugins.StorageException;
import org.syncany.util.FileUtil;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;
import com.github.sardine.impl.SardineException;
import com.github.sardine.impl.SardineImpl;

public class WebdavTransferManager extends AbstractTransferManager {
	private static final String APPLICATION_CONTENT_TYPE = "application/x-syncany";
	private static final int HTTP_NOT_FOUND = 404;
	private static final Logger logger = Logger.getLogger(WebdavTransferManager.class.getSimpleName());

	private Sardine sardine;

	private String repoPath;
	private String multichunkPath;
	private String databasePath;

	public WebdavTransferManager(WebdavConnection connection) {
		super(connection);

		this.repoPath = connection.getUrl().replaceAll("/$", "") + "/";
		this.multichunkPath = repoPath + "multichunks/";
		this.databasePath = repoPath + "databases/";
	}

	@Override
	public WebdavConnection getConnection() {
		return (WebdavConnection) super.getConnection();
	}

	@Override
	public void connect() throws StorageException {
		if (sardine == null) {
			logger.log(Level.INFO, "WebDAV: Connect called. Creating Sardine ...");
			
			if (getConnection().isSecure()) {
				final SSLSocketFactory sslSocketFactory = getConnection().getSslSocketFactory();

				sardine = new SardineImpl() {
					@Override
					protected SSLSocketFactory createDefaultSecureSocketFactory() {
						return sslSocketFactory;
					}
				};

				sardine.setCredentials(getConnection().getUsername(), getConnection().getPassword());
			}
			else {
				sardine = SardineFactory.begin(getConnection().getUsername(), getConnection().getPassword());
			}
		}
	}

	@Override
	public void disconnect() {
		sardine = null;
	}

	@Override
	public void init(boolean createIfRequired) throws StorageException {
		connect();

		try {
			if (!testRepoFileExists() && createIfRequired) {
				logger.log(Level.INFO, "WebDAV: Init called; creating repo directories ... ");
				
				sardine.createDirectory(repoPath);
				sardine.createDirectory(multichunkPath);
				sardine.createDirectory(databasePath);
			}
		}
		catch (Exception e) {
			logger.log(Level.SEVERE, "Cannot initialize WebDAV folder.", e);
			throw new StorageException(e);
		}
	}

	@Override
	public void download(RemoteFile remoteFile, File localFile) throws StorageException {
		connect();
		String remoteURL = getRemoteFileUrl(remoteFile);

		try {
			logger.log(Level.INFO, "WebDAV: Downloading " + remoteURL + " to temp file " + localFile + " ...");
			
			InputStream webdavFileInputStream = sardine.get(remoteURL);
			FileOutputStream localFileOutputStream = new FileOutputStream(localFile);

			FileUtil.appendToOutputStream(webdavFileInputStream, localFileOutputStream);

			localFileOutputStream.close();
			webdavFileInputStream.close();
		}
		catch (IOException ex) {
			logger.log(Level.SEVERE, "Error while downloading file from WebDAV: " + remoteURL, ex);
			throw new StorageException(ex);
		}
	}

	@Override
	public void upload(File localFile, RemoteFile remoteFile) throws StorageException {
		connect();
		String remoteURL = getRemoteFileUrl(remoteFile);

		try {
			logger.log(Level.INFO, "WebDAV: Uploading local file " + localFile + " to " + remoteURL + " ...");
			InputStream localFileInputStream = new FileInputStream(localFile);

			sardine.put(remoteURL, localFileInputStream, APPLICATION_CONTENT_TYPE);
			localFileInputStream.close();
		}
		catch (Exception ex) {
			logger.log(Level.SEVERE, "Error while uploading file to WebDAV: " + remoteURL, ex);
			throw new StorageException(ex);
		}
	}

	@Override
	public <T extends RemoteFile> Map<String, T> list(Class<T> remoteFileClass) throws StorageException {
		connect();

		try {
			// List folder
			String remoteFileUrl = getRemoteFilePath(remoteFileClass);			
			logger.log(Level.INFO, "WebDAV: Listing objects in " + remoteFileUrl + " ...");
			
			List<DavResource> resources = sardine.list(remoteFileUrl);

			// Create RemoteFile objects
			String rootPath = repoPath.substring(0, repoPath.length() - new URI(repoPath).getRawPath().length());
			Map<String, T> remoteFiles = new HashMap<String, T>();

			for (DavResource res : resources) {
				// WebDAV returns the parent resource itself; ignore it
				String fullResourceUrl = rootPath + res.getPath().replaceAll("/$", "") + "/";				
				boolean isParentResource = remoteFileUrl.equals(fullResourceUrl.toString());

				if (!isParentResource) {
					try {
						T remoteFile = RemoteFile.createRemoteFile(res.getName(), remoteFileClass);
						remoteFiles.put(res.getName(), remoteFile);

						logger.log(Level.FINE, "WebDAV: Matching WebDAV resource: " + res);
					}
					catch (Exception e) {
						logger.log(Level.FINEST, "Cannot create instance of " + remoteFileClass.getSimpleName() + " for object " + res.getName()
								+ "; maybe invalid file name pattern. Ignoring file.");
					}
				}
			}

			return remoteFiles;
		}
		catch (Exception ex) {
			logger.log(Level.SEVERE, "WebDAV: Unable to list WebDAV directory.", ex);
			throw new StorageException(ex);
		}
	}

	@Override
	public boolean delete(RemoteFile remoteFile) throws StorageException {
		connect();
		String remoteURL = getRemoteFileUrl(remoteFile);

		try {
			logger.log(Level.FINE, "WebDAV: Deleting " + remoteURL);
			sardine.delete(remoteURL);
			
			return true;
		}
		catch (SardineException e) {
			if (e.getStatusCode() == HTTP_NOT_FOUND) {
				return true;
			}
			else {
				return false;
			}
		}
		catch (IOException ex) {
			logger.log(Level.SEVERE, "Error while deleting file from WebDAV: " + remoteURL, ex);
			throw new StorageException(ex);
		}
	}

	private String getRemoteFileUrl(RemoteFile remoteFile) {
		return getRemoteFilePath(remoteFile.getClass()) + remoteFile.getName();
	}

	private String getRemoteFilePath(Class<? extends RemoteFile> remoteFile) {
		if (remoteFile.equals(MultiChunkRemoteFile.class)) {
			return multichunkPath;
		}
		else if (remoteFile.equals(DatabaseRemoteFile.class)) {
			return databasePath;
		}
		else {
			return repoPath;
		}
	}

	@Override
	public boolean testTargetCanWrite() throws StorageException {
		try {
			String testFileUrl = repoPath + "syncany-write-test";
			
			sardine.put(testFileUrl, new byte[] { 0x01 }, APPLICATION_CONTENT_TYPE);
			sardine.delete(testFileUrl);
			
			logger.log(Level.INFO, "testTargetCanWrite: Can write, test file created/deleted successfully.");
			return true;			
		}
		catch (Exception e) {
			logger.log(Level.INFO, "testTargetCanWrite: Can NOT write to target.", e);
			return false;
		}
	}

	/**
	 * Checks if the repo exists at the repo URL.
	 * 
	 * <p><b>Note:</b> This uses list() instead of exists() because Sardine implements
	 * the exists() method with a HTTP HEAD only. Some WebDAV servers respond with "Forbidden" 
	 * if for directories.
	 */
	@Override
	public boolean testTargetExists() {
		try {
			sardine.list(repoPath);
			
			logger.log(Level.INFO, "testTargetExists: Target exists.");
			return true;					
		}
		catch (Exception e) {
			logger.log(Level.WARNING, "testTargetExists: Exception thrown while testing if folder exists.", e);
			return false;
		}
	}

	@Override
	public boolean testTargetCanCreate() throws StorageException {
		try {
			if (testTargetExists()) {
				logger.log(Level.INFO, "testTargetCanCreate: Target already exists, so 'can create' test successful.");
				return true;
			}
			else {
				sardine.createDirectory(repoPath);
				sardine.delete(repoPath);

				logger.log(Level.INFO, "testTargetCanCreate: Target can be created (test-created successfully).");
				return true;
			}			
		}
		catch (Exception e) {
			logger.log(Level.INFO, "testTargetCanCreate: Target can NOT be created.", e);
			return false;
		}
	}

	@Override
	public boolean testRepoFileExists() throws StorageException {
		try {
			String repoFileUrl = getRemoteFileUrl(new RepoRemoteFile());
			
			if (sardine.exists(repoFileUrl)) {
				logger.log(Level.INFO, "testRepoFileExists: Repo file exists.");
				return true;
			} 
			else {
				logger.log(Level.INFO, "testRepoFileExists: Repo file does NOT exist.");
				return false;
			}
		} 
		catch (Exception e) {
			logger.log(Level.WARNING, "testRepoFileExists: Exception thrown while testing if repo file exists.", e);
			return false;
		}
	}
}
