package gwtdistcc.client;

import gwtdistcc.client.CompileUtils.RunResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gwt.dev.CompilePerms;
import com.google.gwt.dev.Precompile;

/**
 * Wait for builds to appear on the server(s) and build them using CompilePerms
 * 
 * May monitor several queues.
 * 
 * Scans *.properties in the queues folder, each file defines one queue to watch with these keys:
 * 
 * 1. server: Server URL
 * 2. q: Queue ID(s) (may be more than one, comma-separated) (defaults to "default")
 * 3. label: Worker Label (optional, default to local host name)
 * 4. key: Secret Key
 * 
 * 
 */
public class BuildSlave {

	private static ApiClient client;
	private static Logger logger;
	private static String workerId;
	private static String workerLabel;
	private static File workDir;

	private static final class Build implements Runnable {
		private final BuildInProgress bip;
		private final String key;
		private final String uploadURL;

		private Build(BuildInProgress bip, String key,
				String uploadURL) {
			this.bip = bip;
			this.key = key;
			this.uploadURL = uploadURL;
		}

		@Override
		public void run() {
			doBuild(bip, uploadURL, key);
		}
	}
	static class QueueToWatch implements Comparable<QueueToWatch> {
		String server;
		String queues;
		String workerLabel;
		String key;
		public QueueToWatch(String server, String queues, String workerLabel,
				String key) {
			super();
			this.server = server;
			this.queues = queues;
			this.workerLabel = workerLabel;
			this.key = key;
		}
		@Override
		public int compareTo(QueueToWatch o) {
			int cmp = 0;
			if((cmp = server.compareTo(o.server)) != 0)
				return cmp;
			if((cmp = queues.compareTo(o.queues)) != 0)
				return cmp;
			if((cmp = workerLabel.compareTo(o.workerLabel)) != 0)
				return cmp;
			if((cmp = key.compareTo(o.key)) != 0)
				return cmp;
			return 0;
		}
		private String getCheckForWorkURL() {
			StringBuffer url = new StringBuffer(this.server+"/check-for-work?");
			TreeMap<String,String> params = new TreeMap<String, String>();
			params.put("q", this.queues);
			params.put("workerLabel", this.workerLabel);
			params.put("workerId", BuildSlave.workerId);
			TreeSet<String> cachedBuilds = getCachedBuilds();
			if(!cachedBuilds.isEmpty()) {
				params.put("cache", StringUtils.join(cachedBuilds,","));
			}
			
			ApiClient.appendQueryString(url, params);
			return url.toString();
		}
		private TreeSet<String> getCachedBuilds() {
			TreeSet<String> cachedBuilds = new TreeSet<String>();
			File[] buildDirs = BuildSlave.workDir.listFiles();
			for(File dir : buildDirs) {
				if(!dir.isDirectory())
					continue;
				File payload = new File(dir, "payload");
				if(!payload.exists())
					continue;
				File serverFile = new File(dir, "server");
				if(!serverFile.exists())
					continue;
				try {
					String server = FileUtils.readFileToString(serverFile).trim();
					if(server.equals(this.server))
						cachedBuilds.add(dir.getName());
				} catch (IOException e) {
				}
			}
			return cachedBuilds;
		}
		static BuildSlave.QueueToWatch load(File f) throws IOException,
				FileNotFoundException {
			Properties p = new Properties();
			p.load(new FileReader(f));
			BuildSlave.QueueToWatch qtw = new BuildSlave.QueueToWatch(
					ApiClient.normalizeServerURL(p.getProperty("server")),
					p.getProperty("q", p.getProperty("queue", p.getProperty("queues", "default"))),
					p.getProperty("label", BuildSlave.workerLabel),
					p.getProperty("key")
					);
			return qtw;
		}
	}
	static class BuildWorker implements Runnable {
		@Override
		public void run() {
			// TODO Auto-generated method stub
			//super.run();
		}
	}
	public static void main(String[] args) {
		workDir = new File("build").getAbsoluteFile();
		workerId = UUID.randomUUID().toString();
		boolean once=false;
		int localWorkers=Runtime.getRuntime().availableProcessors(); // default to the number of processors on the system
		for(int i=0; i < args.length; i++) {
			if(args[i].startsWith("-")) {
				if(args[i].equals("-workDir")) {
					workDir = new File(args[i+1]);
				} else if(args[i].equals("-once")) {
					once = true;
					continue;
				} else if(args[i].equals("-label")) {
					workerLabel = args[i+1];
				} else if(args[i].equals("-id")) {
					workerId = args[i+1];
				} else if(args[i].equals("-localWorkers")) {
					if("runtime.availableProcessors".equals(args[i+1])) {
						localWorkers=Runtime.getRuntime().availableProcessors();
					} else if(args[i+1].startsWith("runtime.availableProcessors-")) {
						localWorkers=Math.max(1, Runtime.getRuntime().availableProcessors() - Integer.parseInt(args[i+1].substring("runtime.availableProcessors-".length())));
					} else {
						localWorkers=Integer.parseInt(args[i+1]);
					}
				}
				i++; // skip following argument too
			}
		}
		if(workerLabel == null) {
			try {
				workerLabel = java.net.InetAddress.getLocalHost().getHostName();
			} catch (UnknownHostException e1) {
				e1.printStackTrace();
				System.exit(1);
				return;
			}
		}
		File queuesDir = new File(workDir, "queues");
		
		client = new ApiClient();
		logger = LoggerFactory.getLogger(BuildSlave.class);
		
		String workerStatus="";
		
		ThreadPoolExecutor executor = new ThreadPoolExecutor(localWorkers, localWorkers,
		  0L, TimeUnit.MILLISECONDS,
		  new LinkedBlockingQueue<Runnable>());
		// Loop forever
		for(;;) {
			boolean newBuild=false;
			File[] files = queuesDir.listFiles();
			if(files == null || files.length == 0) {
				System.err.println("No queues defined in "+queuesDir+" please create some or I'll have nothing to do.");
			} else for(File f : files) {
				TreeSet<QueueToWatch> queuesToWatch = new TreeSet<QueueToWatch>();
				if(f.isFile() && f.getName().endsWith(".properties")) {
					try {
						QueueToWatch qtw = QueueToWatch.load(f);
						if(qtw.server==null || qtw.server.isEmpty()) {
							logger.warn("Queue file "+f+" invalid; no server specified");
							continue;
						}
						if(qtw.queues.isEmpty()) {
							logger.warn("Queue file "+f+" invalid; no queues specified");
							continue;
						}
						queuesToWatch.add(qtw);
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				
				for(QueueToWatch qtw : queuesToWatch) {
					// Get a list of builds for which we have cached the AST file
					if(qtw.queues.isEmpty())
						continue;
					try {
						String url = qtw.getCheckForWorkURL();
						GetMethod get = new GetMethod(url);
						client.executeMethod(get);
						try {
							Header invalidCachedBuildsHeader = get.getResponseHeader("X-Delete-Cached-Builds");
							if(invalidCachedBuildsHeader != null && !invalidCachedBuildsHeader.getValue().isEmpty()) {
								for(String delBuildId : invalidCachedBuildsHeader.getValue().split(",")) {
									File buildDir = new File(workDir, delBuildId);
									logger.info("Deleting expired/old build "+buildDir);
									FileUtils.deleteDirectory(buildDir);
								}
							}
							if(get.getStatusCode() == HttpStatus.SC_OK || get.getStatusCode() == HttpStatus.SC_NOT_MODIFIED) {
								// Got a build back ...
								Header buildIdHeader = get.getResponseHeader("X-Build-ID");
								if(buildIdHeader == null) {
									logger.error("Server didn't return a build ID for the build!");
									continue;
								}
								final String buildId = buildIdHeader.getValue();
								Header permHeader = get.getResponseHeader("X-Permutation");
								if(permHeader == null) {
									logger.error("Server didn't return a permutation number for the build!");
									continue;
								}
								final int perm = Integer.parseInt(permHeader.getValue());
								Header uploadResultURLHeader = get.getResponseHeader("X-Upload-Result-To");
								if(uploadResultURLHeader == null) {
									logger.error("Server didn't return an upload URL for the build result!");
									continue;
								}
								final String uploadURL = uploadResultURLHeader.getValue();
								BuildInProgress bip = new BuildInProgress(qtw.server, buildId, perm);
								if(beginNewBuild(bip)) {
									try {
										newBuild=true;
										File buildDir = new File(workDir, buildId);
										buildDir.mkdirs();
										File payloadFile = new File(buildDir, "payload");
										if(payloadFile.exists()) {
											logger.info("Payload already downloaded at "+payloadFile+", skipping download.");
										} else if(get.getStatusCode() == HttpStatus.SC_NOT_MODIFIED) {
											logger.error("Server thought we have this build here already, but we don't.  No smart handling for this scenario is in place yet.");
											continue;
										} else {
											FileOutputStream fos = new FileOutputStream(payloadFile);
											InputStream payloadStream = get.getResponseBodyAsStream();
											try {
												IOUtils.copy(payloadStream, fos); // Write response to a file first
											} finally {
												fos.close();
												payloadStream.close();
											}
										}
										executor.execute(new Build(bip, qtw.key, uploadURL));
									} catch (IOException e) {
										e.printStackTrace();
										continue;
									}
								} else {
									continue; // Build already started
								}
							} else if(get.getStatusCode() == HttpStatus.SC_NOT_FOUND){
								logger.debug("No builds on "+url);
							} else {
								logger.warn("Error reading "+url+": "+get.getStatusLine());
							}
						} finally {
							get.releaseConnection();
						}
					} catch (HttpException e1) {
						e1.printStackTrace();
						continue;
					} catch (IOException e1) {
						e1.printStackTrace();
						continue;
					}
				}
			}
			
			if(once)
				break;
			
			
			try {
				boolean wait;
				do {
					synchronized(buildsInProgress) {
						wait = (executor.getActiveCount() >= localWorkers || buildsInProgress.size()>=localWorkers);
						for(BuildInProgress bip : new ArrayList<BuildInProgress>(buildsInProgress)) {
							try {
								int sc = client.buildAlive(bip.server, bip.buildId, bip.perm, workerId);
								if(sc == HttpStatus.SC_NOT_FOUND && bip.thread != null) {
									// Bad permutation,  abort!
									bip.thread.interrupt();
									buildsInProgress.remove(bip);
								}
							} catch (Exception e) {
								logger.error("Error sending build ping to server", e);
							}
						}
						String newWorkerStatus = executor.getActiveCount()+" of "+localWorkers+" workers active for "+buildsInProgress.size()+" builds in progress";
						if(!newWorkerStatus.equals(workerStatus))
							logger.info(workerStatus = newWorkerStatus);
					}
					if(wait || !newBuild)
						Thread.sleep(5000);
				} while(wait);
			} catch(InterruptedException ie) {
				System.err.println("Interrupted, exiting.");
				System.exit(1);
			}
		}
	}
	static class BuildInProgress {
		String server;
		String buildId;
		int perm;
		Thread thread;
		
		public BuildInProgress(String server, String buildId, int perm) {
			super();
			this.server = server;
			this.buildId = buildId;
			this.perm = perm;
		}
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result
					+ ((buildId == null) ? 0 : buildId.hashCode());
			result = prime * result + perm;
			result = prime * result
					+ ((server == null) ? 0 : server.hashCode());
			return result;
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			BuildInProgress other = (BuildInProgress) obj;
			if (buildId == null) {
				if (other.buildId != null)
					return false;
			} else if (!buildId.equals(other.buildId))
				return false;
			if (perm != other.perm)
				return false;
			if (server == null) {
				if (other.server != null)
					return false;
			} else if (!server.equals(other.server))
				return false;
			return true;
		}
		
	}
	static final HashSet<BuildInProgress> buildsInProgress = new HashSet<BuildInProgress>();
	static boolean beginNewBuild(BuildInProgress bip) {
		synchronized (buildsInProgress) {
			return buildsInProgress.add(bip);
		}
	}
	static void exitBuild(BuildInProgress bip) {
		synchronized (buildsInProgress) {
			buildsInProgress.remove(bip);
		}
	}
	
	static String decryptPayload(String buildId, String cryptKey) throws IOException {
		File buildDir = new File(workDir, buildId);
		File payloadFile = new File(buildDir, "payload");
		
		// Decrypt payload
		FileInputStream fis = new FileInputStream(payloadFile);
		int version = fis.read();
		if(version != DistCompile.V1_BYTE) {									
			throw new Error("Build file has unspported version number, or is corrupted.  Aborting!");
		}
		InputStream in = CompileUtils.maybeDecryptStream(cryptKey, fis);
		
		String moduleName = CompileUtils.readSmallString(in);
		
		File compileDir = getCompileDir(buildDir, moduleName);
		if(!compileDir.exists() && !compileDir.mkdirs()) {
			throw new Error("Unable to create compilation folder "+compileDir+"; is the module name valid?");
		}
		File astFile = new File(compileDir, Precompile.PRECOMPILE_FILENAME);
		if(astFile.exists()) {
			logger.info("Decrypted AST file already on disk, re-using existing file.");
		} else {
			File tempFile = File.createTempFile("payload", ".tmp", compileDir);
			
			FileOutputStream fos = new FileOutputStream(tempFile);
			try {
				IOUtils.copy(in, fos);
			} finally {
				in.close();
				fos.close();
			}
			if(!tempFile.renameTo(astFile) && !astFile.exists()) {
				throw new Error("Unable to rename temp decryption file "+tempFile+" to AST file "+astFile);
			}
		}
		return moduleName;
	}
	public static void doBuild(BuildInProgress bip, String uploadURL, String cryptKey) {
		String server = bip.server;
		String buildId = bip.buildId;
		int perm = bip.perm;
		try {
			bip.thread = Thread.currentThread();
			
			String moduleName = decryptPayload(buildId, cryptKey);
			logger.info("Compiling "+moduleName+" in build ID "+buildId+" and permutation "+perm);
			
			File buildDir = new File(workDir, buildId);
			
			// Decrypt payload
			File compileDir = getCompileDir(buildDir, moduleName);
			
			File cachedFailure = new File(buildDir, "failure.txt");
			if(cachedFailure.exists()) {
				String failure = FileUtils.readFileToString(cachedFailure);
				logger.info("Rejecting build since we've already failed to build this one before.  Hopefully another worker will pick it up.");
				client.addBuildFailure(server, buildId, perm, workerId, failure);
				return;
			}
			
			try {
				File permutationFile = new File(compileDir, "permutation-"+perm+".js");
				if(!permutationFile.exists()) {
					RunResult buildResult = CompileUtils.launchTool(CompilePerms.class, moduleName, "-workDir", buildDir.getAbsolutePath(), "-perms", String.valueOf(perm));
					try {
						if(buildResult.waitFor() != 0) {
							String failure = "CompileDist returned non-zero exit status.";
							if(buildResult.isOutOfMemoryError()) {
								failure = "out of memory";
							} else if(buildResult.isClassNotFound()) {
								failure = "class not found";
							}
							FileUtils.writeStringToFile(cachedFailure, failure);
							client.addBuildFailure(server, buildId, perm, workerId, failure);
							return;
						}
					} finally {
						buildResult.terminateProcess();
					}
				} else {
					logger.info("Permutation "+permutationFile+" already exists, using cached artifact");
				}
				File encryptedJs = new File(compileDir, "permutation-"+perm+".js.crypt");
				boolean alreadyEncrypted = encryptedJs.exists() && encryptedJs.lastModified() >= permutationFile.lastModified();
				if(!alreadyEncrypted) {
					CompileUtils.encryptFile(cryptKey, permutationFile, encryptedJs);
				}
				try {
					client.addBuildResult(server, buildId, perm, uploadURL, workerId, encryptedJs);
				} catch (NumberFormatException e) {
					throw new Error(e);
				} catch (ApiException e) {
					e.printStackTrace();
					return;
				}
				
			} catch (InterruptedException e) {
				client.addBuildFailure(server, buildId, perm, workerId, "interrupted");
				logger.error("Interrupted build, exiting ...");
			}
			
		} catch (Throwable t) {
			try {
				logger.error("Build failed", t);
				client.addBuildFailure(server, buildId, perm, workerId, StringUtils.defaultString(t.getLocalizedMessage(), t.getClass().getName()));
			} catch (HttpException e) {
				logger.error("Failed to report build failure to server.", e);
			} catch (IOException e) {
				logger.error("Failed to report build failure to server.", e);
			}
			return;
		} finally {
			exitBuild(bip);
		}
	}
	private static File getCompileDir(File buildDir, String moduleName) {
		File moduleDir = new File(buildDir, moduleName);
		File compileDir = new File(moduleDir, "compiler");
		return compileDir;
	}
}
