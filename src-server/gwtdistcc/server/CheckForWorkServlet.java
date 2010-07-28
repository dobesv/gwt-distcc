package gwtdistcc.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.Set;

import javax.jdo.PersistenceManager;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.appengine.api.blobstore.BlobstoreService;
import com.google.appengine.api.blobstore.BlobstoreServiceFactory;

public class CheckForWorkServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private BlobstoreService blobstoreService = BlobstoreServiceFactory.getBlobstoreService();
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {
		Set<String> queues = ServletUtil.getQueues(req);
		if (queues.isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Please provide the list of queues to check.");
			return;
		}
		String workerId = req.getParameter("workerId");
		String workerLabel = req.getParameter("workerLabel");
		if(workerId != null && workerLabel == null) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "If workerId is provided, workerLabel must also be provided.");
			return;
		}
		PersistenceManager pm = DB.getPersistenceManager();
		try {
			resp.setHeader("Cache-Control", "no-cache, must-revalidate");
			verifyClientCache(req, resp, pm);

			boolean foundBuild = false;
			for (String queueId : queues) {
				for (Build b : Build.list(pm, queueId)) {
					if(System.currentTimeMillis() - b.getCreated().getTime() > (12*3600000)) {
						// Time limit on a build ...
						pm.deletePersistent(b);
						continue;
					}
					if(b.getCompleted() != null) {
						// Build complete, don't return it
						continue;
					}
					for (Permutation p : b.getPermutations()) {
						if (p.isAvailable()) {
							if(workerId != null && !req.getMethod().equals("HEAD")) {
								p.setWorkerId(workerId);
								p.setWorkerLabel(workerLabel);
								p.setBuildAlive(new Date());
								resp.setHeader("X-Queue-ID", queueId);
								resp.setHeader("X-Build-ID", b.getId());
								resp.setHeader("ETag", b.getId());
								resp.setHeader("X-Build-Label", b.getLabel());
								resp.setDateHeader("X-Build-Created", b.getCreated().getTime());
								resp.setHeader("X-Permutation", String.valueOf(p.getPermutation()));
								String uploadToUrl = blobstoreService.createUploadUrl("/build-result");
								resp.setHeader("X-Upload-Result-To", uploadToUrl);

								if(getCachedBuilds(req).contains(b.getId())) {
									// Notify the client that they already have this in their cache
									resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
								} else {
									blobstoreService.serve(b.getData(), resp);
								}
							} else {
								resp.setContentType("text/plain");
								resp.getWriter().println(b.toString());
							}
							return;
						}
					}
				}
			}
			if (!foundBuild) {
				resp.sendError(HttpServletResponse.SC_NOT_FOUND, "No builds at this time");
				return;
			}
		} finally {
			pm.close();
		}
	}

	/**
	 * Read the cache parameter and return back a list of builds that
	 * no longer exist so the client can delete them from its cache.
	 * 
	 * The list is put in the HTTP header X-Delete-Cached-Builds
	 */
	private void verifyClientCache(HttpServletRequest req,
			HttpServletResponse resp, PersistenceManager pm) throws IOException {
		StringBuffer sb = new StringBuffer();
		for(String cachedBuildId : getCachedBuilds(req)) {
			// Check the status of the build
			Build b = pm.getObjectById(Build.class, cachedBuildId);
			if(b == null || b.getCompleted() != null) {
				sb.append(", ");
				sb.append(cachedBuildId);
			}
		}
		if(sb.length() > 0)
			resp.setHeader("X-Delete-Cached-Builds", sb.substring(2));
	}

	private Set<String> getCachedBuilds(HttpServletRequest req) {
		req.getHeader("If-None-Match");
		
		return ServletUtil.getStrings(req, "cache");
	}

}
