package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MediaCacheTest {
    @Test public void reconciliationOnlyRemovesStaleFilesInTheOwnedNamespace() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File owned = new File(context.getCacheDir(), MediaCache.DIRECTORY_NAME);
        if (!owned.isDirectory()) assertTrue(owned.mkdirs());
        File stale = new File(owned, "selected-video-stale.mp4");
        File recent = new File(owned, "selected-video-recent.mp4");
        File unrelated = new File(context.getCacheDir(), "tiktok-unrelated.tmp");
        assertTrue(stale.createNewFile());
        assertTrue(recent.createNewFile());
        assertTrue(unrelated.createNewFile());
        long old = System.currentTimeMillis() - MediaCache.STALE_AFTER_MS - 1_000L;
        assertTrue(stale.setLastModified(old));
        try {
            MediaCache.reconcile(context);
            assertFalse(stale.exists());
            assertTrue(recent.exists());
            assertTrue(unrelated.exists());
        } finally {
            stale.delete();
            recent.delete();
            unrelated.delete();
            owned.delete();
        }
    }

    @Test public void anUnreadableJournalIsNotReplacedByTheNextPendingRow() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File owned = new File(context.getCacheDir(), MediaCache.DIRECTORY_NAME);
        assertTrue(owned.isDirectory() || owned.mkdirs());
        File journal = new File(owned, "pending-uris.tsv");
        assertTrue(journal.mkdir());
        try {
            org.junit.Assert.assertThrows(IOException.class, () -> MediaCache.markPending(
                    context, Uri.parse("content://media/existing")));
            assertTrue("the unreadable journal must remain for recovery", journal.isDirectory());
        } finally {
            journal.delete();
            owned.delete();
        }
    }

    @Test public void updatingAnExistingJournalDoesNotRequireRenameOverAnExistingFile()
            throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File directory = new File(context.getNoBackupFilesDir(), MediaCache.DIRECTORY_NAME);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File base = new File(directory, "pending-uris.tsv");
        File backup = new File(directory, "pending-uris.tsv.bak");
        File temporary = new File(directory, "pending-uris.tsv.new");
        for (File file : new File[] {base, backup, temporary}) {
            if (file.exists()) assertTrue("could not clear test journal " + file, file.delete());
        }

        try {
            Uri first = Uri.parse("content://media/one");
            Uri second = Uri.parse("content://media/two");
            MediaCache.markPending(context, first);
            assertTrue(base.isFile());
            assertTrue(new String(Files.readAllBytes(base.toPath()), StandardCharsets.UTF_8)
                    .contains(first.toString()));

            // Updating an existing base file is the operation that fails when the framework
            // AtomicFile implementation attempts renameTo(.new, base) on Windows.
            MediaCache.markPending(context, second);

            String contents = new String(Files.readAllBytes(base.toPath()), StandardCharsets.UTF_8);
            assertTrue(contents, contents.contains(first.toString()));
            assertTrue(contents, contents.contains(second.toString()));
            assertFalse("backup should be retired after commit", backup.exists());
            assertFalse("temporary should be moved after commit", temporary.exists());
        } finally {
            for (File file : new File[] {base, backup, temporary}) file.delete();
        }
    }
}
