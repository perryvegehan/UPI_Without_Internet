package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.DemoService;
import com.demo.upimesh.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The killer test: simulates the "three bridges deliver at the same instant"
 * scenario the user explicitly cared about.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyConcurrencyTest {

    @Autowired private DemoService demoService;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private IdempotencyService idempotency;
    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;
    @Autowired private MockMvc mockMvc;
    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder serverKey;

    @BeforeEach
    void clear() {
        idempotency.clear();
    }

    @Test
    void singlePacketDeliveredByThreeBridgesSettlesExactlyOnce() throws Exception {
        // Capture starting balances
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@demo").orElseThrow().getBalance();

        // One packet, but we'll deliver it from 3 "bridges" simultaneously
        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("100.00"), "1234", 5);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger settled = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();

        Future<?>[] futures = new Future[3];
        for (int i = 0; i < 3; i++) {
            final String node = "bridge-" + i;
            futures[i] = pool.submit(() -> {
                try {
                    start.await();
                    BridgeIngestionService.IngestResult r = bridge.ingest(packet, node, 3);
                    if ("SETTLED".equals(r.outcome())) settled.incrementAndGet();
                    else if ("DUPLICATE_DROPPED".equals(r.outcome())) duplicates.incrementAndGet();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }

        start.countDown(); // release all 3 threads at once
        for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, settled.get(), "exactly one bridge should settle");
        assertEquals(2, duplicates.get(), "the other two should be duplicates");

        // Balance moved exactly once
        BigDecimal aliceAfter = accounts.findById("alice@demo").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@demo").orElseThrow().getBalance();
        assertEquals(aliceBefore.subtract(new BigDecimal("100.00")), aliceAfter);
        assertEquals(bobBefore.add(new BigDecimal("100.00")), bobAfter);
    }

    @Test
    void tamperedCiphertextIsRejected() throws Exception {
        MeshPacket packet = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("50.00"), "1234", 5);

        // Flip a byte in the middle of the ciphertext
        char[] chars = packet.getCiphertext().toCharArray();
        chars[chars.length / 2] = chars[chars.length / 2] == 'A' ? 'B' : 'A';
        packet.setCiphertext(new String(chars));

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, "bridge-x", 1);
        assertEquals("INVALID", r.outcome());
    }

    @Test
    void encryptDecryptRoundTrip() throws Exception {
        PaymentInstruction original = new PaymentInstruction(
                "alice@demo", "bob@demo", new BigDecimal("123.45"),
                "abcdef", "nonce-1", System.currentTimeMillis());

        String ct = crypto.encrypt(original, serverKey.getPublicKey());
        PaymentInstruction decrypted = crypto.decrypt(ct);

        assertEquals(original.getSenderVpa(), decrypted.getSenderVpa());
        assertEquals(original.getReceiverVpa(), decrypted.getReceiverVpa());
        assertEquals(0, original.getAmount().compareTo(decrypted.getAmount()));
        assertEquals(original.getNonce(), decrypted.getNonce());
    }

    @Test
    void paymentGuardrailsRejectSelfTransfersAndAmountsAboveConfiguredLimit() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@demo").orElseThrow().getBalance();

        MeshPacket selfTransfer = demoService.createPacket(
                "alice@demo", "alice@demo", new BigDecimal("100.00"), "1234", 5);
        BridgeIngestionService.IngestResult selfTransferResult =
                bridge.ingest(selfTransfer, "bridge-test", 1);

        assertEquals("REJECTED", selfTransferResult.outcome());
        assertEquals("self_transfer_not_allowed", selfTransferResult.reason());
        assertEquals("self_transfer_not_allowed",
                transactions.findById(selfTransferResult.transactionId()).orElseThrow().getRejectionReason());

        MeshPacket overLimit = demoService.createPacket(
                "alice@demo", "bob@demo", new BigDecimal("10001.00"), "1234", 5);
        BridgeIngestionService.IngestResult overLimitResult =
                bridge.ingest(overLimit, "bridge-test", 1);

        assertEquals("REJECTED", overLimitResult.outcome());
        assertEquals("limit_exceeded", overLimitResult.reason());
        assertEquals(aliceBefore, accounts.findById("alice@demo").orElseThrow().getBalance());
    }

    @Test
    void transactionSearchAppliesFiltersAndPageSize() {
        String uniqueSender = "ledger-search-" + UUID.randomUUID();
        Transaction transaction = new Transaction();
        transaction.setPacketHash(UUID.randomUUID().toString().replace("-", ""));
        transaction.setSenderVpa(uniqueSender);
        transaction.setReceiverVpa("search-target@demo");
        transaction.setAmount(new BigDecimal("12.50"));
        transaction.setSignedAt(Instant.now());
        transaction.setSettledAt(Instant.now());
        transaction.setBridgeNodeId("bridge-search");
        transaction.setHopCount(2);
        transaction.setStatus(Transaction.Status.SETTLED);
        transactions.saveAndFlush(transaction);

        Page<Transaction> results = transactions.search(
                Transaction.Status.SETTLED, uniqueSender.substring(0, 20), null, PageRequest.of(0, 1));

        assertEquals(1, results.getTotalElements());
        assertEquals(1, results.getContent().size());
        assertEquals(uniqueSender, results.getContent().get(0).getSenderVpa());
    }

    @Test
    void transactionSearchApiReturnsPageMetadata() throws Exception {
        mockMvc.perform(get("/api/transactions/search")
                        .param("page", "0")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").exists());
    }

    @Test
    void demoSendRejectsInvalidPaymentInput() throws Exception {
        mockMvc.perform(post("/api/demo/send")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "senderVpa": "alice@demo",
                                  "receiverVpa": "bob@demo",
                                  "amount": 0,
                                  "pin": "not-a-pin",
                                  "ttl": 5
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
