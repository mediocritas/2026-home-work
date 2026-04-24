package company.vk.edu.distrib.compute.mediocritas.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static company.vk.edu.distrib.compute.mediocritas.storage.validation.DaoKeyValidatorUtils.validateKey;

public class VersionedFileDao {

    private static final Logger log = LoggerFactory.getLogger(VersionedFileDao.class);

    private static final String FILE_NAME = "data.bin";

    private static final int TOMBSTONE_SIZE = 1;
    private static final int KEY_LENGTH_SIZE = 4;
    private static final int VALUE_LENGTH_SIZE = 4;
    private static final int TIMESTAMP_SIZE = 8;
    private static final int HEADER_SIZE = TOMBSTONE_SIZE + KEY_LENGTH_SIZE + VALUE_LENGTH_SIZE + TIMESTAMP_SIZE;

    private static final byte ALIVE = 0;
    private static final byte DELETED = 1;

    private final Map<String, Long> index;
    private final AtomicLong writePosition;
    private final FileChannel channel;

    public VersionedFileDao(String basePath) throws IOException {
        this.index = new ConcurrentHashMap<>();
        Path dir = Path.of(basePath);
        Files.createDirectories(dir);
        Path dataFile = dir.resolve(FILE_NAME);
        this.channel = FileChannel.open(dataFile,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE);
        this.writePosition = new AtomicLong(channel.size());
        restoreIndex();
    }

    public VersionedValue get(String key) {
        Long offset = index.get(key);
        if (offset == null) {
            return null;
        }

        try {
            ByteBuffer tombstoneBuffer = ByteBuffer.allocate(TOMBSTONE_SIZE);
            readFully(tombstoneBuffer, offset);
            tombstoneBuffer.flip();
            final boolean isDeleted = tombstoneBuffer.get() == DELETED;

            ByteBuffer valueLenBuffer = ByteBuffer.allocate(VALUE_LENGTH_SIZE);
            readFully(valueLenBuffer, offset + TOMBSTONE_SIZE + KEY_LENGTH_SIZE);
            valueLenBuffer.flip();
            final int valueLength = valueLenBuffer.getInt();

            ByteBuffer timestampBuffer = ByteBuffer.allocate(TIMESTAMP_SIZE);
            readFully(timestampBuffer, offset + TOMBSTONE_SIZE + KEY_LENGTH_SIZE + VALUE_LENGTH_SIZE);
            timestampBuffer.flip();
            long timestamp = timestampBuffer.getLong();

            if (isDeleted) {
                return VersionedValue.deleted(timestamp);
            }

            ByteBuffer dataBuffer = ByteBuffer.allocate(valueLength);
            readFully(dataBuffer, offset + HEADER_SIZE);
            dataBuffer.flip();
            return new VersionedValue(dataBuffer.array(), timestamp);
        } catch (IOException e) {
            return null;
        }
    }

    public void upsert(String key, VersionedValue value) throws IOException {
        validateKey(key);

        byte[] data = value.data() != null ? value.data() : new byte[0];
        byte tombstone = value.tombstone() ? DELETED : ALIVE;

        int recordSize = HEADER_SIZE + data.length + key.length();
        long offset = writePosition.getAndAdd(recordSize);

        ByteBuffer buf = ByteBuffer.allocate(recordSize)
                .put(tombstone)
                .putInt(key.length())
                .putInt(data.length)
                .putLong(value.timestamp())
                .put(data)
                .put(key.getBytes(StandardCharsets.UTF_8));

        buf.flip();
        writeFully(buf, offset);
        index.put(key, offset);
    }

    public void close() throws IOException {
        channel.close();
    }

    public int keyCount() {
        int count = 0;
        for (Map.Entry<String, Long> entry : index.entrySet()) {
            try {
                ByteBuffer tombstoneBuffer = ByteBuffer.allocate(TOMBSTONE_SIZE);
                readFully(tombstoneBuffer, entry.getValue());
                tombstoneBuffer.flip();
                if (tombstoneBuffer.get() == ALIVE) {
                    count++;
                }
            } catch (IOException e) {
                if (log.isWarnEnabled()) {
                    log.warn("Failed to read tombstone for key '{}', skipping", entry.getKey(), e);
                }
            }
        }
        return count;
    }

    private void readFully(ByteBuffer buf, long offset) throws IOException {
        int total = buf.remaining();
        while (buf.hasRemaining()) {
            int bytesRead = channel.read(buf, offset + (total - buf.remaining()));
            if (bytesRead == -1) {
                throw new EOFException("Unexpected end of file at offset " + offset);
            }
        }
    }

    private void writeFully(ByteBuffer buf, long offset) throws IOException {
        int total = buf.remaining();
        while (buf.hasRemaining()) {
            channel.write(buf, offset + (total - buf.remaining()));
        }
    }

    private void restoreIndex() throws IOException {
        long fileSize = channel.size();
        long offset = 0;

        while (offset < fileSize) {
            ByteBuffer tombstoneBuffer = ByteBuffer.allocate(TOMBSTONE_SIZE);
            readFully(tombstoneBuffer, offset);
            tombstoneBuffer.flip();

            ByteBuffer keyLenBuffer = ByteBuffer.allocate(KEY_LENGTH_SIZE);
            readFully(keyLenBuffer, offset + TOMBSTONE_SIZE);
            keyLenBuffer.flip();
            int keyLength = keyLenBuffer.getInt();

            ByteBuffer valueLenBuffer = ByteBuffer.allocate(VALUE_LENGTH_SIZE);
            readFully(valueLenBuffer, offset + TOMBSTONE_SIZE + KEY_LENGTH_SIZE);
            valueLenBuffer.flip();
            int valueLength = valueLenBuffer.getInt();

            ByteBuffer keyBuffer = ByteBuffer.allocate(keyLength);
            readFully(keyBuffer, offset + HEADER_SIZE + valueLength);
            keyBuffer.flip();
            String key = StandardCharsets.UTF_8.decode(keyBuffer).toString();

            byte tombstone = tombstoneBuffer.get();
            if (tombstone == ALIVE || tombstone == DELETED) {
                index.put(key, offset);
            } else {
                throw new IOException("Corrupted file: unknown tombstone value " + tombstone + " at offset " + offset);
            }

            offset += HEADER_SIZE + valueLength + keyLength;
        }
    }
}
