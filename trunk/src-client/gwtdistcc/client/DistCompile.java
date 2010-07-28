package gwtdistcc.client;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gwt.dev.Link;
import com.google.gwt.dev.Precompile;

/**
 * Run Precompile, then send it out to compile, the wait for the build to complete, then Link.
 * 
 */
public class DistCompile {
	
	public static final byte V1_BYTE = 33;


	static final Logger logger = LoggerFactory.getLogger(DistCompile.class);
	
	static final HashSet<String> PARAMETER_ARGS = new HashSet<String>(Arrays.asList(
			"-workDir", 
			"-logLevel", 
			"-gen", 
			"-style",
			"-war",
			"-extra",
			"-server",
			"-queue",
			"-key",
			"-label"));
	static final HashSet<String> COMPILER_ARGS = new HashSet<String>(Arrays.asList(
			"-workDir", 
			"-logLevel", 
			"-gen", 
			"-style", 
			"-ea", 
			"-XdisableClassMetadata", 
			"-XdisableCastChecking", 
			"-validateOnly", 
			"-draftCompile", 
			"-compileReport"));
	static final HashSet<String> LINKER_ARGS = new HashSet<String>(Arrays.asList(
			"-workDir", 
			"-logLevel", 
			"-extra", 
			"-war"));
	
