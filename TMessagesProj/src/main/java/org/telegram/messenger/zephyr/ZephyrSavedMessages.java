package org.telegram.messenger.zephyr;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps copies of messages that were deleted on the server side (and old revisions of edited
 * messages) in a separate SQLite database file, "zephyr-saved.db". The main MessagesStorage schema
 * is never touched.
 *
 * All access to the database happens on a dedicated DispatchQueue. Sync readers (getDeleted,
 * getEdits) block the calling thread, so they must not be called from the UI thread; they return an
 * empty list in that case.
 */
public class ZephyrSavedMessages {

    private static final String DB_NAME = "zephyr-saved.db";
    private static final long SELF_DELETE_WINDOW_MS = 2 * 60 * 1000L;
    private static final int PRESERVED_LIMIT = 4096;
    private static final long SYNC_TIMEOUT_SECONDS = 5;

    /** One superseded text of an edited message. */
    public static class EditRevision {
        /** Date of this revision (edit_date of the old message, or its send date for the first one). */
        public int editDate;
        public String text;
    }

    private static volatile ZephyrSavedMessages instance;

    public static ZephyrSavedMessages getInstance() {
        ZephyrSavedMessages local = instance;
        if (local == null) {
            synchronized (ZephyrSavedMessages.class) {
                local = instance;
                if (local == null) {
                    instance = local = new ZephyrSavedMessages();
                }
            }
        }
        return local;
    }

    private final DispatchQueue queue = new DispatchQueue("zephyrSavedQueue");
    // touched on the queue thread only
    private SQLiteDatabase db;

