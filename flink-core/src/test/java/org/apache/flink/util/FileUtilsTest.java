/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.util;

import org.apache.flink.core.fs.FSDataOutputStream;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.core.testutils.CheckedThread;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Tests for the {@link FileUtils}.
 */
public class FileUtilsTest {

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	// ------------------------------------------------------------------------
	//  Tests
	// ------------------------------------------------------------------------

	@Test
	public void testDeletePathIfEmpty() throws IOException {
		final FileSystem localFs = FileSystem.getLocalFileSystem();

		final File dir = tmp.newFolder();
		assertTrue(dir.exists());

		final Path dirPath = new Path(dir.toURI());

		// deleting an empty directory should work
		assertTrue(FileUtils.deletePathIfEmpty(localFs, dirPath));

		// deleting a non existing directory should work
		assertTrue(FileUtils.deletePathIfEmpty(localFs, dirPath));

		// create a non-empty dir
		final File nonEmptyDir = tmp.newFolder();
		final Path nonEmptyDirPath = new Path(nonEmptyDir.toURI());
		new FileOutputStream(new File(nonEmptyDir, "filename")).close();
		assertFalse(FileUtils.deletePathIfEmpty(localFs, nonEmptyDirPath));
	}

	@Test
	public void testDeleteQuietly() throws Exception {
		// should ignore the call
		FileUtils.deleteDirectoryQuietly(null);

		File doesNotExist = new File(tmp.getRoot(), "abc");
		FileUtils.deleteDirectoryQuietly(doesNotExist);

		File cannotDeleteParent = tmp.newFolder();
		File cannotDeleteChild = new File(cannotDeleteParent, "child");

		try {
			assumeTrue(cannotDeleteChild.createNewFile());
			assumeTrue(cannotDeleteParent.setWritable(false));
			assumeTrue(cannotDeleteChild.setWritable(false));

			FileUtils.deleteDirectoryQuietly(cannotDeleteParent);
		}
		finally {
			//noinspection ResultOfMethodCallIgnored
			cannotDeleteParent.setWritable(true);
			//noinspection ResultOfMethodCallIgnored
			cannotDeleteChild.setWritable(true);
		}
	}

	@Test
	public void testDeleteDirectory() throws Exception {

		// deleting a non-existent file should not cause an error

		File doesNotExist = new File(tmp.newFolder(), "abc");
		FileUtils.deleteDirectory(doesNotExist);

		// deleting a write protected file should throw an error

		File cannotDeleteParent = tmp.newFolder();
		File cannotDeleteChild = new File(cannotDeleteParent, "child");

		try {
			assumeTrue(cannotDeleteChild.createNewFile());
			assumeTrue(cannotDeleteParent.setWritable(false));
			assumeTrue(cannotDeleteChild.setWritable(false));

			FileUtils.deleteDirectory(cannotDeleteParent);
			fail("this should fail with an exception");
		}
		catch (AccessDeniedException ignored) {
			// this is expected
		}
		finally {
			//noinspection ResultOfMethodCallIgnored
			cannotDeleteParent.setWritable(true);
			//noinspection ResultOfMethodCallIgnored
			cannotDeleteChild.setWritable(true);
		}
	}

	@Test
	public void testDeleteDirectoryWhichIsAFile() throws Exception {

		// deleting a directory that is actually a file should fails

		File file = tmp.newFile();
		try {
			FileUtils.deleteDirectory(file);
			fail("this should fail with an exception");
		}
		catch (IOException ignored) {
			// this is what we expect
		}
	}

	@Ignore
	@Test
	public void testDeleteDirectoryConcurrently() throws Exception {
		final File parent = tmp.newFolder();

		generateRandomDirs(parent, 20, 5, 3);

		// start three concurrent threads that delete the contents
		CheckedThread t1 = new Deleter(parent);
		CheckedThread t2 = new Deleter(parent);
		CheckedThread t3 = new Deleter(parent);
		t1.start();
		t2.start();
		t3.start();
		t1.sync();
		t2.sync();
		t3.sync();

		// assert is empty
		assertFalse(parent.exists());
	}

