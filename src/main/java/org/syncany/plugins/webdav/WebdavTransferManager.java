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
package org.syncany.plugins.webdav;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;

import org.apache.commons.io.IOUtils;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.TrustStrategy;
import org.syncany.config.Config;
import org.syncany.config.UserConfig;
import org.syncany.crypto.CipherUtil;
import org.syncany.plugins.UserInteractionListener;
import org.syncany.plugins.transfer.AbstractTransferManager;
import org.syncany.plugins.transfer.StorageException;
import org.syncany.plugins.transfer.StorageMoveException;
import org.syncany.plugins.transfer.files.ActionRemoteFile;
import org.syncany.plugins.transfer.files.CleanupRemoteFile;
import org.syncany.plugins.transfer.files.DatabaseRemoteFile;
import org.syncany.plugins.transfer.files.MultichunkRemoteFile;
import org.syncany.plugins.transfer.files.RemoteFile;
import org.syncany.plugins.transfer.files.SyncanyRemoteFile;
import org.syncany.plugins.transfer.files.TempRemoteFile;
import org.syncany.plugins.transfer.files.TransactionRemoteFile;
import org.syncany.util.StringUtil;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;
import com.github.sardine.impl.SardineException;
import com.github.sardine.impl.SardineImpl;

public class WebdavTransferManager extends AbstractTransferManager {
	private static final Logger logger = Logger.getLogger(WebdavTransferManager.class.getSimpleName());

	private static final String APPLICATION_CONTENT_TYPE = "application/octet-stream";
	private static final int HTTP_NOT_FOUND = 404;
	
	private static boolean hasNewCertificates;

	private Sardine sardine;	

	private String repoPath;
	private String multichunksPath;
	private String databasesPath;
	private String actionsPath;
	private String transactionsPath;
	private String tempPath;
	
	public WebdavTransferManager(WebdavTransferSettings connection, Config config) {
		super(connection, config);

		this.sardine = null;

		this.repoPath = connection.getUrl().replaceAll("/$", "") + "/";
		this.multichunksPath = repoPath + "multichunks/";
		this.databasesPath = repoPath + "databases/";
		this.actionsPath = repoPath + "actions/";
		this.transactionsPath = repoPath + "transactions/";
		this.tempPath = repoPath + "temporary/";
	}

	public WebdavTransferSettings getSettings() {
		return (WebdavTransferSettings) settings;
	}

	@Override
	public void connect() throws StorageException {
		if (sardine == null) {
			if (getSettings().isSecure()) {
				logger.log(Level.INFO, "WebDAV: Connect called. Creating Sardine (SSL!) ...");

				try {									
					final ConnectionSocketFactory sslSocketFactory = initSsl();
	
					sardine = new SardineImpl() {
						@Override
						protected ConnectionSocketFactory createDefaultSecureSocketFactory() {
							return sslSocketFactory;
						}
					};
	
					sardine.setCredentials(getSettings().getUsername(), getSettings().getPassword());
				}
				catch (Exception e) {
					throw new StorageException(e);
				}
			}
			else {
				logger.log(Level.INFO, "WebDAV: Connect called. Creating Sardine (non-SSL) ...");
				sardine = SardineFactory.begin(getSettings().getUsername(), getSettings().getPassword());
			}
		}
	}

	@Override
	public void disconnect() {
		storeTrustStore();
		sardine = null;
	}

