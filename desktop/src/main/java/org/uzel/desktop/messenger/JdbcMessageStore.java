package org.uzel.desktop.messenger;

import org.sqlite.mc.HmacAlgorithm;
import org.sqlite.mc.KdfAlgorithm;
import org.sqlite.mc.SQLiteMCSqlCipherConfig;
import org.uzel.messenger.store.AbstractTransactionalStore;
import org.uzel.messenger.store.StoreFileLock;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Encrypted SQLite3MultipleCiphers store; only callback-scoped records are exposed. */
public final class JdbcMessageStore extends AbstractTransactionalStore {
    private final StoreFileLock fileLock;
    private final Connection connection;

    public JdbcMessageStore(Path database, byte[] key) throws Exception {
        validateKey(key);
        fileLock = new StoreFileLock(database);
        Connection opened = null;
        try {
            var config = SQLiteMCSqlCipherConfig.getV4Defaults().setLegacy(0)
                    .setLegacyPageSize(4096).setHmacUse(true).setKdfIter(256000).setKdfAlgorithm(KdfAlgorithm.SHA512)
                    .setHmacAlgorithm(HmacAlgorithm.SHA512).setPlaintextHeaderSize(0)
                    .withKey(hexPassphrase(key)).build();
            opened = config.createConnection("jdbc:sqlite:" + fileLock.database());
            connection = opened;
            execute("PRAGMA hmac_check=1");
            execute("PRAGMA page_size=4096");
            execute("PRAGMA journal_mode=WAL");
            execute("PRAGMA synchronous=FULL");
            execute("PRAGMA temp_store=MEMORY");
            for (String[] p : new String[][]{{"cipher", "sqlcipher"}, {"legacy", "0"}, {"hmac_use", "1"},
                    {"hmac_check", "1"}, {"kdf_iter", "256000"}, {"kdf_algorithm", "2"}, {"hmac_algorithm", "2"},
                    {"plaintext_header_size", "0"}, {"page_size", "4096"}, {"journal_mode", "wal"},
                    {"synchronous", "2"}, {"temp_store", "2"}})
                if (!scalar("PRAGMA " + p[0]).equalsIgnoreCase(p[1])) throw new SQLException("Required encrypted storage profile was not applied: " + p[0]);
            if (!scalar("PRAGMA integrity_check").equals("ok")) throw new SQLException("Encrypted storage integrity check failed");
            execute("CREATE TABLE IF NOT EXISTS records(space TEXT NOT NULL,key TEXT NOT NULL,value BLOB NOT NULL,PRIMARY KEY(space,key))");
        } catch (Exception | Error e) {
            if (opened != null) try { opened.close(); } catch (Exception closeFailure) { e.addSuppressed(closeFailure); }
            try { fileLock.close(); } catch (Exception closeFailure) { e.addSuppressed(closeFailure); }
            throw e;
        }
    }
    private void execute(String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private String scalar(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Missing storage profile result");
            return result.getString(1);
        }
    }
    @Override protected void begin(boolean write) throws SQLException { connection.setAutoCommit(false); }
    @Override protected void commit() throws SQLException { connection.commit(); connection.setAutoCommit(true); }
    @Override protected void rollback() throws SQLException { connection.rollback(); connection.setAutoCommit(true); }
    @Override protected byte[] load(String space, String key) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT value FROM records WHERE space=? AND key=?")) {
            statement.setString(1, space); statement.setString(2, key);
            try (var result = statement.executeQuery()) { return result.next() ? result.getBytes(1) : null; }
        }
    }
    @Override protected void save(String space, String key, byte[] value) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO records(space,key,value) VALUES(?,?,?) ON CONFLICT(space,key) DO UPDATE SET value=excluded.value")) {
            statement.setString(1, space); statement.setString(2, key); statement.setBytes(3, value); statement.executeUpdate();
        }
    }
    @Override protected void delete(String space, String key) throws SQLException {
        try (var statement = connection.prepareStatement("DELETE FROM records WHERE space=? AND key=?")) {
            statement.setString(1, space); statement.setString(2, key); statement.executeUpdate();
        }
    }
    @Override protected Map<String, byte[]> scan(String space) throws SQLException {
        Map<String, byte[]> records = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("SELECT key,value FROM records WHERE space=? ORDER BY key")) {
            statement.setString(1, space);
            try (var result = statement.executeQuery()) { while (result.next()) records.put(result.getString(1), result.getBytes(2)); }
        }
        return records;
    }
    @Override protected void closeBackend() throws Exception {
        try { connection.close(); } finally { fileLock.close(); }
    }
}
