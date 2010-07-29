package gwtdistcc.server;

import java.util.Date;

import javax.jdo.annotations.IdGeneratorStrategy;
import javax.jdo.annotations.PersistenceCapable;
import javax.jdo.annotations.Persistent;
import javax.jdo.annotations.PrimaryKey;

import com.google.appengine.api.blobstore.BlobKey;
import com.google.appengine.api.datastore.Key;

@PersistenceCapable
public class Permutation implements Comparable<Permutation> {
    @PrimaryKey
    @Persistent(valueStrategy = IdGeneratorStrategy.IDENTITY)
    private Key key;
    
	@Persistent
	Build build;
	
	@Persistent
	int permutation;
	
	@Persistent
	Date started;
	
	@Persistent
	Date buildAlive;
	
	@Persistent
	Date finished;
	
	@Persistent
	Date downloaded;
	
	@Persistent
	String workerId;

	@Persistent
	String workerLabel;
	
	@Persistent
	BlobKey resultData;
	
	public Permutation() {
	}
	
	public Permutation(Build build, int n) {
		this.build = build;
		this.permutation = n;
	}

	/**
	 * Build this this is a permutation of.
	 */
	public Build getBuild() {
		return build;
	}

	public void setBuild(Build build) {
		this.build = build;
	}

	/**
	 * Permutation identifier
	 * @return
	 */
	public int getPermutation() {
		return permutation;
	}

	public void setPermutation(int n) {
		this.permutation = n;
	}

	/**
	 * Timestamp when the build was started, or null for not started
	 */
	public Date getStarted() {
		return started;
	}

	public void setStarted(Date started) {
		this.started = started;
	}

	/**
	 * Date the permutation finished compiling, null if not yet compiled.
	 * 
	 * Note this is set after the permutation results are done uploading.
	 */
	public Date getFinished() {
		return finished;
	}

	public void setFinished(Date finished) {
		this.finished = finished;
	}

	/**
	 * Unique string provided by the current worker if a compile is in progress,
	 * null otherwise.
	 */
	public String getWorkerId() {
		return workerId;
	}

	public void setWorkerId(String workerId) {
		this.workerId = workerId;
	}

	/**
	 * Label provided by the current worker if a compile is in progress,
	 * null otherwise.
	 */
	public String getWorkerLabel() {
		return workerLabel;
	}

	public void setWorkerLabel(String workerLabel) {
		this.workerLabel = workerLabel;
	}

	/**
	 * Latest date we received a download request for this permutation
	 * result, or null if never downloaded.
	 */
	public Date getDownloaded() {
		return downloaded;
	}

	public void setDownloaded(Date downloaded) {
		this.downloaded = downloaded;
	}
	
	@Override
	public String toString() {
		return getBuild().toString()+" permutation "+getPermutation()+
		(workerId != null ? " worker "+workerId+" ("+workerLabel+")":"")+
		(finished != null ? " finished "+finished : "")+
		(downloaded != null ? " downloaded "+downloaded : "");
	}

	public Date getBuildAlive() {
		return buildAlive;
	}

	public void setBuildAlive(Date buildAlive) {
		this.buildAlive = buildAlive;
	}

	public boolean isAvailable() {
		return finished == null && (workerId == null || buildAlive == null || (System.currentTimeMillis() > buildAlive.getTime() + 300000));
	}

	@Override
	public int compareTo(Permutation o) {
		return permutation - o.getPermutation();
	}

	public void setKey(Key key) {
		this.key = key;
	}

	public Key getKey() {
		return key;
	}

	public void setResultData(BlobKey resultData) {
		this.resultData = resultData;
	}

	public BlobKey getResultData() {
		return resultData;
	}
	
	
}