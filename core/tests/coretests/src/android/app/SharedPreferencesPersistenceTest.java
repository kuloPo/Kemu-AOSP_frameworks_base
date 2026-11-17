/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.FileUtils;
import android.system.Os;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SharedPreferencesPersistenceTest {
    private Context mContext;
    private File mDirectory;
    private File mFile;
    private String mContextName;

    @Before
    public void setUp() {
        assertFalse("Persistence tests require file sync", Flags.sharedpreferencesSkipFsync());
        mContext = InstrumentationRegistry.getTargetContext();
        mContextName = "persistence-" + System.nanoTime();
        mDirectory = new File(mContext.getCacheDir(), mContextName);
        assertTrue(mDirectory.mkdir());
        mFile = new File(mDirectory, "prefs.xml");
    }

    @After
    public void tearDown() {
        if (mContext != null && mContextName != null) {
            mContext.deleteSharedPreferences(mContextName);
        }
        if (mDirectory != null) {
            FileUtils.deleteContentsAndDir(mDirectory);
        }
    }

    private SharedPreferences reload(File file) throws Exception {
        Constructor<?> constructor = Class.forName("android.app.SharedPreferencesImpl")
                .getDeclaredConstructor(File.class, int.class);
        constructor.setAccessible(true);
        return (SharedPreferences) constructor.newInstance(file, Context.MODE_PRIVATE);
    }

    private SharedPreferences baseline() throws Exception {
        SharedPreferences prefs = reload(mFile);
        assertTrue(prefs.edit().putString("value", "old").commit());
        return prefs;
    }

    @Test
    public void commitPublishesCompleteFile() throws Exception {
        SharedPreferences prefs = baseline();
        assertTrue(prefs.edit().putString("value", "new").commit());
        assertEquals("new", reload(mFile).getString("value", null));
        assertFalse(new File(mFile + ".new").exists());
        assertFalse(new File(mFile + ".bak").exists());
    }

    @Test
    public void unfinishedStagingFileDoesNotReplacePreviousValue() throws Exception {
        baseline();
        Files.write(new File(mFile + ".new").toPath(), "<map>".getBytes(StandardCharsets.UTF_8));
        assertEquals("old", reload(mFile).getString("value", null));
        assertTrue(reload(mFile).edit().putString("value", "new").commit());
        assertEquals("new", reload(mFile).getString("value", null));
    }

    @Test
    public void legacyBackupRecoversAndDoesNotUndoLaterCommit() throws Exception {
        baseline();
        File backup = new File(mFile + ".bak");
        assertTrue(mFile.renameTo(backup));
        Files.write(mFile.toPath(), new byte[0]);
        SharedPreferences prefs = reload(mFile);
        assertEquals("old", prefs.getString("value", null));
        assertFalse(backup.exists());
        assertTrue(prefs.edit().putString("value", "new").commit());
        assertEquals("new", reload(mFile).getString("value", null));
    }

    @Test
    public void syncFailureReturnsFalseAndPreservesPublishedFile() throws Exception {
        SharedPreferences prefs = baseline();
        byte[] previous = Files.readAllBytes(mFile.toPath());
        Os.symlink("/dev/null", mFile + ".new");
        assertFalse(prefs.edit().putString("value", "new").commit());
        assertArrayEquals(previous, Files.readAllBytes(mFile.toPath()));
        assertEquals("old", reload(mFile).getString("value", null));
        assertTrue(prefs.edit().commit());
        assertEquals("new", reload(mFile).getString("value", null));
    }

    @Test
    public void writeFailureReturnsFalseAndPreservesPublishedFile() throws Exception {
        SharedPreferences prefs = baseline();
        byte[] previous = Files.readAllBytes(mFile.toPath());
        Os.symlink("/dev/full", mFile + ".new");
        assertFalse(prefs.edit().putString("value", "new").commit());
        assertArrayEquals(previous, Files.readAllBytes(mFile.toPath()));
        assertEquals("old", reload(mFile).getString("value", null));
    }

    @Test
    public void firstCommitCreatesParentDirectory() throws Exception {
        File file = new File(mDirectory, "new-parent/prefs.xml");
        assertTrue(reload(file).edit().putString("value", "first").commit());
        assertEquals("first", reload(file).getString("value", null));
    }

    @Test
    public void commitWaitsForEarlierApply() throws Exception {
        SharedPreferences prefs = baseline();
        prefs.edit().putString("value", "async").apply();
        assertTrue(prefs.edit().commit());
        assertEquals("async", reload(mFile).getString("value", null));
    }

    @Test
    public void deletePreferencesRemovesStagingFile() throws Exception {
        assertTrue(mContext.getSharedPreferences(mContextName, 0).edit()
                .putString("value", "old").commit());
        File file = mContext.getSharedPreferencesPath(mContextName);
        File staging = new File(file + ".new");
        Files.write(staging.toPath(), "partial".getBytes(StandardCharsets.UTF_8));
        assertTrue(mContext.deleteSharedPreferences(mContextName));
        assertFalse(file.exists());
        assertFalse(staging.exists());
    }
}