	@Override
	public void init(boolean createIfRequired) throws StorageException {
		connect();

		try {
			logger.log(Level.INFO, "WebDAV: Init called; creating repo directories ... ");				

			if (!testTargetExists() && createIfRequired) {
				sardine.createDirectory(repoPath);
			}
			
			sardine.createDirectory(multichunksPath);
			sardine.createDirectory(databasesPath);
			sardine.createDirectory(actionsPath);
			sardine.createDirectory(transactionsPath);
			sardine.createDirectory(tempPath);
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

			IOUtils.copy(webdavFileInputStream, localFileOutputStream);

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
			sardine.put(remoteURL, localFileInputStream, APPLICATION_CONTENT_TYPE, true, localFile.length());
			
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
	
	@Override
	public void move(RemoteFile sourceFile, RemoteFile targetFile) throws StorageException {
		connect();
		
		String sourceURL = getRemoteFileUrl(sourceFile);
		String targetURL = getRemoteFileUrl(targetFile);

		try {		
			sardine.move(sourceURL, targetURL);
		}
		catch (IOException e) {
			throw new StorageMoveException("Unable to move " + sourceURL + " to " + targetURL, e);
		}
	}

	private String getRemoteFileUrl(RemoteFile remoteFile) {
		return getRemoteFilePath(remoteFile.getClass()) + remoteFile.getName();
	}

	private String getRemoteFilePath(Class<? extends RemoteFile> remoteFile) {
		if (remoteFile.equals(MultichunkRemoteFile.class)) {
			return multichunksPath;
		}
		else if (remoteFile.equals(DatabaseRemoteFile.class) || remoteFile.equals(CleanupRemoteFile.class)) {
			return databasesPath;
		}
		else if (remoteFile.equals(ActionRemoteFile.class)) {
			return actionsPath;
		}
		else if (remoteFile.equals(TransactionRemoteFile.class)) {
			return transactionsPath;
		}
		else if (remoteFile.equals(TempRemoteFile.class)) {
			return tempPath;
		}
		else {
			return repoPath;
		}
	}

	@Override
	public boolean testTargetCanWrite() throws StorageException {
		try {
			String testFileUrl = repoPath + "syncany-write-test";
			
			sardine.put(testFileUrl, new byte[] { 0x01 });
			sardine.delete(testFileUrl);
			
			logger.log(Level.INFO, "testTargetCanWrite: Can write, test file created/deleted successfully.");
			return true;			
		}
		catch (SSLPeerUnverifiedException e) {
			logger.log(Level.SEVERE, "testTargetCanWrite: SSL handshake failed; peer not authenticated.", e);
			throw new StorageException("SSL handshake failed; peer not authenticated.", e);
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
	public boolean testTargetExists() throws StorageException {
		try {
			sardine.list(repoPath);
			
			logger.log(Level.INFO, "testTargetExists: Target exists.");
			return true;					
		}
		catch (SSLPeerUnverifiedException e) {
			logger.log(Level.SEVERE, "testTargetCanWrite: SSL handshake failed; peer not authenticated.", e);
			throw new StorageException("SSL handshake failed; peer not authenticated.", e);
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
		catch (SSLPeerUnverifiedException e) {
			logger.log(Level.SEVERE, "testTargetCanWrite: SSL handshake failed; peer not authenticated.", e);
			throw new StorageException("SSL handshake failed; peer not authenticated.", e);
		}
		catch (Exception e) {
			logger.log(Level.INFO, "testTargetCanCreate: Target can NOT be created.", e);
			return false;
		}
	}

	@Override
	public boolean testRepoFileExists() throws StorageException {
		try {
			String repoFileUrl = getRemoteFileUrl(new SyncanyRemoteFile());
			
			if (sardine.exists(repoFileUrl)) {
				logger.log(Level.INFO, "testRepoFileExists: Repo file exists.");
				return true;
			} 
			else {
				logger.log(Level.INFO, "testRepoFileExists: Repo file does NOT exist.");
				return false;
			}
		} 
		catch (SSLPeerUnverifiedException e) {
			logger.log(Level.SEVERE, "testTargetCanWrite: SSL handshake failed; peer not authenticated.", e);
			throw new StorageException("SSL handshake failed; peer not authenticated.", e);
		}
		catch (Exception e) {
			logger.log(Level.WARNING, "testRepoFileExists: Exception thrown while testing if repo file exists.", e);
			return false;
		}
	}
	
	private void storeTrustStore() {
		if (!hasNewCertificates) {
			logger.log(Level.INFO, "WebDAV: No new certificates. Nothing to store.");
		}
		else {
			logger.log(Level.INFO, "WebDAV: New certificates. Storing trust store on disk.");

			UserConfig.storeTrustStore();
			hasNewCertificates = false;
		}
	}

	private ConnectionSocketFactory initSsl() throws Exception {
		TrustStrategy trustStrategy = new TrustStrategy() {
			@Override
			public boolean isTrusted(X509Certificate[] certificateChain, String authType) throws CertificateException {
				logger.log(Level.INFO, "WebDAV: isTrusted("+certificateChain.toString()+", "+authType+")");
								
				try {
					// First check if already in trust store, if so; okay!
					X509Certificate serverCertificate = certificateChain[0];
					
					for (int i = 0; i < certificateChain.length; i++) {
						X509Certificate certificate = certificateChain[i];

						logger.log(Level.FINE, "WebDAV: Checking certificate validity: " + certificate.getSubjectDN().toString());
						logger.log(Level.FINEST, "WebDAV:              Full certificate: " + certificate);
						
						// Check validity
						try {
							certificate.checkValidity();	
						}
						catch (CertificateException e) {
							logger.log(Level.FINE, "WebDAV: Certificate is NOT valid.", e);
							return false;
						}
						
						logger.log(Level.FINE, "WebDAV: Checking is VALID.");
						
						// Certificate found; we trust this, okay!
						if (inTrustStore(certificate)) {
							logger.log(Level.FINE, "WebDAV: Certificate found in trust store.");
							return true;
						}
						
						// Certificate is new; continue ...
						else {
							logger.log(Level.FINE, "WebDAV: Certificate NOT found in trust store.");
						}
					}
						
					// We we reach this code, none of the CAs are known in the trust store
					// So we ask the user if he/she wants to add the server certificate to the trust store  
					UserInteractionListener userInteractionListener = getSettings().getUserInteractionListener();
					
					if (userInteractionListener == null) {
						throw new RuntimeException("pluginListener cannot be null!");
					}
					
					boolean userTrustsCertificate = userInteractionListener.onUserConfirm("Unknown SSL/TLS certificate", formatCertificate(serverCertificate), "Do you want to trust this certificate?");
					
					if (!userTrustsCertificate) {
						logger.log(Level.INFO, "WebDAV: User does not trust certificate. ABORTING.");
						throw new RuntimeException("User does not trust certificate. ABORTING.");
					}
					
					logger.log(Level.INFO, "WebDAV: User trusts certificate. Adding to trust store.");
					addToTrustStore(serverCertificate);
	
					return true;
				}
				catch (KeyStoreException e) {
					logger.log(Level.SEVERE, "WebDAV: Key store exception.", e);
					return false;
				}
			}		
			
			private boolean inTrustStore(X509Certificate certificate) throws KeyStoreException {
				String certAlias = getCertificateAlias(certificate);		
				return UserConfig.getUserTrustStore().containsAlias(certAlias);
			}
			
			private void addToTrustStore(X509Certificate certificate) throws KeyStoreException {
				String certAlias = getCertificateAlias(certificate);
				UserConfig.getUserTrustStore().setCertificateEntry(certAlias, certificate);
				
				hasNewCertificates = true;				
			}
			
			private String getCertificateAlias(X509Certificate certificate) {
				return StringUtil.toHex(certificate.getSignature());
			}
		};

		SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null, trustStrategy).useTLS().build();
				
		return new SSLConnectionSocketFactory(sslContext, new AllowAllHostnameVerifier());
	}
	
	private String formatCertificate(X509Certificate cert) {
		try {			
			CipherUtil.enableUnlimitedStrength(); // Dirty!
			
			String checksumMd5 = formatChecksum(createChecksum(cert.getEncoded(), "MD5"));
			String checksumSha1 = formatChecksum(createChecksum(cert.getEncoded(), "SHA1"));
			String checksumSha256 = formatChecksum(createChecksum(cert.getEncoded(), "SHA256"));
			
			StringBuilder sb = new StringBuilder();
			
			sb.append(String.format("Owner: %s\n", cert.getSubjectDN().getName()));
			sb.append(String.format("Issuer: %s\n", cert.getIssuerDN().getName()));
			sb.append(String.format("Serial number: %d\n", cert.getSerialNumber()));
			sb.append(String.format("Valid from %s until: %s\n", cert.getNotBefore().toString(), cert.getNotAfter().toString()));
			sb.append("Certificate fingerprints:\n");
			sb.append(String.format(" MD5:  %s\n", checksumMd5));
			sb.append(String.format(" SHA1: %s\n", checksumSha1));
			sb.append(String.format(" SHA256: %s", checksumSha256));
						
			return sb.toString();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}		
	}
	
	private String formatChecksum(byte[] checksum) {
		StringBuilder sb = new StringBuilder();
		
		for (int i=0; i<checksum.length; i++) {
			sb.append(StringUtil.toHex(new byte[] { checksum[i] }).toUpperCase());
			
			if (i < checksum.length-1) {
				sb.append(":");
			}
		}
		
		return sb.toString();
	}

	private byte[] createChecksum(byte[] data, String digestAlgorithm) {
		try {
			MessageDigest digest = MessageDigest.getInstance(digestAlgorithm);
			digest.update(data, 0, data.length);
			
			return digest.digest();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
