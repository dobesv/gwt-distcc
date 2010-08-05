package gwtdistcc.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.jdo.JDOObjectNotFoundException;
import javax.jdo.PersistenceManager;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.appengine.api.blobstore.BlobKey;
import com.google.appengine.api.blobstore.BlobstoreService;
import com.google.appengine.api.blobstore.BlobstoreServiceFactory;

public class AddBuildServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	
	private BlobstoreService blobstoreService = BlobstoreServiceFactory.getBlobstoreService();
	
	@Override
	protected void doHead(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		doGet(req, resp);
	}
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		String uploadUrl = blobstoreService.createUploadUrl("/add-build");
		resp.setHeader("Cache-Control", "no-cache, must-revalidate");
		resp.setDateHeader("Expires", System.currentTimeMillis());
		resp.setHeader("X-Upload-URL", uploadUrl);
		resp.setContentType("text/html");
		if(!req.getMethod().equals("HEAD")) {
			PrintWriter w = resp.getWriter();
			BufferedReader r = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("form-template.html")));
			String line;
			while((line = r.readLine()) != null) {
				w.println(line.replace("%URL%", uploadUrl));
			}
		}
	}
	
	static final Pattern VALID_BUILD_ID = Pattern.compile("[A-Za-z0-9_-]{8,80}");
	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		Map<String, BlobKey> blobs = blobstoreService.getUploadedBlobs(req);
        
		if(blobs.isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing AST data");
			return;
		}
		BlobKey blob = blobs.values().iterator().next();
		Set<String> queues = ServletUtil.getQueues(req);
		if(queues.isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing list of queues");
			return;
		}
		String permutationsStr = req.getParameter("perms");
		if(permutationsStr == null || permutationsStr.isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Must pass the permutation");
			return;
		}
		int permutations;
		try {
			permutations = Integer.parseInt(permutationsStr);
		} catch (NumberFormatException e) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Permutation is not a number");
			return;
		}
		String label = req.getParameter("label");
		if(label == null || label.isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing label");
			return;
		}
		String id = req.getParameter("id");
		if(id == null || id.isEmpty()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing build ID");
			return;
		}
		if(!VALID_BUILD_ID.matcher(id).matches()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Build ID must contain only letters, numbers, underscores, and hyphens.");
			return;
		}
		
		resp.setContentType("text/plain");
		PersistenceManager pm = DB.getPersistenceManager();
        try {
        	Build existing;
			try {
				existing = pm.getObjectById(Build.class, id);
			} catch (JDOObjectNotFoundException notFound) {
				existing=null;
			}
        	if(existing == null) {
        		pm.makePersistent(new Build(id, label, queues, permutations, blob));
        		resp.sendRedirect("/build-status?id="+id);
        	} else {
        		resp.sendError(HttpServletResponse.SC_CONFLICT, "Build with that ID already exists!");
        	}
        } finally {
        	pm.close();
        }
	}
}
