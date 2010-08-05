package gwtdistcc.server;

import java.util.Collection;
import java.util.Date;
import java.util.TreeSet;
import java.util.logging.Logger;

import javax.jdo.PersistenceManager;
import javax.jdo.Query;
import javax.jdo.annotations.PersistenceCapable;
import javax.jdo.annotations.Persistent;
import javax.jdo.annotations.PrimaryKey;

import com.google.appengine.api.blobstore.BlobKey;
import com.google.appengine.api.blobstore.BlobstoreService;

@PersistenceCapable
public class Build {
	private static final int BUILD_EXPIRY_TIME = 600000;
	private static final Logger log = Logger.getLogger(Build.class.getName());

	@PrimaryKey
	@Persistent
	String id;
	
	@Persistent
	String label;

	@Persistent
	TreeSet<String> queueIds;
	
	@Persistent(mappedBy="build", embedded="true")
	TreeSet<Permutation> permutations;
	
	@Persistent
	Date created;
	
	@Persistent
	BlobKey data;
	
	@Persistent
	Date uploaded;
	
	@Persistent
	Date completed;
	
	@Persistent
	Date downloaded;
	
	@Persistent
	Date lastStatusCheck;

	public Build(String id, String label, Collection<String> queueIds, int numPermutations, BlobKey blob) {
		super();
		this.id = id;
		this.label = label;
		this.created = new Date();
		this.queueIds = new TreeSet<String>(queueIds);
		this.permutations = new TreeSet<Permutation>();
		for(int n=0; n < numPermutations; n++) {
			this.permutations.add(new Permutation(this, n));
		}
		this.data = blob;
	}

	public Build() {
	}
	
	/**
	 * Unique ID for the build, provided by the client
	 */
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	/**
	 * Label for the build, provided by the client
	 */
	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	/**
	 * Creation date of the build
	 */
	public Date getCreated() {
		return created;
	}

	public void setCreated(Date created) {
		this.created = created;
	}

	/**
	 * When we finished all permutations, or null if not done building yet
	 */
	public Date getCompleted() {
		return completed;
	}

	public void setCompleted(Date completed) {
		this.completed = completed;
	}

	/**
	 * Max date when we received a download request for the results
	 * of a permutation.
	 */
	public Date getDownloaded() {
		return downloaded;
	}

	public void setDownloaded(Date downloaded) {
		this.downloaded = downloaded;
	}
	
	@Override
	public String toString() {
		return "BUILD "+id+" ("+label+")"+
		(created != null ? " created "+created : "")+
		(uploaded != null ? " uploaded "+uploaded : "")+
		(completed != null ? " completed "+completed : "")+
		(downloaded != null ? " downloaded "+downloaded : "");
	}

	public TreeSet<String> getQueueIds() {
		return queueIds;
	}

	public void setQueueIds(TreeSet<String> queueIds) {
		this.queueIds = queueIds;
	}
	
	@SuppressWarnings("unchecked")
	public static Collection<Build> list(PersistenceManager pm, String queueId) {
		Query q = pm.newQuery(Build.class);
		q.setFilter("this.queueId == queueId");
		q.setOrdering("created ASC");
		return (Collection<Build>) q.execute(queueId);
	}

	public TreeSet<Permutation> getPermutations() {
		return permutations;
	}

	public void setPermutations(TreeSet<Permutation> permutations) {
		this.permutations = permutations;
	}

	public void setData(BlobKey data) {
		this.data = data;
	}

	public BlobKey getData() {
		return data;
	}

	/**
	 * Return the last time the build's status was checked.  If a build remains
	 * idle for a while we'll delete it from the system, this means that all the
	 * time stamps on the build are old.
	 */
	public Date getLastStatusCheck() {
		return lastStatusCheck;
	}

	public void setLastStatusCheck(Date lastStatusCheck) {
		this.lastStatusCheck = lastStatusCheck;
	}
	
	/**
	 * Return the maximum date for all the timestamps on this build
	 * @return
	 */
	public Date getFreshness() {
		Date result = created;
		if(completed != null) result = completed;
		if(downloaded != null) result = downloaded;
		if(lastStatusCheck != null && lastStatusCheck.after(result)) result = lastStatusCheck;
		for(Permutation perm : getPermutations()) {
			Date permFreshness = perm.getFreshness();
			if(permFreshness.after(result))
				result = permFreshness;
		}
		return result;
	}

	public boolean deleteIfStale(PersistenceManager pm, BlobstoreService blobstoreService) {
		Date freshness = getFreshness();
		boolean buildExpired = (System.currentTimeMillis() - freshness.getTime()) > BUILD_EXPIRY_TIME;
		if(buildExpired) {
			log.info("Deleting stale build; not touched since "+freshness);
			// Time limit on a build, it must be touched or viewed at least once a minute or we'll can it
			delete(pm, blobstoreService);
		}
		return buildExpired;
	}

	public void delete(PersistenceManager pm, BlobstoreService blobstoreService) {
		if(getData() != null)
			blobstoreService.delete(getData());
		for(Permutation p : getPermutations()) {
			if(p.getResultData() != null) {
				blobstoreService.delete(p.getResultData());
			}
		}
		pm.deletePersistent(this);
	}
}
