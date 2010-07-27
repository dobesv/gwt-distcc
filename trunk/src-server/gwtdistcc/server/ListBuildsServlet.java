package gwtdistcc.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;

import javax.jdo.PersistenceManager;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ListBuildsServlet extends HttpServlet {

	public void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {
		Set<String> queues = ServletUtil.getQueues(req);
		if (queues.isEmpty()) {
			return;
		}
		PersistenceManager pm = DB.getPersistenceManager();
		try {
			resp.setContentType("text/plain");
			PrintWriter w = resp.getWriter();
			boolean foundBuild = false;
			boolean showAll = req.getParameter("list") != null;
			for (String queueId : queues) {
				for (Build b : Build.list(pm, queueId)) {
					for (Permutation p : b.getPermutations()) {
						if (p.isAvailable()) {
							w.println("BUILD " + b.getId() + " PERM "
									+ p.getPermutation());
							foundBuild = true;
							if (!showAll)
								break;
						}
					}
					if (foundBuild && !showAll)
						break;
				}
			}
			if (!foundBuild) {
				w.println("NO BUILDS");
				resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
			}
		} finally {
			pm.close();
		}
	}

}
