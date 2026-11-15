package org.leo.ai.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AiToolResultArchiveTest {

    private final AtomicLong now = new AtomicLong(100);
    private final AiToolResultArchive archive = new AiToolResultArchive(now::get);

    @Test
    void capacityUsesInsertionOrderEvenWhenOlderContentIsRead() {
        String first = put("first", 60_000);
        String second = put("second", 60_000);
        for (int i = 2; i < 256; i++) put("content", 60_000);
        assertEquals("first", page(first).content());

        String latest = put("latest", 60_000);

        assertEquals(256, archive.size());
        assertNull(page(first));
        assertEquals("second", page(second).content());
        assertEquals("latest", page(latest).content());
    }

    @Test
    void writesReleaseExpiredContentBeforeEvictingLiveArchives() {
        List<String> live = new ArrayList<>();
        for (int i = 0; i < 8; i++) live.add(put("live", 60_000));
        for (int i = 8; i < 256; i++) put("expired", 1000);
        now.addAndGet(1001);

        String latest = put("new", 60_000);

        assertEquals(9, archive.size());
        for (String id : live) assertEquals("live", page(id).content());
        assertEquals("new", page(latest).content());
    }

    @ParameterizedTest
    @CsvSource({"-1,1800000", "0,1800000", "1,1000", "999,1000", "1000,1000", "5000,5000"})
    void expiryKeepsTheDefaultAndMinimumTtlWithoutReadRenewal(long requestedTtl, long expectedTtl) {
        String id = put("content", requestedTtl);
        now.addAndGet(expectedTtl);
        assertNotNull(page(id));

        now.incrementAndGet();

        assertNull(page(id));
        assertEquals(0, archive.size());
    }

    @ParameterizedTest
    @CsvSource(value = {"owner,other", "null,<no-memory>", "<no-memory>,null"}, nullValues = "null")
    void ownershipUsesTheActualMemoryIdWithoutPlaceholderCollisions(String owner, String other) {
        String id = archive.put(owner, "tool", "private content", null, 60_000);

        assertEquals("private content", archive.page(owner, id, 0, 8000).content());
        assertNull(archive.page(other, id, 0, 8000));
        assertNull(new AiToolResultArchive(now::get).page(owner, id, 0, 8000));
    }

    @ParameterizedTest
    @CsvSource({"-4,-1,0,1", "0,0,0,1", "7,4,7,11", "0,9000,0,8000",
            "8999,50,8999,9000", "10000,10,9000,9000", "2147483647,2147483647,9000,9000"})
    void pagesClampBoundsAndKeepContinuationMetadata(int offset, int limit, int start, int end) {
        String content = "0123456789".repeat(900);
        String id = put(content, 60_000);

        AiToolResultArchive.ArchivePage page = archive.page("owner", id, offset, limit);

        assertEquals(id, page.archiveId());
        assertEquals("tool", page.toolName());
        assertEquals(content.substring(start, end), page.content());
        assertEquals(start, page.offset());
        assertEquals(end, page.endOffset());
        assertEquals(9000, page.totalChars());
        assertEquals(end < 9000, page.hasMore());
    }

    @Test
    void metadataIsCopiedAndCannotBeChangedThroughARead() {
        Map<String, Object> metadata = new HashMap<>(Map.of("scope", "original"));
        String id = archive.put("owner", "tool", "content", metadata, 60_000);
        metadata.put("scope", "changed");

        assertEquals(Map.of("scope", "original"), page(id).metadata());
        assertThrows(UnsupportedOperationException.class, () -> page(id).metadata().put("scope", "changed"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void absentContentAndMetadataProduceAnEmptyFinalPage(String content) {
        String id = archive.put("owner", "tool", content, null, 60_000);

        AiToolResultArchive.ArchivePage page = page(id);

        assertEquals("", page.content());
        assertEquals(0, page.totalChars());
        assertFalse(page.hasMore());
        assertEquals(Map.of(), page.metadata());
    }

    private String put(String content, long ttl) {
        return archive.put("owner", "tool", content, null, ttl);
    }

    private AiToolResultArchive.ArchivePage page(String id) {
        return archive.page("owner", id, 0, 8000);
    }
}
