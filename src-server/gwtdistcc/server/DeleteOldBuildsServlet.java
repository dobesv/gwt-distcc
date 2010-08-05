package gwtdistcc.server;

import java.io.IOException;

import javax.jdo.Extent;
import javax.jdo.PersistenceManager;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.appengine.api.blobstore.BlobstoreService;
import com.google.appengine.api.blobstore.BlobstoreServiceFactory;

public class DeleteOldBuildsServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private BlobstoreService blobstoreService = BlobstoreServiceFactory.getBlobstoreService();
	
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		PersistenceManager pm = DB.getPersistenceManager();
		try {
			resp.setHeader("Cache-Control", "no-cache, must-revalidate");
			Extent<Build> builds = pm.getExtent(Build.class);
			for(Build b: builds) {
				b.deleteIfStale(pm, blobstoreService);
			}
			builds.closeAll();
		} finally {
			pm.close();
		}
		
	}
}
