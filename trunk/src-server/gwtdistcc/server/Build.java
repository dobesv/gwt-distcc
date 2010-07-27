package gwtdistcc.server;

import java.util.Collection;
import java.util.Date;
import java.util.TreeSet;

import javax.jdo.PersistenceManager;
import javax.jdo.Query;
import javax.jdo.annotations.PersistenceCapable;
import javax.jdo.annotations.Persistent;
import javax.jdo.annotations.PrimaryKey;

import com.google.appengine.api.blobstore.BlobKey;

@PersistenceCapable
public class Build {
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
}
