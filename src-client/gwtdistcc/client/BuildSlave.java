package gwtdistcc.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
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
		private final String server;
		private final String key;
		private final String buildId;
		private final int perm;
		private final String uploadURL;

		private Build(String server, String key, String buildId, int perm,
				String uploadURL) {
			this.server = server;
			this.key = key;
			this.buildId = buildId;
			this.perm = perm;
			this.uploadURL = uploadURL;
		}

		@Override
		public void run() {
			doBuild(server, buildId, perm, uploadURL, key);
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
			ApiClient.appendQueryString(url, params);
			return url.toString();
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
		int localWorkers=1;
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
					localWorkers=Integer.parseInt(args[i+1]);
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
					TreeSet<String> cachedBuilds = new TreeSet<String>();
					File[] buildDirs = workDir.listFiles();
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
							if(server.equals(qtw.server))
								cachedBuilds.add(dir.getName());
						} catch (IOException e) {
						}
					}
					if(qtw.queues.isEmpty())
						continue;
					try {
						String url = qtw.getCheckForWorkURL();
						GetMethod get = new GetMethod(url);
						client.executeMethod(get);
						try {
							Header invalidCachedBuildsHeader = get.getResponseHeader("X-Delete-Cached-Builds");
							if(invalidCachedBuildsHeader != null && !invalidCachedBuildsHeader.getValue().isEmpty()) {
								for(String delBuild : invalidCachedBuildsHeader.getValue().split(",")) {
									logger.info("Deleting expired/old build "+delBuild);
									FileUtils.deleteDirectory(new File(workDir, delBuild));
								}
							}
							if(get.getStatusCode() == HttpStatus.SC_OK) {
								// Got a build back ...
								final String buildId = get.getResponseHeader("X-Build-ID").getValue();
								final int perm = Integer.parseInt(get.getResponseHeader("X-Permutation").getValue());
								logger.info("Got a build back with ID "+buildId+" and permutation "+perm+" from "+url);
								final String uploadURL = get.getResponseHeader("X-Upload-Result-To").getValue();
								if(beginNewBuild(qtw.server, buildId, perm)) {
									try {
										newBuild=true;
										File buildDir = new File(workDir, buildId);
										buildDir.mkdirs();
										File payloadFile = new File(buildDir, "payload");
										FileOutputStream fos = new FileOutputStream(payloadFile);
										InputStream payloadStream = get.getResponseBodyAsStream();
										try {
											IOUtils.copy(payloadStream, fos); // Write response to a file first
										} finally {
											fos.close();
											payloadStream.close();
										}
										executor.execute(new Build(qtw.server, qtw.key, buildId, perm, uploadURL));
										synchronized(buildsInProgress) {
											System.out.println(executor.getActiveCount()+" workers running for "+buildsInProgress.size()+" builds in progress; max local workers is "+localWorkers+".");
										}
									} catch (IOException e) {
										e.printStackTrace();
										continue;
									}
								} else {
									continue; // Build already started
								}
							} else if(get.getStatusCode() == HttpStatus.SC_NOT_FOUND){
								logger.info("No builds on "+url);
							} else {
								logger.warn("Error reading "+url);
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
						for(BuildInProgress bip : buildsInProgress) {
							try {
								client.buildAlive(bip.server, bip.buildId, bip.perm, workerId);
							} catch (Exception e) {
								logger.error("Error sending build ping to server", e);
							}
						}
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
	static boolean beginNewBuild(String server, String buildId, int perm) {
		synchronized (buildsInProgress) {
			return buildsInProgress.add(new BuildInProgress(server, buildId, perm));
		}
	}
	static void exitBuild(String server, String buildId, int perm) {
		synchronized (buildsInProgress) {
			buildsInProgress.remove(new BuildInProgress(server, buildId, perm));
		}
	}
	public static void doBuild(String server, String buildId, int perm, String uploadURL, String cryptKey) {
		try {
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
			compileDir.mkdirs();
			File astFile = new File(compileDir, Precompile.PRECOMPILE_FILENAME);
			FileOutputStream fos = new FileOutputStream(astFile);
			try {
				IOUtils.copy(in, fos);
			} finally {
				in.close();
				fos.close();
			}
			
			try {
				File permutationFile = new File(compileDir, "permutation-"+perm+".js");
				if(!permutationFile.exists()) {
					int rc = CompileUtils.launchToolAndWait(CompilePerms.class, moduleName, "-workDir", buildDir.getAbsolutePath(), "-perms", String.valueOf(perm));
					if(rc != 0) {
						logger.warn("Build failed ... TODO report this back to the server");
						return;
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
				logger.error("Interrupted, exiting ...");
				System.exit(1);
			}
			
		} catch (IOException e) {
			e.printStackTrace();
			return;
		} finally {
			exitBuild(server, buildId, perm);
		}
	}
	private static File getCompileDir(File buildDir, String moduleName) {
		File moduleDir = new File(buildDir, moduleName);
		File compileDir = new File(moduleDir, "compiler");
		return compileDir;
	}
}
