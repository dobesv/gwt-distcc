package gwtdistcc.server;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import javax.jdo.PersistenceManager;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.appengine.api.blobstore.BlobKey;
import com.google.appengine.api.blobstore.BlobstoreService;
import com.google.appengine.api.blobstore.BlobstoreServiceFactory;

public class BuildResultServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private BlobstoreService blobstoreService = BlobstoreServiceFactory.getBlobstoreService();

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		String buildId = req.getParameter("id");
		if(buildId == null || buildId.isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Must pass the build ID");
			return;
		}
		String workerId = req.getParameter("workerId");
		if(workerId == null || workerId.isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Must pass the worker ID");
			return;
		}
		String permutationStr = req.getParameter("perm");
		if(permutationStr == null || permutationStr.isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Must pass the permutation");
			return;
		}
		int permutation;
		try {
			permutation = Integer.parseInt(permutationStr);
		} catch (NumberFormatException e) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Permutation is not a number");
			return;
		}
		
		PersistenceManager pm = DB.getPersistenceManager();
		try {
			Build b = pm.getObjectById(Build.class, buildId);
			if(b == null) {
				resp.sendError(HttpServletResponse.SC_NOT_FOUND, "No build with that ID found.");
				return;
			}
			for(Permutation p : b.getPermutations()) {
				if(p.getPermutation() == permutation) {
					if(!p.getWorkerId().equals(workerId)) {
						resp.sendError(HttpServletResponse.SC_NOT_FOUND, "You are not the current worker for that permutation.");
						return;
					}
					p.setBuildAlive(new Date());
					String error = req.getParameter("error");
					if(error != null) {
						if("".equals(error) || "interrupted".equals(error) || "out of memory".equals(error) || "class not found".equals(error)) {
							// If the error is probably a configuration/capacity issue, try and pass it onto another worker if there are any others
							p.setWorkerId(null);
							p.setWorkerLabel(null);
						} else {
							p.setBuildError(error);
							p.setBuildErrorTime(new Date());
						}
						return;
					}
					
					Map<String, BlobKey> blobs;
					try {
						blobs = blobstoreService.getUploadedBlobs(req);
					} catch (IllegalStateException e) {
						// Probably didn't get any blobs ..
						blobs = Collections.emptyMap();
					}
					if(blobs.isEmpty()) {
						// No uploaded data, this is just a ping
					} else {
						if(p.getFinished() != null || p.getResultData() != null) {
							resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "This permutation is already marked as complete.");
							return;
						}
						BlobKey blob = blobs.values().iterator().next();
						p.setFinished(new Date());
						p.setResultData(blob);
					}
					return;
				}
			}
			resp.sendError(HttpServletResponse.SC_NOT_FOUND, "No matching permutation found on that build.");
		} finally {
			pm.close();
		}
	}
	
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		String buildId = req.getParameter("id");
		if(buildId == null || buildId.isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Must pass the build ID");
			return;
		}
		String permutationStr = req.getParameter("perm");
		if(permutationStr == null || permutationStr.isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Must pass the permutation");
			return;
		}
		int permutation;
		try {
			permutation = Integer.parseInt(permutationStr);
		} catch (NumberFormatException e) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Permutation is not a number");
			return;
		}
		
		PersistenceManager pm = DB.getPersistenceManager();
		try {
			Build b = pm.getObjectById(Build.class, buildId);
			if(b == null) {
				resp.sendError(HttpServletResponse.SC_NOT_FOUND, "No build with that ID found.");
				return;
			}
			for(Permutation p : b.getPermutations()) {
				if(p.getPermutation() == permutation) {
					if(p.getFinished() == null || p.getResultData() == null) {
						resp.sendError(HttpServletResponse.SC_NOT_FOUND, "No build result was uploaded for this permutation");
						return;
					}
					p.setDownloaded(new Date());
					blobstoreService.serve(p.getResultData(), resp);
					return;
				}
			}
			resp.sendError(HttpServletResponse.SC_NOT_FOUND, "No matching permutation found on that build.");
		} finally {
			pm.close();
		}
	}
}
