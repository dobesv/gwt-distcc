package gwtdistcc.client;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.methods.multipart.StringPart;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApiClient {
    static final Logger logger = LoggerFactory.getLogger(ApiClient.class);
	final HttpClient client = new HttpClient(new MultiThreadedHttpConnectionManager());
	
	public static String buildQueryString(Map<String,String> params) {
	    if(params.isEmpty())
	        return "";
		StringBuffer sb = new StringBuffer();
		sb.append('?');
		appendQueryString(sb, params);
		return sb.toString();
	}
	
	public static void appendQueryString(StringBuffer sb, Map<String, String> params) {
		try {
			boolean first = true;
			for(Map.Entry<String, String> pair : params.entrySet()) {
				if(first) first = false;
				else sb.append('&');
				String key = pair.getKey();
                String value = pair.getValue();
                if(key == null) throw new NullPointerException("Null key in query params; value is "+value);
                if(value == null) throw new NullPointerException("Null value in query params; key is "+key);
                sb.append(URLEncoder.encode(key, "utf8"));
				sb.append('=');
                sb.append(URLEncoder.encode(value, "utf8"));
			}
		} catch (UnsupportedEncodingException e) {
			throw new Error(e); // utf8 is supposed to be supported
		}
	}
	public String postForm(String url, Map<String, String> params) {
		try {
			PostMethod method = new PostMethod(url);
			for(Map.Entry<String, String> param : params.entrySet()) {
			    method.addParameter(param.getKey(), param.getValue());
			}
			try {
    			client.executeMethod(method);
    			logger.debug("POST {} {}", url, new String(((ByteArrayRequestEntity)method.getRequestEntity()).getContent(), method.getRequestCharSet()));
				return method.getResponseBodyAsString();
			} finally {
			    method.releaseConnection();
			}
		} catch (IOException e) {
			throw new Error(e);
		}
	}
	public String get(String url) {
	    try {
	        GetMethod method = new GetMethod(url);
	        try {
	            client.executeMethod(method);
	            logger.debug("GET {}", url);
	            return method.getResponseBodyAsString();
	        } finally {
	            method.releaseConnection();
	        }
	    } catch(IOException e) {
	        throw new Error(e);
	    }
	}

	public String getUploadURL(String server) throws HttpException, IOException, ApiException {
		StringBuffer url = new StringBuffer(server);
		url.append("/add-build");
		HeadMethod head = new HeadMethod(url.toString());
		client.executeMethod(head);
		if(head.getStatusCode() != HttpStatus.SC_OK) {
			throw new ApiException(head.getStatusText());
		}		
		String uploadURL = head.getResponseHeader("X-Upload-URL").getValue();
		if(uploadURL.startsWith("https://") || uploadURL.startsWith("http://"))
			return uploadURL;
		else
			return server + uploadURL;
	}
	
	public void addBuild(String server, String buildLabel, String buildId, String[] queues, int perms, File payloadFile) throws HttpException, IOException, ApiException {
		if(buildId == null) throw new IllegalArgumentException("Build UI must not be null.");
		if(queues == null) throw new IllegalArgumentException("Queues must not be null.");
		if(perms <= 0) throw new IllegalArgumentException("permsCount must be > 0.");
		String url = getUploadURL(server);
		MultipartRequestEntity mp = new MultipartRequestEntity(
				new Part[] {
						new StringPart("id", buildId),
						new StringPart("q", StringUtils.join(queues, ",")),
						new StringPart("perms", String.valueOf(perms)),
						new StringPart("label", buildLabel),
						new FilePart("data", payloadFile)
				}, new HttpMethodParams());
		PostMethod post = new PostMethod(url);
		if(logger.isDebugEnabled())
			logger.debug("Uploading build to "+post.getURI()+" from file "+payloadFile+" with build ID "+buildId+" and queues "+StringUtils.join(queues, ",")+" and "+perms+" permutations");
		else
			logger.info("Uploading build to "+server+" with build ID "+buildId+" and queues "+StringUtils.join(queues, ",")+" and "+perms+" permutations");
		post.setRequestEntity(mp);
		client.executeMethod(post);
		post.releaseConnection();
		if(post.getStatusCode() != HttpStatus.SC_OK && post.getStatusCode() != HttpStatus.SC_MOVED_TEMPORARILY) {
			throw new ApiException(post.getStatusCode(), post.getStatusText());
		}
		Header h = post.getResponseHeader("Location");
		if(h != null) {
			logger.info("Build uploaded; status URL is at "+h.getValue());
		} else {
			logger.warn("Upload complete.  No build status URL was returned by the server, however, which was not expected.");
		}
	}

	public void addBuildResult(String server, String buildId, int perm, String uploadURL, String workerId, File payloadFile) throws HttpException, IOException, ApiException {
		if(buildId == null) throw new IllegalArgumentException("Build UI must not be null.");
		MultipartRequestEntity mp = new MultipartRequestEntity(
				new Part[] {
						new StringPart("id", buildId),
						new StringPart("perm", String.valueOf(perm)),
						new StringPart("workerId", workerId),
						new FilePart("data", payloadFile)
				}, new HttpMethodParams());
		if(!(uploadURL.startsWith("https:") || uploadURL.startsWith("http:")))
			uploadURL = server + uploadURL;
		PostMethod post = new PostMethod(uploadURL);
		logger.info("Uploading build result to "+post.getURI()+" from file "+payloadFile+" with build ID "+buildId+" and "+perm+" permutation");
		post.setRequestEntity(mp);
		client.executeMethod(post);
		post.releaseConnection();
		if(post.getStatusCode() != HttpStatus.SC_OK && post.getStatusCode() != HttpStatus.SC_MOVED_TEMPORARILY) {
			throw new ApiException(post.getStatusCode(), post.getStatusText());
		}
	}
	

	public void buildAlive(String server, String buildId, int perm, String workerId) throws HttpException, IOException {
		TreeMap<String,String> params = new TreeMap<String,String>();
		params.put("id", buildId);
		params.put("workerId", workerId);
		params.put("perm", String.valueOf(perm));
		StringBuffer url=new StringBuffer(server).append("/build-result?");
		appendQueryString(url, params);
		PostMethod post = new PostMethod(url.toString());
		executeMethod(post);
		post.releaseConnection();
		if(post.getStatusCode() != HttpStatus.SC_OK) {
			logger.error("Got bad ping response: "+post.getStatusLine());
		}
		logger.debug("Notified build server we are still building at "+post.getURI());
	}
	
	public void executeMethod(HttpMethod method) throws HttpException, IOException {
		client.executeMethod(method);
	}

	public HeadMethod getBuildStatus(String server, String buildId) throws HttpException, IOException {
		String buildStatusURL = server+"/build-status?id="+buildId;
		HeadMethod req = new HeadMethod(buildStatusURL);
		executeMethod(req);
		return req;
	}

	static String normalizeServerURL(String server) {
		if(!(server.startsWith("http://") || server.startsWith("https://")))
			server = "https://"+server;
		if(server.endsWith("/"))
			server = server.substring(0, server.length()-1);
		return server;
	}

}
