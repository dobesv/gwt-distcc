package gwtdistcc.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;

import javax.jdo.JDOObjectNotFoundException;
import javax.jdo.PersistenceManager;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class BuildStatusServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		String id = req.getParameter("id");
		if(id == null) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing build ID");
			return;
		}
		PersistenceManager pm = DB.getPersistenceManager();
		try {
			Build build = pm.getObjectById(Build.class, id);
			build.setLastStatusCheck(new Date());
			
			StringBuffer permsStarted=new StringBuffer();
			StringBuffer permsComplete=new StringBuffer();
			StringBuffer permsFailed=new StringBuffer();
			int startedCount=0;
			int completeCount=0;
			int failedCount=0;
			for(Permutation p : build.getPermutations()) {
				if(p.getBuildError() != null) {
					if(permsFailed.length()>0) permsFailed.append(",");
					permsFailed.append(p.getPermutation());
					failedCount++;
					resp.addHeader("X-Permutation-"+p.getPermutation()+"-Error", p.getBuildError());
					resp.addDateHeader("X-Permutation-"+p.getPermutation()+"-Error-Time", p.getBuildErrorTime().getTime());
				}
				if(p.getFinished() != null) {
					if(permsComplete.length()>0) permsComplete.append(",");
					permsComplete.append(p.getPermutation());
					completeCount++;
					resp.addDateHeader("X-Permutation-"+p.getPermutation()+"-Finished", p.getFinished().getTime());
				} else if(p.getStarted() != null) {
					if(permsStarted.length()>0) permsStarted.append(",");
					permsStarted.append(p.getPermutation());
					startedCount++;
					resp.addDateHeader("X-Permutation-"+p.getPermutation()+"-Started", p.getStarted().getTime());
				}
				if(p.getWorkerId() != null) {
					resp.addHeader("X-Permutation-"+p.getPermutation()+"-Worker-ID", p.getWorkerId());
					resp.addHeader("X-Permutation-"+p.getPermutation()+"-Worker", p.getWorkerLabel());
					resp.addDateHeader("X-Permutation-"+p.getPermutation()+"-Active", p.getBuildAlive().getTime());
				}
			}
			
			resp.setHeader("X-Remaining-Permutations", String.valueOf(build.getPermutations().size()-completeCount));
			resp.setHeader("X-Permutations-Count", String.valueOf(build.getPermutations().size()));
			resp.setHeader("X-Permutations-Started-Count", String.valueOf(startedCount));
			resp.setHeader("X-Permutations-Finished-Count", String.valueOf(completeCount));
			resp.setHeader("X-Permutations-Failed-Count", String.valueOf(failedCount));
			resp.setHeader("X-Permutations-Started", permsStarted.toString());
			resp.setHeader("X-Permutations-Finished", permsComplete.toString());
			resp.setHeader("X-Permutations-Failed", permsFailed.toString());
			resp.setHeader("X-Complete", String.valueOf((completeCount+failedCount)==build.getPermutations().size()));
			
			if(!req.getMethod().equals("HEAD")) {
				resp.setContentType("text/plain");
				PrintWriter w = resp.getWriter();
				w.print(build.toString());
				w.print(" ");
				w.print("QUEUES");
				for(String queueId : build.getQueueIds()) {
					w.print(" ");
					w.print(queueId);
				}
				w.println();
				for(Permutation p : build.getPermutations()) {
					w.println(p.toString());
				}
				w.close();
			}
		} catch(JDOObjectNotFoundException notFound) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND, "No build found with ID "+id);
		} finally {
			pm.close();
		}

	}
	
	@Override
	protected void doHead(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		doGet(req, resp);
	}
}
