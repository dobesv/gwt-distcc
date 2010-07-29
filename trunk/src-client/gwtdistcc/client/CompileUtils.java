package gwtdistcc.client;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompileUtils {
	/**
	 * A system property that can be used to override the command used to invoke
	 * a JVM instance.
	 */
	public static final String JAVA_COMMAND_PROPERTY = "gwt.jjs.javaCommand";

	/**
	 * A system property that can be used to override the JVM args passed to the
	 * subprocess.
	 */
	public static final String JVM_ARGS_PROPERTY = "gwt.jjs.javaArgs";

	static final Logger logger = LoggerFactory.getLogger(CompileUtils.class);

	/**
	 * Launches an external tool passing the given arguments, using the same
	 * classpath and JVM args as the current JVM is using.
	 * 
	 * @return A handle to the new process, which can be used to wait for completion and check the result
	 * @throws IOException If the launch fails 
	 */
	public static Process launchTool(Class<?> clazz, String ... argsArray)
			throws IOException {

		String javaCommand = System.getProperty(JAVA_COMMAND_PROPERTY, System
				.getProperty("java.home")
				+ File.separator + "bin" + File.separator + "java");
		logger.trace("javaCommand = " + javaCommand);

		// Construct the arguments
		List<String> args = new ArrayList<String>();
		args.add(javaCommand);

		// This will include -Xmx, -D, etc...
		String userJvmArgs = System.getProperty(JVM_ARGS_PROPERTY);
		if (userJvmArgs == null) {
			args.addAll(ManagementFactory.getRuntimeMXBean()
					.getInputArguments());
		} else {
			args.addAll(Arrays.asList(userJvmArgs.split(" ")));
		}

	    // Filter undesirable arguments
	    for (Iterator<String> iter = args.iterator(); iter.hasNext();) {
	      String arg = iter.next();
	      if (arg.startsWith("-agentlib")) {
	        iter.remove();
	      }
	    }
		
		// Cook up the classpath, main class, and extra args
		args.addAll(Arrays.asList("-classpath", ManagementFactory
				.getRuntimeMXBean().getClassPath(), clazz.getName()));
				
		args.addAll(Arrays.asList(argsArray));

		
		ProcessBuilder builder = new ProcessBuilder();
		builder.command(args);
		
		logger.info(StringUtils.join(args, " "));
		
		final Process proc = builder.start();
		final BufferedReader bin = new BufferedReader(new InputStreamReader(proc.getInputStream()));
		final BufferedReader berr = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
		final Logger procLogger = LoggerFactory.getLogger(clazz.getName());

		// Threads to copy stdout, stderr to the logger
		new Thread(new Runnable() {
			public void run() {
				while (true) {
					try {
						String line = bin.readLine();
						if (line == null) {
							break;
						}
						System.out.println(line);
					} catch (EOFException e) {
						// Ignore
					} catch (IOException e) {
						procLogger.error("Unable to read from subprocess", e);
					}
				}
			}
		}).start();

		new Thread(new Runnable() {
			public void run() {
				while (true) {
					try {
						String line = berr.readLine();
						if (line == null) {
							break;
						}
						System.err.println(line);
					} catch (EOFException e) {
						// Ignore
					} catch (IOException e) {
						procLogger.error("Unable to read from subprocess", e);
					}
				}
			}
		}).start();

		// The child process should not outlive this JVM
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			public void run() {
				try {
					proc.exitValue();
				} catch (IllegalThreadStateException e) {
					proc.destroy();
				}
			}
		}));

		return proc;
	}

	public static int launchToolAndWait(Class<?> clazz, String ... argsArray)
			throws IOException, InterruptedException {
		return launchTool(clazz, argsArray).waitFor();
	}
	
	public static void launchToolAndWaitAndExitOnFailure(Class<?> clazz, String ... argsArray)
			throws IOException, InterruptedException {
		int rc = launchToolAndWait(clazz, argsArray);
		if(rc != 0)
			System.exit(rc);
	}

	public static Cipher getCipher() {
		if(cipher == null) {
			try {
				cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			} catch (Exception e) {
				throw new Error(e);
			}
		}
		return cipher;
	}
	static InputStream maybeDecryptStream(String cryptKey,
			InputStream is) throws IOException {
		InputStream in;
		if(cryptKey != null && !cryptKey.isEmpty()) {
			byte[] iv = new byte[16];
			is.read(iv);
			Cipher cipher = getCipher();
			try {
				SecretKeySpec secretKey = new SecretKeySpec(DigestUtils.md5(cryptKey), "AES");
				cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
			} catch (Exception e) {
				throw new Error(e);
			}
			in = new CipherInputStream(is, cipher);
			
		} else {
			System.err.println("No cryptkey specified (by passing -key to the command line), data will NOT be encrypted.");
			in = is;
		}
		return in;
	}

	static OutputStream maybeEncryptStream(String cryptKey,
			OutputStream fo) throws IOException {
		OutputStream out;
		if(cryptKey != null) {
			Cipher cipher = getCipher();
			try {
				cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(DigestUtils.md5(cryptKey), "AES"));
			} catch (InvalidKeyException e) {
				throw new Error(e);
			}
			byte[] iv = cipher.getIV();
			if(iv.length != 16) throw new Error("Expected IV size of 16!");
			fo.write(iv);
			out = new CipherOutputStream(fo, cipher);
		} else {
			System.err.println("No gwtdistcc.cryptkey specified, data will NOT be encrypted.");
			out = fo;
		}
		return out;
	}

	/**
	 * SHA1 the given file
	 * 
	 * @param prelude If provided, this is digested before the file contents
	 * @param f File to digest
	 * @return SHA1 hash of the prelude (if present) and the file's contents
	 */
	public static String digestFile(byte[] prelude, File f) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA1");
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
		if(prelude != null)
			digest.update(prelude);
		byte[] buffer = new byte[8192];
		FileInputStream input = new FileInputStream(f);
	    int n = 0;
	    while (-1 != (n = input.read(buffer))) {
	    	digest.update(buffer, 0, n);
	    }
	    return new String(Hex.encodeHex(digest.digest()));
	}

	static void encryptFile(String cryptKey, File plainTextFile,
			File cipherTextFile) throws FileNotFoundException, IOException {
		InputStream in;
		File tempCipherTextFile = File.createTempFile("cipher", ".tmp", cipherTextFile.getParentFile());
		OutputStream out = new GZIPOutputStream(maybeEncryptStream(cryptKey, new FileOutputStream(tempCipherTextFile)));
		in = new FileInputStream(plainTextFile);
		try {
			IOUtils.copy(in, out);
		} finally {
			in.close();
			out.close();
		}
		if(!tempCipherTextFile.renameTo(cipherTextFile)) {
			throw new IOException("Failed to rename temp encryption file "+cipherTextFile+" to "+cipherTextFile);
		}
	}

	static Cipher cipher;

	public static void writeStreamToFile(InputStream stream, File file)
			throws FileNotFoundException, IOException {
		File tempFile = File.createTempFile("stream", ".tmp", file.getParentFile());
		FileOutputStream permOut = new FileOutputStream(tempFile);
		try {
			IOUtils.copy(stream, permOut);
		} finally {
			permOut.close();
		}
		if(!tempFile.renameTo(file)) {
			throw new IOException("Failed to rename temp file "+tempFile+" to "+file);
		}
	}

	public static void decryptStreamToFile(String cryptKey, InputStream stream,
			File file) throws IOException, Error, FileNotFoundException {
		InputStream is = new GZIPInputStream(maybeDecryptStream(cryptKey, stream));
		try {
			writeStreamToFile(is, file);
		} finally {
			is.close();
		}
	}

	static void encryptPayload(String moduleName, String cryptKey,
			File astFile, File payloadFile) throws FileNotFoundException,
			IOException, NoSuchAlgorithmException, NoSuchPaddingException,
			InvalidKeyException {
		File tempFile = File.createTempFile("stream", ".tmp", payloadFile.getParentFile());
		FileOutputStream fo = new FileOutputStream(tempFile);
		try {
			fo.write(DistCompile.V1_BYTE);
			byte[] moduleNameBytes = moduleName.getBytes();
			if(moduleNameBytes.length > 127) {
				System.err.println("Oops, your module name is too long (> 127 bytes).  This system currently doesn't support that.");
				System.exit(1);
			}
			FileInputStream in = new FileInputStream(astFile);
			try {
				OutputStream out = maybeEncryptStream(cryptKey, fo);
				try {
					out.write((byte)moduleNameBytes.length);
					out.write(moduleNameBytes);
					IOUtils.copy(in, out);
				} finally {
					out.close();
				}
			} finally {
				in.close();
			}
		} finally {
			IOUtils.closeQuietly(fo); // May already be closed by the CipherOutputStream
		}
		if(payloadFile.exists()) {
			File tempDeleteFile = new File(payloadFile.getParentFile(), payloadFile.getName()+".deleteme");
			payloadFile.renameTo(tempDeleteFile);
			tempDeleteFile.delete();
		}
		if(!tempFile.renameTo(payloadFile)) {
			throw new IOException("Failed to rename temp file "+tempFile+" to payload file "+payloadFile);
		}
	}

	public static String readSmallString(InputStream in) throws IOException {
		int moduleNameLen = in.read();
		byte[] moduleNameBytes = new byte[moduleNameLen];
		in.read(moduleNameBytes);
		String moduleName = new String(moduleNameBytes);
		return moduleName;
	}
}