    // account:dialogKey:id of messages the server deleted while saveDeleted was on (UI keeps them in the open chat)
    private final Object lock = new Object();
    private final LinkedHashMap<String, Boolean> preserved = new LinkedHashMap<String, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > PRESERVED_LIMIT;
        }
    };
    // account:id -> time when this client itself asked the server to delete the message
    private final HashMap<String, Long> selfDeleted = new HashMap<>();

    private ZephyrSavedMessages() {

    }

    // ------------------------------------------------------------------------------------------
    // in-memory registries (any thread)
    // ------------------------------------------------------------------------------------------

    private static String preservedKey(int account, long dialogKey, int messageId) {
        return account + ":" + dialogKey + ":" + messageId;
    }

    private static String selfKey(int account, int messageId) {
        return account + ":" + messageId;
    }

    private boolean isSelfDeleted(int account, int messageId) {
        synchronized (lock) {
            Long time = selfDeleted.get(selfKey(account, messageId));
            return time != null && SystemClock.elapsedRealtime() - time < SELF_DELETE_WINDOW_MS;
        }
    }

    /**
     * Called for every server-side deletion update (before the UI is notified).
     *
     * @param dialogKey 0 for deletions of non-channel messages, -channelId for channel messages (same
     *                  key MessagesController uses for its deletedMessages array).
     */
    public void markServerDeleted(int account, long dialogKey, ArrayList<Integer> ids) {
        if (!ZephyrConfig.saveDeleted || ids == null) {
            return;
        }
        synchronized (lock) {
            for (int a = 0, N = ids.size(); a < N; a++) {
                int id = ids.get(a);
                if (id <= 0 || isSelfDeleted(account, id)) {
                    continue;
                }
                preserved.put(preservedKey(account, dialogKey, id), Boolean.TRUE);
            }
        }
    }

    /** True if the open chat must keep showing this message after the server deleted it. */
    public boolean isPreserved(int account, long dialogKey, int messageId) {
        if (!ZephyrConfig.saveDeleted) {
            return false;
        }
        synchronized (lock) {
            return preserved.containsKey(preservedKey(account, dialogKey, messageId));
        }
    }

    /**
     * The user deleted these messages from this client: do not treat the deletion as a server-side one,
     * and drop any saved copy so that deleting a "deleted" message from the chat really removes it.
     *
     * @param channelId 0 for non-channel dialogs, positive channel id otherwise
     */
    public void onUserDeleted(int account, long dialogId, long channelId, ArrayList<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        final long dialogKey = channelId != 0 ? -channelId : 0;
        final ArrayList<Integer> copy = new ArrayList<>(ids);
        final long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            if (selfDeleted.size() > 1024) {
                Iterator<Map.Entry<String, Long>> iterator = selfDeleted.entrySet().iterator();
                while (iterator.hasNext()) {
                    if (now - iterator.next().getValue() >= SELF_DELETE_WINDOW_MS) {
                        iterator.remove();
                    }
                }
            }
            for (int a = 0, N = copy.size(); a < N; a++) {
                int id = copy.get(a);
                if (id <= 0) {
                    continue;
                }
                selfDeleted.put(selfKey(account, id), now);
                preserved.remove(preservedKey(account, dialogKey, id));
            }
        }
        queue.postRunnable(() -> {
            SQLiteDatabase database = getDb();
            if (database == null) {
                return;
            }
            try {
                database.beginTransaction();
                try {
                    for (int a = 0, N = copy.size(); a < N; a++) {
                        database.delete("deleted_messages", "account = ? AND dialog_id = ? AND message_id = ?",
                                new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(copy.get(a))});
                    }
                    database.setTransactionSuccessful();
                } finally {
                    database.endTransaction();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // capture
    // ------------------------------------------------------------------------------------------

    /**
     * Reads the given messages from messages_v2 and saves them. MUST be called on the MessagesStorage
     * queue thread, before the rows are deleted (it uses the storage database directly and does not
     * block). Does nothing when saveDeleted is off.
     *
     * @param dialogKey same value that is passed to MessagesStorage.markMessagesAsDeleted as dialogId
     *                  (0 means "non-channel messages of any dialog")
     */
    public void captureServerDeleted(int account, MessagesStorage storage, long dialogKey, ArrayList<Integer> ids) {
        if (!ZephyrConfig.saveDeleted || storage == null || ids == null || ids.isEmpty()) {
            return;
        }
        org.telegram.SQLite.SQLiteDatabase mainDatabase = storage.getDatabase();
        if (mainDatabase == null) {
            return;
        }
        ArrayList<Integer> wanted = new ArrayList<>(ids.size());
        for (int a = 0, N = ids.size(); a < N; a++) {
            int id = ids.get(a);
            if (id > 0 && !isSelfDeleted(account, id)) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        final String idList = TextUtils.join(",", wanted);
        ArrayList<TLRPC.Message> found = new ArrayList<>();
        SQLiteCursor cursor = null;
        try {
            if (dialogKey != 0) {
                cursor = mainDatabase.queryFinalized(String.format(Locale.US, "SELECT uid, data FROM messages_v2 WHERE mid IN(%s) AND uid = %d", idList, dialogKey));
            } else {
                cursor = mainDatabase.queryFinalized(String.format(Locale.US, "SELECT uid, data FROM messages_v2 WHERE mid IN(%s) AND is_channel = 0", idList));
            }
            while (cursor.next()) {
                long uid = cursor.longValue(0);
                if (DialogObject.isEncryptedDialog(uid)) {
                    continue;
                }
                NativeByteBuffer data = cursor.byteBufferValue(1);
                if (data == null) {
                    continue;
                }
                try {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, selfId);
                        message.dialog_id = uid;
                        found.add(message);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    data.reuse();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        if (!found.isEmpty()) {
            saveDeleted(account, found);
        }
    }

    /** Saves the messages as deleted "now". Serialization happens on the calling thread, the write is async. */
    public void saveDeleted(int account, ArrayList<TLRPC.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        final int deletedAt = (int) (System.currentTimeMillis() / 1000L);
        final ArrayList<ContentValues> rows = new ArrayList<>(messages.size());
        for (int a = 0, N = messages.size(); a < N; a++) {
            TLRPC.Message message = messages.get(a);
            if (message == null || message.id <= 0 || message instanceof TLRPC.TL_messageEmpty) {
                continue;
            }
            try {
                long dialogId = MessageObject.getDialogId(message);
                if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
                    continue;
                }
                SerializedData blob = new SerializedData();
                message.serializeToStream(blob);
                SerializedData entities = new SerializedData();
                Vector.serialize(entities, message.entities != null ? message.entities : new ArrayList<TLRPC.MessageEntity>());

                ContentValues values = new ContentValues();
                values.put("account", account);
                values.put("dialog_id", dialogId);
                values.put("message_id", message.id);
                values.put("date", message.date);
                values.put("from_id", message.from_id != null ? DialogObject.getPeerDialogId(message.from_id) : 0L);
                values.put("peer_id", message.peer_id != null ? DialogObject.getPeerDialogId(message.peer_id) : 0L);
                String text = message.message;
                values.put("text", text);
                values.put("entities", entities.toByteArray());
                values.put("data", blob.toByteArray());
                values.put("deleted_at", deletedAt);
                rows.add(values);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        queue.postRunnable(() -> {
            SQLiteDatabase database = getDb();
            if (database == null) {
                return;
            }
            try {
                database.beginTransaction();
                try {
                    for (int a = 0, N = rows.size(); a < N; a++) {
                        // the first capture wins (push and update can both report the same deletion)
                        database.insertWithOnConflict("deleted_messages", null, rows.get(a), SQLiteDatabase.CONFLICT_IGNORE);
                    }
                    database.setTransactionSuccessful();
                } finally {
                    database.endTransaction();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Called by MessagesStorage when an existing row is replaced by an edited version. Saves the old
     * revision if saveEdited is on and the text actually changed.
     */
    public void onMessageEdited(int account, TLRPC.Message oldMsg, TLRPC.Message newMsg) {
        if (!ZephyrConfig.saveEdited || oldMsg == null || newMsg == null) {
            return;
        }
        String oldText = oldMsg.message == null ? "" : oldMsg.message;
        String newText = newMsg.message == null ? "" : newMsg.message;
        if (oldText.equals(newText)) {
            return;
        }
        saveEditRevision(account, oldMsg);
    }

    /** Stores the text of oldMsg as a superseded revision. */
    public void saveEditRevision(int account, TLRPC.Message oldMsg) {
        if (oldMsg == null || oldMsg.id <= 0) {
            return;
        }
        final long dialogId = MessageObject.getDialogId(oldMsg);
        if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        final ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("dialog_id", dialogId);
        values.put("message_id", oldMsg.id);
        values.put("edit_date", oldMsg.edit_date != 0 ? oldMsg.edit_date : oldMsg.date);
        String text = oldMsg.message == null ? "" : oldMsg.message;
        values.put("old_text", text);
        queue.postRunnable(() -> {
            SQLiteDatabase database = getDb();
            if (database == null) {
                return;
            }
            try {
                database.insertWithOnConflict("edits", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // queries (blocking, not for the UI thread)
    // ------------------------------------------------------------------------------------------

    /**
     * Saved deleted messages of the dialog with minId <= id <= maxId, newest first. Every returned
     * message has zephyrDeleted = true and dialog_id set.
     */
    public ArrayList<TLRPC.Message> getDeleted(int account, long dialogId, int minId, int maxId, int limit) {
        if (limit <= 0) {
            limit = 500;
        }
        final int finalLimit = limit;
        ArrayList<TLRPC.Message> result = runSync(() -> {
            ArrayList<TLRPC.Message> list = new ArrayList<>();
            SQLiteDatabase database = getDb();
            if (database == null) {
                return list;
            }
            final long selfId = UserConfig.getInstance(account).getClientUserId();
            Cursor cursor = null;
            try {
                cursor = database.rawQuery("SELECT data FROM deleted_messages WHERE account = ? AND dialog_id = ? AND message_id >= ? AND message_id <= ? ORDER BY message_id DESC LIMIT " + finalLimit,
                        new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(minId), String.valueOf(maxId)});
                while (cursor.moveToNext()) {
                    byte[] blob = cursor.getBlob(0);
                    if (blob == null) {
                        continue;
                    }
                    try {
                        SerializedData data = new SerializedData(blob);
                        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        if (message != null) {
                            message.readAttachPath(data, selfId);
                            message.dialog_id = dialogId;
                            message.zephyrDeleted = true;
                            message.unread = false;
                            message.media_unread = false;
                            list.add(message);
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return list;
        });
        return result != null ? result : new ArrayList<>();
    }

    /** Superseded revisions of one message, oldest first. */
    public ArrayList<EditRevision> getEdits(int account, long dialogId, int messageId) {
        ArrayList<EditRevision> result = runSync(() -> {
            ArrayList<EditRevision> list = new ArrayList<>();
            SQLiteDatabase database = getDb();
            if (database == null) {
                return list;
            }
            Cursor cursor = null;
            try {
                cursor = database.rawQuery("SELECT edit_date, old_text FROM edits WHERE account = ? AND dialog_id = ? AND message_id = ? ORDER BY edit_date ASC, id ASC",
                        new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(messageId)});
                while (cursor.moveToNext()) {
                    EditRevision revision = new EditRevision();
                    revision.editDate = cursor.getInt(0);
                    revision.text = cursor.getString(1);
                    list.add(revision);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return list;
        });
        return result != null ? result : new ArrayList<>();
    }

    /** Time (unix seconds) when the message was captured as server-deleted, 0 if it is not saved. Not for the UI thread. */
    public int getDeletedAt(int account, long dialogId, int messageId) {
        Integer result = runSync(() -> {
            SQLiteDatabase database = getDb();
            if (database == null) {
                return 0;
            }
            Cursor cursor = null;
            try {
                cursor = database.rawQuery("SELECT deleted_at FROM deleted_messages WHERE account = ? AND dialog_id = ? AND message_id = ?",
                        new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(messageId)});
                if (cursor.moveToNext()) {
                    return cursor.getInt(0);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return 0;
        });
        return result != null ? result : 0;
    }

    /** {deleted messages count, edit revisions count, database file size in bytes}. Not for the UI thread. */
    public long[] getStats() {
        long[] result = runSync(() -> {
            long[] stats = new long[3];
            SQLiteDatabase database = getDb();
            if (database == null) {
                return stats;
            }
            Cursor cursor = null;
            try {
                cursor = database.rawQuery("SELECT (SELECT COUNT(*) FROM deleted_messages), (SELECT COUNT(*) FROM edits)", null);
                if (cursor.moveToNext()) {
                    stats[0] = cursor.getLong(0);
                    stats[1] = cursor.getLong(1);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            stats[2] = new File(ApplicationLoader.getFilesDirFixed(), DB_NAME).length();
            return stats;
        });
        return result != null ? result : new long[3];
    }

    /** Removes every saved deleted message and edit revision. Not for the UI thread. */
    public void clearAll() {
        runSync(() -> {
            SQLiteDatabase database = getDb();
            if (database == null) {
                return Boolean.FALSE;
            }
            try {
                database.delete("deleted_messages", null, null);
                database.delete("edits", null, null);
                database.execSQL("VACUUM");
            } catch (Throwable e) {
                FileLog.e(e);
            }
            return Boolean.TRUE;
        });
    }

    // ------------------------------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------------------------------

    private interface SyncJob<T> {
        T run();
    }

    private <T> T runSync(SyncJob<T> job) {
        if (Thread.currentThread() == queue) {
            return job.run();
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            FileLog.e("ZephyrSavedMessages: blocking read requested on the UI thread, ignored");
            return null;
        }
        final AtomicReference<T> ref = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        queue.postRunnable(() -> {
            try {
                ref.set(job.run());
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            return null;
        }
        return ref.get();
    }

    /** Queue thread only. */
    private SQLiteDatabase getDb() {
        if (db != null && db.isOpen()) {
            return db;
        }
        try {
            File file = new File(ApplicationLoader.getFilesDirFixed(), DB_NAME);
            db = SQLiteDatabase.openOrCreateDatabase(file.getAbsolutePath(), null);
            db.execSQL("CREATE TABLE IF NOT EXISTS deleted_messages (" +
                    "account INTEGER NOT NULL, dialog_id INTEGER NOT NULL, message_id INTEGER NOT NULL, " +
                    "date INTEGER, from_id INTEGER, peer_id INTEGER, text TEXT, entities BLOB, data BLOB, deleted_at INTEGER, " +
                    "PRIMARY KEY (account, dialog_id, message_id))");
            db.execSQL("CREATE TABLE IF NOT EXISTS edits (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, account INTEGER NOT NULL, dialog_id INTEGER NOT NULL, message_id INTEGER NOT NULL, " +
                    "edit_date INTEGER NOT NULL, old_text TEXT, " +
                    "UNIQUE (account, dialog_id, message_id, edit_date))");
        } catch (Throwable e) {
            FileLog.e(e);
            try {
                if (db != null) {
                    db.close();
                }
            } catch (Throwable ignore) {

            }
            db = null;
        }
        return db;
    }
}