	public static void main(String[] args) {
		
		try {
			// TODO Need to separate out the compiler vs linker args and pass them to the appropriate step
			TreeSet<String> modules = new TreeSet<String>();
			File workDir = new File(".");
			String server = "https://gwtdistcc.appspot.com";
			String buildLabel = "Build"; 
			TreeSet<String> queues = new TreeSet<String>();
			String cryptKey = null;
			ArrayList<String> compileArgs = new ArrayList<String>();
			ArrayList<String> linkerArgs = new ArrayList<String>();
			for(int i=0; i < args.length; i++) {
				if(args[i].charAt(0) == '-') {
					if(args[i].equals("-workDir")) {
						workDir = new File(args[i+1]);
					} else if(args[i].equals("-server")) {
						server = ApiClient.normalizeServerURL(args[i+1]);
					} else if(args[i].equals("-queue")) {
						for(String q : args[i+1].split(",")) {
							queues.add(q.trim());
						}
					} else if(args[i].equals("-label")) {
						buildLabel = args[i+1];
					} else if(args[i].equals("-key")) {
						cryptKey = args[i+1];
					}
					boolean hasParameter = PARAMETER_ARGS.contains(args[i]) && (i+1) < args.length;
					
					if(COMPILER_ARGS.contains(args[i])) {
						compileArgs.add(args[i]);
						if(hasParameter)
							compileArgs.add(args[i+1]);
					}
					if(LINKER_ARGS.contains(args[i])) {
						linkerArgs.add(args[i]);
						if(hasParameter)
							linkerArgs.add(args[i+1]);
					}
					
					if(hasParameter)
						i++; // Skip the argument, too
					
				} else {
					String moduleName = args[i];
					modules.add(moduleName);
					compileArgs.add(moduleName);
					linkerArgs.add(moduleName);
				}
			}
			if(modules.isEmpty()) {
				System.err.println("Failed to parse module name(s) from command line arguments.");
				System.exit(1);
			}
			if(queues.isEmpty()) {
				System.err.println("You must specify one or more queues to submit to by passing the -queue command line argument.");
				System.exit(1);
			}
			CompileUtils.launchToolAndWaitAndExitOnFailure(Precompile.class, compileArgs.toArray(new String[compileArgs.size()]));
			TreeMap<String,TreeSet<String>> waitingForBuilds = new TreeMap<String, TreeSet<String>>();
			TreeMap<String,String> moduleNameForBuild = new TreeMap<String, String>();
			ApiClient apiClient = new ApiClient();
			for(String moduleName : modules) {
				logger.info("Compiling module "+moduleName+" workDir is "+workDir+" server is "+server);
				File moduleDir = new File(workDir, moduleName);
				File moduleCompileDir = new File(moduleDir, "compiler");
				File permsCountFile = new File(moduleCompileDir, "permCount.txt");
				int permCount = Integer.parseInt(IOUtils.toString(new FileReader(permsCountFile)).trim());
				File astFile = new File(moduleCompileDir, Precompile.PRECOMPILE_FILENAME);
				String buildId = CompileUtils.digestFile(cryptKey.getBytes(), astFile);
				
				File buildDir = new File(workDir, buildId);
				if(!buildDir.mkdirs()) {
					System.err.println("Failed to create folder "+buildDir);
				}
				File payloadFile = new File(buildDir, "payload");
				CompileUtils.encryptPayload(moduleName, cryptKey, astFile, payloadFile);
				File buildModuleDir = new File(buildDir, moduleName);
				FileUtils.copyDirectoryToDirectory(moduleDir, buildModuleDir);
				
				try {
					apiClient.addBuild(server, buildLabel, buildId, queues.toArray(new String[queues.size()]), permCount, payloadFile);
				} catch(ApiException ae) {
					if(ae.getStatusCode() == HttpStatus.SC_CONFLICT) {
						logger.info("Build appears to have already been uploaded, previous build results might be re-used.");
					} else {
						logger.error("Error submitting build", ae);
						System.exit(1);
						return;
					}
				}
	
				// Now that its on the server, flag it that way in our local folder so other build slaves
				// will see it and delete it at the appropriate time
				File serverFile = new File(buildDir, "server");
				FileUtils.writeStringToFile(serverFile, server);
	
				
				TreeSet<String> waitingForPermutations = new TreeSet<String>();
				for(int i=0; i < permCount; i++) {
					waitingForPermutations.add(String.valueOf(i));
				}
				waitingForBuilds.put(buildId, waitingForPermutations);
				moduleNameForBuild.put(buildId, moduleName);
			}
			// Now wait for the build to finish
			long timeout = System.currentTimeMillis() + 1200000;
			while(!waitingForBuilds.isEmpty() && System.currentTimeMillis() < timeout) {
				Thread.sleep(5000);
				for(Map.Entry<String,TreeSet<String>> buildEntry : new ArrayList<Map.Entry<String,TreeSet<String>>>(waitingForBuilds.entrySet())) {
					String buildId = buildEntry.getKey();
					TreeSet<String> waitingForPermutations = buildEntry.getValue();
					String buildStatusURL = server+"/build-status?id="+buildId;
					HeadMethod req = new HeadMethod(buildStatusURL);
					apiClient.executeMethod(req);
					System.out.println("Waiting for build results to come back... "+buildStatusURL);
					if(req.getStatusCode() != HttpStatus.SC_OK) {
						logger.error("Error checking build status: "+req.getStatusLine());
						System.exit(1);
						return;
					}
					String completedPermsString = req.getResponseHeader("X-Permutations-Finished").getValue();
					String[] completedPermsStrArray = completedPermsString.split(",");
					for(String perm : completedPermsStrArray) {
						if(waitingForPermutations.remove(perm)) {
							logger.info("Downloading permutation "+perm);
							GetMethod dlreq = new GetMethod(server+"/build-result?id="+buildId+"&perm="+perm);
							apiClient.executeMethod(dlreq);
							if(dlreq.getStatusCode() != HttpStatus.SC_OK) {
								logger.error("Error trying to download permutation "+perm+": "+dlreq.getStatusLine());
								System.exit(1);
								return;
							}
							String moduleName = moduleNameForBuild.get(buildId);
							File moduleDir = new File(workDir, moduleName);
							File moduleCompileDir = new File(moduleDir, "compiler");
							moduleCompileDir.mkdirs();
							File permFile = new File(moduleCompileDir, "permutation-"+perm+".js");
							CompileUtils.decryptStreamToFile(cryptKey, dlreq.getResponseBodyAsStream(), permFile);
							
							if(waitingForPermutations.isEmpty()) {
								waitingForBuilds.remove(buildId);
							}
						}
					}
					if(!waitingForPermutations.isEmpty() && "true".equals(req.getResponseHeader("X-Complete").getValue())) {
						logger.error("Server returns build done, but we didn't get all the permutations we were expecting!");
						System.exit(1);
						return;
					}
				}
			}
			
			if(waitingForBuilds.isEmpty()) {
				// Got them all
				logger.info("All permutations done compiling, running link...");
				CompileUtils.launchToolAndWaitAndExitOnFailure(Link.class, linkerArgs.toArray(new String[linkerArgs.size()]));
			}
			
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}
}
