package gwtdistcc.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

public class ServletUtil {

	public static Set<String> getQueues(HttpServletRequest req) {
		String parameterName = "q";
		return ServletUtil.getStrings(req, parameterName);
	}

	public static Set<String> getStrings(HttpServletRequest req,
			String parameterName) {
		String queuesStr = req.getParameter(parameterName);
		if(queuesStr == null || queuesStr.isEmpty())
			return Collections.emptySet();
		return new HashSet<String>(Arrays.asList(queuesStr.split(",")));
	}

}