	@Test
	public void testReadAllBytes() throws Exception {
		TemporaryFolder tmpFolder = null;
		try {
			tmpFolder = new TemporaryFolder(new File(this.getClass().getResource("/").getPath()));
			tmpFolder.create();

			final int fileSize = 1024;
			final String testFilePath = tmpFolder.getRoot().getAbsolutePath() + File.separator
					+ this.getClass().getSimpleName() + "_" + fileSize + ".txt";

			String expectedMD5 = generateTestFile(testFilePath, fileSize);

			{
				final int directBufferSize = 500;
				final byte[] data = FileUtils.readAllBytes((new File(testFilePath)).toPath(), directBufferSize);
				assertEquals(expectedMD5, md5Hex(data));
			}

			{
				final int directBufferSize = 1024;
				final byte[] data = FileUtils.readAllBytes((new File(testFilePath)).toPath(), directBufferSize);
				assertEquals(expectedMD5, md5Hex(data));
			}

			{
				final int directBufferSize = 2048;
				final byte[] data = FileUtils.readAllBytes((new File(testFilePath)).toPath(), directBufferSize);
				assertEquals(expectedMD5, md5Hex(data));
			}

			{
				final int directBufferSize = -1;
				final byte[] data = FileUtils.readAllBytes((new File(testFilePath)).toPath(), directBufferSize);
				assertEquals(expectedMD5, md5Hex(data));
			}
		} finally {
			if (tmpFolder != null) {
				tmpFolder.delete();
			}
		}
	}

	// ------------------------------------------------------------------------
	//  Utilities
	// ------------------------------------------------------------------------

	private static void generateRandomDirs(File dir, int numFiles, int numDirs, int depth) throws IOException {
		// generate the random files
		for (int i = 0; i < numFiles; i++) {
			File file = new File(dir, new AbstractID().toString());
			try (FileOutputStream out = new FileOutputStream(file)) {
				out.write(1);
			}
		}

		if (depth > 0) {
			// generate the directories
			for (int i = 0; i < numDirs; i++) {
				File subdir = new File(dir, new AbstractID().toString());
				assertTrue(subdir.mkdir());
				generateRandomDirs(subdir, numFiles, numDirs, depth - 1);
			}
		}
	}

	/**
	 * Generates a random content file.
	 *
	 * @param outputFile the path of the output file
	 * @param length the size of content to generate
	 *
	 * @return MD5 of the output file
	 *
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 */
	private static String generateTestFile(String outputFile, int length) throws IOException, NoSuchAlgorithmException {
		Path outputFilePath = new Path(outputFile);

		final FileSystem fileSystem = outputFilePath.getFileSystem();
		try (final FSDataOutputStream fsDataOutputStream = fileSystem.create(outputFilePath, FileSystem.WriteMode.NO_OVERWRITE)) {
			return writeRandomContent(fsDataOutputStream, length);
		}
	}

	private static String writeRandomContent(OutputStream out, int length) throws IOException, NoSuchAlgorithmException {
		MessageDigest messageDigest = MessageDigest.getInstance("MD5");

		Random random = new Random();
		char startChar = 32, endChar = 127;
		for (int i = 0; i < length; i++) {
			int rnd = random.nextInt(endChar - startChar);
			byte b = (byte) (startChar + rnd);

			out.write(b);
			messageDigest.update(b);
		}

		byte[] b = messageDigest.digest();
		return org.apache.flink.util.StringUtils.byteToHexString(b);
	}

	private static String md5Hex(byte[] data) throws NoSuchAlgorithmException {
		MessageDigest messageDigest = MessageDigest.getInstance("MD5");
		messageDigest.update(data);

		byte[] b = messageDigest.digest();
		return org.apache.flink.util.StringUtils.byteToHexString(b);
	}

	// ------------------------------------------------------------------------

	private static class Deleter extends CheckedThread {

		private final File target;

		Deleter(File target) {
			this.target = target;
		}

		@Override
		public void go() throws Exception {
			FileUtils.deleteDirectory(target);
		}
	}
}
