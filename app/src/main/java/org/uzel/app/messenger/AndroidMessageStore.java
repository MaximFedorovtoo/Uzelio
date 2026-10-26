package org.uzel.app.messenger;

import android.database.sqlite.SQLiteDatabaseCorruptException;
import net.zetetic.database.sqlcipher.SQLiteConnection;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook;
import org.uzel.messenger.store.AbstractTransactionalStore;
import org.uzel.messenger.store.StoreFileLock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** SQLCipher Community storage. A corrupt database is preserved for explicit recovery. */
public final class AndroidMessageStore extends AbstractTransactionalStore {
    private final StoreFileLock fileLock;
    private final SQLiteDatabase database;

    public AndroidMessageStore(File file, byte[] key) throws Exception {
        validateKey(key);
        fileLock = new StoreFileLock(file.toPath());
        SQLiteDatabase opened = null;
        byte[] passphrase = hexPassphrase(key).getBytes(StandardCharsets.UTF_8);
        try {
            System.loadLibrary("sqlcipher");
            opened = SQLiteDatabase.openOrCreateDatabase(fileLock.database().toFile(), passphrase, null,
                    (corrupt, cause) -> { throw new SQLiteDatabaseCorruptException("Encrypted database is corrupt; recovery is required"); },
                    new SQLiteDatabaseHook() {
                        @Override public void preKey(SQLiteConnection connection) { }
                        @Override public void postKey(SQLiteConnection connection) {
                            for (String pragma : new String[]{"cipher_compatibility=4", "cipher_page_size=4096",
                                    "kdf_iter=256000", "cipher_use_hmac=ON", "cipher_hmac_algorithm=HMAC_SHA512",
                                    "cipher_kdf_algorithm=PBKDF2_HMAC_SHA512", "cipher_plaintext_header_size=0"})
                                connection.executeRaw("PRAGMA " + pragma, null, null);
                        }
                    });
            database = opened;
            if (!database.enableWriteAheadLogging()) throw new IllegalStateException("Encrypted WAL could not be enabled");
            database.execSQL("PRAGMA synchronous=FULL");
            database.execSQL("PRAGMA temp_store=MEMORY");
            if (!scalar("PRAGMA cipher_version").startsWith("4.")) throw new IllegalStateException("Required SQLCipher version is unavailable");
            for (String[] p : new String[][]{{"cipher_page_size", "4096"}, {"kdf_iter", "256000"},
                    {"cipher_use_hmac", "1"}, {"cipher_hmac_algorithm", "HMAC_SHA512"},
                    {"cipher_kdf_algorithm", "PBKDF2_HMAC_SHA512"}, {"cipher_plaintext_header_size", "0"},
                    {"journal_mode", "wal"}, {"synchronous", "2"}, {"temp_store", "2"}})
                if (!scalar("PRAGMA " + p[0]).equalsIgnoreCase(p[1])) throw new IllegalStateException("Required encrypted storage profile was not applied: " + p[0]);
            if (!scalar("PRAGMA integrity_check").equals("ok")) throw new SQLiteDatabaseCorruptException("Encrypted storage integrity check failed");
            database.execSQL("CREATE TABLE IF NOT EXISTS records(space TEXT NOT NULL,key TEXT NOT NULL,value BLOB NOT NULL,PRIMARY KEY(space,key))");
        } catch (Exception | Error e) {
            if (opened != null) try { opened.close(); } catch (Exception closeFailure) { e.addSuppressed(closeFailure); }
            try { fileLock.close(); } catch (Exception closeFailure) { e.addSuppressed(closeFailure); }
            throw e;
        } finally { Arrays.fill(passphrase, (byte) 0); }
    }

    private String scalar(String sql) {
        try (var cursor = database.rawQuery(sql, new String[0])) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("Missing encrypted storage profile result");
            return cursor.getString(0);
        }
    }
    // Both callbacks use the primary connection; settings above cannot drift to a pooled reader.
    @Override protected void begin(boolean write) { database.beginTransaction(); }
    @Override protected void commit() { database.setTransactionSuccessful(); database.endTransaction(); }
    @Override protected void rollback() { if (database.inTransaction()) database.endTransaction(); }
    @Override protected byte[] load(String space, String key) {
        try (var cursor = database.rawQuery("SELECT value FROM records WHERE space=? AND key=?", new String[]{space, key})) {
            return cursor.moveToFirst() ? cursor.getBlob(0) : null;
        }
    }
    @Override protected void save(String space, String key, byte[] value) {
        database.execSQL("INSERT INTO records(space,key,value) VALUES(?,?,?) ON CONFLICT(space,key) DO UPDATE SET value=excluded.value", new Object[]{space, key, value});
    }
    @Override protected void delete(String space, String key) {
        database.execSQL("DELETE FROM records WHERE space=? AND key=?", new Object[]{space, key});
    }
    @Override protected Map<String, byte[]> scan(String space) {
        Map<String, byte[]> records = new LinkedHashMap<>();
        try (var cursor = database.rawQuery("SELECT key,value FROM records WHERE space=? ORDER BY key", new String[]{space})) {
            while (cursor.moveToNext()) records.put(cursor.getString(0), cursor.getBlob(1));
        }
        return records;
    }
    @Override protected void closeBackend() throws Exception {
        try { database.close(); } finally { fileLock.close(); }
    }
}
